import unittest

import experiment_noisy_acceptance as experiment


class CutoffTest(unittest.TestCase):
    def test_cutoff_matches_brute_force_with_weights_ties_and_restoration(self):
        cases = [dict(typed='teh', target='the'), dict(typed='word', target='word'),
                 dict(typed='thsi', target='this'), dict(typed='dont', target="don't"),
                 dict(typed='helo', target='hello')]
        logits = [2., 1., 1., -10., 4.]
        eligible = [True, True, True, True, False]
        words = ['the', 'ward', 'this', 'donut', 'hello']
        restorations = ['', '', '', "don't", '']
        weights = [1., .25, 2., 1., 1.]

        def score(threshold):
            tp = fp = typos = 0.
            for c, z, e, word, restored, w in zip(cases, logits, eligible, words, restorations, weights):
                typo = c['typed'] != c['target']
                fix = restored or (word if e and z >= threshold else None)
                changed = bool(fix and fix != c['typed'])
                hit = bool(changed and typo and fix == c['target'])
                typos += w * typo
                tp += w * hit
                fp += w * (changed and not hit)
            return 1.25 * tp / (tp + fp + .25 * typos)

        actual = experiment.cutoff(cases, logits, eligible, words, restorations, weights)
        self.assertEqual(score(actual), max(score(t) for t in [5.] + logits))
        # Equal logits are accepted together, not split by label/order.
        self.assertEqual(actual, 1.)

    def test_all_bad_fixes_choose_abstention(self):
        threshold = experiment.cutoff([dict(typed='word', target='word')],
                                     [3.], [True], ['ward'], [''], [1.])
        self.assertGreater(threshold, 3.)

    def test_case_only_decision_is_not_a_spelling_error(self):
        threshold = experiment.cutoff([dict(typed='word', target='word')],
                                     [3.], [True], ['Word'], ['Word'], [1.])
        self.assertGreater(threshold, 3.)


if __name__ == '__main__':
    unittest.main()
