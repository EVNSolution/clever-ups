"""The layout of engines/ is fixed by the owner. This test fails when someone changes its shape."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
ENGINES = {"address"}
TOP = {"README.md", "RUNBOOK.md", "engine.toml", "src", "tests", "evidence"}


def tracked(prefix):
    out = subprocess.run(["git", "ls-files", prefix], cwd=ROOT, capture_output=True, text=True, check=True)
    return [line for line in out.stdout.splitlines() if line]


class EngineLayoutTest(unittest.TestCase):
    def test_only_known_engines_exist(self):
        names = {Path(p).parts[1] for p in tracked("engines") if len(Path(p).parts) > 2}
        self.assertEqual(names - ENGINES, set(), "an engine directory that is not in tests/test_engine_layout.py")

    def test_each_engine_keeps_the_fixed_top_level(self):
        for engine in ENGINES:
            entries = {Path(p).parts[2] for p in tracked(f"engines/{engine}") if len(Path(p).parts) >= 3}
            self.assertEqual(entries - TOP, set(), f"engines/{engine} has an entry outside the fixed layout")
            for required in ("README.md", "RUNBOOK.md", "engine.toml"):
                self.assertIn(f"engines/{engine}/{required}", tracked(f"engines/{engine}"))

    def test_evidence_tracks_only_its_readme(self):
        for engine in ENGINES:
            files = tracked(f"engines/{engine}/evidence")
            self.assertEqual(files, [f"engines/{engine}/evidence/README.md"])


if __name__ == "__main__":
    unittest.main()
