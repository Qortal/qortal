#!/usr/bin/env python3
"""Offline script regressions. All files, Git refs and API responses are fixtures."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASH = shutil.which('bash')
PYTHON = shutil.which('python3')


class ScriptTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='qortal scripts ')
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.bin = self.work / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}',
                        HOME=str(self.work), TMPDIR=str(self.work))
        for key in ('BASH_ENV', 'ENV', 'BASE_URL', 'QORTAL_STOP_TIMEOUT',
                    'GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE'):
            self.env.pop(key, None)

    def fake(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)

    def run_script(self, name, *args, input=None, cwd=None, env=None):
        return subprocess.run([BASH, str(ROOT / name), *args], input=input,
                              cwd=cwd or self.work, env=env or self.env,
                              text=True, capture_output=True, timeout=15)

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.work,
                                       env=self.env, text=True).strip()

    def init_repo(self):
        self.git('init', '-q', '-b', 'master')
        self.git('config', 'user.name', 'Script tests')
        self.git('config', 'user.email', 'scripts@example.invalid')
        self.git('remote', 'add', 'origin', 'https://github.com/example/qortal')
        (self.work / 'pom.xml').write_text(
            '<project><artifactId>qortal</artifactId>\n<version>6.1.0</version></project>')
        self.git('add', 'pom.xml')
        self.git('commit', '-qm', 'Fixture source')

    def fake_api(self, status=0):
        self.env['CURL_RECORD'] = str(self.work / 'curl.json')
        (self.bin / 'curl').write_text(
            f'#!{PYTHON}\nimport json, os, sys\n'
            'open(os.environ["CURL_RECORD"], "w").write(json.dumps(sys.argv[1:]))\n'
            f'sys.exit({status})\n')
        (self.bin / 'curl').chmod(0o755)

    def test_stop_rejects_invalid_or_unrelated_pid_without_api(self):
        self.fake_api()
        (self.work / 'apikey.txt').write_text('fixture-key')
        for pid in ('0', '1', '-1', '2 3', 'not-a-pid', str(os.getpid())):
            with self.subTest(pid=pid):
                (self.work / 'run.pid').write_text(pid)
                result = self.run_script('stop.sh')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.work / 'curl.json').exists())
        # Even a Java-like command line must not identify another Linux checkout.
        if Path('/proc/self/cwd').exists():
            self.fake('ps', 'case "$*" in *stat=*) echo S;; *args=*) echo "java -jar qortal.jar";; esac')
            self.assertNotEqual(self.run_script('stop.sh').returncode, 0)
            self.assertFalse((self.work / 'curl.json').exists())

    def test_stop_header_timeout_ports_and_http_failure(self):
        self.fake_api()
        (self.work / 'apikey.txt').write_bytes(b'fixture-key\r\n')
        for script, args, port in [('stop.sh', [], 12391),
                                   ('testnet/stop.sh', [], 62391),
                                   ('stop.sh', ['-t'], 62391),
                                   ('stop.sh', ['--port', '12400'], 12400)]:
            result = self.run_script(script, *args)
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = json.loads((self.work / 'curl.json').read_text())
            self.assertIn('--fail', calls)
            self.assertIn('--max-time', calls)
            self.assertIn('X-API-KEY: fixture-key', calls)
            self.assertIn(f'http://127.0.0.1:{port}/admin/stop', calls)
            self.assertNotIn('apiKey=', ' '.join(calls))
        self.fake_api(22)
        self.assertNotEqual(self.run_script('stop.sh').returncode, 0)

    def test_stop_wait_is_bounded_and_preserves_pid(self):
        self.fake_api()
        (self.work / 'apikey.txt').write_text('fixture-key')
        (self.work / 'run.pid').write_text('99999999')
        self.fake('ps', 'case "$*" in *stat=*) echo S;; *args=*) echo "java -jar qortal.jar";; esac')
        result = self.run_script('stop.sh', '--timeout', '1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('still running', result.stderr)
        self.assertTrue((self.work / 'run.pid').exists())

    def test_stop_removes_pid_only_after_exit(self):
        self.fake_api()
        (self.work / 'apikey.txt').write_text('fixture-key')
        (self.work / 'run.pid').write_text('99999999')
        self.env['PS_COUNT'] = str(self.work / 'ps-count')
        self.fake('ps', '''case "$*" in
*args=*) echo 'java -jar qortal.jar';;
*stat=*) if [ -f "$PS_COUNT" ]; then exit 1; fi; touch "$PS_COUNT"; echo S;;
esac''')
        result = self.run_script('stop.sh', '--timeout', '1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Qortal stopped', result.stdout)
        self.assertFalse((self.work / 'run.pid').exists())

    def test_start_rejects_duplicate_and_ambiguous_jars(self):
        self.fake('id', 'echo 1000')
        self.fake('java', 'echo \'openjdk version "17.0.1"\' >&2')
        self.fake('nohup', 'echo launched > "$TMPDIR/launched"')
        (self.work / 'run.pid').write_text(str(os.getpid()))
        result = self.run_script('start.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('still running', result.stderr)
        (self.work / 'run.pid').unlink()
        (self.work / 'target').mkdir()
        for script in ('start.sh', 'testnet/start.sh'):
            self.assertNotEqual(self.run_script(script).returncode, 0)
        for name in ('qortal-a.jar', 'qortal-b.jar'):
            (self.work / 'target' / name).write_text('fixture-jar')
        result = self.run_script('start.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.work / 'launched').exists())
        self.assertFalse((self.work / 'qortal.jar').exists())

    def test_update_branch_preserves_checkout_and_index(self):
        self.init_repo()
        (self.work / 'qortal.update').write_bytes(b'fixture update')
        (self.work / 'staged.txt').write_text('keep staged')
        self.git('add', 'staged.txt')
        before = self.git('status', '--porcelain')
        result = self.run_script('tools/create-auto-update-branch.sh',
                                 'qortal.update', 'auto-update-fixture', 'Fixture update')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git('status', '--porcelain'), before)
        self.assertEqual(self.git('branch', '--show-current'), 'master')
        self.assertEqual(self.git('ls-tree', '--name-only', 'auto-update-fixture'), 'qortal.update')
        self.assertEqual(len(self.git('rev-list', '--parents', '-n1', 'auto-update-fixture').split()), 1)
        tip = self.git('rev-parse', 'auto-update-fixture')
        repeat = self.run_script('tools/create-auto-update-branch.sh',
                                 'qortal.update', 'auto-update-fixture', 'Repeat')
        self.assertNotEqual(repeat.returncode, 0)
        self.assertEqual(self.git('rev-parse', 'auto-update-fixture'), tip)

    def test_build_dry_run_needs_no_artifacts_and_never_publishes(self):
        self.init_repo()
        publisher = self.work / 'tools/auto-update-scripts/publish-auto-update.py'
        publisher.parent.mkdir(parents=True)
        publisher.write_text('raise RuntimeError("must not execute")')
        self.git('add', 'tools')
        self.git('commit', '-qm', 'Fixture publisher')
        for name in ('mvn', 'java', 'python3'):
            self.fake(name, 'echo unexpected > "$TMPDIR/unexpected"; exit 99')
        source = self.git('rev-parse', 'HEAD')
        # Choose a global dry run, then deliberately request real publication.
        answers = '\ny\ny\nn\nn\ny\ny\ny\ny\ny\n'
        result = self.run_script('tools/auto-update-scripts/build-auto-update.sh', input=answers)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('publisher skipped', result.stdout)
        self.assertIn('6.1.1', result.stdout)
        self.assertEqual(self.git('rev-parse', 'HEAD'), source)
        self.assertIn('6.1.0', (self.work / 'pom.xml').read_text())
        self.assertFalse((self.work / 'unexpected').exists())
        self.assertEqual(self.git('for-each-ref', '--format=%(refname)', 'refs/tags'), '')

    def test_publisher_hashes_remote_blob_and_dry_run_needs_no_key(self):
        self.init_repo()
        source = self.git('rev-parse', '--short', 'HEAD')
        update = self.work / 'qortal.update'
        update.write_bytes(b'remote fixture bytes')
        result = self.run_script('tools/create-auto-update-branch.sh', 'qortal.update',
                                 f'auto-update-{source}', 'Fixture update')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.git('update-ref', f'refs/remotes/origin/auto-update-{source}',
                 self.git('rev-parse', f'auto-update-{source}'))
        update.write_bytes(b'wrong local update')
        result = subprocess.run([PYTHON, str(ROOT / 'tools/auto-update-scripts/publish-auto-update.py'),
                                 source, '--dry-run'], cwd=self.work, env=self.env,
                                input='', text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(hashlib.sha256(b'remote fixture bytes').hexdigest(), result.stdout)
        self.assertEqual(update.read_bytes(), b'wrong local update')
        self.git('update-ref', '-d', f'refs/remotes/origin/auto-update-{source}')
        missing = subprocess.run([PYTHON, str(ROOT / 'tools/auto-update-scripts/publish-auto-update.py'),
                                  source, '--dry-run'], cwd=self.work, env=self.env,
                                 input='', text=True, capture_output=True, timeout=15)
        self.assertNotEqual(missing.returncode, 0)

    def test_publisher_uses_http_timeout_and_auth_session(self):
        spec = importlib.util.spec_from_file_location('publisher', ROOT / 'tools/auto-update-scripts/publish-auto-update.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with mock.patch.object(module.API, 'post') as post:
            post.return_value.text = 'fixture-public-key'
            self.assertEqual(module.get_public_key('fixture-private-key', 12391), 'fixture-public-key')
            self.assertEqual(post.call_args.kwargs['timeout'], 30)
            self.assertEqual(post.call_args.kwargs['data'], 'fixture-private-key')

    @unittest.skipUnless(shutil.which('7z') and shutil.which('jq'), '7z and jq are needed')
    def test_release_notes_exclude_runtime_secrets_and_use_release_tag(self):
        runtime = self.work / 'running core'
        runtime.mkdir()
        for file in ('qortal.jar', 'log4j2.properties', 'start.sh', 'stop.sh', 'qort'):
            (runtime / file).write_text('fixture runtime')
        (runtime / 'settings.json').write_text('{"apiKey":"fixture-secret"}')
        (runtime / 'apikey.txt').write_text('fixture-secret')
        (runtime / 'wallets').mkdir()
        (runtime / 'wallets/private').write_text('fixture-private-data')
        original_mtime = (runtime / 'qortal.jar').stat().st_mtime_ns
        self.fake('git', "printf 'abc refs/tags/v6.1.8\\ndef refs/tags/v6.1.9\\n'")
        self.fake('curl', '''case "$*" in
*compare*) echo '{"status":"ahead","commits":[{"sha":"abcdef1234","author":{"login":"fixture"},"commit":{"message":"Fixture change"}}]}' ;;
*commits/v6.1.9*) echo '{"commit":{"committer":{"date":"2026-10-01T12:00:00Z"}}}' ;;
*) echo 'Unexpected URL' >&2; exit 99 ;;
esac''')
        self.env['WORKING_QORTAL_DIR'] = str(runtime)
        result = self.run_script('tools/auto-update-scripts/generate-release-notes.sh', '6.1.9')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        with zipfile.ZipFile(self.work / 'qortal.zip') as archive:
            self.assertEqual({x for x in archive.namelist() if not x.endswith('/')},
                             {f'qortal/{x}' for x in ('qortal.jar', 'log4j2.properties', 'start.sh', 'stop.sh', 'qort', 'settings.json')})
            self.assertEqual(archive.read('qortal/settings.json'), b'{}\n')
        self.assertEqual((runtime / 'qortal.jar').stat().st_mtime_ns, original_mtime)
        self.assertEqual((runtime / 'settings.json').read_text(), '{"apiKey":"fixture-secret"}')
        self.assertIn(hashlib.sha256(b'fixture runtime').hexdigest(),
                      (self.work / 'release-notes.txt').read_text())

    def test_release_hashes_work_with_https_origin_and_spaces(self):
        self.init_repo()
        script = self.work / 'tools/build-release.sh'
        script.parent.mkdir()
        shutil.copyfile(ROOT / 'tools/build-release.sh', script)
        installer = self.work / 'WindowsInstaller/Qortal-SetupFiles/qortal.exe'
        installer.parent.mkdir(parents=True)
        installer.write_bytes(b'fixture exe')
        (self.work / 'target').mkdir()
        (self.work / 'target/qortal-test.jar').write_bytes(b'fixture jar')
        (self.work / 'qortal.zip').write_bytes(b'fixture zip')
        result = subprocess.run([BASH, str(script), 'v6.1.0'], cwd=self.work, env=self.env,
                                text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('https://github.com/example/qortal/releases/download/v6.1.0/qortal.jar', result.stdout)
        for data in (b'fixture exe', b'fixture jar', b'fixture zip'):
            for algorithm in (hashlib.md5, hashlib.sha1, hashlib.sha256):
                self.assertIn(algorithm(data).hexdigest(), result.stdout)
        installer.unlink()
        self.assertNotEqual(subprocess.run([BASH, str(script), 'v6.1.0'], cwd=self.work,
                                          env=self.env, capture_output=True).returncode, 0)

    def test_docker_jvm_wildcards_are_literal(self):
        settings = self.work / 'settings.json'
        settings.write_text('{}')
        arguments = self.work / 'start-arguments.txt'
        arguments.write_text('-Dtest=*')
        (self.work / '-Dtest=expanded').touch()
        self.env.update(QORTAL_SETTINGS_FILE=str(settings), QORTAL_START_ARGUMENTS_FILE=str(arguments))
        self.fake('java', 'printf "ARG=%s\\n" "$@"')
        result = self.run_script('docker-start.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ARG=-Dtest=*', result.stdout)
        self.assertNotIn('ARG=-Dtest=expanded', result.stdout)

    def test_pirate_preflight_rejects_nested_and_linked_bundle_content(self):
        bundle = self.work / 'bundle'
        bundle.mkdir()
        (bundle / 'MANIFEST.txt').write_text(
            'artifact-sha256: 55bd5979c01d424ce4b26428ac35b51aed2da2131ceb18c29443e0b46c18e018\n'
            'bundle-kind: host-specific\nplatform: linux-x86_64\nnative-library: librust-linux-x86_64.so\n')
        for file in ('LICENSE-qortal-jni.txt', 'qortal-handoff.md', 'librust-linux-x86_64.so'):
            (bundle / file).write_text('fixture content; never executed')
        self.assertEqual(self.run_script('tools/publish-pirate-unified-qdn.sh', 'validate', 'test', str(bundle)).returncode, 0)
        (bundle / 'unexpected').mkdir()
        result = self.run_script('tools/publish-pirate-unified-qdn.sh', 'validate', 'test', str(bundle))
        self.assertNotEqual(result.returncode, 0)
        (bundle / 'unexpected').rmdir()
        (bundle / 'unexpected').symlink_to(self.work)
        self.assertNotEqual(self.run_script('tools/publish-pirate-unified-qdn.sh', 'validate', 'test', str(bundle)).returncode, 0)

    @unittest.skipUnless(shutil.which('7z'), '7z is needed for packaging verification')
    def test_zip_uses_unique_staging_and_preserves_old_output_on_failure(self):
        self.init_repo()
        for file in ('log4j2.properties', 'start.sh', 'stop.sh', 'tools/qort'):
            dest = self.work / file
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text('fixture runtime file')
        self.git('add', '.')
        self.git('commit', '-qm', 'Runtime files')
        target = self.work / 'target'
        target.mkdir()
        (target / 'qortal-test.jar').write_bytes(b'fixture jar')
        sentinel = self.work / 'qortal'
        sentinel.mkdir()
        (sentinel / 'keep').write_text('leave me alone')
        result = self.run_script('tools/build-zip.sh', 'v6.1.0')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        archive = self.work / 'qortal.zip'
        with zipfile.ZipFile(archive) as z:
            self.assertEqual({x for x in z.namelist() if not x.endswith('/')},
                             {f'qortal/{x}' for x in ('qortal.jar', 'log4j2.properties', 'start.sh', 'stop.sh', 'qort', 'settings.json')})
            self.assertEqual(z.read('qortal/qortal.jar'), b'fixture jar')
            self.assertEqual(z.read('qortal/settings.json'), b'{}\n')
        self.assertTrue((sentinel / 'keep').exists())
        previous = archive.read_bytes()
        self.fake('7z', 'exit 9')
        self.assertNotEqual(self.run_script('tools/build-zip.sh', 'v6.1.0').returncode, 0)
        self.assertEqual(archive.read_bytes(), previous)
        (target / 'qortal-other.jar').write_text('another jar')
        self.assertNotEqual(self.run_script('tools/build-zip.sh', 'v6.1.0').returncode, 0)

    def test_keystore_failure_preserves_existing_store_and_cleans_keys(self):
        certs = self.work / 'letsencrypt/live/example.invalid'
        certs.mkdir(parents=True)
        store = self.work / 'existing.keystore'
        store.write_bytes(b'old store')
        self.fake('openssl', 'while [ "$1" != -out ]; do shift; done; echo fixture > "$2"')
        self.fake('keytool', 'exit 7')
        result = self.run_script('tools/build-keystore.sh', 'example.invalid', str(store), 'percent%s')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(store.read_bytes(), b'old store')
        self.assertEqual(list(self.work.glob('.qortal-keystore.*')), [])
        self.assertFalse((self.work / 'example.invalid.p12').exists())
        self.fake('keytool', 'while [ "$1" != -destkeystore ]; do shift; done; echo new > "$2"')
        result = self.run_script('tools/build-keystore.sh', 'example.invalid', str(store), 'percent%s')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(store.read_bytes(), b'new\n')
        self.assertIn('percent%s', result.stdout)
        self.assertEqual(store.stat().st_mode & 0o777, 0o600)


if __name__ == '__main__':
    unittest.main(verbosity=2)
