import unittest
from unittest.mock import patch

import numpy as np
import experiment_revisit_policy as revisit


class RevisitPolicyTest(unittest.TestCase):
    def test_f05_utility_rewards_undo_of_wrong_fix_and_penalizes_undo_of_correct_fix(self):
        typo = dict(typed='teh', target='the')
        self.assertGreater(revisit.gain_label(typo, 'ten', 'teh', .85), 0.)
        self.assertLess(revisit.gain_label(typo, 'the', 'teh', .85), 0.)
        self.assertEqual(revisit.gain_label(typo, 'ten', 'tea', .85), 0.)
        clean = dict(typed='word', target='word')
        self.assertGreater(revisit.gain_label(clean, 'ward', 'word', .85), 0.)
        self.assertLess(revisit.gain_label(clean, 'word', 'ward', .85), 0.)

    def test_gate_can_include_low_confidence_accepted_corrections(self):
        seed = dict(broad=True, margin=.5, accepted=True)
        self.assertFalse(revisit.admitted(seed, dict(gate='rejected_4'), None))
        self.assertTrue(revisit.admitted(seed, dict(gate='both_1'), None))
        self.assertTrue(revisit.admitted(seed, dict(gate='accepted_4'), None))
        self.assertFalse(revisit.admitted(dict(seed, margin=2.), dict(gate='both_1'), None))
        self.assertFalse(revisit.admitted(dict(seed, broad=False), dict(gate='both_wide'), None))

    def test_likelihood_batch_conditions_each_future_on_its_own_prefix(self):
        class Probe:
            def query(self, request):
                self.request = request
                return dict(evidence=[dict(logProbability=v, supported=True)
                                      for v in [-30., -2., -33., -5., -35., -7.]])
        seed = dict(typed='typo', initial='word', margin=.5, accepted=True, features=[1.], candidates=[
            dict(word='typo', rank=-1, score=None, edits=0, dictionary=0.),
            dict(word='word', rank=0, score=-2., edits=1, dictionary=1.)])
        p = Probe()
        events = revisit.evidence(p, seed, 'left ', [['next'], ['next', 'last']])
        self.assertEqual(len(events), 2)
        self.assertEqual([e['alternatives'][0]['gain'] for e in events], [0., 0.])
        self.assertEqual(p.request['sequences'], ['typo', 'word', 'typo next', 'word next', 'typo next last', 'word next last'])
        self.assertNotIn('target', p.request)

    def test_replay_undoes_accepted_correction_and_feeds_restored_word_forward(self):
        class Probe:
            def __init__(self):
                self.calls = []
            def predict(self, left, typed):
                self.calls.append((left, typed))
                return dict(correction='ward' if typed == 'word' else None)
        def seed(acceptance, typed, output):
            return dict(typed=typed, initial='ward', accepted=True, broad=True, margin=.5) if typed == 'word' else None
        events = [dict(lag=1, alternatives=[dict(word='word', rank=-1, gain=2., features=[1.])])]
        rule = dict(gate='both_1', max_following=2, candidate_limit=3, positive_future=True, threshold=.1)
        model = dict(means=[0.], scales=[1.], coefficients=[0., 1.])
        p = Probe()
        cases = [dict(id=str(i), typed=w) for i, w in enumerate(['word', 'next', 'last'])]
        with patch.object(revisit, 'seed_for', side_effect=seed), patch.object(revisit, 'evidence', return_value=events):
            words, changes = revisit.replay_trial(p, None, cases, rule, model, None)
        self.assertEqual(words, ['word', 'next', 'last'])
        self.assertTrue(changes[0]['initially_accepted'])
        self.assertIn(('word next ', 'last'), p.calls)
        self.assertEqual(len(changes), 1)

    def test_candidate_limit_always_retains_original_spelling_for_undo(self):
        event = dict(lag=1, alternatives=[dict(word='original', rank=-1, gain=1., features=[1.]),
                                         dict(word='fourth', rank=4, gain=1., features=[2.])])
        rule = dict(max_following=2, candidate_limit=3, positive_future=True)
        model = dict(means=[0.], scales=[1.], coefficients=[0., 1.])
        self.assertEqual(revisit.best_event(event, {}, rule, model)['word'], 'original')

    def test_ridge_reproduces_simple_gain_relationship(self):
        x = np.arange(20, dtype=float)[:, None]
        model = revisit.fit_ridge(x, .1 * x[:, 0] - 1., np.ones(20), .00001)
        self.assertLess(np.max(np.abs(revisit.predict(model, x) - (.1 * x[:, 0] - 1.))), .0001)

    def test_only_two_pending_decisions_and_no_target_needed_at_runtime(self):
        class Probe:
            def predict(self, left, typed):
                return dict(correction=None)
        def seed(acceptance, typed, out):
            return dict(typed=typed, initial=typed, accepted=False, broad=True, margin=-.5)
        checked = []
        def evidence(probe, seed, left, following):
            checked.append(seed['typed'])
            return []
        rule = dict(gate='rejected_4', max_following=3, candidate_limit=3, positive_future=True, threshold=1.)
        cc = [dict(id=str(i), typed=w) for i, w in enumerate(['first', 'second', 'third', 'fourth'])]
        with patch.object(revisit, 'seed_for', side_effect=seed), patch.object(revisit, 'evidence', side_effect=evidence):
            words, changes = revisit.replay_trial(Probe(), None, cc, rule, {}, {})
        self.assertEqual(checked.count('first'), 1)
        self.assertEqual(words, ['first', 'second', 'third', 'fourth'])
        self.assertEqual(changes, [])

    def test_fit_does_not_read_calibration_or_selection_targets(self):
        cases = [dict(fit_split='fit', typed='teh', target='the'),
                 dict(fit_split='fit', typed='word', target='word'),
                 dict(fit_split='selection', typed='selection', target='one')]
        records = [dict(initial='teh', seed=dict(features=[1.]), events=[dict(alternatives=[dict(word='the', features=[1.])])]),
                   dict(initial='word', seed=dict(features=[0.]), events=[dict(alternatives=[dict(word='ward', features=[0.])])]),
                   dict(initial='selection', seed=dict(features=[100.]), events=[dict(alternatives=[dict(word='one', features=[100.])])])]
        models, queue, cuts, report = revisit.train(cases, records)
        cases[2]['target'] = 'completely-different'
        second = revisit.train(cases, records)
        self.assertEqual(models, second[0])
        self.assertEqual(queue, second[1])
        self.assertEqual(cuts, second[2])
        self.assertEqual(report['proposal_rows'], 2)


if __name__ == '__main__':
    unittest.main()
