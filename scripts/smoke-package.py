#!/usr/bin/env python3
"""Run the actual packaged launcher with no Java on PATH, using isolated test data."""
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def api(url, path='', method='GET'):
    request = urllib.request.Request(url + path, data=b'{}' if method == 'POST' else None,
        headers={'Content-Type': 'application/json', 'Origin': url.rstrip('/')}, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        body = response.read()
        return json.loads(body) if body else None

def main():
    archive = Path((ROOT / 'build/distributions/latest-archive.txt').read_text(encoding='utf-8'))
    staging = ROOT / 'build/packaging'
    staging.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='smoke-', dir=staging) as temporary:
        work = Path(temporary).resolve()
        if not work.is_relative_to(staging.resolve()):
            raise RuntimeError('Invalid smoke path')
        image = work / 'app with spaces 한글'
        image.mkdir()
        if archive.suffix == '.zip':
            with zipfile.ZipFile(archive) as zipped:
                for member in zipped.namelist():
                    if not (image / member).resolve().is_relative_to(image):
                        raise RuntimeError('Unsafe archive path')
                zipped.extractall(image)
        else:
            with tarfile.open(archive) as tar:
                tar.extractall(image, filter='data')
        system = platform.system()
        launcher = image / ('DevPlatform/DevPlatform.exe' if system == 'Windows' else
            'DevPlatform.app/Contents/MacOS/DevPlatform' if system == 'Darwin' else 'DevPlatform/bin/DevPlatform')
        data = work / 'user data'
        no_java = work / 'no-java'
        no_java.mkdir()
        env = os.environ.copy()
        for key in list(env):
            if key.startswith(('SPRING_', 'GIT_SERVER_')) or key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                del env[key]
        env.update(DEV_PLATFORM_DATA_DIR=str(data), DEV_PLATFORM_OPEN_BROWSER='false', JAVA_HOME=str(no_java), PATH=str(no_java))
        flags = subprocess.CREATE_NO_WINDOW if system == 'Windows' else 0
        process = None
        log = work / 'launcher.log'
        def start():
            return subprocess.Popen([str(launcher)], cwd=work, env=env, stdout=stream, stderr=subprocess.STDOUT, creationflags=flags)
        def wait_ready(child):
            for _ in range(120):
                if child.poll() is not None:
                    raise RuntimeError('Packaged launcher exited before ready; inspect local smoke log')
                marker = data / 'running-url.txt'
                if marker.exists():
                    url = marker.read_text(encoding='utf-8')
                    try:
                        if api(url, 'api/application')['packaged']:
                            return url
                    except Exception:
                        pass
                time.sleep(.25)
            raise RuntimeError('Packaged application startup timed out')
        with log.open('wb') as stream:
            try:
                process = start()
                url = wait_ready(process)
                assert (data / 'logs/application.log').is_file()
                with urllib.request.urlopen(url, timeout=10) as page:
                    assert 'Dev Platform' in page.read().decode('utf-8')
                assert api(url, 'api/connection')['host'] == ''
                second = start()
                try:
                    assert second.wait(timeout=10) == 0
                finally:
                    if second.poll() is None: second.kill(); second.wait()
                assert process.poll() is None
                assert (data / 'running-url.txt').read_text(encoding='utf-8') == url
                api(url, 'api/application/exits', 'POST')
                assert process.wait(timeout=15) == 0
                assert not (data / 'running-url.txt').exists()
                # Synthetic offline fixture only. No remote connection is requested by this test.
                fixture = ('host=example.invalid\nport=22\nusername=git\npassword=synthetic-only\n'
                    'basePath=/srv/git\nsudoEnabled=false\nsudoUsername=git\nsudoPassword=\n'
                    'cloneUsername=git\ngroup=\nhostKey=synthetic-placeholder\n')
                (data / 'connection.properties').write_text(fixture, encoding='utf-8')
                process = start()
                url = wait_ready(process)
                assert api(url, 'api/connection')['saved'] is True
                assert (data / 'connection.properties').read_text(encoding='utf-8') == fixture
                api(url, 'api/application/exits', 'POST')
                assert process.wait(timeout=15) == 0
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
                reports = ROOT / 'build/reports'
                reports.mkdir(parents=True, exist_ok=True)
                stream.flush()
                shutil.copy2(log, reports / 'package-smoke.log')
        result = dict(os=system, architecture=platform.machine(), system_java_available=False,
            launcher_started=True, spaced_unicode_path=True, duplicate_launch_blocked=True,
            browser_opened=False, settings_preserved_after_restart=True, remote_repository_created=False)
        (ROOT / 'build/reports/package-smoke.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('Packaged launcher smoke checks passed (no system Java, isolated settings, no remote writes).')

if __name__ == '__main__':
    main()
