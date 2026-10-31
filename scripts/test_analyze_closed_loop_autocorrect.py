import unittest

from analyze_closed_loop_autocorrect import error_type, identify_attempts, repaired_prefix, replay_trial


class FakeProbe:
    def __init__(self):
        self.requests = []

    def predict(self, left, typed, limit=8):
        self.requests.append((left,typed,limit))
        correction = 'help' if typed == 'helo' else None
        return dict(candidates=[],correction=correction,known=False,literalScore=None)


class ClosedLoopAuditTest(unittest.TestCase):
    def test_oracle_repairs_only_the_requested_previous_error_class(self):
        previous = [
            (dict(typed='helo',target='hello'),'help'),
            (dict(typed='wrold',target='world'),'wrold'),
            (dict(typed='abc',target=None),'abc'),
            (dict(typed='fine',target='fine'),'fine'),
        ]
        self.assertEqual('help world abc fine ',repaired_prefix(previous,'missed'))
        self.assertEqual('hello wrold abc fine ',repaired_prefix(previous,'wrong_edit'))
        self.assertEqual('hello world abc fine ',repaired_prefix(previous,'all'))
        self.assertEqual('wrong_edit',error_type(*previous[0]))
        self.assertEqual('unlabelled',error_type(*previous[2]))

    def test_oracle_never_enters_the_real_editor_history(self):
        cases = [dict(id=str(i),typed=w,target=t,left=l) for i,(w,t,l) in enumerate([
            ('helo','hello',''),('wrold','world','helo '),('nxt','next','helo wrold ')])]
        records = replay_trial(FakeProbe(),cases)
        self.assertEqual(['','help ','help wrold '],[r['left'] for r in records])
        self.assertEqual(['help','wrold','nxt'],[r['committed'] for r in records])
        other = [dict(c,target='different') for c in cases]
        changed_labels = replay_trial(FakeProbe(),other)
        self.assertEqual([r['left'] for r in records],[r['left'] for r in changed_labels])
        self.assertEqual([r['committed'] for r in records],[r['committed'] for r in changed_labels])

    def test_multiword_repair_preserves_the_observed_event_structure(self):
        previous = [(dict(typed='alot',target='a lot'),'alot')]
        self.assertEqual('a lot ',repaired_prefix(previous,'all'))
        self.assertEqual('alot ',repaired_prefix(previous,'wrong_edit'))

    def test_repeated_prompt_ids_keep_distinct_observed_attempts(self):
        cases=[dict(id='trial:0',typed=w,target='hello',start=0,end=len(w)) for w in ('helo','hello')]
        trials=[dict(id='trial',observed=w,words=['trial:0']) for w in ('helo','hello')]
        cc,tt,audit=identify_attempts(cases,trials)
        self.assertEqual(['helo','hello'],[c['typed'] for c in cc])
        self.assertEqual(2,len({c['id'] for c in cc}))
        self.assertEqual([c['id'] for c in cc],[t['words'][0] for t in tt])
        self.assertEqual(2,audit['attempts_reidentified'])


if __name__ == '__main__':
    unittest.main()
