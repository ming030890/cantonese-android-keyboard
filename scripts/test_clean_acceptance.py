import copy
import unittest

import numpy as np

from experiment_clean_acceptance import recenter_dictionary_input


class DictionaryFineTuningTest(unittest.TestCase):
    def test_reparameterization_preserves_function_and_zero_flag_is_invariant(self):
        rng = np.random.default_rng(17)
        policy = dict(means=rng.normal(size=22).tolist(), scales=rng.uniform(.2, 2., size=22).tolist(),
                      w1=rng.normal(size=(24, 22)).tolist(), b1=rng.normal(size=24).tolist())
        original = copy.deepcopy(policy)
        raw = rng.normal(size=(100, 22))
        raw[:, 20] = np.arange(100) % 2

        def hidden(p):
            return (raw - p['means']) / p['scales'] @ np.array(p['w1']).T + p['b1']

        before = hidden(policy)
        recenter_dictionary_input(policy)
        np.testing.assert_allclose(before, hidden(policy), rtol=1e-13, atol=1e-13)
        for i, row in enumerate(policy['w1']):
            row[20] += i / 10
        after = hidden(policy)
        np.testing.assert_allclose(before[raw[:, 20] == 0], after[raw[:, 20] == 0], rtol=1e-13, atol=1e-13)
        self.assertGreater(float(np.max(np.abs(before[raw[:, 20] == 1] - after[raw[:, 20] == 1]))), 1.)
        # Recentring affects only the designated column and hidden bias.
        columns = [i for i in range(22) if i != 20]
        np.testing.assert_array_equal(np.array(policy['w1'])[:, columns], np.array(original['w1'])[:, columns])


if __name__ == '__main__':
    unittest.main()
