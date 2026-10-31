import copy
import unittest

from score_ite_messages import summarize


class MessageMetricsTest(unittest.TestCase):
    def fixture(self):
        metadata = [dict(id='a', participant='p', prompt='q', stratum='two_errors',
            platform='android', prompt_source='mobile', observed='teh cta', reference='the cat')]
        sessions = [dict(id='a:english', group='a', layout='english', expected_text='the cat',
            events=[dict(kind='type', text='teh cta'), dict(kind='submit')])]
        report = dict(results=[dict(id='a:english', submit_actions=1,
            submitted_texts=['the cta'], actual='the cat', composing='')])
        return metadata, sessions, report

    def test_scores_sent_payload_and_requires_complete_recovery(self):
        result = summarize(*self.fixture())['overall']
        self.assertEqual(result['complete_lexical_recovery'], 0)
        self.assertEqual(result['word_error_reduction_fraction'], .5)

    def test_clean_controls_do_not_pad_error_recovery(self):
        metadata, sessions, report = self.fixture()
        metadata.append(dict(metadata[0], id='b', observed='the cat', stratum='clean'))
        sessions.append(dict(sessions[0], id='b:english', group='b',
            events=[dict(kind='type', text='the cat'), dict(kind='submit')]))
        report['results'].append(dict(report['results'][0], id='b:english', submitted_texts=['the cat']))
        result = summarize(metadata, sessions, report)['overall']
        self.assertEqual(result['error_attempts'], 1)
        self.assertEqual(result['complete_lexical_recovery_fraction'], 0)
        self.assertEqual(result['clean_lexical_damage'], 0)

    def test_rejects_incomplete_or_duplicate_replays(self):
        for mutate in (lambda r: r['results'][0].update(submit_actions=0),
                       lambda r: r['results'][0].update(composing='cat'),
                       lambda r: r['results'].append(copy.deepcopy(r['results'][0]))):
            metadata, sessions, report = self.fixture()
            mutate(report)
            with self.assertRaises(ValueError):
                summarize(metadata, sessions, report)

    def test_scope_is_independent_of_output(self):
        metadata, sessions, report = self.fixture()
        metadata[0].update(observed='meet 7', reference='meet 8')
        sessions[0]['expected_text'] = 'meet 8'
        sessions[0]['events'][0]['text'] = 'meet 7'
        for output in ('meet 7', 'meet 8'):
            report['results'][0]['submitted_texts'] = [output]
            self.assertEqual(summarize(metadata, sessions, report)['word_substitution_scope']['attempts'], 0)

    def test_lexical_and_strict_case_are_distinct(self):
        metadata, sessions, report = self.fixture()
        report['results'][0]['submitted_texts'] = ['The cat.']
        result = summarize(metadata, sessions, report)['overall']
        self.assertEqual(result['complete_lexical_recovery'], 1)
        self.assertEqual(result['exact_error_recovery'], 0)

    def test_rejects_label_injected_into_observed_input(self):
        metadata, sessions, report = self.fixture()
        sessions[0]['events'][0]['text'] = metadata[0]['reference']
        with self.assertRaisesRegex(ValueError, 'Replay input differs'):
            summarize(metadata, sessions, report)


if __name__ == '__main__':
    unittest.main()
