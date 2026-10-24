"""Evaluation regressions: denominators, label isolation and whole-stream harm."""
import json
import unittest

from autocorrect_eval_v2 import (authored, metrics, native_requests, overlap,
                                prevalence_sensitivity, stream_metrics, uncertainty)


def case(typed, target, group='g', **extra):
    return dict(id=typed, left='please ', typed=typed, target=target, group=group,
                domain='test', **extra)


class EvaluationTests(unittest.TestCase):
    def test_wrong_fix_is_both_false_positive_and_missed_typo(self):
        cases = [case('teh', 'the'), case('nad', 'and'), case('Ng', 'Ng'), case('qz', 'quiz')]
        out = [{'correction': 'the'}, {'correction': 'bad'}, {'correction': 'no'}, {'correction': None}]
        m = metrics(cases, out)
        self.assertEqual((m['tp'], m['fp'], m['fn'], m['clean_changes']), (1, 2, 2, 1))
        self.assertAlmostEqual(m['f05'], 1.25 / 3.75)
        self.assertEqual(m['typos'], 3)  # Missing candidates were not filtered out.

    def test_unchanged_native_literal_is_not_a_correction(self):
        m = metrics([case('teh', 'the'), case('Ng', 'Ng')],
                    [{'correction': 'teh'}, {'correction': 'ng'}])
        self.assertEqual((m['tp'], m['fp'], m['fn']), (0, 0, 1))

    def test_gold_right_context_and_taps_never_enter_native_requests(self):
        row = case('teh', 'secret', right='hidden', points=[1, 2], reference='hidden too')
        request = json.loads(native_requests([row]))
        self.assertEqual(request, dict(left='please ', typed='teh', spelling_limit=8, timing=True))

    def test_ambiguous_words_stay_in_whole_trial_cer(self):
        row = case('ax', None, start=0, end=2)
        trial = dict(id='trial', participant='p', observed='ax', reference='at', words=['ax'])
        word = metrics([row], [{'correction': 'zz'}])
        self.assertEqual((word['unlabelled'], word['typos'], word['fp']), (1, 0, 0))
        literal = stream_metrics([trial], [row], [None])['overall']
        changed = stream_metrics([trial], [row], ['zz'])['overall']
        self.assertEqual((literal['cer'], changed['cer']), (.5, 1.))

    def test_cluster_resampling_keeps_correlated_scenario_variants_together(self):
        cases = [case('aa', 'ab', 'one'), case('bb', 'bc', 'one'), case('cc', 'cd', 'two')]
        outputs = [{'correction': 'ab'}, {'correction': 'bc'}, {'correction': None}]
        ci = uncertainty(cases, outputs, outputs, repeats=50)
        self.assertEqual(ci['groups'], 2)
        self.assertEqual(ci['paired_delta_f05_95_interval'], [0., 0.])

    def test_clean_harm_matters_more_at_low_error_prevalence(self):
        m = dict(tp=8, fp=2, typos=10, clean=100, clean_changes=1)
        scenarios = prevalence_sensitivity(m)['scenarios']
        self.assertLess(scenarios[0]['precision'], scenarios[-1]['precision'])

    def test_overlap_annotates_without_changing_membership(self):
        cases = [case('teh', 'the')]
        training = [dict(cases[0], fit_split='fit')]
        audit = overlap(cases, training)['overlaps']['acceptance_fit_and_calibration']
        self.assertEqual(audit['exact_input_overlap'], 1)
        self.assertEqual(len(cases), 1)

    def test_authored_stress_has_clean_counterparts_and_coverage(self):
        cases = authored()
        self.assertEqual(len(cases), len({c['id'] for c in cases}))
        groups = {c['group'] for c in cases}
        self.assertEqual(groups, {c['group'] for c in cases if c['category'] == 'authored_clean'})
        domains = {c['domain'] for c in cases}
        self.assertTrue({'short_words', 'large_distance', 'slang', 'names', 'identifiers',
                         'mixed_cantonese', 'word_boundaries', 'real_word_intent'} <= domains)
        self.assertGreaterEqual(len(cases), 350)


if __name__ == '__main__':
    unittest.main()
