import unittest
from unittest.mock import patch

import experiment_revisit_distance as experiment


class DistanceTest(unittest.TestCase):
    def test_expires_by_following_word_distance_not_pending_count(self):
        class Acceptance:
            threshold=1.
            def seed(self,typed,out):
                return dict(typed=typed,logit=.9,alternatives=[dict(word='fixed',score_gap=0.)]) if typed=='typo' else None
        class Probe:
            def predict(self,left,typed):
                return dict(correction=None)
        def evidence(probe,seed,left,following):
            return dict(lag=len(following),alternatives=[dict(word='fixed',score_gap=0.,future_gain=3. if len(following)==2 else 0.)])
        cases=[dict(id=str(i),typed=w) for i,w in enumerate(['typo','one','two','three'])]
        policy=dict(band_width=1.,future_weight=1.,max_following=2,revision_margin=1.)
        with patch.object(experiment.old,'evidence',side_effect=evidence):
            words,revisions=experiment.replay(Probe(),Acceptance(),cases,policy,1)
            self.assertEqual(words[0],'typo')
            self.assertEqual(revisions,[])
            words,revisions=experiment.replay(Probe(),Acceptance(),cases,policy,2)
            self.assertEqual(words[0],'fixed')
            self.assertEqual(revisions[0]['lag'],2)
            words,_=experiment.replay(Probe(),Acceptance(),cases,policy,None)
            self.assertEqual(words[0],'fixed')

    def test_invalid_distance_rejected(self):
        with self.assertRaises(ValueError):
            experiment.replay(None,None,[],{},0)


if __name__=='__main__':
    unittest.main()
