import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createHash, createHmac } from 'node:crypto';
import { Miniflare } from 'miniflare';

const secret = 'synthetic-signing-secret-at-least-32-bytes';
const consumer = 'synthetic-consumer-secret-at-least-32-bytes';
async function fixture(t, bindings = {}) {
  const mf = new Miniflare({ modules: true, scriptPath: fileURLToPath(new URL('entry.mjs', import.meta.url)),
    modulesRules: [{ type: 'Text', include: ['**/*.sql'] }], compatibilityDate: '2026-07-01',
    host: '127.0.0.1', port: 0, d1Databases: ['DB'],
    bindings: { SOURCE: 'isolated', TEBEX_SECRET: secret, CONSUMER_SECRET: consumer, ...bindings } });
  t.after(() => mf.dispose());
  const db = await mf.getD1Database('DB');
  for (const statement of readFileSync(new URL('schema.sql', import.meta.url), 'utf8').split(';').filter(s => s.trim()))
    await db.prepare(statement).run();
  const post = (path, body = {}, auth = consumer) => mf.dispatchFetch('https://inbox.example' + path,
    { method: 'POST', body: JSON.stringify(body), headers: { authorization: 'Bearer ' + auth } });
  const send = (id, size) => {
    let body = JSON.stringify({ type: 'payment.completed', subject: { transaction_id: 'tbx-' + id } });
    if (size) body += ' '.repeat(size - Buffer.byteLength(body));
    const sig = createHmac('sha256', secret).update(createHash('sha256').update(body).digest('hex')).digest('hex');
    return mf.dispatchFetch('https://inbox.example/tebex', { method: 'POST', body, headers: { 'x-signature': sig } });
  };
  const expire = () => db.prepare('UPDATE inbox SET lease_until=unixepoch()-1').run();
  return { db, post, send, expire };
}

test('atomic row capacity: concurrent unique arrivals cannot over-admit; duplicates still succeed', async t => {
  const f = await fixture(t, { INBOX_MAX_ROWS: '1' });
  const responses = await Promise.all(['one', 'two'].map(id => f.send(id)));
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 503]);
  assert.equal(responses.find(r => r.status === 503).headers.get('retry-after'), '60');
  const winner = ['one', 'two'][responses.findIndex(r => r.status === 200)];
  assert.equal((await f.send(winner)).status, 200);
  assert.equal((await f.db.prepare('SELECT count(*) AS n FROM inbox').first()).n, 1);
  const row = await (await f.post('/consumer/claim')).json();
  await f.post('/consumer/ack', { id: row.id, lease_token: row.lease_token });
  assert.equal((await f.send('new-after-ack')).status, 503); // tombstone still consumes a row
  assert.equal((await f.send(winner)).status, 200);
});

test('quarantine transitions are bounded and exhausted rows cannot escape through claim', async t => {
  const f = await fixture(t, { INBOX_MAX_ATTEMPTS: '1' });
  const insert = f.db.prepare("INSERT INTO inbox(source,id,body,signature,attempts,lease_until) VALUES(?1,?2,?3,?4,1,unixepoch()-1)");
  const body = new TextEncoder().encode('synthetic').buffer;
  await f.db.batch(Array.from({ length: 101 }, (_, i) => insert.bind('isolated', String(i), body, 'a'.repeat(64))));
  await insert.bind('another-store', 'keep', body, 'a'.repeat(64)).run();
  assert.equal((await f.post('/consumer/claim')).status, 204);
  let status = await (await f.post('/consumer/status')).json();
  assert.equal(status.quarantined, 100); assert.equal(status.exhausted, 1); assert.equal(status.attention, true);
  assert.equal((await f.post('/consumer/claim')).status, 204);
  status = await (await f.post('/consumer/status')).json();
  assert.equal(status.quarantined, 101); assert.equal(status.exhausted, 0);
  assert.equal((await f.db.prepare("SELECT state FROM inbox WHERE source='another-store'").first()).state, 'pending');
});

test('payload byte budget frees only after durable ACK', async t => {
  const f = await fixture(t, { INBOX_MAX_BYTES: '262144' });
  assert.equal((await f.send('large', 262144)).status, 200);
  assert.equal((await f.send('other')).status, 503);
  const row = await (await f.post('/consumer/claim')).json();
  assert.equal((await f.post('/consumer/ack', { id: row.id, lease_token: row.lease_token })).status, 204);
  assert.equal((await f.send('other')).status, 200);
});

test('concurrent byte admission and quarantine cannot evade the database-wide budget', async t => {
  const f = await fixture(t, { INBOX_MAX_BYTES: '262144', INBOX_MAX_ATTEMPTS: '1' });
  const responses = await Promise.all([f.send('one', 131073), f.send('two', 131073)]);
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 503]);
  await f.post('/consumer/claim'); await f.expire(); await f.post('/consumer/claim');
  assert.equal((await f.db.prepare('SELECT state FROM inbox').first()).state, 'quarantined');
  assert.equal((await f.send('three', 131073)).status, 503);
  // Dedicated database capacity counts other namespaces too; maintenance may
  // never delete another namespace just to make room for this one.
  await f.db.prepare("UPDATE inbox SET source='another-store'").run();
  assert.equal((await f.send('four', 131073)).status, 503);
});

test('operator requeue preserves evidence and rejects stale recovery generations and other sources', async t => {
  const f = await fixture(t, { INBOX_MAX_ATTEMPTS: '1' });
  await f.send('recovery'); await f.post('/consumer/claim'); await f.expire(); await f.post('/consumer/claim');
  const before = await f.db.prepare('SELECT * FROM inbox').first();
  const inspection = await f.db.prepare(readFileSync(new URL('inspect-quarantine.sql', import.meta.url), 'utf8')).bind('isolated').first();
  assert.equal(inspection.id, before.id); assert.equal(inspection.quarantine_count, 1);
  assert.equal('body' in inspection, false); assert.equal('signature' in inspection, false);
  const sql = readFileSync(new URL('requeue.sql', import.meta.url), 'utf8');
  assert.equal(await f.db.prepare(sql).bind('other', before.id, 1).first(), null);
  assert.ok(await f.db.prepare(sql).bind('isolated', before.id, 1).first());
  const after = await f.db.prepare('SELECT * FROM inbox').first();
  assert.deepEqual(after.body, before.body); assert.equal(after.signature, before.signature);
  assert.equal(after.created_at, before.created_at); assert.equal(after.attempts, 0);
  await f.post('/consumer/claim'); await f.expire(); await f.post('/consumer/claim');
  assert.equal((await f.db.prepare('SELECT quarantine_count FROM inbox').first()).quarantine_count, 2);
  assert.equal(await f.db.prepare(sql).bind('isolated', before.id, 1).first(), null);
  assert.equal((await f.post('/consumer/requeue', { id: before.id })).status, 404);
});

test('bounded retention keeps quarantine and other stores; completed duplicates do not refresh ACK age', async t => {
  const f = await fixture(t, { INBOX_MAX_ATTEMPTS: '1' });
  await f.send('held'); await f.post('/consumer/claim'); await f.expire(); await f.post('/consumer/claim');
  await f.db.prepare('UPDATE inbox SET created_at=unixepoch()-400*86400, quarantined_at=unixepoch()-400*86400').run();
  const insert = f.db.prepare("INSERT INTO inbox(source,id,state,completed_at) VALUES(?1,?2,'done',unixepoch()-91*86400)");
  await f.db.batch(Array.from({ length: 101 }, (_, i) => insert.bind('isolated', String(i))));
  await insert.bind('another-store', 'keep').run();
  await f.post('/consumer/claim');
  assert.equal((await f.db.prepare("SELECT count(*) AS n FROM inbox WHERE source='isolated' AND state='done'").first()).n, 1);
  await f.post('/consumer/claim');
  assert.equal((await f.db.prepare("SELECT count(*) AS n FROM inbox WHERE source='isolated' AND state='done'").first()).n, 0);
  const held = await f.db.prepare("SELECT * FROM inbox WHERE state='quarantined'").first(); assert.ok(held.body);
  assert.ok(await f.db.prepare("SELECT id FROM inbox WHERE source='another-store'").first());
  await f.send('recent'); const claimed = await (await f.post('/consumer/claim')).json();
  await f.post('/consumer/ack', { id: claimed.id, lease_token: claimed.lease_token });
  await f.db.prepare("UPDATE inbox SET completed_at=unixepoch()-89*86400 WHERE id=?1").bind(claimed.id).run();
  const completed = await f.db.prepare('SELECT completed_at FROM inbox WHERE id=?1').bind(claimed.id).first();
  await f.send('recent');
  assert.deepEqual(await f.db.prepare('SELECT completed_at FROM inbox WHERE id=?1').bind(claimed.id).first(), completed);
});

test('last allowed lease can ACK; only exhausted expired events quarantine and never auto-retry', async t => {
  const f = await fixture(t, { INBOX_MAX_ATTEMPTS: '1' });
  await f.send('poison');
  const lease = await (await f.post('/consumer/claim')).json();
  assert.equal((await f.post('/consumer/claim')).status, 204);
  assert.equal((await f.db.prepare('SELECT state FROM inbox').first()).state, 'pending');
  await f.expire();
  assert.equal((await f.post('/consumer/claim')).status, 204);
  const quarantined = await f.db.prepare('SELECT * FROM inbox').first();
  assert.equal(quarantined.state, 'quarantined'); assert.ok(quarantined.body); assert.ok(quarantined.signature);
  assert.equal(quarantined.quarantine_count, 1); assert.equal(quarantined.lease_token, null);
  const status = await (await f.post('/consumer/status')).json();
  assert.equal(status.quarantined, 1); assert.equal(status.attention, true);
  assert.equal((await f.post('/consumer/ack', { id: lease.id, lease_token: lease.lease_token })).status, 409);
  assert.equal((await f.send('poison')).status, 200);
  assert.equal((await f.post('/consumer/claim')).status, 204);
  await f.send('healthy');
  const healthy = await (await f.post('/consumer/claim')).json();
  assert.equal((await f.post('/consumer/ack', { id: healthy.id, lease_token: healthy.lease_token })).status, 204);
});

test('retention uses ACK time and deletes only old completed tombstones, never unprocessed bodies', async t => {
  const f = await fixture(t);
  await f.send('done');
  const row = await (await f.post('/consumer/claim')).json();
  await f.post('/consumer/ack', { id: row.id, lease_token: row.lease_token });
  await f.send('pending');
  // Make arrival old, but leave completion recent: it must survive maintenance.
  await f.db.prepare('UPDATE inbox SET created_at=unixepoch()-200*86400').run();
  await f.post('/consumer/claim');
  assert.equal((await f.db.prepare("SELECT count(*) AS n FROM inbox WHERE state='done'").first()).n, 1);
  await f.db.prepare("UPDATE inbox SET completed_at=unixepoch()-91*86400 WHERE state='done'").run();
  await f.post('/consumer/claim');
  assert.equal((await f.db.prepare("SELECT count(*) AS n FROM inbox WHERE state='done'").first()).n, 0);
  assert.equal((await f.db.prepare("SELECT count(*) AS n FROM inbox WHERE state='pending' AND body IS NOT NULL").first()).n, 1);
});

test('status is authenticated and payload-free; invalid policy fails closed', async t => {
  const f = await fixture(t, { INBOX_MAX_ROWS: '1' });
  await f.send('private-reference');
  assert.equal((await f.post('/consumer/status', {}, 'wrong')).status, 403);
  const result = await f.post('/consumer/status'); assert.equal(result.status, 200);
  const text = await result.text(); assert.equal(text.includes('private-reference'), false);
  const status = JSON.parse(text); assert.equal(status.rows, 1); assert.equal(status.attention, true);
  const invalid = await fixture(t, { INBOX_MAX_ROWS: '0' });
  assert.equal((await invalid.send('invalid')).status, 503);
});
