import gzip
import json
import tempfile
from pathlib import Path
import unittest

from build_independent_autocorrect_eval import channel,fold,github_rows,perturb
from experiment_conditional_english_scores import eligible


class IndependentEvalTest(unittest.TestCase):
    def test_repository_assignment_does_not_depend_on_words_or_outputs(self):
        self.assertEqual(fold('repo'),fold('repo'))
        self.assertIn(fold('repo'),('development','confirmation'))

    def test_real_word_edits_and_code_changes_are_not_nonword_gold(self):
        def edit(a,b,path='README.md'):
            return dict(src=dict(text=a,path=path,lang='eng'),tgt=dict(text=b,path=path,lang='eng'),prob_typo=.99)
        commit=dict(repo='r',commit='c',edits=[
            edit('this is a serivce example','this is a service example'),
            edit('this is her example','this is here example'),
            edit('this is a serivce example','this is a service example','file.ts')])
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'corpus.gz'
            with gzip.open(path,'wt') as f:f.write(json.dumps(commit)+'\n')
            rows,_=github_rows(path,{'service','here'},{'service','here','her'})
            self.assertEqual(1,len(rows))
            self.assertEqual(('serivce','service'),(rows[0]['typed'],rows[0]['target']))

    def test_only_fit_errors_supply_the_synthetic_channel(self):
        cases=[dict(split='fit',typed='teh',target='the'),
               dict(split='selection',typed='zhe',target='the'),
               dict(split='fit',typed='thhe',target='the')]
        patterns=channel(cases)
        self.assertIn(('transpose','he','eh'),patterns)
        self.assertNotIn(('substitute','t','z'),patterns)
        self.assertEqual('teh',perturb('the','transpose',patterns,'id'))

    def test_development_selection_cannot_trade_clean_damage_for_recall(self):
        base={'typo':dict(tp=10,fp=1,clean_changes=0),'clean':dict(tp=0,fp=1,clean_changes=1)}
        good={'typo':dict(tp=12,fp=1,clean_changes=0),'clean':dict(tp=0,fp=1,clean_changes=1)}
        bad={'typo':dict(tp=12,fp=1,clean_changes=0),'clean':dict(tp=0,fp=2,clean_changes=2)}
        self.assertTrue(eligible(good,base))
        self.assertFalse(eligible(bad,base))


if __name__=='__main__':unittest.main()
