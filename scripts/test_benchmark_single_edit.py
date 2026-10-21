import unittest

from benchmark_single_edit import POSITIONS, corruptions, summarize


class SingleEditBenchmarkTest(unittest.TestCase):
    def test_each_generated_error_is_exactly_the_named_operation(self):
        for word in ('a', 'is', 'the', 'aaa', 'letter', 'keyboard'):
            variants = corruptions(word)
            self.assertEqual(variants, corruptions(word))
            for kind, typed in variants.items():
                self.assertNotEqual(word, typed)
                if kind.endswith('substitution'):
                    self.assertEqual(len(word), len(typed))
                    changed = [(a, b) for a, b in zip(word, typed) if a != b]
                    self.assertEqual(1, len(changed))
                    if kind == 'qwerty_substitution':
                        a, b = changed[0]
                        self.assertTrue(all(abs(x-y) <= 1 for x, y in zip(POSITIONS[a], POSITIONS[b])))
                elif kind == 'deletion':
                    self.assertIn(typed, [word[:i]+word[i+1:] for i in range(len(word))])
                elif kind == 'insertion':
                    self.assertIn(word, [typed[:i]+typed[i+1:] for i in range(len(typed))])
                elif kind == 'transposition':
                    self.assertIn(typed, [word[:i]+word[i+1]+word[i]+word[i+2:] for i in range(len(word)-1)])
        self.assertNotIn('transposition', corruptions('aaa'))

    def test_wrong_typo_replacement_counts_as_both_false_positive_and_miss(self):
        def row(typed, correction, operation='substitution'):
            return dict(typed=typed, target='the', correction=correction, operation=operation,
                        top1=False, top8=False, target_returned=False,
                        failure_stage='not_returned', elapsedMicros=100)
        result = summarize([row('tge', 'the'), row('thx', 'this'), row('thz', None),
                            row('the', 'then', 'clean'), row('the', None, 'clean')])
        self.assertEqual(1, result['correct'])
        self.assertEqual(2, result['wrong'])
        self.assertEqual(1, result['false_changes'])
        self.assertEqual(2, result['controls'])
        self.assertEqual(3, result['typos'])
        self.assertAlmostEqual(1.25/(1.25+2+.25*2), result['f05'])


if __name__ == '__main__':
    unittest.main()
