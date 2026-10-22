import unittest

from public_touch_eval import records, observed_stream, word_labels, stream_metrics, nearest
from train_synthetic_touch import replacement_penalty


class PublicTouchEvaluationTest(unittest.TestCase):
    def test_labels_support_merged_words_and_reject_ambiguous_alignment(self):
        self.assertEqual(['in the','cat'],word_labels('inthe cat','in the cat'))
        self.assertEqual(['cat','dog'],word_labels('cst dog','cat dog'))
        self.assertEqual([None,None],word_labels('a a','a'))
        self.assertEqual([None,None],word_labels('in the','inthe'))

    def test_observations_do_not_depend_on_reference_or_email_context(self):
        keys={'a':(0.,0.,1.,1.),'b':(2.,0.,1.,1.),'<sp>':(1.,2.,3.,1.),'<b>':(4.,0.,1.,1.)}
        first=dict(IN=['0,0,0;2,0,1|2,0,2|4,0,3|1,2,4'],REF='a',LEFT='secret')
        second=dict(first,REF='b',LEFT='different secret')
        a,p,_=observed_stream(first,keys)
        b,q,_=observed_stream(second,keys)
        self.assertEqual(('a ',[[0.,0.],[1.,2.]]),(a,p))
        self.assertEqual((a,p),(b,q))

    def test_malformed_events_are_not_silently_dropped(self):
        with self.assertRaises(ValueError):
            observed_stream(dict(IN=['SWIPE']),{})

    def test_literal_sentence_metric_keeps_unlabelled_words(self):
        trials=[dict(id='t',participant='p1',observed='in the',reference='inthe',words=['x','y'])]
        cases=[dict(id='x',start=0,end=2),dict(id='y',start=3,end=6)]
        r=stream_metrics(trials,cases,[None,None])
        self.assertEqual(1,r['overall']['character_errors'])

    def test_substitution_prior_removed_only_for_matching_edit_path(self):
        self.assertAlmostEqual(.574436466482213,replacement_penalty('fame','game',1,0))
        self.assertIsNone(replacement_penalty('teh','the',1,0))
        self.assertIsNone(replacement_penalty('hel','hello',0,2))


if __name__=='__main__':
    unittest.main()
