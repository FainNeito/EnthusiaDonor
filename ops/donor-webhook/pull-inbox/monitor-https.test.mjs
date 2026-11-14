import { test } from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { probe } from './monitor.mjs';

test('one-shot monitor enforces TLS, hostname, no redirects, body cap and total read deadline', { timeout: 30000 }, async t => {
  const dir = mkdtempSync(join(tmpdir(), 'donor-monitor-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const javaHome = process.env.JAVA_HOME || (process.platform === 'win32' ? 'C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot' : null);
  const keytool = javaHome ? join(javaHome, 'bin', process.platform === 'win32' ? 'keytool.exe' : 'keytool') : 'keytool';
  const password = 'synthetic-fixture-password';
  const keys = join(dir, 'fixture.p12'), cert = join(dir, 'fixture.pem');
  const run = args => assert.equal(spawnSync(keytool, args, { timeout: 20000, encoding: 'utf8' }).status, 0, 'Local fixture certificate generation');
  run(['-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-storetype', 'PKCS12',
    '-keystore', keys, '-storepass', password, '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost', '-validity', '2', '-noprompt']);
  run(['-exportcert', '-rfc', '-alias', 'fixture', '-keystore', keys, '-storepass', password, '-file', cert]);
  const ca = readFileSync(cert);
  let mode = 'ok', traps = 0, authorized = false;
  const token = 'synthetic-monitor-secret-at-least-32-bytes';
  const server = https.createServer({ pfx: readFileSync(keys), passphrase: password }, (req, res) => {
    if (req.url === '/trap') { traps++; res.end(); return; }
    authorized = req.url === '/monitor/status' && req.method === 'POST' && req.headers.authorization === 'Bearer ' + token;
    req.resume(); res.setHeader('content-type', 'application/json');
    if (mode === 'redirect') { res.writeHead(307, { location: '/trap' }); res.end(); }
    else if (mode === 'large') { res.write(' '.repeat(8193)); res.end(); }
    else if (mode === 'stall') { res.write('{'); }
    else if (mode === 'secret-error') { res.statusCode = 503; res.end(token); }
    else res.end(JSON.stringify({ rows: 0, payload_bytes: 0, pending: 0, quarantined: 0, completed: 0,
      exhausted_present: 0, oldest_unprocessed_seconds: 0, max_rows: 10000, max_payload_bytes: 67108864,
      attention: false, observed_at: Math.floor(Date.now() / 1000) }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const origin = 'https://localhost:' + server.address().port;
  assert.equal((await probe(origin, token)).code, 3); // not system-trusted
  assert.equal((await probe(origin.replace('localhost', '127.0.0.1'), token, { ca })).code, 3);
  assert.equal((await probe(origin, token, { ca })).code, 0); assert.equal(authorized, true);
  for (const value of ['redirect', 'large', 'stall', 'secret-error']) {
    mode = value; const result = await probe(origin, token, { ca, deadline: 150 });
    assert.equal(result.code, 3); assert.equal(JSON.stringify(result).includes(token), false);
  }
  assert.equal(traps, 0);
  for (const invalid of ['http://localhost', origin + '/path', origin + '?token=private', origin.replace('https://', 'https://user:pass@')])
    assert.equal((await probe(invalid, token, { ca })).code, 3);
});

test('monitor executable is inactive without explicit check, with safe output', () => {
  const result = spawnSync(process.execPath, ['monitor.mjs'], { encoding: 'utf8',
    env: { ...process.env, INBOX_MONITOR_ORIGIN: 'https://unused.invalid', INBOX_MONITOR_TOKEN: 'PRIVATE_FIXTURE' } });
  assert.equal(result.status, 3); assert.equal(JSON.parse(result.stdout).reasons[0], 'inactive_requires_explicit_check');
  assert.equal(result.stdout.includes('PRIVATE_FIXTURE'), false); assert.equal(result.stderr, '');
});
