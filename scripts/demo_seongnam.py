#!/usr/bin/env python3
"""Set up the Seongnam center of one operating day from the Fleetune upload file, for a demo.

    python3 scripts/import_reference.py --xlsx "<file>" --date 2026-10-05          # first: the reference set
    python3 scripts/demo_seongnam.py   --xlsx "<file>" --date 2026-10-05 --dry-run
    python3 scripts/demo_seongnam.py   --xlsx "<file>" --date 2026-10-05 --database ups_dev

It writes master data into the database, not into product code:
  - the center (depot, quantity, minutes, sender, notice) from the constants of the `tasks` sheet,
  - the zones and drivers of the `drivers` sheet, each driver with its main zone (the first one listed) and
    the others it serves,
  - the center of the Jobs that hold the file's tracking numbers (job_center, once per Job),
  - the Korean address of the reference set on the matching addresses (ko_source REFERENCE), where a Korean
    address is not there yet.

It never stores a driver's name or phone number, nor a customer's. A vehicle number is the driver code.
It prints counts only and is safe to run again.
"""
import argparse
import importlib.util
import json
import subprocess
import sys
import uuid
from pathlib import Path
from urllib import parse

HERE = Path(__file__).resolve().parent
NAMESPACE = uuid.UUID("5a3f0c1e-7d94-4e6b-9a52-0c1f6d8b3e47")


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def literal(value):
    return "NULL" if value is None else "'" + str(value).replace("'", "''") + "'"


def stable_id(*parts):
    return str(uuid.uuid5(NAMESPACE, ":".join(parts)))


def single(values, what):
    """The one value a column holds in every row. Several values mean the file is not one center's."""
    distinct = {v for v in values if v not in (None, "")}
    if len(distinct) > 1:
        raise SystemExit(f"{what}: 파일에 값이 여러 개입니다. 한 센터의 파일이 아닙니다.")
    return next(iter(distinct), None)


def center_row(tasks):
    note = next((t.get("노트(3)") for t in tasks if t.get("노트(3)")), "")
    code = (note.split(":", 1)[0] or "").strip().upper()
    parts = [p.strip() for p in note.split(",")]
    city = parts[-1].split(":")[0] if parts and ":" in parts[-1] else ""
    return {
        "code": code,
        "name": single((t.get("출발지 이름") for t in tasks), "출발지 이름") or "",
        "depot": single((t.get("출발지") for t in tasks), "출발지") or "",
        "quantity": int(single((t.get("수송량") for t in tasks), "수송량") or 1),
        "minutes": int(single((t.get("작업시간(분)") for t in tasks), "작업시간(분)") or 5),
        "sender": single((t.get("작업완료 발신자") for t in tasks), "작업완료 발신자") or "UPS",
        "notice": single((t.get("작업완료 알림") for t in tasks), "작업완료 알림") == "O",
        "city": city,
    }


def jobs_holding(partner, day, wanted):
    """Ids of the Jobs of the day that hold every wanted tracking number. Ids stay inside the program."""
    headers = {"Accept": "application/json", "User-Agent": partner.USER_AGENT, "x-api-key": partner.read_key()}
    outcome = partner.get("/jobs?" + parse.urlencode({"start_date": day, "end_date": day, "size": 100, "page": 1}), headers)
    if outcome is None or not 200 <= outcome[0] < 300:
        raise SystemExit("Job 목록을 조회하지 못했습니다.")
    found = []
    for job in json.loads(outcome[2]).get("jobs", []):
        seen, page = set(), 1
        while True:
            outcome = partner.get(
                f"/jobs/{job['id']}/tasks?" + parse.urlencode({"page": page, "limit": 100}), headers
            )
            if outcome is None or not 200 <= outcome[0] < 300:
                raise SystemExit("Job 행을 조회하지 못했습니다.")
            body = json.loads(outcome[2])
            seen.update(r.get("trackingNumber") for r in body.get("tasks", []))
            if page >= body.get("totalPages", 1) or not body.get("tasks"):
                break
            page += 1
        if wanted <= seen:
            found.append(job["id"])
    return found


def build_sql(center, zones, drivers, job_ids):
    code = center["code"]
    out = ["BEGIN;"]
    out.append(
        "INSERT INTO center (center_code, name_ko, city_en, depot_name, depot_address_ko, default_quantity, "
        "default_task_min, notify_sender, notify_on_complete) VALUES ("
        f"{literal(code)}, {literal(center['name'])}, {literal(center['city'])}, {literal(center['name'])}, "
        f"{literal(center['depot'])}, {center['quantity']}, {center['minutes']}, {literal(center['sender'])}, "
        f"{'TRUE' if center['notice'] else 'FALSE'}) ON CONFLICT (center_code) DO UPDATE SET "
        "name_ko = EXCLUDED.name_ko, city_en = EXCLUDED.city_en, depot_name = EXCLUDED.depot_name, "
        "depot_address_ko = EXCLUDED.depot_address_ko, default_quantity = EXCLUDED.default_quantity, "
        "default_task_min = EXCLUDED.default_task_min, notify_sender = EXCLUDED.notify_sender, "
        "notify_on_complete = EXCLUDED.notify_on_complete, updated_at = CURRENT_TIMESTAMP;"
    )
    for name in zones:
        out.append(
            "INSERT INTO zone (zone_id, center_code, name) VALUES "
            f"({literal(stable_id(code, 'zone', name))}, {literal(code)}, {literal(name)}) "
            "ON CONFLICT (center_code, name) DO NOTHING;"
        )
    for d in drivers:
        did = stable_id(code, "driver", d["code"])
        out.append(
            "INSERT INTO driver (driver_id, center_code, driver_code, vehicle_no, max_load) VALUES "
            f"({literal(did)}, {literal(code)}, {literal(d['code'])}, {literal(d['code'])}, "
            f"{'NULL' if d['load'] is None else d['load']}) ON CONFLICT (center_code, driver_code) DO NOTHING;"
        )
        for zone in d["zones"]:
            out.append(
                "INSERT INTO driver_zone (driver_id, zone_id) VALUES "
                f"({literal(did)}, (SELECT zone_id FROM zone WHERE center_code = {literal(code)} "
                f"AND name = {literal(zone)})) ON CONFLICT DO NOTHING;"
            )
        if d["zones"]:
            out.append(
                f"UPDATE driver SET main_zone_id = (SELECT zone_id FROM zone WHERE center_code = {literal(code)} "
                f"AND name = {literal(d['zones'][0])}) WHERE driver_id = {literal(did)} AND main_zone_id IS NULL;"
            )
    for job_id in job_ids:
        out.append(
            "INSERT INTO job_center (job_id, center_code, method) VALUES "
            f"({literal(job_id)}, {literal(code)}, 'MANUAL') ON CONFLICT (job_id) DO NOTHING;"
        )
    out.append(
        "UPDATE address_location SET address_ko = r.address_ko, detail_ko = r.detail_ko, ko_source = 'REFERENCE', "
        "updated_at = CURRENT_TIMESTAMP FROM address_reference r "
        "WHERE r.address_key = address_location.address_key AND address_location.address_ko IS NULL;"
    )
    out.append("COMMIT;")
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--xlsx", required=True)
    parser.add_argument("--date", required=True)
    parser.add_argument("--database", default="ups_dev")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    reference = load("import_reference")
    partner = load("ours24_partner")
    tasks = reference.read_named_sheet(args.xlsx, "tasks")
    sheet = reference.read_named_sheet(args.xlsx, "drivers")

    center = center_row(tasks)
    if not center["code"]:
        raise SystemExit("센터 코드를 파일에서 찾지 못했습니다.")
    zones, drivers = [], []
    for row in sheet:
        vehicle = (row.get("차량번호") or "").strip()
        if not vehicle:
            continue
        listed = [z.strip() for z in (row.get("담당권역") or "").split(",") if z.strip()]
        for zone in listed:
            if zone not in zones:
                zones.append(zone)
        load_text = (row.get("최대 수송량") or "").strip()
        drivers.append({"code": vehicle, "zones": listed, "load": int(float(load_text)) if load_text else None})

    wanted = {t.get("노트(2)") for t in tasks if t.get("노트(2)")}
    job_ids = jobs_holding(partner, args.date, wanted)

    summary = {
        "center": center["code"], "zones": len(zones), "drivers": len(drivers),
        "drivers_without_zone": sum(1 for d in drivers if not d["zones"]),
        "jobs_for_center": len(job_ids), "dry_run": args.dry_run,
    }
    if not args.dry_run:
        result = subprocess.run(
            ["psql", "-v", "ON_ERROR_STOP=1", "-q", args.database],
            input=build_sql(center, zones, drivers, job_ids), text=True, capture_output=True,
        )
        if result.returncode != 0:
            sys.stderr.write("DB 입력에 실패했습니다. 아무것도 쓰지 않았습니다.\n")
            return 1
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
