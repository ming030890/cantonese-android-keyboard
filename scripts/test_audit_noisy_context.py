import unittest

import audit_noisy_context as audit


class NoisyContextTest(unittest.TestCase):
    def case(self, **values):
        return dict(dict(id='one', panel='mobile_observed', left='我 think about ',
                         typed='thsi', target='this'), **values)

    def test_edits_only_last_eligible_word_preserving_whitespace_and_punctuation(self):
        left = '我 think about,  '
        for operation in audit.OPERATIONS:
            with self.subTest(operation=operation):
                edited = audit.corrupt(left, operation)
                self.assertNotEqual(left, edited)
                self.assertTrue(edited.startswith('我 think '))
                self.assertTrue(edited.endswith(',  '))
                self.assertEqual(edited, audit.corrupt(left, operation))

    def test_each_operation_has_expected_length_and_character_effect(self):
        word = 'about'
        self.assertEqual(len(audit.corrupt(word, 'delete')), 4)
        self.assertEqual(len(audit.corrupt(word, 'duplicate')), 6)
        self.assertEqual(len(audit.corrupt(word, 'substitute')), 5)
        self.assertEqual(sorted(audit.corrupt(word, 'transpose')), sorted(word))

    def test_ineligible_contexts_and_noop_swaps_are_skipped(self):
        for left in ['', '我係', 'to a ', 'abc123 ', 'éabc ', 'abc我 ', "abc's "]:
            self.assertIsNone(audit.corrupt(left, 'delete'))
        self.assertIsNone(audit.corrupt('aaa ', 'transpose'))
        with self.assertRaises(ValueError):
            audit.corrupt('about ', 'unsupported')

    def test_label_and_input_preservation_and_no_label_dependent_noise(self):
        before = self.case()
        variants = audit.pairs([before])
        self.assertEqual(len(variants), 4)
        for variant in variants:
            for key in ['typed', 'target', 'panel']:
                self.assertEqual(variant[key], before[key])
            self.assertEqual(variant['original_left'], before['left'])
        changed_label = audit.pairs([self.case(target='other')])
        self.assertEqual([c['left'] for c in variants], [c['left'] for c in changed_label])
        self.assertEqual(before, self.case())

    def test_order_independence_and_unlabelled_exclusion(self):
        cases = [self.case(), self.case(id='two', left='please send ')]
        by_id = lambda rows: {c['id']: c for c in audit.pairs(rows)}
        self.assertEqual(by_id(cases), by_id(cases[::-1]))
        self.assertEqual(audit.pairs([self.case(target=None)]), [])

    def test_paired_counts_include_clean_word_damage_and_recovered_typos(self):
        cases = [self.case(typed='this'), self.case(id='two')]
        original = [dict(correction=None), dict(correction=None)]
        noisy = [dict(correction='thus'), dict(correction='this')]
        result = audit.paired_metrics(cases, original, noisy)
        self.assertEqual(result['regressions'], 1)
        self.assertEqual(result['recoveries'], 1)
        self.assertEqual(result['decisions_changed'], 2)
        self.assertEqual(result['noisy']['clean_changes'], 1)
        self.assertEqual(result['noisy']['tp'], 1)
        with self.assertRaises(ValueError):
            audit.paired_metrics(cases, original[:1], noisy)


if __name__ == '__main__':
    unittest.main()
