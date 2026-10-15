import contextlib
from email.message import Message
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib import error


spec = importlib.util.spec_from_file_location(
    "ours24_partner", Path(__file__).resolve().parents[1] / "scripts" / "ours24_partner.py"
)
partner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(partner)

FAKE_KEY = "TEST-ONLY-NOT-A-REAL-KEY"
JOBS_ARGS = ["jobs", "--start-date", "2026-10-07", "--end-date", "2026-10-07"]


def response(payload, status=200, content_type="application/json"):
    headers = Message()
    headers["Content-Type"] = content_type
    result = io.BytesIO(json.dumps(payload).encode())
    result.code = status
    result.headers = headers
    return result


class PartnerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.key = Path(self.directory.name) / "secrets" / "ours24-api-key"
        self.key.parent.mkdir(mode=0o700)
        self.key.write_text(FAKE_KEY + "\n")
        self.key.chmod(0o600)
        patcher = patch.object(partner, "KEY_FILE", self.key)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_main(self, args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = partner.main(args)
        self.assertNotIn(FAKE_KEY, out.getvalue() + err.getvalue())
        return code, out.getvalue(), err.getvalue()

    def test_shape_lists_field_names_and_types_but_no_values(self):
        payload = {
            "jobs": [{"id": "job-secret-id", "status": "COMPLETED", "owner": "Hong Gildong", "phone": "010-1234-5678"}],
            "total": 1,
            "수취인명": "홍길동",
            "weird key {a}": "x",
        }
        with patch.object(partner.request.OpenerDirector, "open", return_value=response(payload)):
            code, output, _ = self.run_main(JOBS_ARGS + ["--shape"])
        self.assertEqual(code, 0)
        shape = json.loads(output)["shape"]
        self.assertEqual(shape["fields"]["jobs[].phone"], ["string"])
        self.assertEqual(shape["fields"]["수취인명"], ["string"])
        self.assertIn("<key>", shape["fields"])
        self.assertEqual(shape["codes"], {"jobs[].status": ["COMPLETED"]})
        for value in ("job-secret-id", "Hong Gildong", "010-1234-5678", "홍길동"):
            self.assertNotIn(value, output)

    def test_tasks_from_date_uses_the_first_job_without_printing_its_id(self):
        jobs = response({"jobs": [{"id": "job-secret-id"}], "total": 1})
        tasks = response({"tasks": [{"rowNo": 1, "status": "READY", "주소": "서울시 어딘가"}], "total": 1,
                          "page": 1, "limit": 1, "totalPages": 1})
        with patch.object(partner.request.OpenerDirector, "open", side_effect=[jobs, tasks]) as opened:
            code, output, _ = self.run_main(["tasks", "--from-date", "2026-10-07", "--shape"])
        self.assertEqual(code, 1)  # READY is not in the documented task statuses, so the schema check fails
        self.assertIn("/jobs/job-secret-id/tasks", opened.call_args_list[1].args[0].full_url)
        self.assertEqual(opened.call_args_list[1].args[0].get_header("X-api-key"), FAKE_KEY)
        self.assertIn("tasks[].주소", json.loads(output)["shape"]["fields"])
        for value in ("job-secret-id", "서울시 어딘가"):
            self.assertNotIn(value, output)

    def test_tracking_from_date_uses_the_first_row_without_printing_its_number(self):
        jobs = response({"jobs": [{"id": "job-secret-id"}], "total": 1})
        tasks = response({"tasks": [{"rowNo": 1, "status": "PENDING", "trackingNumber": "1ZSECRET123"}], "total": 1,
                          "page": 1, "limit": 1, "totalPages": 1})
        tracking = response({"success": True, "carrier": "UPS", "trackingNumber": "1ZSECRET123", "status": "IN_TRANSIT",
                             "timeline": [{"stage": "CARRIER_RES", "status": "IN_TRANSIT", "timestamp": "2026-10-07T00:00:00Z"}]})
        with patch.object(partner.request.OpenerDirector, "open", side_effect=[jobs, tasks, tracking]) as opened:
            code, output, _ = self.run_main(["tracking", "--from-date", "2026-10-07", "--shape"])
        self.assertEqual(code, 0)
        self.assertIn("/carriers/trackings/1ZSECRET123", opened.call_args_list[2].args[0].full_url)
        self.assertEqual(opened.call_args_list[2].args[0].get_header("X-carrier-environment"), "dev")
        for value in ("job-secret-id", "1ZSECRET123"):
            self.assertNotIn(value, output)

    def test_tasks_from_date_stops_when_the_day_has_no_job(self):
        with patch.object(partner.request.OpenerDirector, "open", return_value=response({"jobs": [], "total": 0})):
            code, _, err = self.run_main(["tasks", "--from-date", "2026-10-03"])
        self.assertEqual(code, 1)
        self.assertIn("Job", err)

    def test_shape_is_off_by_default(self):
        payload = {"jobs": [{"id": "x"}], "total": 1}
        with patch.object(partner.request.OpenerDirector, "open", return_value=response(payload)):
            _, output, _ = self.run_main(JOBS_ARGS)
        self.assertNotIn("shape", json.loads(output))

    def test_jobs_injects_key_without_exposing_response_values(self):
        payload = {"jobs": [{"unexpected": FAKE_KEY, "phone": "private-phone"}], "total": 1}
        with patch.object(partner.request.OpenerDirector, "open", return_value=response(payload)) as opened:
            code, output, _ = self.run_main(JOBS_ARGS)
        req = opened.call_args.args[0]
        self.assertEqual(req.get_method(), "GET")
        self.assertEqual(req.get_header("X-api-key"), FAKE_KEY)
        self.assertEqual(req.get_header("User-agent"), "CLEVER-UPS-Partner-Validation/1.0")
        self.assertEqual(req.full_url, partner.BASE_URL + "/jobs?start_date=2026-10-07&end_date=2026-10-07&size=1&page=1")
        self.assertEqual(code, 0)
        self.assertNotIn("private-phone", output)
        self.assertEqual(json.loads(output)["returned_count"], 1)

    def test_http_error_does_not_read_or_print_body(self):
        body = io.BytesIO(FAKE_KEY.encode())
        exc = error.HTTPError(partner.BASE_URL, 401, FAKE_KEY, Message(), body)
        with patch.object(body, "read", side_effect=AssertionError("must not read")) as body_read, patch.object(
            partner.request.OpenerDirector, "open", side_effect=exc
        ):
            code, output, _ = self.run_main(JOBS_ARGS)
        body_read.assert_not_called()
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output)["http_status"], 401)

    def test_network_error_message_is_not_printed(self):
        with patch.object(partner.request.OpenerDirector, "open", side_effect=error.URLError(FAKE_KEY)):
            code, output, _ = self.run_main(JOBS_ARGS)
        self.assertEqual(code, 1)
        self.assertFalse(json.loads(output)["network_ok"])

    def test_unexpected_transport_exception_does_not_print_credential_echo(self):
        with patch.object(partner.request.OpenerDirector, "open", side_effect=http.client.BadStatusLine(FAKE_KEY)):
            self.assertEqual(self.run_main(JOBS_ARGS)[0], 1)

    def test_response_read_exception_does_not_print_partial_body(self):
        broken = response({})
        with patch.object(broken, "read", side_effect=http.client.IncompleteRead(FAKE_KEY.encode())), patch.object(
            partner.request.OpenerDirector, "open", return_value=broken
        ):
            self.assertEqual(self.run_main(JOBS_ARGS)[0], 1)

    def test_redirect_does_not_forward_request(self):
        req = partner.request.Request(partner.BASE_URL, headers={"x-api-key": FAKE_KEY})
        for status in (301, 302, 303, 307, 308):
            self.assertIsNone(partner.NoRedirect().redirect_request(req, None, status, FAKE_KEY, {}, "https://example.com"))

    def test_no_environment_proxy_and_redirect_handler_installed(self):
        with patch.object(partner.request, "build_opener") as builder:
            builder.return_value.open.return_value = response({"jobs": [], "total": 0})
            self.run_main(JOBS_ARGS)
        proxy, redirect = builder.call_args.args
        self.assertEqual(proxy.proxies, {})
        self.assertIsInstance(redirect, partner.NoRedirect)

    def test_without_key_never_reads_secret(self):
        with patch.object(partner, "read_key", side_effect=AssertionError("must not read")), patch.object(
            partner.request.OpenerDirector, "open", return_value=response({"jobs": [], "total": 0})
        ) as opened:
            self.run_main(JOBS_ARGS + ["--without-key"])
        self.assertIsNone(opened.call_args.args[0].get_header("X-api-key"))

    def test_check_key_uses_metadata_only(self):
        with patch.object(partner, "read_key", side_effect=AssertionError("must not read")):
            code, output, _ = self.run_main(["check-key"])
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(output)["key_file_ready"])

    def test_set_key_hides_output_and_preserves_private_permissions(self):
        with patch.object(partner.sys.stdin, "isatty", return_value=True), patch.object(
            partner.getpass, "getpass", return_value=FAKE_KEY
        ):
            code, _, _ = self.run_main(["set-key"])
        self.assertEqual(code, 0)
        self.assertEqual(self.key.read_text(), FAKE_KEY + "\n")
        self.assertEqual(self.key.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.key.parent.stat().st_mode & 0o777, 0o700)

    def test_set_key_rejects_echo_fallback(self):
        with patch.object(partner.sys.stdin, "isatty", return_value=True), patch.object(
            partner.getpass, "getpass", side_effect=partner.getpass.GetPassWarning("echo risk")
        ):
            self.assertEqual(self.run_main(["set-key"])[0], 1)

    def test_rejects_public_permissions_and_symlink(self):
        self.key.chmod(0o644)
        self.assertEqual(self.run_main(JOBS_ARGS)[0], 1)
        self.key.unlink()
        self.key.symlink_to(Path(self.directory.name) / "other-key")
        self.assertEqual(self.run_main(JOBS_ARGS)[0], 1)

    def test_rejects_empty_or_multiline_key(self):
        for value in ("", FAKE_KEY + "\nInjected: header", " "):
            self.key.write_text(value)
            self.assertEqual(self.run_main(JOBS_ARGS)[0], 1)

    def test_tracking_selects_ups_dev_and_checks_business_result(self):
        payload = {"success": False, "carrier": "UPS", "trackingNumber": "TEST123", "status": "FAILED", "timeline": []}
        with patch.object(partner.request.OpenerDirector, "open", return_value=response(payload)) as opened:
            code, output, _ = self.run_main(["tracking", "--tracking-number", "TEST123"])
        req = opened.call_args.args[0]
        self.assertEqual(req.get_header("X-carrier-environment"), "dev")
        self.assertEqual(req.get_header("X-api-version"), "1")
        self.assertTrue(req.full_url.endswith("/TEST123?carrier=UPS"))
        self.assertEqual(code, 1)
        self.assertFalse(json.loads(output)["lookup_success"])

    def test_response_schema_and_size_reject_invalid_data(self):
        for payload in ({"jobs": []}, {"jobs": [FAKE_KEY], "total": 1}, {"jobs": [], "total": float("nan")}, [FAKE_KEY]):
            with patch.object(partner.request.OpenerDirector, "open", return_value=response(payload)):
                self.assertEqual(self.run_main(JOBS_ARGS)[0], 1)
        with patch.object(partner, "MAX_RESPONSE_BYTES", 10), patch.object(
            partner.request.OpenerDirector, "open", return_value=response({"jobs": [], "total": 0})
        ):
            self.assertEqual(self.run_main(JOBS_ARGS)[0], 1)

    def test_invalid_date_order_does_not_send_request(self):
        with patch.object(partner.request.OpenerDirector, "open") as opened:
            self.assertEqual(self.run_main(["jobs", "--start-date", "2026-10-08", "--end-date", "2026-10-07"])[0], 1)
        opened.assert_not_called()


if __name__ == "__main__":
    unittest.main()
