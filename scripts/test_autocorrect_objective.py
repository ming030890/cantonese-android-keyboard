import unittest

from autocorrect_eval_v2 import rows
from autocorrect_objective import balanced_clean, objective
from pathlib import Path


class ObjectiveTests(unittest.TestCase):
    def test_equal_weights_not_case_count_weights(self):
        report = dict(historical_full_mobile=dict(f05=.9, cases=2000), panels={
            'real_touch_observed': dict(candidate=dict(f05=.8, cases=3000)),
            'authored_hard': dict(candidate=dict(f05=.4, cases=92))})
        score, _ = objective(report)
        self.assertAlmostEqual(score, .7)

    def test_clean_selection_keeps_every_domain_and_is_order_independent(self):
        source = rows(Path(__file__).resolve().parents[1] / 'config/autocorrect_eval_v3/authored-hard.jsonl')
        selected = balanced_clean(source)
        self.assertEqual(len(selected), 46)
        self.assertEqual({c['id'] for c in selected}, {c['id'] for c in balanced_clean(list(reversed(source)))})
        self.assertEqual({c['domain'] for c in selected}, {c['domain'] for c in source if c['typed'].casefold() == c['target'].casefold()})
        self.assertTrue(all(c['typed'].casefold() == c['target'].casefold() for c in selected))

    def test_clean_selection_ignores_model_outcomes(self):
        source = [dict(id=str(i), domain='names', typed='x', target='x', correction=None) for i in range(60)]
        before = {c['id'] for c in balanced_clean(source)}
        for c in source:
            c['correction'] = 'wrong'
        self.assertEqual(before, {c['id'] for c in balanced_clean(source)})

    def test_empty_control_pool_is_an_error(self):
        with self.assertRaises(ValueError):
            balanced_clean([], 1)


if __name__ == '__main__':
    unittest.main()
