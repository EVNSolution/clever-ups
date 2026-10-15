import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "geocode_addresses", Path(__file__).resolve().parents[1] / "scripts" / "geocode_addresses.py"
)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def answer(status="OK", x="127.1", y="37.4", road="경기도 성남시 분당구 판교로 1", parcel=None, code=None):
    if status != "OK":
        return {"response": {"status": status, "error": {"code": code}}}
    item = {"point": {"x": x, "y": y}, "address": {"road": road, "parcel": parcel}}
    return {"response": {"status": "OK", "result": {"items": [item]}}}


class GeocodeAddressesTest(unittest.TestCase):
    def test_the_address_loses_its_parentheses_and_extra_space(self):
        self.assertEqual(tool.clean("경기도  성남시 분당구 판교로 1 (백현동) "), "경기도 성남시 분당구 판교로 1")

    def test_a_road_number_asks_for_road_first_and_other_text_for_parcel_first(self):
        calls = []

        def fake(url):
            calls.append(parse_category(url))
            return answer(road="경기도 성남시 분당구 판교로 1")

        with patch.object(tool, "get_json", side_effect=fake):
            self.assertEqual(tool.geocode("경기도 성남시 분당구 판교로 1", "K"), (37.4, 127.1))
            self.assertEqual(calls, ["ROAD"])
            calls.clear()
            tool.geocode("경기도 성남시 분당구 백현동 532", "K")
            self.assertEqual(calls, ["PARCEL"])

    def test_not_found_tries_the_other_category_then_gives_none(self):
        calls = []

        def fake(url):
            calls.append(parse_category(url))
            return answer("NOT_FOUND")

        with patch.object(tool, "get_json", side_effect=fake):
            self.assertIsNone(tool.geocode("경기도 성남시 분당구 판교로 1", "K"))
        self.assertEqual(calls, ["ROAD", "PARCEL"])

    def test_an_answer_from_another_province_is_refused(self):
        with patch.object(tool, "get_json", return_value=answer(road="서울특별시 강남구 테헤란로 1")):
            self.assertIsNone(tool.geocode("경기도 성남시 분당구 판교로 1", "K"))

    def test_a_rate_limit_is_an_error_and_stops_the_run(self):
        with patch.object(tool, "get_json", return_value=answer("ERROR", code="OVER_REQUEST_LIMIT")):
            with self.assertRaises(tool.ProviderError) as caught:
                tool.geocode("경기도 성남시 분당구 판교로 1", "K")
        self.assertEqual(caught.exception.code, "RATE_LIMITED")

    def test_a_dry_run_sends_nothing_and_reads_no_key(self):
        with patch.object(tool, "psql", return_value="k\t경기도 성남시 1\n"), \
                patch.object(tool, "read_key") as key, patch.object(tool, "get_json") as http:
            self.assertEqual(tool.main(["--dry-run"]), 0)
        key.assert_not_called()
        http.assert_not_called()

    def test_a_point_outside_korea_is_not_written(self):
        writes = []

        def fake_psql(_database, sql):
            if sql.startswith("SELECT"):
                return "k1\t경기도 성남시 1\nk2\t경기도 성남시 2\n"
            writes.append(sql)
            return ""

        with patch.object(tool, "psql", side_effect=fake_psql), patch.object(tool, "read_key", return_value="KEY"), \
                patch.object(tool, "geocode", side_effect=[(37.4, 127.1), (10.0, 20.0)]):
            tool.main([])
        self.assertEqual(len(writes), 1)
        self.assertIn("'k1'", writes[0])
        self.assertNotIn("'k2'", writes[0])


class RetryTest(unittest.TestCase):
    def test_a_network_failure_is_tried_again_and_a_refusal_is_not(self):
        calls = []

        def flaky(_url):
            calls.append(1)
            if len(calls) < 3:
                raise tool.ProviderError("NETWORK")
            return {"ok": True}

        with patch.object(tool, "_get_json", side_effect=flaky), patch.object(tool.time, "sleep"):
            self.assertEqual(tool.get_json("u"), {"ok": True})
        self.assertEqual(len(calls), 3)
        with patch.object(tool, "_get_json", side_effect=tool.ProviderError("RATE_LIMITED")) as refused, \
                patch.object(tool.time, "sleep"):
            with self.assertRaises(tool.ProviderError):
                tool.get_json("u")
        self.assertEqual(refused.call_count, 1)


def parse_category(url):
    from urllib import parse
    return parse.parse_qs(parse.urlparse(url).query)["category"][0]


if __name__ == "__main__":
    unittest.main()
