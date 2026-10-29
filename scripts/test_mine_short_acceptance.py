import unittest

from mine_short_acceptance import generate, variants


class ShortMiningTest(unittest.TestCase):
    def test_target_labels_and_splits_do_not_depend_on_model_decisions(self):
        dictionary = {'a', 'an', 'as', 'at', 'cat', 'cot', 'tue'}
        rows = generate(dictionary, [], [])
        self.assertTrue(rows)
        splits = {}
        for row in rows:
            self.assertNotEqual(row['typed'], 'tue')
            self.assertNotEqual(row['target'], 'tue')
            self.assertEqual(row['fit_split'], splits.setdefault(row['target'], row['fit_split']))
            self.assertIn(row['target'], dictionary)
            if row['typed'] != row['target']:
                self.assertIn(row['typed'], variants(row['target']))
                self.assertNotIn(row['typed'], dictionary)

    def test_frozen_triples_are_excluded_and_only_fit_contexts_are_used(self):
        source = [dict(fit_split='fit', typed='cat', target='cat', left='my '),
                  dict(fit_split='selection', typed='cat', target='cat', left='held out ')]
        frozen = [dict(left='', typed='cat', target='cat')]
        rows = generate({'cat'}, source, frozen)
        self.assertFalse(any(r['left'] == '' and r['typed'] == 'cat' for r in rows))
        self.assertTrue(any(r['left'] == 'my ' for r in rows))
        self.assertFalse(any(r['left'] == 'held out ' for r in rows))

    def test_valid_word_corruptions_need_authentic_sentence_context(self):
        dictionary = {'cat', 'cot'}
        source = [dict(fit_split='fit', typed='cat', target='cat', left='my ')]
        for row in generate(dictionary, source, []):
            if row['typed'] != row['target'] and row['typed'] in dictionary:
                self.assertEqual(row['context_kind'], 'authentic')
                self.assertEqual(row['left'], 'my ')


if __name__ == '__main__':
    unittest.main()
