import unittest

from analyze_autocorrect_errors import missing_reason, score_parts


class ErrorAnalysisTest(unittest.TestCase):
    def test_missing_target_attribution_separates_vocabulary_and_search(self):
        self.assertEqual('target_word_absent_from_generation_vocabulary', missing_reason('dont', "don't", set()))
        self.assertEqual('length_difference_greater_than_one', missing_reason('ab', 'wxyz', {'wxyz'}))
        self.assertEqual('outside_length_dependent_edit_radius', missing_reason('ab', 'cd', {'cd'}))
        self.assertEqual('multiword_split_proposal_restriction', missing_reason('yousupports', 'you support', {'you', 'support'}))

    def test_score_decomposition_recovers_lm_without_completion_cost(self):
        parts = score_parts('teh', dict(word='the', score=-4., edits=1, completedChars=0))
        self.assertEqual(dict(lm=-3., cost=1., score=-4.), parts)
        parts = score_parts('hel', dict(word='hello', score=-4., edits=0, completedChars=2))
        self.assertEqual(dict(lm=-4., cost=0., score=-4.), parts)

    def test_split_scores_use_the_production_split_cost(self):
        direct = score_parts('yousupport', dict(word='you support', score=-8., edits=1, completedChars=0))
        mistap = score_parts('youbsupport', dict(word='you support', score=-8., edits=1, completedChars=0))
        self.assertEqual(1.5, direct['cost'])
        self.assertEqual(1., mistap['cost'])


if __name__ == '__main__':
    unittest.main()
