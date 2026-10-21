import unittest

from analyze_single_edit_root_cause import decide, trace


class RootCauseTest(unittest.TestCase):
    def setUp(self):
        self.policy = dict(features=['winner_score', 'runner_margin', 'known_word',
                                     'literal_score', 'score_over_literal', 'observed_apostrophe'],
                           weights=[0., 1., 0., 0., 0., 0., 0.], means=[0.]*6, scales=[1.]*6,
                           threshold=.7, margin_cap=20., minimum_observed_length=3,
                           minimum_candidate_length=3, preserve_known_words=False)
        self.apostrophe = dict(weights=[0.], means=[], scales=[], threshold=1.)

    def output(self, word='pizza', score=1., completed=0):
        return dict(known=False, literalScore=None, apostropheEvidence=[],
                    candidates=[dict(word=word, score=score, completedChars=completed, edits=1)])

    def test_completion_veto_overrides_passing_learned_score(self):
        case, out = dict(typed='pizz'), self.output(completed=1)
        detail = trace(self.policy, case, out, out['candidates'])
        self.assertGreater(detail['decision_score'], self.policy['threshold'])
        self.assertEqual('completion', detail['guard'])
        self.assertIsNone(detail['decision'])
        self.assertIsNone(decide(self.policy, self.apostrophe, case, out, 'without_learned_gate')[0])

    def test_lm_ablation_removes_penalty_only_from_winner_acceptance_feature(self):
        case, out = dict(typed='plizza'), self.output(score=-2.)
        baseline = trace(self.policy, case, out, out['candidates'])
        ablated = trace(self.policy, case, out, out['candidates'], winner_lm=True)
        self.assertEqual(4.5, baseline['score_parts']['cost'])
        self.assertEqual(-2., baseline['features']['winner_score'])
        self.assertEqual(2.5, ablated['features']['winner_score'])
        self.assertEqual(baseline['features']['runner_margin'], ablated['features']['runner_margin'])
        self.assertIsNone(baseline['decision'])
        self.assertEqual('pizza', ablated['decision'])
        self.assertEqual(-2., out['candidates'][0]['score'])

    def test_known_word_needs_literal_evidence_and_short_input_stays_blocked(self):
        out = self.output()
        out['known'] = True
        self.assertEqual('known_without_literal', trace(self.policy, dict(typed='piza'), out, out['candidates'])['guard'])
        out['known'] = False
        self.assertEqual('short_input', trace(self.policy, dict(typed='pi'), out, out['candidates'])['guard'])


if __name__ == '__main__':
    unittest.main()
