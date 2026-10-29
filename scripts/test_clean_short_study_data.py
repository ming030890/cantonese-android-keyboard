import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import numpy as np


class EvaluationQuarantineTest(unittest.TestCase):
    def test_training_rejects_an_unquarantined_cache_by_default(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = root / 'data'
            data.mkdir()
            (data / 'protocol.json').write_text('{}')
            script = Path(__file__).with_name('experiment_short_features.py')
            result = subprocess.run([sys.executable, str(script), 'train', '--data', str(data),
                                     '--variant', 'data_control', '--output', str(root / 'trained')],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('clean_short_study_data.py', result.stderr)
            self.assertFalse((root / 'trained').exists())

    def test_input_matches_are_removed_independent_of_label_and_arrays_stay_aligned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data, suite, out = root / 'data', root / 'suite', root / 'clean'
            data.mkdir(); suite.mkdir()
            cases = [dict(id=str(i), left=left, typed=typed, target=target, fit_split=split)
                     for i, (left, typed, target, split) in enumerate([
                         ('try ', 'ir', 'it', 'fit'), ('keep ', 'word', 'word', 'fit'),
                         ('try ', 'IR', 'ir', 'selection'), ('other ', 'x', 'y', 'selection')])]
            (data / 'cases.jsonl').write_text(''.join(json.dumps(c) + '\n' for c in cases))
            (data / 'protocol.json').write_text('{}')
            (data / 'parent-policy.json').write_text('{}')
            (data / 'decisions.json').write_text(json.dumps(dict(words=['zero', 'one', 'two', 'three'], restorations=[''] * 4)))
            np.savez(data / 'features.npz', raw=np.arange(12).reshape(4, 3), labels=np.arange(4),
                     eligible=np.ones(4, dtype=bool), old_count=2)
            (suite / 'cases.jsonl').write_text(json.dumps(dict(id='frozen', panel='mobile', left='try ', typed='ir', target='it')) + '\n')
            script = Path(__file__).with_name('clean_short_study_data.py')
            subprocess.run([sys.executable, str(script), '--data', str(data), '--suite', str(suite), '--output', str(out)],
                           capture_output=True, text=True, check=True)
            clean = [json.loads(line) for line in (out / 'cases.jsonl').read_text().splitlines()]
            self.assertEqual(['1', '3'], [c['id'] for c in clean])
            matrix = np.load(out / 'features.npz')
            np.testing.assert_array_equal(matrix['raw'], np.arange(12).reshape(4, 3)[[1, 3]])
            np.testing.assert_array_equal(matrix['labels'], [1, 3])
            self.assertEqual(1, int(matrix['old_count']))
            self.assertEqual(['one', 'three'], json.loads((out / 'decisions.json').read_text())['words'])
            self.assertTrue(json.loads((out / 'protocol.json').read_text())['evaluation_inputs_excluded'])


if __name__ == '__main__':
    unittest.main()
