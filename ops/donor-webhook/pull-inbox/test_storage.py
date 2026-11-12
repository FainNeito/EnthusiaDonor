"""SQL protocol experiment only; SQLite is not a deployed Cloudflare D1 test."""
import concurrent.futures
from contextlib import closing
import hashlib
from pathlib import Path
import sqlite3
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class InboxStorage(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'inbox.sqlite'
        with closing(self.connect()) as db:
            db.executescript((HERE / 'schema.sql').read_text())

    def connect(self):
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        db.row_factory = sqlite3.Row
        return db

    def run_sql(self, filename, *args):
        with closing(self.connect()) as db:
            return db.execute((HERE / filename).read_text(), args).fetchall()

    def enqueue(self, body=b'fixture', source='test-store'):
        key = hashlib.sha256(body).hexdigest()
        self.run_sql('enqueue.sql', source, key, body, '0' * 64, 10000, 67108864)
        return key

    def claim(self, token='lease-a', source='test-store'):
        return self.run_sql('claim.sql', source, token, 60, 5)

    def ack(self, key, token='lease-a', source='test-store'):
        return self.run_sql('ack.sql', source, key, token)

    def expire(self, key):
        with closing(self.connect()) as db:
            db.execute('UPDATE inbox SET lease_until=unixepoch()-1 WHERE id=?', (key,))

    def test_full_receiver_size_raw_bytes_survive(self):
        body = bytes(range(256)) * 1024
        self.enqueue(body)
        self.assertEqual(body, self.claim()[0]['body'])

    def test_duplicate_enqueue_is_one_delivery(self):
        self.enqueue()
        self.enqueue()
        self.assertEqual(1, len(self.claim()))
        self.assertEqual([], self.claim('lease-b'))

    def test_crash_before_ack_redelivers_after_lease(self):
        key = self.enqueue()
        self.claim()
        self.assertEqual([], self.claim('lease-b'))
        self.expire(key)
        retry = self.claim('lease-b')[0]
        self.assertEqual(2, retry['attempts'])
        self.assertEqual(key, retry['id'])

    def test_old_owner_cannot_ack_new_lease(self):
        key = self.enqueue()
        self.claim()
        self.expire(key)
        self.claim('lease-b')
        self.assertEqual([], self.ack(key))
        self.assertEqual(1, len(self.ack(key, 'lease-b')))

    def test_expired_owner_cannot_ack_even_before_reclaim(self):
        key = self.enqueue()
        self.claim()
        self.expire(key)
        self.assertEqual([], self.ack(key))

    def test_ack_erases_payload_and_replay_stays_done(self):
        key = self.enqueue()
        self.claim()
        self.ack(key)
        self.enqueue()
        self.assertEqual([], self.claim('lease-b'))
        with closing(self.connect()) as db:
            row = db.execute('SELECT state,body,signature,lease_token FROM inbox WHERE id=?', (key,)).fetchone()
        self.assertEqual(('done', None, None, None), tuple(row))

    def test_store_isolation(self):
        key = self.enqueue()
        self.assertEqual([], self.claim(source='another-store'))
        self.claim()
        self.assertEqual([], self.ack(key, source='another-store'))
        self.assertEqual(1, len(self.ack(key)))

    def test_concurrent_claim_has_one_owner(self):
        self.enqueue()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(self.claim, ['lease-a', 'lease-b']))
        self.assertEqual([0, 1], sorted(len(rows) for rows in results))


if __name__ == '__main__':
    unittest.main(verbosity=2)
