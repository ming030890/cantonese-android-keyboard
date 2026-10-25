import unittest

import replay_context_revision as replay


class ReplayTest(unittest.TestCase):
    def test_prompt_continuation_excludes_focal_target_and_stops_at_punctuation(self):
        case = dict(left='we have ', target='fixed')
        self.assertEqual(replay.future_from_prompt(case, 'we have fixed this today, thanks'), ['this', 'today'])
        self.assertEqual(replay.future_from_prompt(case, 'we have fixed. This is later'), [])
        self.assertEqual(replay.future_from_prompt(case, 'some other prompt'), [])
        self.assertEqual(replay.future_from_prompt(dict(left='I am ', target='over it'),
                                                  'I am over it by now'), ['by', 'now'])

    def test_later_support_cannot_override_queue_band_or_expiry(self):
        seed = dict(logit=.5)
        rule = dict(band_width=1., future_weight=1., max_following=2, revision_margin=1.)
        event = dict(lag=1, alternatives=[dict(word='fix', score_gap=0., future_gain=2.)])
        self.assertEqual(replay.propose(seed, event, rule, 1.), 'fix')
        self.assertIsNone(replay.propose(dict(logit=-5.), event, rule, 1.))
        self.assertIsNone(replay.propose(seed, dict(event, lag=3), rule, 1.))
        self.assertIsNone(replay.propose(seed, event, None, 1.))

    def test_alternative_needs_new_positive_future_evidence(self):
        seed = dict(logit=.9)
        rule = dict(band_width=1., future_weight=1., max_following=3, revision_margin=0.)
        event = dict(lag=1, alternatives=[dict(word='fix', score_gap=0., future_gain=0.)])
        self.assertIsNone(replay.propose(seed, event, rule, 1.))
        event['alternatives'][0]['future_gain'] = -1.
        self.assertIsNone(replay.propose(seed, event, rule, 1.))

    def test_suffix_likelihood_removes_original_tokenization_prior(self):
        class Probe:
            def query(self, request):
                self.request = request
                # Original unknown spelling has a much worse prefix likelihood.
                # Both continuations fit equally well after conditioning on it.
                return dict(evidence=[dict(logProbability=v, supported=True) for v in [-30., -2., -33., -5.]])
        probe = Probe()
        seed = dict(typed='typo', logit=.5, alternatives=[dict(word='word', score_gap=0.)])
        result = replay.evidence(probe, seed, 'left ', ['next'])
        self.assertEqual(result['alternatives'][0]['future_gain'], 0.)
        self.assertEqual(probe.request['sequences'], ['typo', 'word', 'typo next', 'word next'])

    def test_wrong_revision_counts_as_false_positive_and_missed_typo(self):
        cases = [dict(typed='teh', target='the'), dict(typed='word', target='word')]
        result = replay.measure(cases, ['ten', 'ward'])
        self.assertEqual((result['tp'], result['fp'], result['fn'], result['clean_changes']), (0, 2, 1, 1))

    def test_closed_loop_uses_revised_history_and_never_reads_targets(self):
        class Acceptance:
            threshold = 1.
            def seed(self, typed, output):
                return dict(typed=typed, logit=.9, alternatives=[dict(word='fixed', score_gap=0.)]) if typed == 'typo' else None
        class Probe:
            def __init__(self):
                self.lefts = []
            def predict(self, left, typed):
                self.lefts.append((left, typed))
                return dict(correction=None)
            def query(self, request):
                return dict(evidence=[dict(logProbability=v, supported=True) for v in [-5., -5., -10., -6.]])
        probe = Probe()
        # No target/reference fields exist, so the state machine cannot consult them.
        cases = [dict(id=str(i), typed=w) for i, w in enumerate(['typo', 'next', 'last'])]
        rule = dict(band_width=1., future_weight=1., max_following=3, revision_margin=0.)
        words, changes = replay.replay_trial(probe, Acceptance(), cases, rule)
        self.assertEqual(words, ['fixed', 'next', 'last'])
        self.assertIn(('fixed next ', 'last'), probe.lefts)
        self.assertEqual(len(changes), 1)

    def test_queue_keeps_only_last_two_unsure_words(self):
        class Acceptance:
            threshold = 1.
            def seed(self, typed, output):
                return dict(typed=typed, logit=.9, alternatives=[dict(word='fixed', score_gap=0.)]) if typed.startswith('miss') else None
        class Probe:
            def __init__(self):
                self.queries = []
            def predict(self, left, typed):
                return dict(correction=None)
            def query(self, request):
                self.queries.append(request)
                return dict(evidence=[dict(logProbability=v, supported=True) for v in [-5., -5., -10., -10.]])
        probe = Probe()
        cases = [dict(id=str(i), typed=w) for i, w in enumerate(['missA', 'missB', 'missC', 'last'])]
        rule = dict(band_width=1., future_weight=1., max_following=3, revision_margin=0.)
        words, changes = replay.replay_trial(probe, Acceptance(), cases, rule)
        self.assertEqual(changes, [])
        self.assertEqual(sum(q['sequences'][0] == 'missA' for q in probe.queries), 1)
        self.assertEqual(words, ['missA', 'missB', 'missC', 'last'])

    def test_right_refresh_is_bounded_and_uses_new_prefix(self):
        class Acceptance:
            threshold = 1.
            def seed(self, typed, output, include_accepted=False):
                if typed in ['missA', 'missB']:
                    return dict(typed=typed, logit=.9, alternatives=[dict(word='fixedA' if typed == 'missA' else 'fixedB', score_gap=0.)])
        class Probe:
            def __init__(self):
                self.calls = []
            def predict(self, left, typed):
                self.calls.append((left, typed))
                return dict(correction='fixedB' if left == 'fixedA ' and typed == 'missB' else None)
            def query(self, request):
                return dict(evidence=[dict(logProbability=v, supported=True) for v in [-5., -5., -10., -6.]])
        cc = [dict(id=str(i), typed=w) for i, w in enumerate(['missA', 'missB', 'last'])]
        rule = dict(band_width=1., future_weight=1., max_following=2, revision_margin=0.)
        probe = Probe()
        words, changes = replay.replay_trial(probe, Acceptance(), cc, rule, 'ordinary')
        self.assertEqual(words, ['fixedA', 'fixedB', 'last'])
        self.assertIn(('fixedA ', 'missB'), probe.calls)
        self.assertIn(('fixedA fixedB ', 'last'), probe.calls)
        self.assertEqual(len(probe.calls), 4)  # Three inputs and one right refresh.
        self.assertEqual(sum(c.get('refreshed', False) for c in changes), 1)
        strict = Probe()
        words, changes = replay.replay_trial(strict, Acceptance(), cc, rule, 'strict')
        # Native immediate approval alone cannot change an older word under strict.
        # It waits until 'last' supplies positive following evidence.
        self.assertEqual(words, ['fixedA', 'fixedB', 'last'])
        self.assertEqual(changes[-1]['lag'], 1)

    def test_refresh_does_not_reconsider_confident_right_words(self):
        class Acceptance:
            threshold = 1.
            def seed(self, typed, output, include_accepted=False):
                return dict(typed=typed, logit=.9, alternatives=[dict(word='fixed', score_gap=0.)]) if typed == 'typo' else None
        class Probe:
            def __init__(self):
                self.calls = []
            def predict(self, left, typed):
                self.calls.append((left, typed))
                return dict(correction=None)
            def query(self, request):
                return dict(evidence=[dict(logProbability=v, supported=True) for v in [-5., -5., -10., -6.]])
        cc = [dict(id=str(i), typed=w) for i, w in enumerate(['typo', 'confident'])]
        probe = Probe()
        rule = dict(band_width=1., future_weight=1., max_following=2, revision_margin=0.)
        replay.replay_trial(probe, Acceptance(), cc, rule, 'ordinary')
        self.assertEqual(len(probe.calls), 2)


if __name__ == '__main__':
    unittest.main()
