#!/usr/bin/env python3
"""Keep the owner password in an encrypted file and call the local API with it, without ever printing it.

    python3 scripts/ups_vault.py init                      # make the key file, once
    python3 scripts/ups_vault.py seal --source <file>      # encrypt a plain password file, remove the plain file
    python3 scripts/ups_vault.py login                     # sign in; the session is kept encrypted too
    python3 scripts/ups_vault.py call GET /api/me          # call the API; arrays print as a shape, not as data
    python3 scripts/ups_vault.py logout

Where things are:
  - secrets/<name>.enc and secrets/.session-<name>.enc: AES-256-CBC (PBKDF2, 600,000 rounds) by `openssl`.
    `secrets/` is ignored by Git. The password never appears in an argument, an environment variable or output.
  - The key is a file outside the project (UPS_VAULT_KEY_FILE, default ~/.config/ups/vault.key, mode 600).
    It is not under Documents, so a folder sync or a backup of the project does not carry it.

What this protects, and what it does not:
  - It protects against the file being attached, pasted, committed, synced or backed up in clear.
  - It does not protect against a program that runs as the same user on this Mac: that program can read the key
    file and decrypt. For that, keep the key on an encrypted disk image or in a password manager.
"""

import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
from urllib import error, request

ROOT = Path(__file__).resolve().parents[1]
VAULT_DIR = ROOT / "secrets"
KEY_FILE = Path(os.environ.get("UPS_VAULT_KEY_FILE", str(Path.home() / ".config" / "ups" / "vault.key")))
BASE_URL = os.environ.get("UPS_API_BASE", "http://127.0.0.1:8080")
ROUNDS = "600000"
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
SHOWN_HEADERS = ("X-Data-Incomplete", "Retry-After")


class LocalError(Exception):
    """A problem the person can fix. The message never holds a secret."""


def private_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(data)
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)


def ensure_key(create: bool) -> None:
    if KEY_FILE.is_file():
        if KEY_FILE.stat().st_mode & 0o077:
            raise LocalError("The key file must be readable by this user only (chmod 600).")
        return
    if not create:
        raise LocalError("There is no key file. Run `init` first.")
    private_write(KEY_FILE, base64.b64encode(secrets.token_bytes(32)) + b"\n")


def openssl(args: list[str], data: bytes) -> bytes:
    command = ["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", ROUNDS, "-pass", f"file:{KEY_FILE}", *args]
    done = subprocess.run(command, input=data, capture_output=True, check=False)
    if done.returncode != 0:
        raise LocalError("openssl could not encrypt or decrypt (wrong key file, or a damaged file).")
    return done.stdout


def encrypt(data: bytes) -> bytes:
    return openssl(["-salt"], data)


def decrypt(blob: bytes) -> bytes:
    return openssl(["-d"], blob)


def vault_path(name: str) -> Path:
    return VAULT_DIR / f"{name}.enc"


def session_path(name: str) -> Path:
    return VAULT_DIR / f".session-{name}.enc"


def load_json(path: Path) -> dict:
    if not path.is_file():
        raise LocalError(f"{path.name} does not exist.")
    ensure_key(create=False)
    return json.loads(decrypt(path.read_bytes()))


def save_json(path: Path, value: dict) -> None:
    private_write(path, encrypt(json.dumps(value).encode()))


def parse_set_cookie(header: str) -> tuple[str, dict]:
    parts = [part.strip() for part in header.split(";")]
    name, _, value = parts[0].partition("=")
    attributes = {}
    for part in parts[1:]:
        key, _, attribute = part.partition("=")
        attributes[key.lower()] = attribute
    return name, {"value": value, "path": attributes.get("path", "/"), "gone": attributes.get("max-age") == "0"}


def apply_cookies(jar: dict, headers: list[str]) -> None:
    for header in headers:
        name, cookie = parse_set_cookie(header)
        if cookie["gone"] or cookie["value"] == "":
            jar.pop(name, None)
        else:
            jar[name] = {"value": cookie["value"], "path": cookie["path"]}


def cookie_header(jar: dict, path: str) -> str:
    return "; ".join(f"{name}={c['value']}" for name, c in jar.items() if path.startswith(c["path"]))


def send(method: str, path: str, jar: dict, csrf: str | None, body: object | None = None,
         extra: dict | None = None) -> tuple[int, dict, bytes, list[str]]:
    headers = {"Accept": "application/json", "User-Agent": "CLEVER-UPS-Vault/1.0", **(extra or {})}
    cookies = cookie_header(jar, path.split("?")[0])
    if cookies:
        headers["Cookie"] = cookies
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if csrf and method != "GET":
        headers["X-CSRF-Token"] = csrf
    req = request.Request(BASE_URL + path, data=data, method=method, headers=headers)
    try:
        with request.urlopen(req, timeout=60) as response:
            return response.status, dict(response.headers), response.read(MAX_RESPONSE_BYTES), response.headers.get_all("Set-Cookie") or []
    except error.HTTPError as problem:
        return problem.code, dict(problem.headers), problem.read(MAX_RESPONSE_BYTES), problem.headers.get_all("Set-Cookie") or []
    except error.URLError as problem:
        raise LocalError(f"Cannot reach the API at {BASE_URL}. Is it running?") from problem


def header(headers: dict, name: str) -> str | None:
    for key, value in headers.items():
        if key.lower() == name.lower():
            return value
    return None


def shape(value: object) -> str:
    """What a response holds, without its content: counts and field names only."""
    if isinstance(value, list):
        keys = sorted(value[0]) if value and isinstance(value[0], dict) else []
        return json.dumps({"items": len(value), "fields": keys}, ensure_ascii=False)
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False)
    return json.dumps({"type": type(value).__name__})


def cmd_init(_: argparse.Namespace) -> None:
    ensure_key(create=True)
    print(f"key file ready: {KEY_FILE} (mode 600)")


def cmd_seal(args: argparse.Namespace) -> None:
    ensure_key(create=True)
    source = Path(args.source).expanduser()
    if not source.is_file():
        raise LocalError("The source file does not exist.")
    password = source.read_bytes().decode().strip("\r\n")
    if not password:
        raise LocalError("The source file is empty.")
    payload = json.dumps({"loginId": args.login_id, "password": password}).encode()
    blob = encrypt(payload)
    if decrypt(blob) != payload:
        raise LocalError("The encrypted copy did not read back the same. The source file was left in place.")
    private_write(vault_path(args.name), blob)
    print(f"sealed: secrets/{args.name}.enc ({len(blob)} bytes), login ID {args.login_id}")
    if args.keep_original:
        print("the original file was kept; delete it yourself")
        return
    source.write_bytes(os.urandom(source.stat().st_size))
    source.unlink()
    print("the original file was overwritten and removed")


def cmd_login(args: argparse.Namespace) -> None:
    creds = load_json(vault_path(args.name))
    jar: dict = {}
    status, headers, body, set_cookies = send(
        "POST", "/api/auth/login", jar, None, {"loginId": creds["loginId"], "password": creds["password"]})
    if status != 200:
        retry = header(headers, "Retry-After")
        raise LocalError(f"login failed: HTTP {status}" + (f", retry after {retry} s" if retry else ""))
    apply_cookies(jar, set_cookies)
    save_json(session_path(args.name), {"cookies": jar, "csrf": header(headers, "X-CSRF-Token")})
    print(f"signed in: {body.decode()}")


def refresh(session: dict) -> bool:
    status, headers, _, set_cookies = send("POST", "/api/auth/refresh", session["cookies"], None,
                                           extra={"X-UPS-Refresh": "1"})
    apply_cookies(session["cookies"], set_cookies)
    if status == 204:
        session["csrf"] = header(headers, "X-CSRF-Token")
        return True
    return False


def cmd_call(args: argparse.Namespace) -> None:
    session = load_json(session_path(args.name))
    body = json.loads(args.json) if args.json else None
    status, headers, payload, set_cookies = send(args.method, args.path, session["cookies"], session["csrf"], body)
    if status == 401 and refresh(session):
        save_json(session_path(args.name), session)
        status, headers, payload, set_cookies = send(args.method, args.path, session["cookies"], session["csrf"], body)
        print("(the access token was renewed)", file=sys.stderr)
    apply_cookies(session["cookies"], set_cookies)
    save_json(session_path(args.name), session)
    shown = {name: header(headers, name) for name in SHOWN_HEADERS if header(headers, name)}
    print(f"HTTP {status} {shown if shown else ''}".rstrip())
    if not payload:
        return
    if args.raw:
        sys.stdout.write(payload.decode(errors="replace") + "\n")
        return
    try:
        print(shape(json.loads(payload)))
    except json.JSONDecodeError:
        print(f"({len(payload)} bytes, not JSON)")


def cmd_logout(args: argparse.Namespace) -> None:
    path = session_path(args.name)
    if not path.is_file():
        print("not signed in")
        return
    session = load_json(path)
    status, _, _, _ = send("POST", "/api/auth/logout", session["cookies"], session["csrf"])
    path.unlink()
    print(f"signed out (HTTP {status}); the local session was removed")


def cmd_status(args: argparse.Namespace) -> None:
    print(f"key file : {'present' if KEY_FILE.is_file() else 'missing'} ({KEY_FILE})")
    print(f"vault    : {'present' if vault_path(args.name).is_file() else 'missing'} (secrets/{args.name}.enc)")
    print(f"session  : {'present' if session_path(args.name).is_file() else 'none'}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", default="ups-dev-owner", help="name of the sealed credential")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init").set_defaults(run=cmd_init)
    seal = commands.add_parser("seal")
    seal.add_argument("--source", required=True, help="a plain file that holds only the password")
    seal.add_argument("--login-id", default="ups-dev-owner")
    seal.add_argument("--keep-original", action="store_true")
    seal.set_defaults(run=cmd_seal)
    commands.add_parser("login").set_defaults(run=cmd_login)
    call = commands.add_parser("call")
    call.add_argument("method", choices=["GET", "POST", "PUT", "DELETE"])
    call.add_argument("path", help="for example /api/me or '/api/orders?from=2026-10-11'")
    call.add_argument("--json", help="a JSON body for POST or PUT")
    call.add_argument("--raw", action="store_true", help="print the whole body (it may hold customer data)")
    call.set_defaults(run=cmd_call)
    commands.add_parser("logout").set_defaults(run=cmd_logout)
    commands.add_parser("status").set_defaults(run=cmd_status)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        args.run(args)
    except LocalError as problem:
        print(f"error: {problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
