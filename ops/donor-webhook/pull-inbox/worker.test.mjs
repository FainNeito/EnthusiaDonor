import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createWorker, boundedBody } from './worker.mjs';
const worker = createWorker(Object.fromEntries(['enqueue', 'claim', 'ack'].map(name => [name, readFileSync(new URL(name + '.sql', import.meta.url), 'utf8')])));

const secret = 'synthetic-tebex-secret-at-least-32-bytes';
const token = 'synthetic-consumer-secret-at-least-32-bytes';
const env = { TEBEX_SECRET: secret, CONSUMER_SECRET: token, SOURCE: 'fixture' };
const payment = JSON.stringify({ type: 'payment.completed', subject: { transaction_id: 'tbx-fixture-1' } });
const signature = body => createHmac('sha256', secret).update(createHash('sha256').update(body).digest('hex')).digest('hex');
function request(body = payment, path = '/tebex', headers = {}) {
  return new Request('https://inbox.example' + path, { method: 'POST', body,
    headers: { 'x-signature': signature(body), ...headers } });
}
function database(run) { return { prepare() { return { bind(...args) { return { run: () => run(args), first: () => run(args) }; } }; } }; }

test('reject unsigned before reading or touching storage', async () => {
  const r = request(); r.headers.delete('x-signature');
  assert.equal((await worker.fetch(r, env)).status, 403);
  assert.equal(r.bodyUsed, false);
});
test('exact path, method and no query', async () => {
  for (const path of ['/tebex?x=1', '/tebex/', '/unknown'])
    assert.equal((await worker.fetch(request(payment, path), env)).status, 404);
  assert.equal((await worker.fetch(new Request('https://inbox.example/tebex'), env)).status, 405);
});
test('verify exact raw bytes, then signed validation without persistence', async () => {
  const body = '{"type":"validation.webhook","id":"fixture-123"}';
  const r = await worker.fetch(request(body), env);
  assert.equal(r.status, 200); assert.deepEqual(await r.json(), { id: 'fixture-123' });
  assert.equal((await worker.fetch(request(body + ' ', '/tebex', { 'x-signature': signature(body) }), env)).status, 403);
});
test('do not acknowledge before durable insert resolves; retain exact bytes', { timeout: 1000 }, async () => {
  let commit; let called; let settled = false;
  const committed = new Promise(resolve => { commit = resolve; });
  const entered = new Promise(resolve => { called = resolve; });
  const DB = database(args => { assert.equal(args[0], 'fixture'); assert.deepEqual(new Uint8Array(args[2]), new TextEncoder().encode(payment)); called(); return committed; });
  const pending = worker.fetch(request(), { ...env, DB }).then(r => { settled = true; return r; });
  await entered; await new Promise(resolve => setImmediate(resolve)); assert.equal(settled, false);
  commit({ success: true }); assert.equal((await pending).status, 200);
});
test('storage errors and unsuccessful results never acknowledge payment', async () => {
  for (const run of [() => { throw Error('private failure'); }, () => ({ success: false })]) {
    const r = await worker.fetch(request(), { ...env, DB: database(run) });
    assert.equal(r.status, 503); assert.equal((await r.text()).includes('private'), false);
  }
});
test('reject oversized, malformed and unsupported signed events', async () => {
  for (const [body, status] of [['x'.repeat(262145), 413], ['{', 400], ['{"type":"unknown"}', 400],
    ['{"type":"payment.completed","subject":{"transaction_id":" "}}', 400]])
    assert.equal((await worker.fetch(request(body), env)).status, status);
});
test('consumer requires separate bearer secret, no credential crossover', async () => {
  for (const authorization of ['', 'Bearer ' + secret, 'Bearer wrong'])
    assert.equal((await worker.fetch(request('{}', '/consumer/claim', { authorization }), env)).status, 403);
  const r = await worker.fetch(request('{}', '/consumer/claim', { authorization: 'Bearer ' + token }), { ...env, DB: database(() => null) });
  assert.equal(r.status, 204);
});
test('expired or wrong lease ACK is conflict, not success', async () => {
  const body = JSON.stringify({ id: 'a'.repeat(64), lease_token: 'b'.repeat(64) });
  const r = await worker.fetch(request(body, '/consumer/ack', { authorization: 'Bearer ' + token }), { ...env, DB: database(() => null) });
  assert.equal(r.status, 409);
});
test('total read deadline cancels stalled and trickling uploads', async () => {
  for (const trickle of [false, true]) {
    let cancelled = false; let interval;
    const body = new ReadableStream({ start(c) { if (trickle) interval = setInterval(() => c.enqueue(new Uint8Array([1])), 5); },
      cancel() { cancelled = true; clearInterval(interval); } });
    const r = new Request('https://inbox.example', { method: 'POST', body, duplex: 'half' });
    await assert.rejects(boundedBody(r, 1000, 40), error => error.status === 408);
    assert.equal(cancelled, true);
  }
});
test('renewals use the official transaction reference; bad configuration fails closed', async () => {
  const body = JSON.stringify({ type: 'recurring-payment.renewed', subject: { last_payment: { transaction_id: 'tbx-renewal' } } });
  assert.equal((await worker.fetch(request(body), { ...env, DB: database(() => ({ success: true })) })).status, 200);
  assert.equal((await worker.fetch(request(body.replace('tbx-renewal', 'invalid')), env)).status, 400);
  assert.equal((await worker.fetch(request(), { ...env, CONSUMER_SECRET: secret })).status, 503);
});
