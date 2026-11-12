"""Synthetic local SQLite snapshot exercise, not Cloudflare Time Travel validation."""
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


def run(db, filename, *args):
    # D1 uses ordered ?N placeholders; Python's named bindings use numeric keys.
    return db.execute((HERE / filename).read_text(),
                      {str(i): value for i, value in enumerate(args, 1)}).fetchall()


class BackupReview(unittest.TestCase):
    def test_ack_and_retention_do_not_erase_independent_snapshot(self):
        with tempfile.TemporaryDirectory(prefix='donor-backup-review-') as directory:
            root = Path(directory)
            with closing(sqlite3.connect(root / 'live.sqlite')) as live, \
                    closing(sqlite3.connect(root / 'snapshot.sqlite')) as snapshot, \
                    closing(sqlite3.connect(root / 'isolated-restore.sqlite')) as restored:
                live.executescript((HERE / 'schema.sql').read_text())
                raw = b'synthetic payment bytes - no live customer data'
                run(live, 'enqueue.sql', 'fixture', 'digest', raw, 'synthetic-signature', 10000, 67108864)
                run(live, 'claim.sql', 'fixture', 'old-lease', 90, 5)
                live.commit()
                live.backup(snapshot)
                self.assertEqual([('digest',)], run(live, 'ack.sql', 'fixture', 'digest', 'old-lease'))
                live.commit()
                self.assertEqual(('done', None, None), live.execute('SELECT state,body,signature FROM inbox').fetchone())
                live.execute('UPDATE inbox SET completed_at=unixepoch()-91*86400')
                run(live, 'prune.sql', 'fixture', 90*86400)
                live.commit()
                self.assertEqual(0, live.execute('SELECT count(*) FROM inbox').fetchone()[0])
                snapshot.backup(restored)
                self.assertEqual(('pending', raw, 'synthetic-signature', 'old-lease'),
                                 restored.execute('SELECT state,body,signature,lease_token FROM inbox').fetchone())
                # A restored lease is not proof of current ownership. Simulate its
                # later expiry only inside the disconnected fixture.
                restored.execute('UPDATE inbox SET lease_until=unixepoch()-1')
                self.assertEqual([], run(restored, 'ack.sql', 'fixture', 'digest', 'old-lease'))
                replay = run(restored, 'claim.sql', 'fixture', 'new-lease', 90, 5)[0]
                self.assertEqual('digest', replay[0])  # rollback can resurrect deliverable work
                self.assertEqual(0, live.execute('SELECT count(*) FROM inbox').fetchone()[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
