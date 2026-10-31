import unittest

import numpy as np

from calibrate_bilingual_acceptance import calibrate,fit_full


class IdentityFeatures:
    @staticmethod
    def parameters(policy):return None
    @staticmethod
    def predict_logits(params,x):return x[:,0]


class BilingualCalibrationTest(unittest.TestCase):
    def test_more_recall_cannot_spend_the_clean_word_budget(self):
        cases=[dict(typed='godo',target='good',source_kind='mixed_typo'),
               dict(typed='wrok',target='work',source_kind='mixed_typo'),
               dict(typed='name',target='name',source_kind='mixed_clean')]
        before=[dict(correction='good'),dict(correction=None),dict(correction=None)]
        policy=dict(means=[0.],scales=[1.],threshold=.5)
        selected,report=calibrate(cases,before,policy,np.array([[4.],[2.],[3.]]),
            [True]*3,['good','work','same'],[None]*3,IdentityFeatures())
        self.assertTrue(report['feasible'])
        self.assertAlmostEqual(3.5,report['cutoff_logit'])
        self.assertEqual(0,report['selected_counts']['mixed_clean']['fp'])
        self.assertGreater(selected['threshold'],.5)

    def test_an_unconditional_bad_edit_is_not_hidden_by_a_cutoff(self):
        cases=[dict(typed='godo',target='good',source_kind='mixed_typo'),
               dict(typed='name',target='name',source_kind='mixed_clean')]
        before=[dict(correction='good'),dict(correction=None)]
        selected,report=calibrate(cases,before,dict(means=[0.],scales=[1.],threshold=.5),
            np.array([[4.],[3.]]),[True,True],['good','same'],[None,'same'],IdentityFeatures())
        self.assertIsNone(selected)
        self.assertFalse(report['feasible'])

    def test_full_fit_preserves_a_function_already_at_the_target(self):
        policy=dict(means=[0.],scales=[1.],w1=[[.2] for _ in range(24)],b1=[0.]*24,
            w2=[.1]*24,b2=-.5,threshold=.5)
        x=np.array([[-1.],[0.],[1.]])
        z=np.maximum(x@np.array(policy['w1']).T,0)@np.array(policy['w2'])+policy['b2']
        y=1/(1+np.exp(-z))
        out=fit_full(policy,x,y,np.ones(3),np.ones(3),0,.001)[100]
        for key in ['w1','b1','w2','b2']:np.testing.assert_allclose(policy[key],out[key],atol=1e-12)


if __name__=='__main__':unittest.main()
