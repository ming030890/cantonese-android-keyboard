import unittest

from analyze_autocorrect_errors import mutate, outcome, rank


class ErrorAnalysisTest(unittest.TestCase):
    def test_miss_stages_are_distinct_from_clean_damage(self):
        case = dict(typed='helo', target='hello')
        def output(words, correction=None):
            return dict(candidates=[dict(word=w) for w in words], correction=correction)
        self.assertEqual('corrected', outcome(case, output(['hello'], 'Hello')))
        self.assertEqual('target_top1_not_committed', outcome(case, output(['hello'])))
        self.assertEqual('target_rank2_to8', outcome(case, output(['help','hello'], 'help')))
        self.assertEqual('target_absent_top8', outcome(case, output(['help'])))
        clean = dict(typed='hello', target='hello')
        self.assertEqual('clean_preserved', outcome(clean, output(['help'])))
        self.assertEqual('clean_damaged', outcome(clean, output(['help'], 'help')))
        self.assertEqual('unlabelled', outcome(dict(typed='a', target=None), output([])))

    def test_rank_uses_casefold_and_absent_is_not_zero(self):
        self.assertEqual(2, rank('Hello', ['help', 'hello']))
        self.assertIsNone(rank('hello', ['help']))

    def test_synthetic_errors_are_deterministic_and_change_input(self):
        for word in ('meeting', 'api', 'aabb', 'work'):
            for operation in ('delete','substitute','duplicate','transpose'):
                a = mutate(word, operation, 'fixed')
                self.assertEqual(a, mutate(word, operation, 'fixed'))
                self.assertNotEqual(word, a)
        self.assertIsNone(mutate('aaa', 'transpose', 'fixed'))


if __name__ == '__main__':
    unittest.main()
