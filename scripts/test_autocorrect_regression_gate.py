import unittest

from check_autocorrect_regressions import evaluate


class RegressionGateTest(unittest.TestCase):
    def test_ambiguity_failure_cannot_be_offset_by_a_successful_correction(self):
        cases=[dict(id='ambiguous',typed='butger',expected='butger'),
               dict(id='correctable',typed='gwme',expected='game')]
        report=evaluate(cases,[dict(correction='burger'),dict(correction='game')])
        self.assertFalse(report['passed'])
        self.assertEqual('ambiguous',report['failures'][0]['id'])

    def test_case_restoration_is_not_a_spelling_replacement(self):
        report=evaluate([dict(id='literal',typed='Name',expected='Name')],[dict(correction='name')])
        self.assertTrue(report['passed'])

    def test_missing_guard_outputs_are_not_silently_ignored(self):
        with self.assertRaisesRegex(ValueError,'membership'):
            evaluate([dict(id='literal',typed='word',expected='word')],[])


if __name__=='__main__':unittest.main()
