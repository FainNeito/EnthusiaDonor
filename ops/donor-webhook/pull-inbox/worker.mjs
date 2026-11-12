const encoder = new TextEncoder();
const hex = bytes => Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('');
const digest = bytes => crypto.subtle.digest('SHA-256', bytes);
const response = (status, body = null) => new Response(body === null ? null : JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' }
});
class Rejection extends Error { constructor(status) { super('Request rejected'); this.status = status; } }

// Total elapsed read deadline, not a per-chunk idle timeout. Do not await an
// untrusted stream's cancellation hook. Never echo or log payment data.
export async function boundedBody(request, limit, timeout = 5000) {
  const length = request.headers.get('content-length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > limit)) throw new Rejection(413);
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader(); let timer;
  const expired = new Promise((_, reject) => { timer = setTimeout(() => reject(new Rejection(408)), timeout); });
  const readAll = async () => {
    const result = new Uint8Array(limit); let size = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value.byteLength > limit - size) throw new Rejection(413);
      result.set(value, size); size += value.byteLength;
    }
    return result.slice(0, size);
  };
  try {
    // Race once for the whole upload, avoiding one retained timer reaction per
    // tiny chunk and keeping application buffering bounded by the byte limit.
    return await Promise.race([readAll(), expired]);
  } catch (error) { void reader.cancel().catch(() => {}); throw error; }
  finally { clearTimeout(timer); reader.releaseLock(); }
}
function json(bytes) {
  try { const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (!value || Array.isArray(value) || typeof value !== 'object') throw Error(); return value;
  } catch { throw new Rejection(400); }
}
async function authorized(request, secret) {
  const value = request.headers.get('authorization') || '';
  if (value.length > 512) return false;
  const actual = new Uint8Array(await digest(encoder.encode(value)));
  const expected = new Uint8Array(await digest(encoder.encode('Bearer ' + secret)));
  let difference = 0;
  for (let i = 0; i < actual.length; i++) difference |= actual[i] ^ expected[i];
  return difference === 0;
}
function base64(body) {
  const bytes = new Uint8Array(body); let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 8192)
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  return btoa(binary);
}

// The entry module supplies the same SQL used by SQLite and workerd tests.
// No deployment configuration or live resource identifiers are included.
export function createWorker(sql) {
  return { async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.protocol !== 'https:') return response(400);
      if (url.search || !['/tebex', '/consumer/claim', '/consumer/ack'].includes(url.pathname)) return response(404);
      if (request.method !== 'POST') return response(405);
      if (typeof env.SOURCE !== 'string' || !/^[a-z0-9_.-]{1,64}$/.test(env.SOURCE)
          || ![env.TEBEX_SECRET, env.CONSUMER_SECRET].every(s => typeof s === 'string' && encoder.encode(s).length >= 32 && s.length <= 256)
          || env.TEBEX_SECRET === env.CONSUMER_SECRET) return response(503);
      if (request.headers.has('content-encoding')) return response(415);
      if (url.pathname === '/tebex') {
        const signature = request.headers.get('x-signature') || '';
        if (!/^[a-fA-F0-9]{64}$/.test(signature)) return response(403);
        const body = await boundedBody(request, 262144);
        const id = hex(await digest(body));
        const key = await crypto.subtle.importKey('raw', encoder.encode(env.TEBEX_SECRET), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
        const signed = Uint8Array.from(signature.match(/../g), s => parseInt(s, 16));
        if (!await crypto.subtle.verify('HMAC', key, signed, encoder.encode(id))) return response(403);
        const event = json(body);
        if (event.type === 'validation.webhook') {
          return typeof event.id === 'string' && /^[A-Za-z0-9-]{1,128}$/.test(event.id) ? response(200, { id: event.id }) : response(400);
        }
        const subject = event.type === 'payment.completed' ? event.subject
          : event.type === 'recurring-payment.renewed' ? event.subject?.last_payment : null;
        if (typeof subject?.transaction_id !== 'string' || !/^tbx-[A-Za-z0-9-]{1,128}$/.test(subject.transaction_id)) return response(400);
        // Persistence must finish before success, including duplicate delivery.
        const result = await env.DB.prepare(sql.enqueue).bind(env.SOURCE, id, body.buffer, signature).run();
        if (!result.success) return response(503);
        return response(200, {});
      }
      if (!await authorized(request, env.CONSUMER_SECRET)) return response(403);
      const input = json(await boundedBody(request, 1024));
      if (url.pathname === '/consumer/claim') {
        if (Object.keys(input).length) return response(400);
        const lease = hex(crypto.getRandomValues(new Uint8Array(32)));
        const row = await env.DB.prepare(sql.claim).bind(env.SOURCE, lease, 90).first();
        return row ? response(200, { id: row.id, body: base64(row.body), signature: row.signature,
          lease_token: row.lease_token, lease_until: row.lease_until, attempts: row.attempts }) : response(204);
      }
      if (Object.keys(input).sort().join(',') !== 'id,lease_token'
          || ![input.id, input.lease_token].every(s => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s))) return response(400);
      const row = await env.DB.prepare(sql.ack).bind(env.SOURCE, input.id, input.lease_token).first();
      return response(row ? 204 : 409);
    } catch (error) { return response(error instanceof Rejection ? error.status : 503); }
  } };
}
