import unittest
from pathlib import Path
import importlib.util

spec = importlib.util.spec_from_file_location("watch", Path(__file__).with_name("upstream_watch.py"))
watch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watch)

class RadarTests(unittest.TestCase):
    def test_release_note_signal_extraction(self):
        self.assertEqual(
            watch.notes("## Changes\n- Fix Android 17 Shizuku startup\n- unrelated detail\n- Fix binder authorization"),
            ["Fix Android 17 Shizuku startup", "Fix binder authorization"]
        )

    def test_change_detection_and_retirement(self):
        now = {"a/b": {"head": "next", "release": "v1"}}
        old = {"a/b": {"head": "before", "release": "v1"}, "c/d": {"head": "gone"}}
        self.assertEqual([e[0] for e in watch.changes_for(now, old)], ["commit", "untracked"])

    def test_feature_categories(self):
        self.assertEqual(
            watch.review_area({"head_changes": ["Fixed Dhizuku UID verification"]}),
            "Device Owner / Dhizuku interoperability"
        )
        self.assertEqual(
            watch.review_area({"head_changes": ["Add Axeron PTY"]}),
            "Axeron runtime, modules and WebUI"
        )

    def test_baseline_report(self):
        catalog = {"curated": {"a/b": "test"}, "external_references": []}
        item = {"name": "a/b", "origin": "curated", "release": "v1", "license": "Apache-2.0"}
        report = watch.render({"a/b": item}, [], [], catalog, True)
        self.assertIn("Initial baseline captured", report)
        self.assertIn("Apache-2.0", report)

    def test_recent_candidate_score(self):
        item = {"name": "x/Shizuku-Fork", "description": "A Shizuku fork", "pushed_at": "2026-11-15", "release": "v1"}
        self.assertGreaterEqual(watch.rank(item), 5)

if __name__ == "__main__":
    unittest.main()
