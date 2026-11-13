#!/usr/bin/env python3
"""Run with python3 tools/tests/test_qort.py; never contacts a Qortal node."""
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "qort"
BASH = shutil.which("bash")
CURL = shutil.which("curl")


class QortTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qort-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.script = self.root / "install" / "tools" / "qort"
        self.script.parent.mkdir(parents=True)
        shutil.copy2(SCRIPT, self.script)
        self.cwd = self.root / "work"
        self.cwd.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.home = self.root / "home"
        self.home.mkdir()
        self.capture = self.root / "arguments.json"
        self.env = dict(os.environ, PATH=str(self.bin), HOME=str(self.home),
                        QORT_CAPTURE=str(self.capture), QORT_RESPONSE='{"ok":true}')
        for variable in ("BASE_URL", "BASH_ENV", "ENV", "method"):
            self.env.pop(variable, None)
        for tool in ("cat", "dirname", "tr"):
            (self.bin / tool).symlink_to(shutil.which(tool))
        self.write_executable("curl", '''
import json, os, sys
if sys.argv[1:] == ['--help', 'all']:
    print('--fail-with-body' if not os.environ.get('QORT_OLD_CURL') else '--fail')
    sys.exit(0)
with open(os.environ['QORT_CAPTURE'], 'w') as output:
    json.dump(sys.argv[1:], output)
sys.stdout.write(os.environ.get('QORT_RESPONSE', ''))
sys.exit(int(os.environ.get('QORT_CURL_STATUS', '0')))
''')

    def write_executable(self, name, source):
        path = self.bin / name
        path.write_text(f"#!{sys.executable}\n" + source)
        path.chmod(0o755)

    def formatter(self, name):
        self.write_executable(name, '''
import json, sys
try:
    print(json.dumps(json.load(sys.stdin), indent=2))
except ValueError:
    sys.exit(4)
''')

    def run_qort(self, *args, input_data=None, **changes):
        env = dict(self.env, **changes)
        return subprocess.run([BASH, str(self.script), *args], cwd=self.cwd,
                              env=env, text=True, input=input_data,
                              capture_output=True, timeout=10)

    def arguments(self):
        return json.loads(self.capture.read_text())

    def value(self, option):
        args = self.arguments()
        return args[args.index(option) + 1]

    def test_default_request_has_no_empty_arguments_or_empty_auth_header(self):
        result = self.run_qort("blocks/last")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("", self.arguments())
        self.assertNotIn("--header", self.arguments())
        self.assertEqual(self.value("--url"), "http://localhost:12391/blocks/last")
        self.assertIn("--show-error", self.arguments())
        self.assertIn("--globoff", self.arguments())
        self.assertIn("--fail-with-body", self.arguments())
        self.assertNotIn("--insecure", self.arguments())

    def test_url_composition(self):
        for base, expected in (
            ("", "http://localhost:12391/blocks/last"),
            ("node.example", "http://node.example:12391/blocks/last"),
            ("http://node.example", "http://node.example:12391/blocks/last"),
            ("node.example:8080", "http://node.example:8080/blocks/last"),
            ("https://node.example:443/api///", "https://node.example:443/api/blocks/last"),
            ("http://node.example/api", "http://node.example:12391/api/blocks/last"),
            ("[::1]", "http://[::1]:12391/blocks/last"),
            ("http://[::1]:8080/api/", "http://[::1]:8080/api/blocks/last"),
        ):
            with self.subTest(base=base):
                result = self.run_qort("/blocks/last", BASE_URL=base)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.value("--url"), expected)

    def test_testnet_and_absolute_urls(self):
        self.assertEqual(self.run_qort("-t", "blocks/last").returncode, 0)
        self.assertEqual(self.value("--url"), "http://localhost:62391/blocks/last")
        url = "https://node.example/api?values=[1,2]&name=a%20b"
        self.assertEqual(self.run_qort("-t", url, BASE_URL="ignored").returncode, 0)
        self.assertEqual(self.value("--url"), url)

    def test_request_methods_and_quoted_bodies(self):
        body = '{"address": "a b", "value": "*", "lines": "a\\nb"}'
        for method in ("GET", "POST", "put", "Patch", "delete"):
            with self.subTest(method=method):
                result = self.run_qort("-j", "-l", "-k", method, "peers", body)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.value("--request"), method.upper())
                self.assertEqual(self.value("--data-binary"), body)
                self.assertEqual(self.value("--header"), "Content-Type: application/json")
                self.assertEqual(self.value("--interface"), "localhost")
                self.assertIn("--insecure", self.arguments())
        self.assertEqual(self.run_qort("peers", "").returncode, 0)
        self.assertEqual(self.value("--data-binary"), "")
        self.assertEqual(self.run_qort("peers", "--insecure").returncode, 0)
        self.assertEqual(self.value("--data-binary"), "--insecure")
        self.assertNotIn("--insecure", self.arguments()[:-1])
        self.assertEqual(self.run_qort("DELETE", "peers", method="inherited").returncode, 0)

    def test_key_lookup_order_and_crlf(self):
        current = self.cwd / "apikey.txt"
        installed = self.script.parent.parent / "apikey.txt"
        home_key = self.home / "qortal" / "apikey.txt"
        home_key.parent.mkdir()
        for path, key in ((current, "cwd-key"), (installed, "installed-key"), (home_key, "home-key")):
            path.write_bytes((key + "\r\n").encode())
        for path, key in ((current, "cwd-key"), (installed, "installed-key"), (home_key, "home-key")):
            self.assertEqual(self.run_qort("blocks/last").returncode, 0)
            self.assertEqual(self.value("--header"), "X-API-KEY: " + key)
            path.unlink()
        current.write_text("")
        self.assertEqual(self.run_qort("blocks/last").returncode, 0)
        self.assertNotIn("--header", self.arguments())
        current.write_text("first\nsecond\n")
        self.assertEqual(self.run_qort("blocks/last").returncode, 2)

    def test_request_failure_status_survives_formatting_and_newline(self):
        self.assertEqual(self.run_qort("blocks/last", QORT_CURL_STATUS="7").returncode, 7)
        self.formatter("jq")
        result = self.run_qort("-p", "blocks/last", QORT_CURL_STATUS="22", QORT_RESPONSE="HTTP error")
        self.assertEqual(result.returncode, 22)
        result = self.run_qort("-p", "blocks/last", QORT_RESPONSE="invalid JSON")
        self.assertEqual(result.returncode, 4)

    def test_pretty_print_dependency_and_fallback(self):
        missing = self.run_qort("-p", "blocks/last")
        self.assertEqual(missing.returncode, 2)
        self.assertIn("jq or json_pp", missing.stderr)
        self.formatter("json_pp")
        fallback = self.run_qort("-p", "blocks/last")
        self.assertEqual(fallback.returncode, 0, fallback.stderr)
        self.assertEqual(json.loads(fallback.stdout), {"ok": True})
        self.formatter("jq")
        (self.bin / "json_pp").write_text("#!/bin/sh\nexit 99\n")
        self.assertEqual(self.run_qort("-p", "blocks/last").returncode, 0)

    def test_older_curl_fallback(self):
        self.assertEqual(self.run_qort("blocks/last", QORT_OLD_CURL="1").returncode, 0)
        self.assertIn("--fail", self.arguments())
        self.assertNotIn("--fail-with-body", self.arguments())

    def test_help_and_invalid_input(self):
        self.assertEqual(self.run_qort("--help").returncode, 0)
        for args in ((), ("",), ("-j",), ("--unknown",), ("peers", "one", "two"),
                     ("POST", "DELETE", "peers"), ("ftp://node.example",)):
            with self.subTest(args=args):
                self.assertEqual(self.run_qort(*args).returncode, 2)
        for base in ("http:///api", "ftp://node.example", "::1", "node.example:bad",
                     "http://node.example?foo=bar", "http://[::1]:bad"):
            with self.subTest(base=base):
                self.assertEqual(self.run_qort("blocks/last", BASE_URL=base).returncode, 2)
        self.assertEqual(self.run_qort("--", "-endpoint").returncode, 0)
        (self.bin / "curl").unlink()
        result = self.run_qort("blocks/last")
        self.assertEqual(result.returncode, 2)
        self.assertIn("curl is required", result.stderr)

    @unittest.skipUnless(CURL, "curl is required for local HTTP integration")
    def test_real_curl_against_isolated_http_server(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self):
                size = int(self.headers.get("Content-Length", "0"))
                data = self.rfile.read(size).decode()
                body = json.dumps({"method": self.command, "path": self.path, "data": data,
                                   "key": self.headers.get("X-API-KEY"),
                                   "type": self.headers.get("Content-Type")}).encode()
                self.send_response(500 if self.path == "/error" else 200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = respond

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        (self.bin / "curl").unlink()
        (self.bin / "curl").symlink_to(CURL)
        self.env["BASE_URL"] = f"http://127.0.0.1:{server.server_port}"
        self.env["NO_PROXY"] = self.env["no_proxy"] = "127.0.0.1"
        self.env["CURL_HOME"] = str(self.home)
        (self.cwd / "apikey.txt").write_text("local-test-key\n")
        result = self.run_qort("blocks/last?values=[1,2]")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["path"], "/blocks/last?values=[1,2]")
        body_file = self.cwd / "body with spaces.json"
        body_file.write_text('{\n  "value": "test"\n}\n')
        result = self.run_qort("-j", "put", "peers", "@" + str(body_file))
        self.assertEqual(result.returncode, 0, result.stderr)
        response = json.loads(result.stdout)
        self.assertEqual(response["method"], "PUT")
        self.assertEqual(response["data"], body_file.read_text())
        self.assertEqual(response["key"], "local-test-key")
        self.assertEqual(response["type"], "application/json")
        result = self.run_qort("-j", "peers", "@-", input_data="{\n\"stdin\":true\n}\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["data"], "{\n\"stdin\":true\n}\n")
        result = self.run_qort("error")
        self.assertEqual(result.returncode, 22)
        # Curl versions without --fail-with-body omit the HTTP error body.
        if result.stdout.strip():
            self.assertEqual(json.loads(result.stdout)["path"], "/error")
        server.shutdown()
        server.server_close()
        result = self.run_qort("blocks/last")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("curl:", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
