import https from 'node:https';
import { pathToFileURL } from 'node:url';

const unknown = reason => ({ code: 3, state: 'UNKNOWN', reasons: [reason] });
const fields = ['rows', 'payload_bytes', 'pending', 'quarantined', 'completed', 'exhausted',
  'oldest_unprocessed_seconds', 'max_rows', 'max_payload_bytes', 'observed_at'];

/** Pure classifier. Never return arbitrary response fields or exception messages. */
export function evaluate(value, now = Math.floor(Date.now() / 1000)) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).sort().join(',') !== [...fields, 'attention'].sort().join(',')
      || fields.some(key => !Number.isSafeInteger(value[key]) || value[key] < 0)
      || typeof value.attention !== 'boolean' || !Number.isSafeInteger(now)) return unknown('invalid_status');
  const v = value;
  if (v.max_rows < 1 || v.max_rows > 10000 || v.max_payload_bytes < 262144 || v.max_payload_bytes > 67108864
      || v.pending + v.quarantined + v.completed > v.rows || v.exhausted > v.pending) return unknown('invalid_status');
  if (now - v.observed_at > 120 || v.observed_at - now > 30) return unknown('stale_or_skewed_status');
  const pressure = Math.max(v.rows / v.max_rows, v.payload_bytes / v.max_payload_bytes);
  const attention = pressure >= 0.8 || v.quarantined > 0 || v.exhausted > 0 || v.oldest_unprocessed_seconds >= 3600;
  if (attention !== v.attention) return unknown('inconsistent_status');
  const reasons = [];
  if (pressure >= 0.95) reasons.push('capacity_critical');
  else if (pressure >= 0.8) reasons.push('capacity_warning');
  if (v.quarantined) reasons.push('quarantine');
  if (v.exhausted) reasons.push('exhausted_attempts');
  if (v.oldest_unprocessed_seconds >= 86400) reasons.push('backlog_critical');
  else if (v.oldest_unprocessed_seconds >= 3600) reasons.push('backlog_warning');
  const critical = pressure >= 0.95 || v.quarantined > 0 || v.exhausted > 0 || v.oldest_unprocessed_seconds >= 86400;
  const code = critical ? 2 : attention ? 1 : 0;
  return { code, state: ['OK', 'WARNING', 'CRITICAL'][code], reasons: reasons.length ? reasons : ['inbox_clear'],
    metrics: Object.fromEntries(fields.map(key => [key, v[key]])) };
}

function originUrl(origin) {
  const url = new URL(origin);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw Error();
  return new URL('/monitor/status', url);
}

// ca/deadline are test seams, never CLI settings. Production always uses system
// trust, hostname verification, a fixed origin, no redirect following and 5s total.
export async function probe(origin, token, { ca, deadline = 5000 } = {}) {
  try {
    const url = originUrl(origin);
    if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{32,256}$/.test(token)) return unknown('invalid_configuration');
    const body = await new Promise((resolve, reject) => {
      let settled = false; let timer;
      const finish = (error, value) => {
        if (settled) return; settled = true; clearTimeout(timer);
        if (error) reject(Error('Probe failed')); else resolve(value);
      };
      const req = https.request(url, { method: 'POST', agent: false, rejectUnauthorized: true, ca,
        headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json', 'content-length': '2' } }, res => {
        if (res.statusCode !== 200 || !/^application\/json(?:;|$)/i.test(res.headers['content-type'] || '')
            || res.headers['content-encoding'] || Number(res.headers['content-length'] || 0) > 8192) {
          finish(true); res.destroy(); return;
        }
        const chunks = []; let size = 0;
        res.on('data', chunk => {
          size += chunk.length;
          if (size > 8192) { finish(true); req.destroy(); return; }
          chunks.push(chunk);
        });
        res.on('error', () => finish(true));
        res.on('end', () => finish(false, Buffer.concat(chunks).toString('utf8')));
      });
      req.on('error', () => finish(true));
      timer = setTimeout(() => { finish(true); req.destroy(); }, deadline);
      req.end('{}');
    });
    return evaluate(JSON.parse(body));
  } catch { return unknown('probe_failed'); }
}

// One-shot adapter only. No scheduler, provider integration, notifications or
// production endpoint are configured. Merely importing/running without --check
// never makes a network request. Secrets come from environment, never arguments.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = process.argv.length === 3 && process.argv[2] === '--check'
    ? await probe(process.env.INBOX_MONITOR_ORIGIN, process.env.INBOX_MONITOR_TOKEN)
    : unknown('inactive_requires_explicit_check');
  process.stdout.write(JSON.stringify(result) + '\n');
  process.exitCode = result.code;
}
