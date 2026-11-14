import { test } from 'node:test';
import assert from 'node:assert/strict';
import { route } from './alert-routing.mjs';
const sample = code => ({ code });

test('opens one incident after persistent failure, deduplicates and escalates to backup', () => {
  let r = route(null, sample(2), 1000); assert.deepEqual(r.intents, []);
  r = route(r.state, sample(2), 1300); assert.equal(r.intents[0].type, 'OPEN');
  assert.deepEqual(r.intents[0].roles, ['primary']); const id = r.intents[0].incident;
  r = route(r.state, sample(2), 1600); assert.deepEqual(r.intents, []);
  r = route(JSON.parse(JSON.stringify(r.state)), sample(2), 2200);
  assert.equal(r.intents[0].type, 'ESCALATE'); assert.equal(r.intents[0].incident, id);
  assert.deepEqual(r.intents[0].roles, ['backup']);
  r = route(r.state, sample(2), 2500); assert.deepEqual(r.intents, []);
});
test('warning needs three failures; mixed failures cannot evade incident creation', () => {
  let r = route(null, sample(1), 1000);
  r = route(r.state, sample(1), 1300); assert.deepEqual(r.intents, []);
  r = route(r.state, sample(1), 1600); assert.equal(r.intents[0].type, 'OPEN');
  let mixed = route(null, sample(1), 1000);
  mixed = route(mixed.state, sample(3), 1300); assert.equal(mixed.intents[0].type, 'OPEN');
});
test('three healthy results recover the same incident and a later failure gets a new identity', () => {
  let r = route(null, sample(2), 1000); r = route(r.state, sample(2), 1300);
  const first = r.intents[0].incident;
  r = route(r.state, sample(0), 1600); assert.deepEqual(r.intents, []);
  r = route(r.state, sample(0), 1900); assert.deepEqual(r.intents, []);
  r = route(r.state, sample(0), 2200); assert.equal(r.intents[0].type, 'RECOVERED');
  assert.equal(r.intents[0].incident, first);
  r = route(r.state, sample(2), 2500); r = route(r.state, sample(2), 2800);
  assert.notEqual(r.intents[0].incident, first);
});
test('independent supervisor ticks detect missing results without repeating a probe', () => {
  let r = route(null, sample(0), 1000);
  r = route(r.state, null, 1899); assert.deepEqual(r.intents, []);
  r = route(r.state, null, 1900); assert.equal(r.intents[0].type, 'OPEN');
  assert.equal(r.intents[0].reason, 'MISSING_RESULTS');
});
test('reminders are bounded, inputs are sanitized and backward time is rejected', () => {
  let r = route(null, { code: 2, body: 'PRIVATE_FIXTURE' }, 1000);
  r = route(r.state, sample(2), 1300);
  assert.equal(JSON.stringify(r).includes('PRIVATE_FIXTURE'), false);
  assert.ok(r.intents.every(i => i.dry_run === true));
  assert.throws(() => route(r.state, sample(2), 1200));
  r = route(r.state, sample(2), 2200); // backup escalation
  r = route(r.state, sample(2), 5799); assert.deepEqual(r.intents, []);
  r = route(r.state, sample(2), 5800); assert.equal(r.intents[0].type, 'REMINDER');
});
test('replayed and delayed samples do not inflate streaks or conceal missing checks', () => {
  let r = route(null, { code: 2, checked_at: 1000 }, 1000);
  r = route(r.state, { code: 2, checked_at: 1000 }, 1100); assert.deepEqual(r.intents, []);
  assert.equal(r.state.bad_streak, 1);
  r = route(r.state, { code: 0, checked_at: 1100 }, 1300);
  assert.equal(r.state.last_result_at, 1000);
  r = route(r.state, null, 1900); assert.equal(r.intents[0].reason, 'MISSING_RESULTS');
});
test('corrupt, cross-inbox or future state cannot silently restart incident tracking', () => {
  const r = route(null, sample(0), 1000);
  assert.throws(() => route({ ...r.state, private: 'PRIVATE_FIXTURE' }, null, 1300));
  assert.throws(() => route(r.state, null, 1300, 'different-inbox'));
  assert.throws(() => route(r.state, { code: 0, checked_at: 1400 }, 1300));
  assert.throws(() => route({ ...r.state, incident: { id: 'invented' } }, null, 1300));
});
test('worsening a warning keeps its incident; recovery reaches both responders after escalation', () => {
  let r = route(null, sample(1), 1000); r = route(r.state, sample(1), 1300); r = route(r.state, sample(1), 1600);
  const id = r.intents[0].incident;
  r = route(r.state, sample(2), 1900); assert.equal(r.intents[0].type, 'WORSENED'); assert.equal(r.intents[0].incident, id);
  r = route(r.state, sample(2), 2500); assert.equal(r.intents[0].type, 'ESCALATE');
  r = route(r.state, sample(0), 2800); r = route(r.state, sample(0), 3100); r = route(r.state, sample(0), 3400);
  assert.equal(r.intents[0].type, 'RECOVERED'); assert.deepEqual(r.intents[0].roles, ['primary', 'backup']);
});
