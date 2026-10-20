import unittest
from audit_candidate_pipeline import preceding_context, choose_current, spelling_metrics


class PipelineAuditTest(unittest.TestCase):
    def test_remove_only_current_word_and_keep_separator(self):
        self.assertEqual("He is ", preceding_context("He is EXPEXTED", "expexted"))
        self.assertEqual("", preceding_context("teh", "teh"))
        self.assertIsNone(preceding_context("other word", "teh"))
        self.assertIsNone(preceding_context("something", "thing"))

    def test_known_word_and_completion_are_preserved(self):
        row = dict(word="the", edits=1, completedChars=0, score=-3.)
        self.assertIsNone(choose_current("teh", "", [row], True))
        self.assertEqual("the", choose_current("teh", "", [row], False))
        self.assertIsNone(choose_current("teh", "", [dict(row, completedChars=1)], False))

    def test_missing_target_stays_in_denominator_and_wrong_changes_reduce_precision(self):
        cases = [dict(id="1", typed="teh", target="the", left="", category="recorded_typo"),
                 dict(id="2", typed="waxs", target="waxes", left="", category="recorded_typo"),
                 dict(id="3", typed="name", target="name", left="", category="source_preservation")]
        outputs = [dict(candidates=[dict(word="the", edits=1, completedChars=0, score=-3.)], known=False, correction="the"),
                   dict(candidates=[dict(word="was", edits=1, completedChars=0, score=-4.)], known=False, correction="was"),
                   dict(candidates=[dict(word="same", edits=1, completedChars=0, score=-3.)], known=True, correction=None)]
        s = spelling_metrics(cases, outputs)["current"]["all"]
        self.assertEqual(2, s["typos"])
        self.assertEqual(.5, s["precision"])
        self.assertEqual(.5, s["typo_recall"])
        self.assertEqual(0., s["preservation_change_rate"])

    def test_decision_mirror_must_match_actual_native_decision(self):
        case = dict(id="x", typed="teh", target="the", left="", category="recorded_typo")
        with self.assertRaises(ValueError):
            spelling_metrics([case], [dict(candidates=[], known=False, correction="the")])


if __name__ == "__main__":
    unittest.main()
