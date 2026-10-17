import copy,json,unittest
import xml.etree.ElementTree as ET
from verify_bilingual_pipeline import check_report

def fixture():
    root=ET.Element('testsuite',tests='4',failures='0',errors='0',skipped='0')
    names=[f'english/{m}/rapid={r}/touch={t}/{w}' for m in ('AlphaNum','Quick') for r in ('false','true')
        for t in ('false','true') for w in ('teh','helllo','wprld','typo','Xentara')]
    names+=['cantonese/traditional','cantonese/whole-phrase','mixed/cantonese-english']
    rows=[dict(name=n,quality_regression=False,expected='ok',actual='ok',passed=True) for n in names]
    rows += [dict(name=f'reported/{m}/case{i}',quality_regression=True,expected='fixed',actual='fixed',passed=True)
        for m in ('AlphaNum','Quick') for i in range(7)]
    return root,rows

def evaluate(root,rows):
    ET.SubElement(root,'system-out').text='\n'.join('BILINGUAL_E2E '+json.dumps(r) for r in rows)
    return check_report(root)

class AcceptanceReportTest(unittest.TestCase):
    def test_complete_outputs_pass_both_gates(self):
        report=evaluate(*fixture());self.assertTrue(report['mechanisms_passed']);self.assertTrue(report['reported_corrections_passed'])

    def test_actual_mismatch_fails_even_if_producer_says_pass(self):
        root,rows=fixture();rows[-1]['actual']='still wrong'
        report=evaluate(root,rows);self.assertTrue(report['mechanisms_passed']);self.assertFalse(report['reported_corrections_passed'])

    def test_missing_or_duplicated_cantonese_coverage_fails(self):
        root,rows=fixture();rows[40]=copy.deepcopy(rows[0])
        self.assertFalse(evaluate(root,rows)['mechanisms_passed'])

    def test_skipped_native_checks_never_pass(self):
        root,rows=fixture();root.set('skipped','1');self.assertFalse(evaluate(root,rows)['mechanisms_passed'])

    def test_no_observations_never_pass(self):
        report=evaluate(fixture()[0],[]);self.assertFalse(report['mechanisms_passed']);self.assertFalse(report['reported_corrections_passed'])

if __name__=='__main__':unittest.main()
