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
  for (const statement of readFileSync(new URL('schema.sql', import.meta.url), 'utf8').split('-- TRIGGERS START')[0].split(';').filter(s => s.trim()))
    await db.prepare(statement).run();
    for (const statement of readFileSync(new URL('schema.sql', import.meta.url), 'utf8').split('-- TRIGGERS START')[1].split('-- trigger-break')) await db.prepare(statement).run();
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

test('monitor credentials are read-only and status cannot mutate stored events', async t => {
  const monitor = 'synthetic-read-only-monitor-token-32-bytes';
  const f = await fixture(t, { MONITOR_SECRET: monitor, INBOX_MAX_ATTEMPTS: '1' });
  await f.send('preserve'); await f.post('/consumer/claim'); await f.expire();
  const before = await f.db.prepare('SELECT * FROM inbox').all();
  const r = await f.post('/monitor/status', {}, monitor); assert.equal(r.status, 200);
  const status = await r.json(); assert.equal(status.exhausted, 1); assert.equal(status.attention, true);
  assert.ok(Number.isSafeInteger(status.observed_at));
  for (const path of ['/consumer/claim', '/consumer/ack', '/consumer/status'])
    assert.equal((await f.post(path, {}, monitor)).status, 403);
  assert.equal((await f.post('/monitor/status', {}, consumer)).status, 403);
  assert.equal((await f.post('/monitor/status', { source: 'other' }, monitor)).status, 400);
  assert.deepEqual((await f.db.prepare('SELECT * FROM inbox').all()).results, before.results);
});

test('absent monitor binding is disabled; credential reuse never grants monitoring consumer powers', async t => {
  const missing = await fixture(t);
  assert.equal((await missing.post('/monitor/status')).status, 503);
  assert.equal((await missing.post('/consumer/claim')).status, 204);
  const reused = await fixture(t, { MONITOR_SECRET: consumer });
  for (const path of ['/monitor/status', '/consumer/claim', '/consumer/ack'])
    assert.equal((await reused.post(path)).status, 503);
});

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


test('capacity metadata stays exact through duplicate, ACK and delete and fails closed if missing', async t => {
  const f = await fixture(t, { INBOX_MAX_ROWS: '1' });
  assert.equal((await f.send('quota')).status, 200);
  const usage = async () => await f.db.prepare('SELECT rows, payload_bytes FROM inbox_usage WHERE singleton=1').first();
  const initial=await usage(); assert.equal(initial.rows,1); assert.ok(initial.payload_bytes>0);
  assert.equal((await f.send('quota')).status,200); assert.deepEqual(await usage(),initial);
  const item=await (await f.post('/consumer/claim')).json();
  assert.equal((await f.post('/consumer/ack',{id:item.id,lease_token:item.lease_token})).status,204);
  assert.deepEqual(await usage(),{rows:1,payload_bytes:0});
  await f.db.prepare('DELETE FROM inbox').run(); assert.deepEqual(await usage(),{rows:0,payload_bytes:0});
  await f.db.prepare('DELETE FROM inbox_usage').run();
  assert.equal((await f.send('blocked')).status,503);
});

test('large exhausted backlog and capacity checks stay bounded in local D1', {timeout:30000}, async t => {
  const f=await fixture(t);
  await f.db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<10000)
    INSERT INTO inbox(source,id,body,signature,attempts,lease_until)
    SELECT 'isolated',printf('%064x',x),x'01','s',5,0 FROM c`).run();
  const sql=name=>readFileSync(new URL(name+'.sql',import.meta.url),'utf8');
  const claim=await f.db.prepare(sql('claim')).bind('isolated','token',90,5).run();
  assert.ok(claim.meta.rows_read<100, `claim read ${claim.meta.rows_read}`);
  const quarantine=await f.db.prepare(sql('quarantine')).bind('isolated',5).run();
  assert.ok(quarantine.meta.rows_read<1000, `quarantine read ${quarantine.meta.rows_read}`);
  const enqueue=await f.db.prepare(sql('enqueue')).bind('isolated','f'.repeat(64),new Uint8Array([1]),'s',10000,67108864).run();
  assert.ok(enqueue.meta.rows_read<100, `enqueue read ${enqueue.meta.rows_read}`);
});

test('large active lease set does not cause a full idle claim scan', {timeout:30000}, async t => {
 const f=await fixture(t);
 await f.db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<10000)
 INSERT INTO inbox(source,id,body,signature,attempts,lease_until)
 SELECT 'isolated',printf('%064x',x),x'01','s',1,unixepoch()+3600 FROM c`).run();
 const claim=await f.db.prepare(readFileSync(new URL('claim.sql',import.meta.url),'utf8')).bind('isolated','token',90,5).run();
 assert.ok(claim.meta.rows_read<100, `claim read ${claim.meta.rows_read}`);
});

test('mostly exhausted backlog with one active lease avoids a full claim scan', {timeout:30000}, async t => {
 const f=await fixture(t);
 await f.db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<10000)
 INSERT INTO inbox(source,id,body,signature,attempts,lease_until)
 SELECT 'isolated',printf('%064x',x),x'01','s',CASE WHEN x=10000 THEN 1 ELSE 5 END,
 CASE WHEN x=10000 THEN unixepoch()+3600 ELSE 0 END FROM c`).run();
 const claim=await f.db.prepare(readFileSync(new URL('claim.sql',import.meta.url),'utf8')).bind('isolated','token',90,5).run();
 assert.ok(claim.meta.rows_read<100, `claim read ${claim.meta.rows_read}`);
});

test('usage remains equal to stored data across sources and failed batch rollback', async t => {
 const f=await fixture(t);
 const ins=f.db.prepare('INSERT INTO inbox(source,id,body,signature) VALUES(?1,?2,?3,?4)');
 await ins.bind('isolated','one',new Uint8Array([1,2,3]),'s').run();
 await ins.bind('other','two',new Uint8Array([4,5]),'s').run();
 const verify=async()=>{
   const actual=await f.db.prepare('SELECT count(*) AS rows,coalesce(sum(length(body)),0) AS payload_bytes FROM inbox').first();
   const recorded=await f.db.prepare('SELECT rows,payload_bytes FROM inbox_usage WHERE singleton=1').first();
   assert.deepEqual(recorded,actual);
 };
 await verify();
 await f.db.prepare("UPDATE inbox SET body=x'01020304' WHERE id='one'").run(); await verify();
 await assert.rejects(f.db.batch([ins.bind('isolated','rolled-back',new Uint8Array([1]),'s'),ins.bind('isolated','one',new Uint8Array([1]),'s')]));
 await verify(); assert.equal(await f.db.prepare("SELECT id FROM inbox WHERE id='rolled-back'").first(),null);
 await f.db.prepare("DELETE FROM inbox WHERE source='other'").run();await verify();
});

test('mixed lease-heavy backlog and completed status avoid full scans', {timeout:30000}, async t => {
 const f=await fixture(t);
 await f.db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<10000)
 INSERT INTO inbox(source,id,body,signature,attempts,lease_until)
 SELECT 'isolated',printf('%064x',x),x'01','s',CASE WHEN x=10000 THEN 5 ELSE 1 END,
 CASE WHEN x=10000 THEN 0 ELSE unixepoch()+3600 END FROM c`).run();
 const sql=name=>readFileSync(new URL(name+'.sql',import.meta.url),'utf8');
 const claim=await f.db.prepare(sql('claim')).bind('isolated','token',90,5).run();
 assert.ok(claim.meta.rows_read<200, `claim read ${claim.meta.rows_read}`);
 await f.db.prepare("DELETE FROM inbox").run();
 await f.db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<10000)
 INSERT INTO inbox(source,id,state,completed_at) SELECT 'isolated',printf('%064x',x),'done',unixepoch() FROM c`).run();
 const status=await f.db.prepare(sql('status')).bind('isolated',5).run();
 assert.equal(status.results[0].completed,10000);
 assert.ok(status.meta.rows_read<200, `status read ${status.meta.rows_read}`);
});

test('completed status avoids a full inbox scan', {timeout:30000}, async t => {
 const f=await fixture(t);
 await f.db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<10000)
 INSERT INTO inbox(source,id,state,completed_at) SELECT 'isolated',printf('%064x',x),'done',unixepoch() FROM c`).run();
 const status=await f.db.prepare(readFileSync(new URL('status.sql',import.meta.url),'utf8')).bind('isolated',5).run();
 assert.equal(status.results[0].completed,10000);
 assert.ok(status.meta.rows_read<200, `status read ${status.meta.rows_read}`);
});

test('maintained status equals direct aggregation through source and state transitions', async t => {
 const f=await fixture(t, { INBOX_MAX_ATTEMPTS:'1' });
 await f.send('one'); await f.send('two');
 let claim=await (await f.post('/consumer/claim')).json(); await f.post('/consumer/ack',{id:claim.id,lease_token:claim.lease_token});
 claim=await (await f.post('/consumer/claim')).json(); await f.expire();
 const sql=readFileSync(new URL('status.sql',import.meta.url),'utf8');
 const verify=async source=>{
   const status=await f.db.prepare(sql).bind(source,1).first();
   const actual=await f.db.prepare(`SELECT count(*) AS rows,coalesce(sum(length(body)),0) AS payload_bytes,
   coalesce(sum(source=?1 AND state='pending'),0) AS pending,coalesce(sum(source=?1 AND state='quarantined'),0) AS quarantined,
   coalesce(sum(source=?1 AND state='done'),0) AS completed,
   coalesce(sum(source=?1 AND state='pending' AND attempts>=1 AND lease_until<=unixepoch()),0) AS exhausted FROM inbox`).bind(source).first();
   for(const k of Object.keys(actual)) assert.equal(status[k],actual[k],k);
 };
 await verify('isolated'); await verify('other');
 await f.post('/consumer/claim'); await verify('isolated');
 await f.db.prepare("UPDATE inbox SET source='other' WHERE state='quarantined'").run();
 await verify('isolated'); await verify('other');
 await f.db.prepare("DELETE FROM inbox WHERE source='other'").run(); await verify('other');
 await f.db.prepare("DELETE FROM inbox_source_usage WHERE source='isolated'").run();
 assert.equal((await f.post('/consumer/status')).status,503);
 assert.equal((await f.send('third')).status,503);
});
