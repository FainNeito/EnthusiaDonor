import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluate } from './monitor.mjs';
const now = 1805000000;
const healthy = () => ({ rows: 0, payload_bytes: 0, pending: 0, quarantined: 0, completed: 0,
  exhausted: 0, oldest_unprocessed_seconds: 0, max_rows: 10000, max_payload_bytes: 67108864,
  attention: false, observed_at: now });
test('monitor distinguishes healthy, warning and critical inbox conditions', () => {
  assert.equal(evaluate(healthy(), now).code, 0);
  assert.equal(evaluate({ ...healthy(), rows: 8000, completed: 8000, attention: true }, now).code, 1);
  assert.equal(evaluate({ ...healthy(), rows: 9500, completed: 9500, attention: true }, now).code, 2);
  assert.equal(evaluate({ ...healthy(), rows: 1, quarantined: 1, attention: true }, now).code, 2);
  assert.equal(evaluate({ ...healthy(), rows: 1, pending: 1, exhausted: 1, attention: true }, now).code, 2);
  assert.equal(evaluate({ ...healthy(), rows: 1, pending: 1, oldest_unprocessed_seconds: 3600, attention: true }, now).code, 1);
  assert.equal(evaluate({ ...healthy(), rows: 1, pending: 1, oldest_unprocessed_seconds: 86400, attention: true }, now).code, 2);
});
test('stale, future, malformed and inconsistent status never looks healthy or leaks input', () => {
  for (const value of [null, {}, { ...healthy(), observed_at: now - 121 }, { ...healthy(), observed_at: now + 31 },
    { ...healthy(), rows: -1 }, { ...healthy(), pending: 1 }, { ...healthy(), attention: 'false' },
    { ...healthy(), max_rows: 0 }, { ...healthy(), payload_bytes: Infinity },
    { ...healthy(), customer: 'SENSITIVE_FIXTURE' }, { ...healthy(), rows: 8000, completed: 8000 }]) {
    const result = evaluate(value, now);
    assert.equal(result.code, 3); assert.equal(JSON.stringify(result).includes('SENSITIVE_FIXTURE'), false);
  }
});
