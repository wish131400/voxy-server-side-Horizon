import copy
from datetime import datetime, timezone
import json
from pathlib import Path
import unittest
import tempfile
import acceptance as a


class AcceptanceTest(unittest.TestCase):
    def sample(self, n=0):
        return {"sampleMonotonicNanos": n * 1000000000, "time": datetime.now(timezone.utc).isoformat(),
                "acceptance": {"runId": "run-1", "processId": 123, "enabled": True, "backgroundAllowed": True},
                "label": "cold-load", "frameCount": 60, "windowActive": False, "paused": False,
                "epoch": 1, "dimension": "minecraft:overworld", "camera": [0, 70, 0], "cameraYaw": 0,
                "cameraPitch": 0, "predictionDistanceBlocks": 8192, "resolution": [1920, 1080],
                "loading": {"readyTiles": 1},
                "progress": {"fresh": True, "coverageTotal": 100, "coverageReady": 95,
                             "targetTotal": 100, "targetReady": 95, "surfaceTotal": 10, "surfaceReady": 10}}

    def test_rejects_stale_samples_and_other_process(self):
        s = self.sample()
        a.validate_sample(s, "run-1", 123, "cold-load")
        with self.assertRaises(a.Incomplete): a.validate_sample(s, "run-1", 456, "cold-load")
        with self.assertRaises(a.Incomplete): a.validate_sample(s, "run-1", 123, "cold-load", now=0)

    def test_zero_or_stale_demand_does_not_mean_complete(self):
        s = self.sample(); s["progress"]["coverageTotal"] = 0
        self.assertIsNone(a.loading_metrics([s])["coverage95Seconds"])
        s = self.sample(); s["progress"]["fresh"] = False
        self.assertIsNone(a.loading_metrics([s])["coverage95Seconds"])

    def test_changed_target_or_camera_invalidates_timing(self):
        first, second = self.sample(), self.sample(1)
        second["progress"]["targetTotal"] = 20
        self.assertFalse(a.loading_metrics([first, second])["comparable"])
        second = self.sample(1); second["camera"][0] = 1
        self.assertIsNone(a.loading_metrics([first, second])["coverage95Seconds"])

    def test_sixty_three_block_error_fails_even_with_no_task_failure(self):
        point = {"x": 703, "z": 1727, "realLoaded": True, "realSurfaceY": 219,
                 "predictedSurfaceY": 219, "predictedBiome": "test:valley", "realBiome": "test:valley",
                 "resident": {"x": 703, "z": 1727, "step": 1, "surfaceY": 156, "captured": False}}
        result = a.compare_probe({"status": "complete", "points": [point]}, {"minPoints": 1, "maxHeightError": 1})
        self.assertEqual("failed", result["status"])
        self.assertEqual(63, result["failures"][0]["residentError"])

    def test_captured_or_spatially_different_column_cannot_prove_prediction(self):
        p = {"x": 0, "z": 0, "realLoaded": True, "realSurfaceY": 70, "predictedSurfaceY": 70,
             "predictedBiome": "test:valley", "realBiome": "test:valley",
             "resident": {"x": 0, "z": 0, "step": 1, "surfaceY": 70, "captured": True}}
        self.assertEqual("incomplete", a.compare_probe({"status": "complete", "points": [p]}, {"minPoints": 1, "maxHeightError": 1})["status"])

    def test_frames_need_a_verdict_bound_to_the_actual_hash(self):
        results = {phase: {"status": "awaiting-visual-review", "visual": [{"sha256": "abc"}]} for phase in a.PHASES}
        self.assertEqual("incomplete", a.evaluate(results, {})["status"])
        self.assertEqual("passed", a.evaluate(results, {"abc": {"status": "passed", "observations": "No missing terrain at fixed camera"}})["status"])
        self.assertEqual("failed", a.evaluate(results, {"abc": {"status": "failed", "observations": "Upper terrain disappeared"}})["status"])
        results.pop("warm-load")
        self.assertEqual("incomplete", a.evaluate(results, {"abc": {"status": "passed", "observations": "checked"}})["status"])

    def test_all_ten_targets_have_workflows_and_only_actual_bridge_support_is_claimed(self):
        profiles = [a.read(p) for p in (a.HERE / "profiles").glob("*.json")]
        self.assertEqual(10, len(profiles))
        self.assertEqual(["neoforge-1.21.1"], [p["target"] for p in profiles if p["control"]["upstreamSupportsTarget"]])
        for p in profiles: self.assertEqual(set(a.PHASES), {phase["id"] for phase in p["phases"]})

    def test_complete_loading_does_not_count_as_a_stall(self):
        first, second = self.sample(), self.sample(60)
        for s in (first, second):
            s["progress"]["coverageReady"] = 100; s["progress"]["targetReady"] = 100
        self.assertEqual(0, a.loading_metrics([first, second])["stalledSeconds"])

    def test_warm_transfer_preserves_data_and_never_overwrites_existing_save(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); cold = root / "cold"; warm = root / "warm"
            cache = cold / "saves/test/.vss/prediction"
            cache.mkdir(parents=True); warm.mkdir()
            (cache / "data").write_bytes(b"actual fixture cache")
            proof = a.transfer_warm_cache(cold, warm, ["saves/test"])
            self.assertEqual(1, len(proof)); self.assertEqual(b"actual fixture cache", (warm / "saves/test/.vss/prediction/data").read_bytes())
            with self.assertRaises(ValueError): a.transfer_warm_cache(cold, warm, ["saves/test"])
            with self.assertRaises(ValueError): a.transfer_warm_cache(cold, warm, ["../outside"])


if __name__ == "__main__": unittest.main()
