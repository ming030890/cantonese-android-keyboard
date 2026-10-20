import unittest
from align_mobile_targets import osa, reference_target


class MobileAlignmentTest(unittest.TestCase):
    def test_actual_adjacent_transposition_distance(self):
        self.assertEqual(1, osa("teh", "the"))
        self.assertEqual(1, osa("yousupport", "you support"))

    def test_reference_resolves_swallowed_space_without_injecting_candidates(self):
        c = dict(left="The other ", typed="figurevis", target="figure")
        self.assertEqual("figure is", reference_target(c, "The other figure is correct."))
        self.assertEqual("the", reference_target(dict(left="", typed="teh", target="the"), "The other figure is correct."))

    def test_preceding_error_and_wrong_reference_word_are_rejected(self):
        self.assertIsNone(reference_target(dict(left="Teh other ", typed="figure", target="figure"), "The other figure is correct."))
        self.assertIsNone(reference_target(dict(left="The other ", typed="figure", target="other"), "The other figure is correct."))

    def test_reference_span_does_not_cross_punctuation(self):
        c = dict(left="", typed="helloworld", target="hello")
        self.assertIsNone(reference_target(c, "Hello, world."))


if __name__ == "__main__":
    unittest.main()
