import unittest

import experiment_revisit_gate as gate


class GateTest(unittest.TestCase):
    def test_confidence_and_length_filters_have_exact_boundaries(self):
        rule=dict(band_width=1.5,minimum_length=3,maximum_length=5)
        self.assertTrue(gate.allows(1.5,3,rule))
        self.assertTrue(gate.allows(.5,5,rule))
        self.assertFalse(gate.allows(1.6,4,rule))
        self.assertFalse(gate.allows(1.,2,rule))
        self.assertFalse(gate.allows(1.,6,rule))

    def test_off_does_not_need_acceptance_work(self):
        class Base:
            threshold=1.
            def seed(self,typed,output):
                raise AssertionError('Off gate should not compute an extra seed')
        self.assertIsNone(gate.Gate(Base(),dict(off=True)).seed('word',{}))

    def test_pareto_discards_more_expensive_equal_quality(self):
        def row(calls,score):
            return dict(gate=dict(band_width=4.,minimum_length=None,maximum_length=None),
                        selection=dict(metrics=dict(f05=score),requests=dict(revisit=calls)))
        rows=[row(10,.9),row(5,.9),row(2,.8),row(15,.91)]
        self.assertEqual([r['selection']['requests']['revisit'] for r in gate.front(rows,'selection')],[2,5,15])

    def test_known_and_dictionary_signals_are_separate(self):
        rule=dict(band_width=4.,minimum_length=None,maximum_length=None,known_required=False)
        self.assertTrue(gate.allows(1.,3,rule,False,True))
        self.assertFalse(gate.allows(1.,3,rule,True,False))
        rule=dict(band_width=4.,minimum_length=None,maximum_length=None,dictionary_required=False)
        self.assertTrue(gate.allows(1.,3,rule,True,False))
        self.assertFalse(gate.allows(1.,3,rule,False,True))


if __name__=='__main__':
    unittest.main()
