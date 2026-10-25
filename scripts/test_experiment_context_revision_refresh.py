import csv
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import experiment_context_revision_refresh as experiment


class ExpandedDataTest(unittest.TestCase):
    def test_preserves_prompt_split_and_removes_exact_evaluation_inputs(self):
        old = [dict(id='old', source='real', fit_split='fit', group='train',
                    left='', typed='Word', target='word')]
        frozen = [dict(panel='mobile_observed', group='aalto:eval', left='', typed='word', target='ward')]
        refs = dict(train='Word next', selection='Some word', eval='Word next')
        events = [dict(SENTENCE_ID='train', CURRENT_INPUT='Word', TYPED_WORD='Word', ORIGINAL_WORD='word'),
                  dict(SENTENCE_ID='train', CURRENT_INPUT='Word nexr', TYPED_WORD='nexr', ORIGINAL_WORD='next'),
                  dict(SENTENCE_ID='selection', CURRENT_INPUT='Sone', TYPED_WORD='Sone', ORIGINAL_WORD='some'),
                  dict(SENTENCE_ID='eval', CURRENT_INPUT='Word nexr', TYPED_WORD='nexr', ORIGINAL_WORD='next')]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'events.csv'
            with path.open('w') as stream:
                writer = csv.DictWriter(stream, fieldnames=list(events[0]))
                writer.writeheader()
                writer.writerows(events + [events[1]])
            with patch.object(experiment, 'split', side_effect=lambda g: g):
                cases, audit = experiment.prepare(path, old, frozen, refs)
        self.assertEqual(len(cases), 2)
        self.assertEqual({c['typed'] for c in cases}, {'nexr', 'Sone'})
        self.assertEqual(next(c['fit_split'] for c in cases if c['group'] == 'selection'), 'selection')
        self.assertEqual(audit['excluded_existing_overlapping_inputs'], 1)
        self.assertEqual(audit['exact_evaluation_overlap'], 0)

    def test_refresh_cost_is_attached_to_original_boundary(self):
        class Probe:
            cache = {}
            def query(self, request):
                return dict(elapsedMicros=1000)
        cost = experiment.Costs(Probe())
        cost.next_is_boundary = True
        cost.predict('', 'word')
        cost.predict('fixed ', 'right')
        cost.query(dict(sequences=['a', 'b']))
        self.assertEqual(len(cost.boundaries), 1)
        self.assertEqual(cost.boundaries[0], dict(spelling_ms=1., extra_ms=2., sequence_calls=1, refresh_calls=1))


if __name__ == '__main__':
    unittest.main()
