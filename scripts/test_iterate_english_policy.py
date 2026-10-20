import unittest
from iterate_english_policy import rerank, gate_features, decisions, split, metric, f05


class EnglishIterationTest(unittest.TestCase):
    def test_f05_weights_precision_and_accounts_for_missed_fixes(self):
        self.assertEqual(1., f05(10, 0, 0))
        self.assertEqual(0., f05(0, 0, 0))
        self.assertAlmostEqual(.7155667144906743, f05(399, 112, 345))
        self.assertGreater(f05(336, 28, 408), f05(399, 112, 345))
        self.assertLess(f05(224, 12, 520), f05(399, 112, 345))

    def test_completion_penalty_is_removed_without_changing_edit_scores(self):
        rows = [dict(word="is", score=-3., completedChars=0, edits=1),
                dict(word="interesting", score=-5., completedChars=9, edits=0)]
        ranked = rerank(rows, 0.)
        self.assertEqual("interesting", ranked[0]["word"])
        self.assertEqual(-3., ranked[1]["score"])
        self.assertEqual(-5., rows[1]["score"])

    def test_automatic_gate_cannot_expand_prefix_or_change_known_word(self):
        cases = [dict(typed="inter"), dict(typed="form"), dict(typed="teh")]
        outputs = [dict(known=False), dict(known=True), dict(known=False)]
        ranked = [[dict(word="interesting", score=-1., completedChars=6, edits=0)],
                  [dict(word="from", score=-1., completedChars=0, edits=1)],
                  [dict(word="the", score=-2., completedChars=0, edits=1)]]
        features = gate_features(cases, outputs, ranked)
        self.assertEqual([None, None, "the"], decisions(features, -3., 2., 1))

    def test_fit_metric_excludes_other_groups_and_keeps_missing_targets(self):
        train_group = next(str(i) for i in range(100) if split(str(i)) == "train")
        test_group = next(str(i) for i in range(100) if split(str(i)) == "evaluation")
        cases = [dict(group=train_group, typed="teh", target="the"),
                 dict(group=test_group, typed="wprld", target="world")]
        rows = [[dict(word="then")], [dict(word="world")]]
        result = metric(cases, [{}, {}], rows, [None, "world"], "train")
        self.assertEqual(1, result["cases"])
        self.assertEqual(1, result["typos"])
        self.assertEqual(0., result["recall"])
        self.assertEqual(0., result["top8_rate"])


if __name__ == "__main__":
    unittest.main()
