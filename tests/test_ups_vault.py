import contextlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import secrets
import tempfile
import threading
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "ups_vault", Path(__file__).resolve().parents[1] / "scripts" / "ups_vault.py"
)
vault = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vault)

# Made at every run, with no password-like literal in the file, so a secret scanner has nothing to find.
# The vault does not judge a password's strength: any text is sealed as it is.
TEST_PASSWORD = secrets.token_urlsafe(18)
OTHER_PASSWORD = secrets.token_urlsafe(18)
AT1, AT2, RT1, CSRF1, CSRF2 = ("t" + secrets.token_hex(6) for _ in range(5))


class FakeApi(http.server.BaseHTTPRequestHandler):
    """A stand-in for the API: the access token is good once, then 401 until a refresh."""

    state = {"access": AT1, "refresh": RT1, "seen": []}

    def log_message(self, *args):
        pass

    def _send(self, status, body=b"", headers=()):
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        FakeApi.state["seen"].append((self.path, self.headers.get("Cookie"), self.headers.get("X-CSRF-Token")))
        if self.path == "/api/auth/login":
            if body.get("password") != TEST_PASSWORD:
                return self._send(401)
            return self._send(200, b'{"loginId":"ups-dev-owner","role":"owner"}', [
                ("Set-Cookie", f"UPS_SESSION={AT1}; Path=/; HttpOnly"),
                ("Set-Cookie", f"UPS_REFRESH={RT1}; Path=/api/auth; HttpOnly"),
                ("X-CSRF-Token", CSRF1),
            ])
        if self.path == "/api/auth/refresh":
            if f"UPS_REFRESH={RT1}" not in (self.headers.get("Cookie") or "") or not self.headers.get("X-UPS-Refresh"):
                return self._send(401)
            FakeApi.state["access"] = AT2
            return self._send(204, b"", [
                ("Set-Cookie", f"UPS_SESSION={AT2}; Path=/; HttpOnly"),
                ("Set-Cookie", f"UPS_REFRESH={RT1}; Path=/api/auth; HttpOnly"),
                ("X-CSRF-Token", CSRF2),
            ])
        return self._send(204)

    def do_GET(self):
        FakeApi.state["seen"].append((self.path, self.headers.get("Cookie"), None))
        if f"UPS_SESSION={FakeApi.state['access']}" not in (self.headers.get("Cookie") or ""):
            return self._send(401)
        if self.path.startswith("/api/orders"):
            return self._send(200, json.dumps([{"customerName": "Kim", "address1": "Secret Road 1"}]).encode())
        return self._send(200, b'{"loginId":"ups-dev-owner"}')


class VaultTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.patches = [
            patch.object(vault, "KEY_FILE", base / "config" / "vault.key"),
            patch.object(vault, "VAULT_DIR", base / "secrets"),
        ]
        for item in self.patches:
            item.start()
        FakeApi.state.update({"access": AT1, "refresh": RT1, "seen": []})
        self.server = http.server.HTTPServer(("127.0.0.1", 0), FakeApi)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base_patch = patch.object(vault, "BASE_URL", f"http://127.0.0.1:{self.server.server_port}")
        self.base_patch.start()
        self.plain = base / "plain.txt"
        self.plain.write_text(TEST_PASSWORD + "\n")

    def tearDown(self):
        self.base_patch.stop()
        for item in self.patches:
            item.stop()
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), patch("sys.argv", ["ups_vault", *argv]):
            code = vault.main()
        return code, out.getvalue(), err.getvalue()

    def test_seal_encrypts_removes_the_original_and_prints_no_password(self):
        self.run_cli("init")
        code, out, err = self.run_cli("seal", "--source", str(self.plain))
        self.assertEqual(code, 0)
        self.assertNotIn(TEST_PASSWORD, out + err)
        self.assertFalse(self.plain.exists())
        blob = (vault.VAULT_DIR / "ups-dev-owner.enc").read_bytes()
        self.assertNotIn(TEST_PASSWORD.encode(), blob)
        self.assertEqual(stat.S_IMODE((vault.VAULT_DIR / "ups-dev-owner.enc").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(vault.KEY_FILE.stat().st_mode), 0o600)

    def test_keep_original_leaves_the_source(self):
        self.run_cli("init")
        self.run_cli("seal", "--source", str(self.plain), "--keep-original")
        self.assertTrue(self.plain.exists())

    def test_login_then_call_never_prints_the_password_or_the_tokens(self):
        self.run_cli("init")
        self.run_cli("seal", "--source", str(self.plain))
        code, out, err = self.run_cli("login")
        self.assertEqual(code, 0, err)
        code, out2, err2 = self.run_cli("call", "GET", "/api/me")
        self.assertEqual(code, 0, err2)
        everything = out + err + out2 + err2
        for secret in (TEST_PASSWORD, AT1, RT1, CSRF1):
            self.assertNotIn(secret, everything)
        session_blob = (vault.VAULT_DIR / ".session-ups-dev-owner.enc").read_bytes()
        self.assertNotIn(AT1.encode(), session_blob)

    def test_an_array_prints_as_a_shape_not_as_customer_data(self):
        self.run_cli("init")
        self.run_cli("seal", "--source", str(self.plain))
        self.run_cli("login")
        _, out, _ = self.run_cli("call", "GET", "/api/orders")
        self.assertIn('"items": 1', out)
        self.assertNotIn("Secret Road", out)
        self.assertNotIn("Kim", out)
        _, raw, _ = self.run_cli("call", "GET", "/api/orders", "--raw")
        self.assertIn("Secret Road", raw)

    def test_a_401_is_answered_by_one_refresh_and_a_retry(self):
        self.run_cli("init")
        self.run_cli("seal", "--source", str(self.plain))
        self.run_cli("login")
        FakeApi.state["access"] = AT2  # the server now wants a token the client does not have yet
        code, out, err = self.run_cli("call", "GET", "/api/me")
        self.assertEqual(code, 0)
        self.assertIn("HTTP 200", out)
        paths = [seen[0] for seen in FakeApi.state["seen"]]
        self.assertEqual(paths.count("/api/auth/refresh"), 1)
        refresh_cookie = next(s[1] for s in FakeApi.state["seen"] if s[0] == "/api/auth/refresh")
        self.assertIn(f"UPS_REFRESH={RT1}", refresh_cookie)
        # The refresh cookie is scoped to /api/auth: ordinary calls must not carry it.
        me_cookie = [s[1] for s in FakeApi.state["seen"] if s[0] == "/api/me"][-1]
        self.assertNotIn("UPS_REFRESH", me_cookie)

    def test_a_wrong_password_fails_without_leaking_it(self):
        self.run_cli("init")
        self.plain.write_text(OTHER_PASSWORD + "\n")
        self.run_cli("seal", "--source", str(self.plain))
        code, out, err = self.run_cli("login")
        self.assertEqual(code, 1)
        self.assertIn("HTTP 401", err)
        self.assertNotIn(OTHER_PASSWORD, out + err)

    def test_a_loose_key_file_is_refused(self):
        self.run_cli("init")
        os.chmod(vault.KEY_FILE, 0o644)
        code, _, err = self.run_cli("seal", "--source", str(self.plain))
        self.assertEqual(code, 1)
        self.assertIn("chmod 600", err)


if __name__ == "__main__":
    unittest.main()
