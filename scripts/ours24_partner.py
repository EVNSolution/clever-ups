#!/usr/bin/env python3
"""Inject the local API key into bounded GET requests without printing it."""

import argparse
from datetime import date
import getpass
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import warnings
from urllib import error, parse, request


ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = ROOT / "secrets" / "ours24-api-key"
BASE_URL = "https://api.ours-24.com"
USER_AGENT = "CLEVER-UPS-Partner-Validation/1.0"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024


class LocalError(Exception):
    """An error whose fixed message can safely appear in tool output."""


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check_permissions():
    for path, mode in ((KEY_FILE.parent, 0o700), (KEY_FILE, 0o600)):
        try:
            info = path.lstat()
        except FileNotFoundError:
            raise LocalError("키 파일이 없습니다. 로컬 터미널에서 set-key를 실행하세요.") from None
        if stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid():
            raise LocalError("키 경로는 현재 사용자 소유의 일반 파일·디렉터리여야 합니다.")
        if stat.S_IMODE(info.st_mode) != mode:
            raise LocalError("secrets 디렉터리는 700, 키 파일은 600 권한이어야 합니다.")
    if not KEY_FILE.is_file() or not KEY_FILE.parent.is_dir():
        raise LocalError("키 경로 형식이 올바르지 않습니다.")


def validate_key(value):
    if not value or not re.fullmatch(r"[\x21-\x7e]{1,4096}", value):
        raise LocalError("키는 공백·줄바꿈 없는 ASCII 문자열이어야 합니다. 값은 출력하지 않습니다.")
    return value


def read_key():
    check_permissions()
    fd = os.open(KEY_FILE, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        raw = stream.read(4098)
    try:
        return validate_key(raw.decode("ascii").rstrip("\r\n"))
    except UnicodeError:
        raise LocalError("키 파일 인코딩이 올바르지 않습니다. set-key로 다시 입력하세요.") from None


def set_key():
    # Only the human runs this command in a local interactive terminal.
    if not sys.stdin.isatty():
        raise LocalError("set-key는 로컬 대화형 터미널에서 실행하세요.")
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            value = validate_key(getpass.getpass("Ours24 API 키 (입력 숨김): "))
        except getpass.GetPassWarning:
            raise LocalError("숨김 입력이 가능한 로컬 터미널에서 실행하세요.") from None
    directory = KEY_FILE.parent
    directory.mkdir(mode=0o700, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
        raise LocalError("secrets는 현재 사용자 소유의 일반 디렉터리여야 합니다.")
    directory.chmod(0o700)
    fd, temporary = tempfile.mkstemp(prefix=".ours24-key-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="ascii") as stream:
            stream.write(value + "\n")
        os.replace(temporary, KEY_FILE)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print("키 저장 완료. 키 값은 출력하지 않았습니다.")


def iso_date(value):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise argparse.ArgumentTypeError("YYYY-MM-DD 날짜를 입력하세요.")
    try:
        return date.fromisoformat(value).isoformat()
    except ValueError:
        raise argparse.ArgumentTypeError("YYYY-MM-DD 날짜를 입력하세요.") from None


def identifier(value):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise argparse.ArgumentTypeError("영문·숫자·밑줄·하이픈 식별자를 입력하세요.")
    return value


def build_target(args):
    if args.command == "jobs":
        if args.start_date > args.end_date:
            raise LocalError("시작일은 종료일보다 늦을 수 없습니다.")
        return "/jobs?" + parse.urlencode({
            "start_date": args.start_date, "end_date": args.end_date,
            "size": args.size, "page": 1,
        })
    if args.command == "tasks":
        return f"/jobs/{args.job_id}/tasks?" + parse.urlencode({"page": 1, "limit": args.limit})
    return f"/carriers/trackings/{args.tracking_number}?carrier=UPS"


def summarize(payload, command):
    """Report documented structure only; never return data or server messages."""
    if not isinstance(payload, dict):
        return {"schema_ok": False}
    if command in ("jobs", "tasks"):
        field = command
        items, total = payload.get(field), payload.get("total")
        valid_total = type(total) in (int, float) and 0 <= total <= 10**12
        valid = isinstance(items, list) and all(isinstance(item, dict) for item in items) and valid_total
        if command == "tasks" and valid:
            valid = all(
                type(item.get("rowNo")) is int and item["rowNo"] >= 1
                and item.get("status") in ("PENDING", "PROCESSING", "COMPLETED", "FAILED", "SKIPPED")
                for item in items
            ) and all(type(payload.get(key)) in (int, float) for key in ("page", "limit", "totalPages"))
        result = {"schema_ok": valid}
        if isinstance(items, list):
            result["returned_count"] = len(items)
        if valid_total:
            result["total"] = total
        return result
    valid = (
        type(payload.get("success")) is bool
        and all(isinstance(payload.get(key), str) for key in ("carrier", "trackingNumber", "status"))
        and payload.get("status") in ("RECEIVED", "IN_TRANSIT", "EXCEPTION", "EXCEPTION_PROCESSING", "DISPATCHED", "DELIVERED", "FAILED", "CANCELLED")
        and isinstance(payload.get("timeline"), list)
        and all(
            isinstance(item, dict) and all(isinstance(item.get(key), str) for key in ("stage", "status", "timestamp"))
            for item in payload["timeline"]
        )
    )
    return {"schema_ok": valid, "lookup_success": payload.get("success") is True}


SHAPE_MAX_PATHS = 150
SHAPE_MAX_ITEMS = 20
SHAPE_KEY = re.compile(r"[\w ()./-]{1,40}")  # \w also matches Hangul, so Excel column names stay readable
CODE_KEYS = frozenset({"status", "jobType", "type", "stage", "scanCode", "carrier", "state"})
CODE_VALUE = re.compile(r"[A-Z0-9_]{1,32}")


def json_type(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    return "array" if isinstance(value, list) else "object"


def describe_shape(payload):
    """Field paths and JSON types only. Values are never returned.

    The one exception is short upper-case codes under a few known keys such as status, which are
    vocabulary and not personal data. Keys that look like data are shown as <key>.
    """
    types, codes = {}, {}

    def walk(node, path, depth):
        if depth > 6 or len(types) >= SHAPE_MAX_PATHS:
            return
        types.setdefault(path, set()).add(json_type(node))
        if isinstance(node, dict):
            for name, child in node.items():
                key = name if SHAPE_KEY.fullmatch(name) else "<key>"
                child_path = f"{path}.{key}" if path else key
                if name in CODE_KEYS and isinstance(child, str) and CODE_VALUE.fullmatch(child):
                    codes.setdefault(child_path, set()).add(child)
                walk(child, child_path, depth + 1)
        elif isinstance(node, list):
            for item in node[:SHAPE_MAX_ITEMS]:
                walk(item, path + "[]", depth + 1)

    walk(payload, "", 0)
    return {
        "fields": {path or "$": sorted(kinds) for path, kinds in types.items()},
        "codes": {path: sorted(values)[:12] for path, values in codes.items()},
    }


def get(target, headers):
    """One bounded GET. Returns (status, is_json, body) or None on a network failure."""
    req = request.Request(BASE_URL + target, headers=headers, method="GET")
    # Fixed HTTPS origin, no redirects, no environment proxy, no automatic retry.
    opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
    try:
        response = opener.open(req, timeout=20)
    except error.HTTPError as exc:
        response = exc
    except (error.URLError, TimeoutError, OSError):
        return None
    with response:
        status = response.code
        is_json = response.headers.get_content_type() == "application/json"
        # Do not read error bodies or headers that could echo credentials.
        body = response.read(MAX_RESPONSE_BYTES + 1) if 200 <= status < 300 else b""
    return status, is_json, body


def first_job_id(day, headers):
    """ID of the first Job of a day, found and used inside the program so it never appears in output."""
    target = "/jobs?" + parse.urlencode({"start_date": day, "end_date": day, "size": 1, "page": 1})
    outcome = get(target, headers)
    if outcome is None or not 200 <= outcome[0] < 300 or not outcome[1]:
        raise LocalError("Job 목록을 조회하지 못했습니다.")
    try:
        jobs = json.loads(outcome[2]).get("jobs")
    except (ValueError, UnicodeError, AttributeError):
        raise LocalError("Job 목록 응답을 읽지 못했습니다.") from None
    job = jobs[0] if isinstance(jobs, list) and jobs else None
    value = job.get("id") if isinstance(job, dict) else None
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise LocalError("조회한 날짜에 사용할 수 있는 Job이 없습니다.")
    return value


def first_tracking_number(day, headers):
    """Tracking number of the first row of the first Job of a day. It stays inside the program."""
    job_id = first_job_id(day, headers)
    outcome = get(f"/jobs/{job_id}/tasks?" + parse.urlencode({"page": 1, "limit": 1}), headers)
    if outcome is None or not 200 <= outcome[0] < 300 or not outcome[1]:
        raise LocalError("Job의 행을 조회하지 못했습니다.")
    try:
        tasks = json.loads(outcome[2]).get("tasks")
    except (ValueError, UnicodeError, AttributeError):
        raise LocalError("Job 행 응답을 읽지 못했습니다.") from None
    row = tasks[0] if isinstance(tasks, list) and tasks else None
    value = row.get("trackingNumber") if isinstance(row, dict) else None
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise LocalError("조회한 날짜에 사용할 수 있는 운송장이 없습니다.")
    return value


def call_api(args):
    # Identify this client. The default urllib User-Agent received 403 on 2026-10-07.
    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    if not args.without_key:
        headers["x-api-key"] = read_key()
    if args.command == "tracking":
        headers.update({"x-api-version": "1", "x-carrier-environment": "dev"})
    if args.command == "tasks" and args.from_date:
        args.job_id = first_job_id(args.from_date, headers)
    if args.command == "tracking" and args.from_date:
        args.tracking_number = first_tracking_number(args.from_date, headers)
    outcome = get(build_target(args), headers)
    if outcome is None:
        print(json.dumps({"request": args.command, "network_ok": False}))
        return 1
    status, is_json, raw = outcome
    result = {"request": args.command, "http_status": status, "json_response": is_json}
    if not 200 <= status < 300:
        print(json.dumps(result))
        return 1
    if len(raw) > MAX_RESPONSE_BYTES:
        result["response_too_large"] = True
    elif is_json:
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeError):
            result["schema_ok"] = False
        else:
            result.update(summarize(payload, args.command))
            if args.shape:
                result["shape"] = describe_shape(payload)
    else:
        result["schema_ok"] = False
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result.get("schema_ok") and result.get("lookup_success", True) else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("set-key", help="사용자가 로컬 터미널에서 키를 숨김 입력합니다.")
    sub.add_parser("check-key", help="내용을 읽지 않고 파일 권한과 비어 있는지 확인합니다.")
    jobs = sub.add_parser("jobs", help="Job 목록을 한 페이지만 조회합니다.")
    jobs.add_argument("--start-date", type=iso_date, required=True)
    jobs.add_argument("--end-date", type=iso_date, required=True)
    jobs.add_argument("--size", type=int, choices=range(1, 101), default=1)
    tasks = sub.add_parser("tasks", help="지정 Job의 행을 한 페이지만 조회합니다.")
    which = tasks.add_mutually_exclusive_group(required=True)
    which.add_argument("--job-id", type=identifier)
    which.add_argument("--from-date", type=iso_date, help="그날의 첫 Job을 쓰되 ID는 출력하지 않습니다.")
    tasks.add_argument("--limit", type=int, choices=range(1, 201), default=1)
    tracking = sub.add_parser("tracking", help="UPS 운송장 한 건을 dev 환경으로 조회합니다.")
    one = tracking.add_mutually_exclusive_group(required=True)
    one.add_argument("--tracking-number", type=identifier)
    one.add_argument("--from-date", type=iso_date, help="그날 첫 Job의 첫 행 운송장을 쓰되 번호는 출력하지 않습니다.")
    for command in (jobs, tasks, tracking):
        command.add_argument("--without-key", action="store_true", help="키를 읽지 않고 인증 실패 응답을 확인합니다.")
        command.add_argument("--shape", action="store_true", help="값 없이 필드 경로와 형식만 출력합니다.")
    args = parser.parse_args(argv)
    try:
        if args.command == "set-key":
            set_key()
            return 0
        if args.command == "check-key":
            check_permissions()
            ready = KEY_FILE.stat().st_size > 0
            print(json.dumps({"key_file_ready": ready, "directory_mode": "700", "file_mode": "600"}))
            return 0 if ready else 1
        return call_api(args)
    except LocalError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print("입력을 취소했습니다.", file=sys.stderr)
        return 1
    except Exception:
        # Transport/parser exceptions may contain server text or credential echoes.
        # Do not emit their repr or traceback to agent-visible tool output.
        print("로컬 설정 또는 응답 처리에 실패했습니다. 비밀정보는 출력하지 않습니다.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
