import tempfile
import unittest
from pathlib import Path
from selection_memory import SelectionMemory


class SelectionMemoryTest(unittest.TestCase):
    def setUp(self):
        self.memory = SelectionMemory()
        self.base = ['兜兩', '自兩', '先兩', '看兩', '兒歌', '自歌', 'humo']

    def tearDown(self):
        self.memory.close()

    def select(self, word='兒歌', **flags):
        return self.memory.record('quick3', 'humo', word, self.base,
            **(dict(explicit=True, committed=True, eligible=True) | flags))

    def test_exact_input_and_layout_only(self):
        self.select()
        learned = self.memory.rank_recent('quick3', 'HUMO', self.base)
        self.assertEqual('兒歌', learned[0])
        self.assertEqual(set(self.base), set(learned))
        self.assertEqual(self.base, self.memory.rank_recent('quick5', 'humo', self.base))
        self.assertEqual(self.base, self.memory.rank_recent('quick3', 'h', self.base))

    def test_rejection_rolls_back_one_event_preserving_earlier_history(self):
        first = self.select()
        second = self.select()
        self.assertTrue(self.memory.undo(second))
        self.assertEqual(1, self.memory.evidence('quick3', 'humo')['兒歌'][0])
        self.assertFalse(self.memory.undo(second))
        self.assertTrue(self.memory.undo(first))
        self.assertEqual(self.base, self.memory.rank_recent('quick3', 'humo', self.base))

    def test_undo_of_different_choice_restores_previous_preference(self):
        self.select()
        event = self.select('自歌')
        self.assertEqual('自歌', self.memory.rank_recent('quick3', 'humo', self.base)[0])
        self.memory.undo(event)
        self.assertEqual('兒歌', self.memory.rank_recent('quick3', 'humo', self.base)[0])

    def test_no_automatic_failed_or_unshown_selection_learning(self):
        for flags in [dict(explicit=False), dict(committed=False), dict(eligible=False)]:
            self.assertIsNone(self.select(**flags))
        self.assertIsNone(self.select('不存在'))
        self.assertEqual({}, self.memory.evidence('quick3', 'humo'))

    def test_private_editor_does_not_read_preferences(self):
        self.select()
        self.assertEqual(self.base, self.memory.rank_recent('quick3', 'humo', self.base, eligible=False))

    def test_forget_and_stale_undo_cannot_remove_new_learning(self):
        old = self.select()
        self.select()
        self.memory.forget('quick3', 'humo', '兒歌')
        self.assertEqual({}, self.memory.evidence('quick3', 'humo'))
        new = self.select()
        self.assertNotEqual(old, new)
        self.assertFalse(self.memory.undo(old))
        self.assertEqual(1, self.memory.evidence('quick3', 'humo')['兒歌'][0])

    def test_absent_candidate_is_not_reintroduced(self):
        self.select()
        other = ['自歌', '兜兩']
        self.assertEqual(other, self.memory.rank_recent('quick3', 'humo', other))

    def test_persistence_and_clear(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'preferences.sqlite'
            m = SelectionMemory(path)
            m.record('quick3', 'humo', '兒歌', self.base, explicit=True, committed=True, eligible=True)
            m.close()
            m = SelectionMemory(path)
            self.assertEqual('兒歌', m.rank_recent('quick3', 'humo', self.base)[0])
            m.clear()
            m.close()
            m = SelectionMemory(path)
            self.assertEqual(self.base, m.rank_recent('quick3', 'humo', self.base))
            m.close()


if __name__ == '__main__':
    unittest.main()
