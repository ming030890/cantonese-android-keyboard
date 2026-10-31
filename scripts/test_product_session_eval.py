import copy
import unittest

from product_session_eval import summarize,validate


def session(**extra):
    row=dict(schema='keyboard-product-session-v1',id='s',group='g',origin='authored_diagnostic',
        intent_status='author_specified',language='english',layout='quick3',initial_text='',
        events=[dict(kind='type',text='hello'),dict(kind='space')],expected_text='Hello ',
        expected_composing='',intent='preservation',tags=['capitalization'])
    return dict(row,**extra)


class ProductSessionEvalTest(unittest.TestCase):
    def test_case_damage_is_visible_and_layout_variants_are_one_group(self):
        rows=[session(),session(id='s2',layout='quick5')]
        out=[dict(id=r['id'],actual='hello ',composing='') for r in rows]
        metrics=summarize(rows,out)['overall_diagnostic_counts']
        self.assertEqual(0,metrics['exact_success'])
        self.assertEqual(2,metrics['casefold_success'])
        self.assertEqual(2,metrics['capitalization_only_failures'])
        self.assertEqual(1,metrics['groups'])

    def test_unknown_intent_is_replayed_but_never_scored_as_gold(self):
        rows=[session(intent_status='unreviewed',expected_text=None)]
        self.assertEqual(1,validate(rows)['unreviewed'])
        result=summarize(rows,[dict(id='s',actual='anything',composing='')])
        self.assertEqual(0,result['overall_diagnostic_counts']['labelled'])
        self.assertEqual([],result['failures'])

    def test_recorded_chat_requires_real_provenance_and_split_isolation(self):
        row=session(origin='recorded_chat',intent_status='human_confirmed',participant_id='p',
            conversation_id='c',device_family='device',capture_app_version='74',split='development',
            events=[dict(kind='type',text='hello',at_ms=0),dict(kind='space',at_ms=100)])
        self.assertEqual(0,validate([row])['recorded_chat_confirmation_sessions'])
        other=copy.deepcopy(row);other.update(id='other',group='other',split='confirmation')
        with self.assertRaisesRegex(ValueError,'crosses splits'):validate([row,other])
        other=copy.deepcopy(row);del other['device_family']
        with self.assertRaisesRegex(ValueError,'provenance'):validate([other])
        other=copy.deepcopy(row);other['events'][1]['at_ms']=-1
        with self.assertRaisesRegex(ValueError,'event time'):validate([other])

    def test_duplicate_or_missing_replays_do_not_silently_change_membership(self):
        with self.assertRaisesRegex(ValueError,'Duplicate'):validate([session(),session()])
        with self.assertRaisesRegex(ValueError,'membership'):summarize([session()],[])

    def test_copy_task_reference_and_submission_are_explicit(self):
        row=session(origin='recorded_copy_typing',intent_status='task_reference',events=[dict(kind='type',text='hello'),dict(kind='submit')])
        self.assertEqual(1,validate([row])['sessions'])
        with self.assertRaisesRegex(ValueError,'recorded copy task'):validate([dict(row,origin='authored_diagnostic')])


if __name__=='__main__':unittest.main()
