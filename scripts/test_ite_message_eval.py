import unittest
from build_ite_message_eval import reconstruct,severity,events_for,repair_encoding

def log(text,time,label='',order=0):return dict(INPUT=text,TIMESTAMP=str(time),OBSERVED_KEY=label,_ordinal=order)

class IteMessageEvalTest(unittest.TestCase):
    def test_encoding_artifacts_do_not_manufacture_hard_errors(self):
        self.assertEqual("I’m",repair_encoding('I\x92m'))
        self.assertEqual('one_error',severity('I\x92m taking Wednasday off.',"I'm taking Wednesday off."))
        result,error=reconstruct([log('I\x92m',1)],'I’m')
        self.assertIsNone(error);self.assertEqual('I’m',result['pieces'][0]['text'])
    def test_timestamp_order_and_initial_snapshot_seed(self):
        result,error=reconstruct([log('Hi ',30,'SPACE'),log('Hi',10),log('Hi',20,'SHIFT')],'Hi ')
        self.assertIsNone(error);self.assertEqual(2,result['initial_snapshot_chars']);self.assertEqual('Hi ',''.join(v['text'] for v in result['pieces']))
    def test_other_keyboard_outputs_never_become_user_keystrokes(self):
        for label in ('AC','PRED','GESTURE','AUTO-INPUT'):
            self.assertIsNone(reconstruct([log('teh',1),log('the ',2,label)],'the ')[0])
    def test_deletions_and_inconsistent_final_states_are_quarantined(self):
        self.assertIsNone(reconstruct([log('hello',1),log('hell',2)],'hell')[0])
        self.assertIsNone(reconstruct([log('hi',1)],'hello')[0])
    def test_word_burden_and_submit_do_not_invent_target_input(self):
        self.assertEqual('two_errors',severity('one wrong here','one word there'))
        self.assertEqual('clean',severity('Hello there ','Hello there'))
        self.assertEqual('formatting_only',severity('hello there','Hello there'))
        events=events_for('teh cat')
        self.assertEqual('submit',events[-1]['kind']);self.assertEqual('teh',events[0]['text'])

if __name__=='__main__':unittest.main()
