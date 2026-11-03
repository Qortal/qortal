#!/usr/bin/env python3
"""Offline publication-flow tests: fixture ZIPs, keys and curl; no node access."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LIBRARIES = (
    'librust-linux-x86_64.so', 'librust-linux-aarch64.so',
    'librust-macos-x86_64.dylib', 'librust-macos-aarch64.dylib',
    'librust-windows-x86_64.dll',
)


class ArrrPublicationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='arrr publication ')
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.tools = self.work / 'tools'
        self.tools.mkdir()
        self.bin = self.work / 'bin'
        self.bin.mkdir()
        self.build = self.work / 'stashi build'
        self.build.mkdir()
        self.archive = self.build / 'combined JNI.zip'
        with zipfile.ZipFile(self.archive, 'w') as archive:
            for name in (*LIBRARIES, 'LICENSE-qortal-jni.txt', 'qortal-handoff.md'):
                archive.writestr(name, f'Fixture {name}; never loaded or executed.')
        checksum = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        for name in ('prep-and-publish-arrr-wallet.sh',
                     'stage-pirate-unified-qdn-bundle.sh', 'publish-pirate-unified-qdn.sh'):
            # Trust this fixture archive only in isolated copies of the scripts.
            source = (ROOT / 'tools' / name).read_text()
            source = re.sub(r"(readonly EXPECTED_(?:ARTIFACT_)?SHA256=')[0-9a-f]{64}(')",
                            lambda match: match[1] + checksum + match[2], source)
            target = self.tools / name
            target.write_text(source)
            target.chmod(0o755)
        self.node = self.work / 'qortal'
        self.node.mkdir()
        (self.node / 'apikey.txt').write_bytes(b'fixture-local-api-key\r\n')
        self.env = dict(os.environ, HOME=str(self.work),
                        PATH=f'{self.bin}:{os.environ["PATH"]}',
                        QORTAL_API_URL='http://node.invalid:12391',
                        QDN_OUTPUT_DIR=str(self.work / 'saved publications'),
                        CURL_RECORD=str(self.work / 'calls.jsonl'))
        for key in ('BASH_ENV', 'ENV', 'QORTAL_API_KEY', 'QORTAL_API_KEY_FILE',
                    'QDN_FEE', 'FAIL_STEP', 'INVALID_STEP'):
            self.env.pop(key, None)
        fake_curl = self.bin / 'curl'
        fake_curl.write_text(f'#!{shutil.which("python3")}\n' + '''
import json, os, sys
args = sys.argv[1:]
url = args[-1]
assert url.startswith('http://node.invalid:12391/'), url
step = 'prepare' if '/arbitrary/' in url else 'sign' if url.endswith('/sign') else 'submit'
data = args[args.index('--data-binary') + 1]
if data == '@-':
    data = sys.stdin.read()
with open(os.environ['CURL_RECORD'], 'a') as record:
    record.write(json.dumps(dict(step=step, url=url, args=args, data=data)) + '\\n')
if os.environ.get('FAIL_STEP') == step:
    sys.exit(22)
if os.environ.get('INVALID_STEP') == step:
    print('not-base58-0')
elif step == 'prepare':
    print('3ABC')
elif step == 'sign':
    request = json.loads(data)
    assert request == dict(privateKey='5ABC', transactionBytes='3ABC'), request
    print('4ABC')
else:
    assert data == '4ABC', data
    print(json.dumps(dict(signature='6ABC')))
''')
        fake_curl.chmod(0o755)

    def run_helper(self, *args, answers=None):
        if answers is None:
            answers = '\n\n5ABC\n'
        return subprocess.run(
            [shutil.which('bash'), str(self.tools / 'prep-and-publish-arrr-wallet.sh'), *args],
            input=answers, text=True, capture_output=True, env=self.env,
            cwd=self.work, timeout=15)

    def calls(self):
        path = Path(self.env['CURL_RECORD'])
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def publication(self):
        return next(Path(self.env['QDN_OUTPUT_DIR']).iterdir())

    def test_prompted_zip_reads_local_key_and_runs_prepare_sign_submit(self):
        result = self.run_helper(answers=f'{self.archive}\nA name with spaces\nnew test id\n5ABC\n')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls()
        self.assertEqual([call['step'] for call in calls], ['prepare', 'sign', 'submit'])
        self.assertTrue(calls[0]['url'].endswith('/ARBITRARY_DATA/A%20name%20with%20spaces/new%20test%20id?fee=1000000'))
        self.assertEqual(calls[0]['data'], str(self.publication() / 'bundle'))
        for call in calls:
            self.assertIn('X-API-KEY: fixture-local-api-key', call['args'])
        bundle = self.publication() / 'bundle'
        self.assertEqual({file.name for file in bundle.iterdir()},
                         set(LIBRARIES) | {'MANIFEST.txt', 'LICENSE-qortal-jni.txt', 'qortal-handoff.md'})
        manifest = (bundle / 'MANIFEST.txt').read_text()
        self.assertIn('jni-version: 0.3.9', manifest)
        self.assertIn('upstream-release: v1.2.6', manifest)
        for name, value in [('unsigned.base58', '3ABC'), ('signed.base58', '4ABC'),
                            ('signature.txt', '6ABC')]:
            path = self.publication() / name
            self.assertEqual(path.read_text().strip(), value)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        for secret in ('fixture-local-api-key', '5ABC'):
            self.assertNotIn(secret, result.stdout + result.stderr)
            for path in self.publication().rglob('*'):
                if path.is_file():
                    self.assertNotIn(secret, path.read_text())

    def test_directory_api_override_and_fee(self):
        self.env.update(QORTAL_API_KEY='override-key', QDN_FEE='2000000')
        result = self.run_helper(str(self.build))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('X-API-KEY: override-key', self.calls()[0]['args'])
        self.assertTrue(self.calls()[0]['url'].endswith('?fee=2000000'))

    def test_tilde_path_and_manual_key_fallback(self):
        (self.node / 'apikey.txt').unlink()
        result = self.run_helper(answers='~/stashi build/combined JNI.zip\n\n\nmanual-key\n5ABC\n')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('X-API-KEY: manual-key', self.calls()[0]['args'])

    def test_ambiguous_zip_directory_never_calls_node(self):
        shutil.copyfile(self.archive, self.build / 'other.zip')
        result = self.run_helper(str(self.build))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('exactly one', result.stderr)
        self.assertEqual(self.calls(), [])

    def test_changed_zip_never_calls_node(self):
        with self.archive.open('ab') as archive:
            archive.write(b'changed')
        result = self.run_helper(str(self.archive))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('SHA-256 mismatch', result.stderr)
        self.assertEqual(self.calls(), [])

    def test_prepare_failure_never_signs_or_submits(self):
        self.env['FAIL_STEP'] = 'prepare'
        result = self.run_helper(str(self.archive))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['step'] for call in self.calls()], ['prepare'])
        self.assertFalse((self.publication() / 'unsigned.base58').exists())

    def test_sign_failure_preserves_unsigned_and_never_submits(self):
        self.env['FAIL_STEP'] = 'sign'
        result = self.run_helper(str(self.archive))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['step'] for call in self.calls()], ['prepare', 'sign'])
        self.assertTrue((self.publication() / 'unsigned.base58').exists())
        self.assertFalse((self.publication() / 'signed.base58').exists())

    def test_submit_failure_preserves_signed_without_claiming_success(self):
        self.env['FAIL_STEP'] = 'submit'
        result = self.run_helper(str(self.archive))
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.publication() / 'signed.base58').exists())
        self.assertFalse((self.publication() / 'signature.txt').exists())
        self.assertNotIn('Broadcast accepted', result.stdout)

    def test_invalid_unsigned_transaction_never_signs(self):
        self.env['INVALID_STEP'] = 'prepare'
        result = self.run_helper(str(self.archive))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['step'] for call in self.calls()], ['prepare'])

    def test_retries_keep_separate_publication_files(self):
        for _ in range(2):
            result = self.run_helper(str(self.archive))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        publications = list(Path(self.env['QDN_OUTPUT_DIR']).iterdir())
        self.assertEqual(len(publications), 2)
        for publication in publications:
            self.assertEqual((publication / 'signature.txt').read_text().strip(), '6ABC')


if __name__ == '__main__':
    unittest.main()
