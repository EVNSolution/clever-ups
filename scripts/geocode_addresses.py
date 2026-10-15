#!/usr/bin/env python3
"""Give a position to the addresses that have a Korean address and no position yet, with V-World.

    python3 scripts/geocode_addresses.py --dry-run
    python3 scripts/geocode_addresses.py --database ups_dev

V-World (vworld.kr, the Korean government's map service) is the one provider. The method is the one CLEVER's
DSV route server uses:
    source:   EVNSolution/clever-route-server, apps/delivery-api/src/modules/geocoding/vworld-geocoding.client.ts
    revision: 55c1a72be82c506870dffbfdc7c5c8cc8678188f
    kept:     the search call (service=search, type=address, category ROAD or PARCEL, crs EPSG:4326, size 1),
              ROAD first when the address has a road number, PARCEL first otherwise, and the check that the
              province of the answer is the province asked for.
    changed:  ported to Python; no cache table, no place search, no DB of its own. Positions go to
              address_location. UPS has its own key; DSV's key is not used.
    license:  internal EVNSolution repository; it carries no separate license file.

What leaves this computer: only the Korean address text (parentheses removed), one request per address, to
api.vworld.kr. No name, phone number or tracking number. Nothing leaves in --dry-run.

The key is read from secrets/vworld-api-key (mode 600) inside this program and is never printed:
    umask 077; read -rs "K?V-World API key: "; printf %s "$K" > secrets/vworld-api-key; unset K

A position outside Korea (latitude 33..39, longitude 124..132) is not written. An address that is not found is
left as it is and counted, so a later run tries it again. A rate-limit answer stops the run. Prints counts only.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from urllib import error, parse, request

ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = ROOT / "secrets" / "vworld-api-key"
SEARCH_URL = "https://api.vworld.kr/req/search"
USER_AGENT = "clever-ups-geocode/0.1"
LAT_RANGE, LNG_RANGE = (33.0, 39.0), (124.0, 132.0)
ROAD_NUMBER = re.compile(r"(?:대로|로|길)\s*\d")
RATE_LIMIT_CODES = {"OVER_REQUEST_LIMIT", "REQUEST_LIMIT_EXCEEDED", "TOO_MANY_REQUESTS"}
PROVINCE = re.compile(
    r"^(서울(?:특별시)?|부산(?:광역시)?|대구(?:광역시)?|인천(?:광역시)?|광주(?:광역시)?|대전(?:광역시)?|"
    r"울산(?:광역시)?|세종(?:특별자치시)?|경기(?:도)?|강원(?:특별자치도|도)?|충청북도|충북|충청남도|충남|"
    r"전북특별자치도|전라북도|전북|전라남도|전남|경상북도|경북|경상남도|경남|제주특별자치도|제주도|제주)(?=\s|$)"
)
SHORT = {
    "충청북도": "충북", "충청남도": "충남", "전북특별자치도": "전북", "전라북도": "전북", "전라남도": "전남",
    "경상북도": "경북", "경상남도": "경남",
}


class ProviderError(Exception):
    """The provider refused or failed. [code] is a short word, never the address."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def read_key():
    if not KEY_FILE.is_file():
        raise SystemExit("secrets/vworld-api-key 파일이 없습니다. 위 설명대로 터미널에서 입력하세요.")
    value = KEY_FILE.read_text().strip()
    if not value or len(value) > 200:
        raise SystemExit("secrets/vworld-api-key 파일의 내용이 올바르지 않습니다.")
    return value


def clean(text):
    """The address as V-World wants it: no parenthesized part, single spaces."""
    return re.sub(r"\s+", " ", re.sub(r"\([^)]*\)", " ", text)).strip()


def province(text):
    match = PROVINCE.match(text)
    if match is None:
        return None
    name = match.group(1)
    for head in ("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "제주"):
        if name.startswith(head):
            return head
    return SHORT.get(name, name)


def get_json(url, tries=3):
    """One answer, trying again after a short wait when the network or the server failed (never on a refusal)."""
    for attempt in range(tries):
        try:
            return _get_json(url)
        except ProviderError as exc:
            transient = exc.code == "NETWORK" or exc.code.startswith("HTTP_5")
            if not transient or attempt == tries - 1:
                raise
            time.sleep(1 + attempt)
    raise ProviderError("NETWORK")


def _get_json(url):
    req = request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with request.urlopen(req, timeout=15) as response:
            return json.loads(response.read(1_000_000))
    except error.HTTPError as exc:
        raise ProviderError("RATE_LIMITED" if exc.code == 429 else f"HTTP_{exc.code}") from None
    except (error.URLError, TimeoutError, OSError):
        raise ProviderError("NETWORK") from None
    except ValueError:
        raise ProviderError("INVALID_RESPONSE") from None


def search(address, category, key):
    """One V-World address search. Returns (lat, lng, label) or None when nothing is found."""
    query = {
        "service": "search", "request": "search", "version": "2.0", "crs": "EPSG:4326", "size": 1, "page": 1,
        "query": address, "type": "address", "category": category, "format": "json", "errorformat": "json",
        "key": key,
    }
    body = get_json(SEARCH_URL + "?" + parse.urlencode(query))
    response = body.get("response") if isinstance(body, dict) else None
    if not isinstance(response, dict):
        raise ProviderError("INVALID_RESPONSE")
    status = str(response.get("status", "")).upper()
    if status == "NOT_FOUND":
        return None
    if status == "ERROR":
        code = str((response.get("error") or {}).get("code", "UNKNOWN")).upper()
        raise ProviderError("RATE_LIMITED" if code in RATE_LIMIT_CODES else f"REJECTED_{code}")
    items = (response.get("result") or {}).get("items")
    if status != "OK" or not isinstance(items, list) or not items or not isinstance(items[0], dict):
        return None
    item = items[0]
    try:
        lat, lng = float(item["point"]["y"]), float(item["point"]["x"])
    except (KeyError, TypeError, ValueError):
        raise ProviderError("INVALID_RESPONSE") from None
    place = item.get("address") or {}
    label = place.get("road") if category == "ROAD" else place.get("parcel")
    return lat, lng, label or place.get("road") or place.get("parcel") or ""


def geocode(text, key):
    """(lat, lng) for a Korean address, or None. ROAD first for a road-number address, else PARCEL first."""
    address = clean(text)
    if not address:
        return None
    order = ("ROAD", "PARCEL") if ROAD_NUMBER.search(address) else ("PARCEL", "ROAD")
    asked = province(address)
    for category in order:
        found = search(address, category, key)
        if found is None:
            continue
        lat, lng, label = found
        answered = province(label) if label else None
        if asked is not None and answered is not None and asked != answered:
            continue
        return lat, lng
    return None


def psql(database, sql):
    result = subprocess.run(
        ["psql", "-v", "ON_ERROR_STOP=1", "-At", "-F", "\t", database], input=sql, text=True, capture_output=True
    )
    if result.returncode != 0:
        raise SystemExit("DB 작업에 실패했습니다.")
    return result.stdout


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--database", default="ups_dev")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true", help="count the addresses to do; send nothing")
    args = parser.parse_args(argv)

    rows = [
        line.split("\t", 1)
        for line in psql(
            args.database,
            "SELECT address_key, address_ko FROM address_location "
            "WHERE address_ko IS NOT NULL AND lat IS NULL AND status <> 'FAILED' "
            f"ORDER BY created_at, address_key LIMIT {int(args.limit)};",
        ).splitlines()
        if "\t" in line
    ]
    summary = {"provider": "vworld", "to_do": len(rows), "found": 0, "not_found": 0, "outside_korea": 0,
               "stopped": None, "dry_run": args.dry_run}
    if args.dry_run or not rows:
        print(json.dumps(summary, ensure_ascii=False))
        return 0

    key = read_key()
    updates = []
    for address_key, text in rows:
        try:
            point = geocode(text, key)
        except ProviderError as exc:
            summary["stopped"] = exc.code
            break
        if point is None:
            summary["not_found"] += 1
        elif not (LAT_RANGE[0] <= point[0] <= LAT_RANGE[1] and LNG_RANGE[0] <= point[1] <= LNG_RANGE[1]):
            summary["outside_korea"] += 1
        else:
            summary["found"] += 1
            updates.append(
                "UPDATE address_location SET status = 'DONE', lat = %r, lng = %r, updated_at = CURRENT_TIMESTAMP "
                "WHERE address_key = %s AND lat IS NULL;" % (point[0], point[1], literal(address_key))
            )
    if updates:
        psql(args.database, "BEGIN;\n" + "\n".join(updates) + "\nCOMMIT;")
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["stopped"] is None else 1


if __name__ == "__main__":
    sys.exit(main())
