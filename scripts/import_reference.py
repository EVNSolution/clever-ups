#!/usr/bin/env python3
"""Import the Fleetune upload file as the Korean-address reference set (address_reference).

    python3 scripts/import_reference.py --xlsx "<upload file>" --date 2026-10-05 --dry-run
    python3 scripts/import_reference.py --xlsx "<upload file>" --date 2026-10-05 --database ups_dev

What it does, and what it never does:
  - Reads the file locally. The file is not copied anywhere and is not committed.
  - Finds the English original of each row in Ours24 by tracking number (GET only, key read inside
    scripts/ours24_partner.py), so the address key is exactly the one the API computes.
  - Writes address_location (the original, if new) and address_reference (the Korean address of the file).
  - Stores no customer name, no phone number and no tracking number: only a SHA-256 of the number.
  - Prints counts, never addresses or names.
  - Is safe to run again: an existing row is left as it is, a different Korean address for a stored key is
    counted as a conflict and not written.
"""
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from urllib import parse
from xml.etree import ElementTree

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
TRACKING, DESTINATION, DETAIL = "노트(2)", "목적지", "목적지 상세"
HANGUL = re.compile(r"[가-힣]")
LATIN = re.compile(r"[A-Za-z]")
MAX_PAGES = 60


def address_key(address1, address2, postal_code, company_name):
    """The same key as AddressKey.kt: trim, collapse white space, upper-case, join with newline, SHA-256."""
    def normalize(part):
        return re.sub(r"\s+", " ", (part or "").strip()).upper()

    parts = [normalize(p) for p in (address1, address2, postal_code, company_name)]
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()


def read_sheet(archive, index, strings):
    rows = []
    root = ElementTree.fromstring(archive.read(f"xl/worksheets/sheet{index}.xml"))
    for row in root.find("m:sheetData", NS):
        cells = {}
        for cell in row:
            column = 0
            for letter in re.match(r"[A-Z]+", cell.get("r")).group(0):
                column = column * 26 + ord(letter) - 64
            value = cell.find("m:v", NS)
            if value is None:
                continue
            cells[column] = strings[int(value.text)] if cell.get("t") == "s" else value.text
        rows.append(cells)
    return rows


def read_named_sheet(path, sheet):
    """Rows of one sheet as dicts keyed by header name."""
    with zipfile.ZipFile(path) as archive:
        root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
        strings = ["".join(t.text or "" for t in si.iter(f"{{{NS['m']}}}t")) for si in root.findall("m:si", NS)]
        names = [s.get("name") for s in ElementTree.fromstring(archive.read("xl/workbook.xml")).find("m:sheets", NS)]
        rows = read_sheet(archive, names.index(sheet) + 1, strings)
    header = rows[0]
    return [{header[c]: row.get(c) for c in header} for row in rows[1:]]


def read_tasks(path):
    """Rows of the `tasks` sheet as dicts keyed by header name."""
    return read_named_sheet(path, "tasks")


def korean_only(text):
    """A Korean address has Hangul and no Latin letters. Anything else is not a usable reference."""
    return bool(text) and bool(HANGUL.search(text)) and not LATIN.search(text)


def ours24_rows(partner, day, wanted):
    """Rows of the day's Jobs that carry one of the wanted tracking numbers. Stops when all are found."""
    headers = {"Accept": "application/json", "User-Agent": partner.USER_AGENT, "x-api-key": partner.read_key()}
    found, pages = {}, 0
    outcome = partner.get("/jobs?" + parse.urlencode({"start_date": day, "end_date": day, "size": 100, "page": 1}), headers)
    if outcome is None or not 200 <= outcome[0] < 300:
        raise SystemExit("Job 목록을 조회하지 못했습니다.")
    for job in json.loads(outcome[2]).get("jobs", []):
        page = 1
        while pages < MAX_PAGES and len(found) < len(wanted):
            target = f"/jobs/{job['id']}/tasks?" + parse.urlencode({"page": page, "limit": 100})
            outcome = partner.get(target, headers)
            pages += 1
            if outcome is None or not 200 <= outcome[0] < 300:
                raise SystemExit("Job 행을 조회하지 못했습니다.")
            body = json.loads(outcome[2])
            for row in body.get("tasks", []):
                if row.get("trackingNumber") in wanted:
                    found.setdefault(row["trackingNumber"], row)
            if page >= body.get("totalPages", 1) or not body.get("tasks"):
                break
            page += 1
    return found, pages


def literal(value):
    return "NULL" if value is None else "'" + str(value).replace("'", "''") + "'"


def build_sql(pairs, source):
    """One transaction: new originals, then references. An existing reference is left as it is."""
    out = ["BEGIN;"]
    for key, (a1, a2, postal, company), ko, detail, tracking_hash in pairs:
        out.append(
            "INSERT INTO address_location (address_key, address1, address2, postal_code, company_name) "
            f"SELECT {literal(key)}, {literal(a1.strip())}, {literal(a2.strip())}, {literal(postal.strip())}, "
            f"{literal(company.strip())} WHERE NOT EXISTS "
            f"(SELECT 1 FROM address_location WHERE address_key = {literal(key)});"
        )
        out.append(
            "INSERT INTO address_reference (address_key, address_ko, detail_ko, postal_code, tracking_hash, source) "
            f"VALUES ({literal(key)}, {literal(ko)}, {literal(detail)}, {literal(postal.strip())}, "
            f"{literal(tracking_hash)}, {literal(source)}) ON CONFLICT (address_key) DO NOTHING;"
        )
    out.append("COMMIT;")
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--xlsx", required=True)
    parser.add_argument("--date", required=True, help="YYYY-MM-DD of the Jobs the file was made from")
    parser.add_argument("--database", default="ups_dev")
    parser.add_argument("--dry-run", action="store_true", help="count only; write nothing")
    args = parser.parse_args(argv)

    spec = importlib.util.spec_from_file_location("ours24_partner", Path(__file__).with_name("ours24_partner.py"))
    partner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(partner)

    tasks = read_tasks(args.xlsx)
    wanted = {t[TRACKING] for t in tasks if t.get(TRACKING)}
    originals, pages = ours24_rows(partner, args.date, wanted)

    counts = {"rows": len(tasks), "no_original": 0, "no_korean": 0, "conflict": 0, "duplicate": 0}
    chosen = {}
    for task in tasks:
        number = task.get(TRACKING)
        original = originals.get(number)
        if original is None:
            counts["no_original"] += 1
            continue
        ko = (task.get(DESTINATION) or "").strip()
        if not korean_only(ko):
            counts["no_korean"] += 1
            continue
        parts = (original.get("address1") or "", original.get("address2") or "",
                 original.get("postalCode") or "", original.get("companyName") or "")
        if not parts[0].strip():
            counts["no_original"] += 1
            continue
        key = address_key(*parts)
        detail = (task.get(DETAIL) or "").strip() or None
        if key in chosen:
            counts["duplicate" if chosen[key][2] == ko else "conflict"] += 1
            continue
        chosen[key] = (key, parts, ko, detail, hashlib.sha256(number.encode()).hexdigest())

    summary = {**counts, "to_write": len(chosen), "ours24_pages_read": pages, "dry_run": args.dry_run}
    if not args.dry_run and chosen:
        source = f"fleetune-upload-{args.date}"
        result = subprocess.run(
            ["psql", "-v", "ON_ERROR_STOP=1", "-q", args.database],
            input=build_sql(list(chosen.values()), source), text=True, capture_output=True,
        )
        if result.returncode != 0:
            sys.stderr.write("DB 입력에 실패했습니다. 아무것도 쓰지 않았습니다.\n")
            return 1
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
