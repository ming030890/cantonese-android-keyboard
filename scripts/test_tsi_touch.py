import math
import unittest

from fit_tsi_touch import LETTERS, log_density, metrics, partition, touch_delta


class TouchChannelTest(unittest.TestCase):
    def setUp(self):
        self.model=dict(keys=[dict(mean=[0.,0.],inverse=[16.,0.,16.],log_det=math.log(1/256)) for _ in LETTERS],max_log_ratio=8.)
        self.geometry=[v for i in range(26) for v in [i,0.,1.,2.]]

    def test_relative_geometry_survives_independent_resize_and_translation(self):
        points=[.8,.2,1.,0.,2.,0.]
        score=touch_delta(self.model,'abc','bbc',points,self.geometry,1,0)
        self.assertGreater(score,0)
        g=[v for i in range(0,104,4) for v in [self.geometry[i]*3+30,self.geometry[i+1]*7-50,3.,14.]]
        p=[v for i in range(0,6,2) for v in [points[i]*3+30,points[i+1]*7-50]]
        self.assertAlmostEqual(score,touch_delta(self.model,'abc','bbc',p,g,1,0))

    def test_unsupported_alignments_are_not_guessed(self):
        for word,edits,completed in [('bac',1,0),('ab',1,0),('abcd',0,1),("a'c",1,0)]:
            self.assertEqual(0.,touch_delta(self.model,'abc',word,[0.]*6,self.geometry,edits,completed))

    def test_wrong_replacement_counts_as_false_positive_and_missed_typo(self):
        words=[dict(typed='cta',target='cat'),dict(typed='dog',target='dog')]
        r=metrics(words,['car','dig'])
        self.assertEqual((0,2,1),(r['correct'],r['wrong'],r['false_changes']))
        self.assertEqual(0.,r['f05'])

    def test_case_only_difference_is_a_preservation_control(self):
        r=metrics([dict(typed='Dog',target='dog')],['dig'])
        self.assertEqual((0,1,1),(r['typos'],r['controls'],r['false_changes']))

    def test_participants_are_disjoint(self):
        self.assertEqual(['fit']*10+['calibration']*2+['selection']*2+['evaluation']*2,
                         [partition(f'user{i:02}') for i in range(1,17)])


if __name__=='__main__':
    unittest.main()
