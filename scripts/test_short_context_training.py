import unittest

from build_short_context_training import generate
from experiment_short_features import extra_features


class ShortContextDataTest(unittest.TestCase):
    def test_intent_labels_holdouts_and_frozen_exclusion(self):
        source = [dict(id='fit-it', source='real', fit_split='fit', group='a', left='try ', typed='it', target='it'),
                  dict(id='thu', source='real', fit_split='fit', group='b', left='see you on ', typed='thursday', target='thursday'),
                  dict(id='tue', source='real', fit_split='fit', group='c', left='on ', typed='tuesday', target='tuesday')]
        forms = {'it': ['it'], 'thu': ['Thu'], 'thursday': ['Thursday'], 'tue': ['Tue'], 'tuesday': ['Tuesday']}
        frozen = [dict(left='try ', typed='it', target='it')]
        rows = generate(source, forms, {'it', 'ir', 'thu', 'tue'}, frozen)
        self.assertTrue(rows)
        self.assertFalse(any(r['typed'] in {'tue', 'ir'} or r['target'] in {'tue', 'ir'} for r in rows))
        self.assertFalse(any(r['left'] == 'try ' and r['typed'] == r['target'] == 'it' for r in rows))
        self.assertTrue(any(r['target'] == 'thu' and r['source'] == 'title_abbreviation_clean' for r in rows))
        for row in rows:
            if row['real_word_error']:
                self.assertTrue(row['left'])

    def test_duplicates_cannot_cross_new_partitions(self):
        source = [dict(id=split, source='real', fit_split=split, group=split, left='do ', typed='it', target='it')
                  for split in ['fit', 'calibration', 'selection']]
        rows = generate(source, {'it': ['it']}, {'it'}, [])
        self.assertEqual({'selection'}, {r['fit_split'] for r in rows})

    def test_features_distinguish_spelling_shape_and_edit_geometry(self):
        forms = {'tue': ['Tue'], 'ir': ['IR', 'ir']}
        tue = extra_features('tue', 'the', 'on ', forms)
        ir = extra_features('ir', 'it', 'try ', forms)
        self.assertEqual(tue[:4], [1., 1., 0., .5])
        self.assertEqual(ir[:4], [0., 0., 1., 1.])
        self.assertEqual(tue[4:6], [.75, 1.])
        self.assertEqual(ir[4:6], [1., 0.])
        self.assertEqual(extra_features('ir', 'it', ' \t\n', forms)[6], 0.)


if __name__ == '__main__':
    unittest.main()
