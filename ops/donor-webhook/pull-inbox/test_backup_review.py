"""Synthetic local SQLite snapshot exercise, not Cloudflare Time Travel validation."""
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class BackupReview(unittest.TestCase):
    def test_ack_and_retention_do_not_erase_independent_snapshot(self):
        with tempfile.TemporaryDirectory(prefix='donor-backup-review-') as directory:
            root = Path(directory)
            with closing(sqlite3.connect(root / 'live.sqlite')) as live, \
                    closing(sqlite3.connect(root / 'snapshot.sqlite')) as snapshot, \
                    closing(sqlite3.connect(root / 'isolated-restore.sqlite')) as restored:
                live.executescript((HERE / 'schema.sql').read_text())
                raw = b'synthetic payment bytes - no live customer data'
                live.execute((HERE / 'enqueue.sql').read_text(), ('fixture', 'digest', raw, 'synthetic-signature', 10000, 67108864)).fetchall()
                live.execute((HERE / 'claim.sql').read_text(), ('fixture', 'old-lease', 90, 5)).fetchall()
                live.commit()
                live.backup(snapshot)
                self.assertEqual([('digest',)], live.execute((HERE / 'ack.sql').read_text(), ('fixture', 'digest', 'old-lease')).fetchall())
                live.commit()
                self.assertEqual(('done', None, None), live.execute('SELECT state,body,signature FROM inbox').fetchone())
                live.execute('UPDATE inbox SET completed_at=unixepoch()-91*86400')
                live.execute((HERE / 'prune.sql').read_text(), ('fixture', 90*86400))
                live.commit()
                self.assertEqual(0, live.execute('SELECT count(*) FROM inbox').fetchone()[0])
                snapshot.backup(restored)
                self.assertEqual(('pending', raw, 'synthetic-signature', 'old-lease'),
                                 restored.execute('SELECT state,body,signature,lease_token FROM inbox').fetchone())
                # A restored lease is not proof of current ownership. Simulate its
                # later expiry only inside the disconnected fixture.
                restored.execute('UPDATE inbox SET lease_until=unixepoch()-1')
                self.assertEqual([], restored.execute((HERE / 'ack.sql').read_text(), ('fixture', 'digest', 'old-lease')).fetchall())
                replay = restored.execute((HERE / 'claim.sql').read_text(), ('fixture', 'new-lease', 90, 5)).fetchone()
                self.assertEqual('digest', replay[0])  # rollback can resurrect deliverable work
                self.assertEqual(0, live.execute('SELECT count(*) FROM inbox').fetchone()[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
