import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createHash, createHmac } from 'node:crypto';
import { Miniflare } from 'miniflare';

test('local workerd/D1: authenticated ingestion, concurrent leases, redelivery, ACK and tombstone', { timeout: 30000 }, async () => {
  const secret = 'synthetic-signing-secret-at-least-32-bytes';
  const consumer = 'synthetic-consumer-secret-at-least-32-bytes';
  const mf = new Miniflare({ modules: true, scriptPath: fileURLToPath(new URL('entry.mjs', import.meta.url)),
    modulesRules: [{ type: 'Text', include: ['**/*.sql'] }], compatibilityDate: '2026-07-01',
    host: '127.0.0.1', port: 0, d1Databases: ['DB'],
    bindings: { SOURCE: 'isolated', TEBEX_SECRET: secret, CONSUMER_SECRET: consumer } });
  try {
    const db = await mf.getD1Database('DB');
    const schema = readFileSync(new URL('schema.sql', import.meta.url), 'utf8').split('-- TRIGGERS START')[0];
    for (const statement of schema.split(';').filter(s => s.trim())) await db.prepare(statement).run();
        for (const statement of readFileSync(new URL('schema.sql', import.meta.url), 'utf8').split('-- TRIGGERS START')[1].split('-- trigger-break')) await db.prepare(statement).run();
    const post = (path, body, headers = {}) => mf.dispatchFetch('https://inbox.example' + path, { method: 'POST', body, headers });
    const claim = () => post('/consumer/claim', '{}', { authorization: 'Bearer ' + consumer });
    // Exact maximum body, including multibyte UTF-8, survives D1 BLOB serialization.
    const prefix = JSON.stringify({ type: 'payment.completed', subject: { transaction_id: 'tbx-synthetic', fixture: 'é' } });
    const body = prefix + ' '.repeat(262144 - Buffer.byteLength(prefix));
    const signature = createHmac('sha256', secret).update(createHash('sha256').update(body).digest('hex')).digest('hex');
    assert.equal((await post('/consumer/claim', '{}')).status, 403);
    assert.equal((await post('/tebex', body, { 'x-signature': signature })).status, 200);
    assert.equal((await post('/tebex', body, { 'x-signature': signature })).status, 200);
    const claims = await Promise.all([claim(), claim()]);
    assert.deepEqual(claims.map(r => r.status).sort(), [200, 204]);
    const old = await claims.find(r => r.status === 200).json();
    assert.equal(Buffer.from(old.body, 'base64').toString('utf8'), body);
    const ack = row => post('/consumer/ack', JSON.stringify({ id: row.id, lease_token: row.lease_token }), { authorization: 'Bearer ' + consumer });
    await db.prepare("UPDATE inbox SET lease_until=unixepoch()-1").run();
    assert.equal((await ack(old)).status, 409);
    const next = await (await claim()).json();
    assert.notEqual(old.lease_token, next.lease_token);
    assert.equal(next.attempts, 2);
    assert.equal((await ack(old)).status, 409);
    assert.equal((await ack(next)).status, 204);
    const row = await db.prepare('SELECT * FROM inbox').first();
    assert.equal(row.body, null); assert.equal(row.signature, null); assert.equal(row.state, 'done');
    assert.equal((await post('/tebex', body, { 'x-signature': signature })).status, 200);
    assert.equal((await claim()).status, 204);
    await db.prepare('DROP TABLE inbox').run();
    assert.equal((await post('/tebex', body, { 'x-signature': signature })).status, 503);
  } finally { await mf.dispose(); }
});
