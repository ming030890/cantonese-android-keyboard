"""The harder cohort is selected without consulting model predictions."""
import unittest

from autocorrect_eval_v2 import authored
from build_autocorrect_hard_eval import retain


class HardCohortTests(unittest.TestCase):
    def test_easy_errors_are_removed_even_if_prediction_is_wrong(self):
        case = dict(panel='authored_challenge', typed='cofee', target='coffee', domain='food', correction=None)
        self.assertFalse(retain(case))
        case['correction'] = 'coffee'
        self.assertFalse(retain(case))

    def test_multi_edit_errors_remain_even_if_prediction_is_correct(self):
        case = dict(panel='authored_challenge', typed='docmnt', target='document', domain='large_distance', correction='document')
        self.assertTrue(retain(case))

    def test_clean_controls_and_natural_records_are_never_filtered(self):
        self.assertTrue(retain(dict(panel='authored_challenge', typed='Cheuk', target='Cheuk')))
        self.assertTrue(retain(dict(panel='real_touch_observed', typed='teh', target='the')))

    def test_structural_and_real_word_errors_are_retained(self):
        for domain, typed, target in [('apostrophes', 'cant', "can't"),
                                       ('word_boundaries', 'backhome', 'back home'),
                                       ('real_word_intent', 'on', 'in')]:
            self.assertTrue(retain(dict(panel='authored_challenge', typed=typed, target=target, domain=domain)))

    def test_complete_authored_cohort_counts(self):
        source = authored()
        selected = [c for c in source if retain(c)]
        self.assertEqual(len(source) - len(selected), 162)
        self.assertEqual(sum(c['typed'].casefold() == c['target'].casefold() for c in selected), 204)
        self.assertEqual(len(selected), 250)


if __name__ == '__main__':
    unittest.main()
