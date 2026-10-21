"""Exploration only: reversible local selection evidence, outside the IME.

The recency ordering is a parameter-free diagnostic, not a calibrated production
ranking policy. SQLite demonstrates persistence and exact event rollback; a
production writer must run off the input thread and have a bounded history.
"""
import sqlite3


class SelectionMemory:
    def __init__(self, path=':memory:'):
        self.db = sqlite3.connect(path)
        self.db.execute('''CREATE TABLE IF NOT EXISTS selections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            layout TEXT NOT NULL, code TEXT NOT NULL, word TEXT NOT NULL)''')
        self.db.execute('CREATE INDEX IF NOT EXISTS by_code ON selections(layout,code)')
        self.cache = {}
        for layout, code in self.db.execute('SELECT DISTINCT layout,code FROM selections'):
            self._refresh(layout, code)

    def _refresh(self, layout, code):
        rows = list(self.db.execute('''SELECT word,COUNT(*),MAX(id) FROM selections
            WHERE layout=? AND code=? GROUP BY word''', (layout, code)))
        if rows:
            self.cache[layout, code] = {word:(count,last) for word,count,last in rows}
        else:
            self.cache.pop((layout, code), None)

    def record(self, layout, code, word, shown, *, explicit, committed, eligible):
        # These facts must be captured with the selection, before composition is
        # reset. Never infer a selection from an automatic commit or a prediction.
        if not (eligible and explicit and committed and word in shown):
            return None
        if not code or not code.isascii() or not code.isalpha():
            return None
        code = code.lower()
        with self.db:
            cursor = self.db.execute('INSERT INTO selections(layout,code,word) VALUES (?,?,?)',
                                     (layout, code, word))
        self._refresh(layout, code)
        return cursor.lastrowid

    def evidence(self, layout, code):
        return dict(self.cache.get((layout, code.lower()), {}))

    def rank_recent(self, layout, code, candidates, *, eligible=True):
        """Diagnostic only. No I/O, candidate injection, or model modification."""
        rows = self.cache.get((layout, code.lower()), {}) if eligible else {}
        if not rows:
            return list(candidates)
        word = max(rows, key=lambda word:rows[word][1])
        if word not in candidates:
            return list(candidates)
        return [word] + [c for c in candidates if c != word]

    def undo(self, event):
        row = self.db.execute('SELECT layout,code FROM selections WHERE id=?', (event,)).fetchone()
        if row is None:
            return False
        with self.db:
            self.db.execute('DELETE FROM selections WHERE id=?', (event,))
        self._refresh(*row)
        return True

    def forget(self, layout, code, word):
        code = code.lower()
        with self.db:
            self.db.execute('DELETE FROM selections WHERE layout=? AND code=? AND word=?', (layout, code, word))
        self._refresh(layout, code)

    def clear(self):
        with self.db:
            self.db.execute('DELETE FROM selections')
        self.cache.clear()

    def close(self):
        self.db.close()
