"""Offline, loopback-only NGINX regression harness. Python stdlib only.

NGINX is supplied explicitly; each case owns a unique prefix, process and ports.
The spy proves transport behavior; optional real Java fixture proves signatures.
--negative-control disables buffering to demonstrate the buffering test detects it.
"""
import argparse
import concurrent.futures
import hashlib
import hmac
import http.client
import http.server
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import tempfile
import threading
import time
import unittest

HERE = Path(__file__).resolve().parent
OPTIONS = None
HOST = "donations.enthusia.info"
SIGNATURE = "0" * 64
SECRET = b"isolated-route-fixture-secret-not-production"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def launch(args, **kwargs):
    return subprocess.Popen(args, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                            **kwargs)


class Spy(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.server.received.put((self.path, dict(self.headers), body))
        self.server.release.wait(5)
        try:
            self.send_response(self.server.response_status)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")
        except (ConnectionError, OSError):
            pass # Intentional abort/negative-control cases can close first.

    def log_message(self, *args):
        pass


class Route(unittest.TestCase):
    def setUp(self):
        self.prefix = Path(tempfile.mkdtemp(prefix="route-", dir=OPTIONS.work)).resolve()
        for name in ("logs", "body-temp", "proxy-temp", "temp"):
            (self.prefix / name).mkdir()
        self.spy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Spy)
        self.spy.daemon_threads = True
        self.spy.received = queue.Queue()
        self.spy.response_status = 200
        self.spy.release = threading.Event()
        self.spy.release.set()
        self.spy_thread = threading.Thread(target=self.spy.serve_forever, daemon=True)
        self.spy_thread.start()
        self.addCleanup(self.stop_spy)
        upstream = OPTIONS.receiver_port if isinstance(self, RealReceiver) else self.spy.server_port
        self.port = free_port()
        config = (HERE / "nginx.conf.in").read_text(encoding="utf-8")
        config = config.replace("@LISTEN@", f"127.0.0.1:{self.port}")
        config = config.replace("@UPSTREAM@", f"127.0.0.1:{upstream}")
        if OPTIONS.negative_control:
            config = config.replace("proxy_request_buffering on;", "proxy_request_buffering off;")
        (self.prefix / "nginx.conf").write_text(config, encoding="utf-8")
        self.command = [str(OPTIONS.nginx), "-p", self.prefix.as_posix() + "/", "-c", "nginx.conf"]
        result = subprocess.run(self.command + ["-t"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.log = (self.prefix / "process.log").open("w")
        self.process = launch(self.command, cwd=self.prefix, stdout=self.log, stderr=self.log)
        self.addCleanup(self.stop_nginx)
        deadline = time.monotonic() + 5
        while True:
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=.1):
                    break
            except OSError:
                if time.monotonic() > deadline:
                    self.fail("NGINX did not open its local fixture port")
                time.sleep(.05)

    def stop_spy(self):
        self.spy.release.set()
        self.spy.shutdown()
        self.spy.server_close()
        self.spy_thread.join(2)

    def stop_nginx(self):
        subprocess.run(self.command + ["-s", "stop"], capture_output=True, timeout=5)
        self.process.wait(timeout=5)
        self.log.close()

    def request(self, body=b"{}", path="/tebex", method="POST", signature=SIGNATURE, host=HOST):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=6)
        headers = {"Host": host}
        if signature is not None:
            headers["X-Signature"] = signature
        try:
            conn.request(method, path, body, headers)
            response = conn.getresponse()
            return response.status, response.read()
        finally:
            conn.close()

    def raw(self, headers, body=b""):
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=6)
        sock.sendall(headers.encode("ascii") + body)
        return sock

    def header(self, extra="", path="/tebex"):
        return f"POST {path} HTTP/1.1\r\nHost: {HOST}\r\nX-Signature: {SIGNATURE}\r\n{extra}\r\n"

    def assert_closed_or_status(self, sock, status):
        try:
            result = sock.recv(4096)
            self.assertTrue(not result or f" {status} ".encode() in result, repr(result[:100]))
        except ConnectionResetError:
            pass


class Transport(Route):
    def test_complete_body_and_signature_forwarded_exactly(self):
        body = b'{ "synthetic": "caf\xc3\xa9", "space": true }\n'
        self.assertEqual(200, self.request(body)[0])
        path, headers, received = self.spy.received.get(timeout=1)
        self.assertEqual(("/tebex", body), (path, received))
        self.assertEqual(SIGNATURE, headers["X-Signature"])

    def test_fixed_body_buffered_before_upstream(self):
        with self.raw(self.header("Content-Length: 20\r\n"), b"{") as sock:
            # Observe TCP accepts, not just completed body reads in the spy.
            time.sleep(.2)
            self.assertFalse(self.upstream_connected(), "Incomplete body reached upstream")
            sock.sendall(b" " * 18 + b"}")
            self.assertIn(b" 200 ", sock.recv(4096))
            self.assertEqual(20, len(self.spy.received.get(timeout=1)[2]))

    def upstream_connected(self):
        # Spy workers begin upon accept, before reading the request body.
        return self.spy.accepted.is_set()

    def setUp(self):
        # Hook accept to distinguish partial upload forwarding from no forwarding.
        super().setUp()
        self.spy.accepted = threading.Event()
        original = self.spy.get_request
        def record_accept():
            result = original()
            self.spy.accepted.set()
            return result
        self.spy.get_request = record_accept

    def test_chunked_body_buffered_and_bytes_preserved(self):
        with self.raw(self.header("Transfer-Encoding: chunked\r\n"), b"1\r\n{\r\n") as sock:
            time.sleep(.2)
            self.assertFalse(self.upstream_connected())
            sock.sendall(b"1\r\n}\r\n0\r\n\r\n")
            self.assertIn(b" 200 ", sock.recv(4096))
        self.assertEqual(b"{}", self.spy.received.get(timeout=1)[2])

    def test_wrong_routes_methods_hosts_rejected(self):
        for path in ("/", "/tebex?x=1", "/tebex/", "/%74ebex", "//tebex", "/x/../tebex"):
            self.assertEqual(404, self.request(path=path)[0], path)
        self.assertEqual(405, self.request(method="GET")[0])
        self.assertEqual(404, self.request(host="other.invalid")[0])
        self.assertTrue(self.spy.received.empty())

    def test_missing_host_http10_rejected(self):
        # $host alone falls back to server_name when the Host header is absent.
        headers = f"POST /tebex HTTP/1.0\r\nX-Signature: {SIGNATURE}\r\nContent-Length: 2\r\n\r\n"
        with self.raw(headers, b"{}") as sock:
            self.assertIn(b" 404 ", sock.recv(4096))
        self.assertFalse(self.upstream_connected())

    def test_missing_malformed_duplicate_signature_rejected_before_body(self):
        self.assertEqual(403, self.request(signature=None)[0])
        self.assertEqual(403, self.request(signature="bad")[0])
        with self.raw(self.header(f"X-Signature: {SIGNATURE}\r\nContent-Length: 100\r\n")) as sock:
            self.assertIn(b" 403 ", sock.recv(4096))
        self.assertFalse(self.upstream_connected())

    def test_declared_oversize_rejected_before_body(self):
        with self.raw(self.header("Content-Length: 262145\r\n")) as sock:
            self.assertIn(b" 413 ", sock.recv(4096))
        self.assertFalse(self.upstream_connected())

    def test_chunked_oversize_rejected(self):
        with self.raw(self.header("Transfer-Encoding: chunked\r\n"), b"40001\r\nx") as sock:
            self.assertIn(b" 413 ", sock.recv(4096))
        self.assertFalse(self.upstream_connected())

    def test_exact_body_limit_accepted(self):
        self.assertEqual(200, self.request(b"x" * 262144)[0])
        self.assertEqual(262144, len(self.spy.received.get(timeout=1)[2]))

    def test_oversize_header_rejected(self):
        with self.raw(self.header("X-Padding: " + "x" * 5000 + "\r\nContent-Length: 0\r\n")) as sock:
            self.assertIn(b" 400 ", sock.recv(4096))
        self.assertFalse(self.upstream_connected())

    def test_ambiguous_framing_rejected(self):
        for framing in ("Content-Length: 1\r\nContent-Length: 2\r\n",
                        "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n"):
            with self.raw(self.header(framing)) as sock:
                self.assertIn(b" 400 ", sock.recv(4096))
        self.assertFalse(self.upstream_connected())

    def test_stalled_header_times_out_and_recovers(self):
        start = time.monotonic()
        with self.raw(f"POST /tebex HTTP/1.1\r\nHost: {HOST}\r\nX-Incomplete:") as sock:
            self.assert_closed_or_status(sock, 408)
        self.assertLess(time.monotonic() - start, 5)
        self.assertFalse(self.upstream_connected())
        self.assertEqual(200, self.request()[0])

    def test_stalled_body_times_out_and_recovers(self):
        start = time.monotonic()
        with self.raw(self.header("Content-Length: 100\r\n"), b"{") as sock:
            self.assert_closed_or_status(sock, 408)
        self.assertLess(time.monotonic() - start, 5)
        self.assertFalse(self.upstream_connected())
        self.assertEqual(200, self.request()[0])

    def test_abandoned_body_never_forwarded(self):
        with self.raw(self.header("Content-Length: 100\r\n"), b"{"):
            pass
        time.sleep(.2)
        self.assertFalse(self.upstream_connected())

    def test_connection_budget_rejects_ninth_incomplete_body(self):
        sockets = []
        try:
            for _ in range(8):
                sockets.append(self.raw(self.header("Content-Length: 100\r\n"), b"{"))
            time.sleep(.2)
            self.assertEqual(429, self.request()[0])
            self.assertFalse(self.upstream_connected())
        finally:
            for sock in sockets:
                sock.close()
        time.sleep(.2)
        self.assertEqual(200, self.request()[0])

    def test_only_two_upstream_requests_no_proxy_retries(self):
        self.spy.release.clear()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(self.request)
            second = pool.submit(self.request)
            self.spy.received.get(timeout=2)
            self.spy.received.get(timeout=2)
            self.assertEqual(503, self.request()[0])
            self.assertTrue(self.spy.received.empty())
            self.spy.release.set()
            self.assertEqual(200, first.result()[0])
            self.assertEqual(200, second.result()[0])

    def test_burst_rate_limited(self):
        statuses = [self.request()[0] for _ in range(24)]
        self.assertIn(200, statuses)
        self.assertIn(429, statuses)

    def test_upstream_failure_returned_without_retry(self):
        self.spy.response_status = 503
        self.assertEqual(503, self.request()[0])
        self.spy.received.get(timeout=1)
        self.assertTrue(self.spy.received.empty())

    def test_sensitive_headers_not_forwarded_or_logged(self):
        with self.raw(self.header("Authorization: Bearer SYNTHETIC-PRIVATE\r\nCookie: private=SYNTHETIC-PRIVATE\r\nX-Forwarded-For: 1.2.3.4\r\nContent-Length: 2\r\n"), b"{}") as sock:
            self.assertIn(b" 200 ", sock.recv(4096))
        headers = {key.lower(): value for key, value in self.spy.received.get(timeout=1)[1].items()}
        for name in ("authorization", "cookie", "x-forwarded-for"):
            self.assertNotIn(name, headers)
        for log in (self.prefix / "logs").glob("*.log"):
            text = log.read_text()
            self.assertNotIn("SYNTHETIC-PRIVATE", text)
            self.assertNotIn(SIGNATURE, text)


class RealReceiver(Route):
    def sign(self, body):
        return hmac.new(SECRET, hashlib.sha256(body).hexdigest().encode("ascii"), hashlib.sha256).hexdigest()

    def test_signed_validation_round_trip(self):
        body = b'{ "id": "route-fixture-validation", "type": "validation.webhook" }\n'
        status, response = self.request(body, signature=self.sign(body))
        self.assertEqual(200, status)
        self.assertEqual({"id": "route-fixture-validation"}, json.loads(response))

    def test_changed_body_rejected(self):
        body = b'{"id":"route-fixture-validation","type":"validation.webhook"}'
        self.assertEqual(403, self.request(body + b" ", signature=self.sign(body))[0])

    def test_signed_malformed_json_rejected(self):
        body = b"{"
        self.assertEqual(400, self.request(body, signature=self.sign(body))[0])

    def test_signed_payment_fails_closed_without_bridge(self):
        body = json.dumps({"id": "fixture-only", "type": "payment.completed",
                           "subject": {"transaction_id": "fixture-transaction"}}).encode()
        self.assertEqual(503, self.request(body, signature=self.sign(body))[0])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--nginx", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--receiver-port", type=int)
    parser.add_argument("--negative-control", action="store_true")
    parser.add_argument("--case", help="Run one Transport test by method name")
    OPTIONS = parser.parse_args()
    OPTIONS.nginx = OPTIONS.nginx.resolve()
    OPTIONS.work.mkdir(parents=True, exist_ok=True)
    suite = unittest.TestSuite()
    if OPTIONS.case:
        suite.addTest(Transport(OPTIONS.case))
    elif OPTIONS.negative_control:
        suite.addTest(Transport("test_fixed_body_buffered_before_upstream"))
    else:
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(Transport))
        if OPTIONS.receiver_port:
            suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(RealReceiver))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
