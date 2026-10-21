import unittest

from iterate_unambiguous_neighbors import fallback, neighbor_replacement, repairs, unique_neighbor


class UnambiguousNeighborTest(unittest.TestCase):
    def test_unique_repair_counts_competing_error_types(self):
        self.assertEqual('friday', unique_neighbor('fridau', {'friday'}))
        for competitor in ('frida', 'fridua', 'fridaux', 'fridax'):
            self.assertIsNone(unique_neighbor('fridau', {'friday', competitor}))
        self.assertIsNone(unique_neighbor('grup', {'grip', 'group'}))
        self.assertIsNone(unique_neighbor('fridau', {'fridau', 'friday'}))
        self.assertTrue(neighbor_replacement('gwme', 'game'))
        self.assertFalse(neighbor_replacement('gpme', 'game'))

    def test_first_ranked_fallback_preserves_existing_and_known_input(self):
        case = dict(typed='gwme')
        out = dict(correction=None, known=False, candidates=[dict(word='game')])
        self.assertEqual('game', fallback(case, out, {'game'}, require_first=True))
        out['known'] = True
        self.assertIsNone(fallback(case, out, {'game'}, require_first=True))
        out['known'] = False
        out['candidates'].insert(0, dict(word='wrong'))
        self.assertIsNone(fallback(case, out, {'game'}, require_first=True))
        out['correction'] = 'existing'
        self.assertEqual('existing', fallback(case, out, {'game'}, require_first=True))

    def test_all_repairs_exclude_identity_and_include_apostrophe(self):
        self.assertEqual({"can't", 'cat', 'cart', 'ant', 'catt'},
                         repairs('cant', {"can't", 'cat', 'cart', 'ant', 'cats', 'catt', 'cant'}))


if __name__ == '__main__':
    unittest.main()
