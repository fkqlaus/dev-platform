#!/usr/bin/env python3
"""Build a self-contained image on the target OS. No credentials or working files are copied."""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--skip-build', action='store_true', help='Use an already tested bootJar')
    args = parser.parse_args()
    system = {'Windows': 'windows', 'Linux': 'linux', 'Darwin': 'macos'}.get(platform.system())
    arch = {'AMD64': 'x64', 'x86_64': 'x64', 'arm64': 'arm64', 'aarch64': 'arm64'}.get(platform.machine())
    if not system or not arch:
        raise SystemExit('Unsupported OS or CPU')
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        java = shutil.which('java')
        if not java:
            raise SystemExit('Building requires JDK 21. End users do not need Java.')
        java_home = str(Path(java).resolve().parent.parent)
    jdk = Path(java_home)
    release = (jdk / 'release').read_text(encoding='utf-8')
    arch_match = re.search(r'^OS_ARCH="([^"]+)"', release, re.MULTILINE)
    jdk_arch = {'amd64': 'x64', 'x86_64': 'x64', 'aarch64': 'arm64', 'arm64': 'arm64'}.get(arch_match.group(1).lower() if arch_match else '')
    if jdk_arch != arch:
        raise SystemExit('JDK architecture does not match the builder architecture. Select a matching JDK/Python environment.')
    suffix = '.exe' if system == 'windows' else ''
    version = subprocess.run([str(jdk / 'bin' / ('java' + suffix)), '-version'], capture_output=True, text=True, check=True)
    if 'version "21.' not in version.stderr:
        raise SystemExit('Set JAVA_HOME to a JDK 21 installation')
    if not args.skip_build:
        if system == 'windows':
            run('cmd.exe', '/d', '/c', 'gradlew.bat', 'test', 'bootJar', '--console=plain')
        else:
            run('sh', 'gradlew', 'test', 'bootJar', '--console=plain')
    jars = [p for p in (ROOT / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
    if len(jars) != 1:
        raise SystemExit('Expected one bootJar in build/libs. Remove stale build output before retrying.')
    with zipfile.ZipFile(jars[0]) as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
        match = re.search(r'^Implementation-Version: ([^\r\n]+)', manifest, re.MULTILINE)
        app_version = match.group(1) if match else ''
        numeric = re.fullmatch(r'(\d+\.\d+\.\d+)(?:[-+][A-Za-z0-9.-]+)?', app_version)
        if not numeric:
            raise SystemExit('bootJar Implementation-Version must be a three-part version with an optional suffix')
        for entry in jar.namelist():
            if entry.endswith(('connection.properties', 'application-local.properties', '.log', 'known_hosts')):
                raise SystemExit('Local configuration or logs found in bootJar; package rejected')
        config = jar.read('BOOT-INF/classes/application.properties').decode('utf-8-sig')
        for setting in ('platform.git.host=${GIT_SERVER_HOST:}', 'platform.git.username=${GIT_SERVER_USER:}',
                        'platform.git.password=${GIT_SERVER_PASSWORD:}', 'platform.git.base-path=${GIT_SERVER_BASE_PATH:}'):
            if setting not in config.splitlines():
                raise SystemExit('Expected empty public connection defaults; package rejected')
    output = ROOT / 'build/distributions'
    staging = ROOT / 'build/packaging'
    output.mkdir(parents=True, exist_ok=True)
    staging.mkdir(parents=True, exist_ok=True)
    # TemporaryDirectory only removes its own directory beneath this fixed build path.
    with tempfile.TemporaryDirectory(prefix='image-', dir=staging) as name:
        work = Path(name).resolve()
        if not work.is_relative_to(staging.resolve()):
            raise SystemExit('Invalid staging path')
        payload = work / 'input'
        payload.mkdir()
        shutil.copy2(jars[0], payload / 'dev-platform.jar')
        # Explicit allowlist: never copy config/, logs, screenshots, workspace or credentials.
        for filename in ('README.md', 'LICENSE', 'THIRD_PARTY.md'):
            (payload / filename).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / filename, payload / filename)
        runtime = work / 'runtime'
        run(jdk / 'bin' / ('jlink' + suffix), '--module-path', jdk / 'jmods',
            '--add-modules', 'ALL-MODULE-PATH', '--strip-debug', '--no-header-files', '--no-man-pages',
            '--output', runtime)
        # Retain distribution notices in addition to jlink's per-module legal/ files.
        for filename in ('NOTICE', 'LICENSE', 'release'):
            if (jdk / filename).is_file():
                shutil.copy2(jdk / filename, runtime / ('jdk-' + filename))
        destination = work / 'image'
        command = [jdk / 'bin' / ('jpackage' + suffix), '--type', 'app-image', '--name', 'DevPlatform',
                   '--app-version', numeric.group(1), '--input', payload, '--main-jar', 'dev-platform.jar',
                   '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
                   '--runtime-image', runtime, '--dest', destination,
                   '--java-options', '-Ddevplatform.packaged=true', '--java-options', '-Djava.awt.headless=false']
        if system == 'windows':
            command.append('--win-console')
        run(*command)
        image = destination / ('DevPlatform.app' if system == 'macos' else 'DevPlatform')
        metadata = {
            'application': 'DevPlatform', 'version': app_version, 'os': system, 'architecture': arch,
            'source_commit': os.environ.get('GITHUB_SHA', 'local-uncommitted-build'),
            'workflow_run': os.environ.get('GITHUB_RUN_ID', ''),
            'java': version.stderr.splitlines()[0],
            'signed': False,
            'note': 'Build metadata is not a security guarantee or a reproducible-build attestation.'
        }
        (destination / 'BUILD-INFO.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
        (destination / 'START-HERE.txt').write_text(
            'Dev Platform\n\nWindows: run DevPlatform/DevPlatform.exe.\n'
            'macOS: open DevPlatform.app.\nLinux: run ./DevPlatform/bin/DevPlatform.\n'
            'The browser opens after startup. Close using the Exit button in the page.\n'
            'No system Java is used. Settings are stored in your user data directory.\n'
            'Unsigned preview: review the source and BUILD-INFO before running.\n'
            'Documentation and application license are included inside the app payload.\n', encoding='utf-8')
        base = output / f'dev-platform-{app_version}-{system}-{arch}'
        if system == 'windows':
            archive = shutil.make_archive(str(base), 'zip', destination)
        else:
            archive = shutil.make_archive(str(base), 'gztar', destination)
        (output / 'latest-archive.txt').write_text(str(Path(archive).resolve()), encoding='utf-8')
        print(f'Created {Path(archive).name} ({Path(archive).stat().st_size / 1024 / 1024:.1f} MiB)')

if __name__ == '__main__':
    main()
