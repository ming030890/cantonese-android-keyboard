import unittest
from unittest.mock import patch

import experiment_real_revisit as revisit
import prepare_real_revisit_streams as prepare


class RealRevisitTest(unittest.TestCase):
    def test_labels_do_not_supply_observed_boundaries_or_future_words(self):
        trials, cases = [], []
        prepare.add_trial(trials, cases, 'id', 'person', 'fit', 'we cam. home',
                          'we came home', 'source', list(range(12)))
        self.assertEqual([c['typed'] for c in cases], ['we', 'cam', 'home'])
        self.assertEqual([c['target'] for c in cases], ['we', 'came', 'home'])
        self.assertEqual(cases[-1]['left'], 'we cam. ')
        self.assertEqual(trials[0]['observed'], 'we cam. home')

    def test_recorded_backspace_changes_observed_stream(self):
        keys = {'a':(0.,0.,1.,1.), 'b':(3.,0.,1.,1.), '<b>':(6.,0.,1.,1.), '<sp>':(9.,0.,1.,1.)}
        record = dict(IN=['0,0,1|3,0,2|6,0,3|9,0,4'])
        text, times = prepare.observed_with_times(record, keys)
        self.assertEqual(text, 'a ')
        self.assertEqual(times, [1.,4.])

    def test_age_bounds_queue_without_eviction_before_last_supported_age(self):
        class Probe:
            def predict(self, left, typed):
                return dict(correction=None)
        def seed(a, typed, out):
            return dict(typed=typed, initial=typed, margin=-.5, accepted=False, features=[])
        def evidence(p, s, left, groups):
            gain = 5. if s['typed']=='typo' and len(groups[0])==2 else 0.
            return [dict(lag=len(groups[0]), alternatives=[dict(word='fixed', gain=gain, features=[0.])])]
        rule = dict(state='both', confidence_band=None, threshold=4., max_following=2, scoring='suffix')
        cc = [dict(id=str(i), typed=w) for i,w in enumerate(['typo','next','last'])]
        with patch.object(revisit.feature,'seed_for',side_effect=seed),patch.object(revisit.feature,'evidence',side_effect=evidence):
            words, changes = revisit.replay(Probe(),None,cc,rule)
        self.assertEqual(words,['fixed','next','last'])
        self.assertEqual(changes[0]['lag'],2)

    def test_calibration_uses_observed_score_breakpoints(self):
        cases=[dict(split='calibration',typed='teh',target='the'),
               dict(split='calibration',typed='word',target='word')]
        records=[]
        for i,(typed,word,gain) in enumerate([('teh','the',3.7),('word','ward',.2)]):
            records.append(dict(initial=typed,seed=dict(accepted=False,margin=-.6-i,features=[]),
                                events=[dict(lag=1,alternatives=[dict(word=word,gain=gain,features=[0.])])]))
        candidates=revisit.tune(cases,records)
        self.assertTrue(any(c['calibration']['f05']==1. for c in candidates))
        self.assertTrue(all(c['rule']['threshold'] in [.2,3.7] for c in candidates))
        self.assertTrue(all(c['rule']['confidence_band'] in [None,.6,1.6] for c in candidates))

    def test_joint_score_has_no_learned_mixture_weight(self):
        seed=dict(features=[1.,2.])
        event=dict(alternatives=[dict(word='a',gain=3.,features=[1.,2.,-5.]),
                                 dict(word='b',gain=2.,features=[1.,2.,-1.])])
        self.assertEqual(revisit.choice(seed,event,'suffix')['word'],'a')
        self.assertEqual(revisit.choice(seed,event,'joint')['word'],'b')


if __name__=='__main__':
    unittest.main()
