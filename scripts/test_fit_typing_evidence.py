import unittest

from fit_typing_evidence import operations, touch_examples


class TypingEvidenceTest(unittest.TestCase):
    def test_operation_direction_and_transposition(self):
        for typed, word, expected in [('hello', 'hello', (0, 0, 0, 0)),
                                      ('yiu', 'you', (1, 0, 0, 0)),
                                      ('helllo', 'hello', (0, 1, 0, 0)),
                                      ('keybord', 'keyboard', (0, 0, 1, 0)),
                                      ('teh', 'the', (0, 0, 0, 1))]:
            self.assertEqual(expected, operations(typed, word), (typed, word))

    def test_geometry_normalization_is_invariant_to_keyboard_size_and_origin(self):
        centers = [[float(i), 0.] for i in range(26)]
        centers[ord('q')-97] = [0., 0.]
        centers[ord('w')-97] = [1., 0.]
        centers[0] = [.5, 2.]
        case = dict(target='a', typed='s', touch=[[.6, 2.4]], centers=centers)
        original = touch_examples(case)[0]
        resized = dict(case, touch=[[11.2, 24.8]], centers=[[2*x+10, 2*y+20] for x, y in centers])
        transformed = touch_examples(resized)[0]
        self.assertEqual(original[0], transformed[0])
        for a, b in zip(original[1], transformed[1]):
            self.assertAlmostEqual(a, b)

    def test_unaligned_or_missing_sensor_evidence_is_excluded(self):
        self.assertEqual([], touch_examples(dict(target='hello', typed='helo', touch=[])))
        self.assertEqual([], touch_examples(dict(target='the', typed='teh', touch=[[0, 0]]*3)))


if __name__ == '__main__':
    unittest.main()
