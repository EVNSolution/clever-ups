import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location(
    "import_reference", Path(__file__).resolve().parents[1] / "scripts" / "import_reference.py"
)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def write_xlsx(path, rows):
    """A minimal workbook with one sheet named tasks. Every cell is a shared string."""
    strings = []

    def index(text):
        if text not in strings:
            strings.append(text)
        return strings.index(text)

    cells = []
    for r, row in enumerate(rows, 1):
        for c, value in enumerate(row, 1):
            if value is not None:
                cells.append((r, c, index(value)))
    sheet = "".join(
        f'<row r="{r}">' + "".join(f'<c r="{chr(64 + c)}{r}" t="s"><v>{i}</v></c>' for rr, c, i in cells if rr == r) + "</row>"
        for r in range(1, len(rows) + 1)
    )
    ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("xl/sharedStrings.xml", f'<sst xmlns="{ns}">' + "".join(f"<si><t>{s}</t></si>" for s in strings) + "</sst>")
        z.writestr("xl/workbook.xml", f'<workbook xmlns="{ns}"><sheets><sheet name="tasks"/></sheets></workbook>')
        z.writestr("xl/worksheets/sheet1.xml", f'<worksheet xmlns="{ns}"><sheetData>{sheet}</sheetData></worksheet>')


class ImportReferenceTest(unittest.TestCase):
    def test_key_ignores_case_and_spacing_and_matches_the_server_rule(self):
        a = tool.address_key("12  Teheran-ro ", "Suite 3", "06134", "ACME")
        b = tool.address_key("12 teheran-ro", " suite   3 ", "06134 ", "acme")
        self.assertEqual(a, b)
        expected = hashlib.sha256("12 TEHERAN-RO\nSUITE 3\n06134\nACME".encode()).hexdigest()
        self.assertEqual(a, expected)
        # Moving text between parts changes the key, as in AddressKeyTest.kt.
        self.assertNotEqual(tool.address_key("A", "B C", "", ""), tool.address_key("A B", "C", "", ""))

    def test_only_a_korean_only_address_is_a_reference(self):
        self.assertTrue(tool.korean_only("경기도 성남시 수정구 123"))
        self.assertFalse(tool.korean_only(""))
        self.assertFalse(tool.korean_only("경기도 Seongnam 123"))
        self.assertFalse(tool.korean_only("123"))

    def test_tasks_are_read_by_header_name(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "upload.xlsx"
            write_xlsx(path, [["작업ID", "목적지", "노트(2)"], ["1", "경기도 성남시 1", "1ZAAA"]])
            tasks = tool.read_tasks(path)
        self.assertEqual(tasks, [{"작업ID": "1", "목적지": "경기도 성남시 1", "노트(2)": "1ZAAA"}])

    def test_sql_escapes_quotes_and_never_updates_an_existing_reference(self):
        pair = ("k" * 64, ("O'Neil St", "", "13500", ""), "경기도 성남시 1", None, "h" * 64)
        sql = tool.build_sql([pair], "fleetune-upload-2026-10-05")
        self.assertIn("'O''Neil St'", sql)
        self.assertIn("ON CONFLICT (address_key) DO NOTHING", sql)
        self.assertNotIn("UPDATE", sql)
        self.assertTrue(sql.startswith("BEGIN;") and sql.rstrip().endswith("COMMIT;"))
        self.assertNotIn("1ZAAA", sql)


if __name__ == "__main__":
    unittest.main()
