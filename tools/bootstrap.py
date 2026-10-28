#!/usr/bin/env python3
"""Fetch official, checksum-verified build tools into this project only."""
import concurrent.futures
import hashlib
import json
import platform
from pathlib import Path
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.toolchain'
DEST.mkdir(exist_ok=True)
HOST=platform.system()
ARCH=platform.machine().lower()
if HOST=='Darwin':
    JDK_OS='mac'; SDK_OS='macosx'
    JDK_ARCH='aarch64' if ARCH in ('arm64','aarch64') else 'x64'
elif HOST=='Linux' and ARCH in ('x86_64','amd64'):
    JDK_OS='linux'; SDK_OS='linux'; JDK_ARCH='x64'
else:
    raise SystemExit('Bootstrap supports macOS and x86_64 Linux. Use a compatible JDK/Android SDK manually on this host.')


def fetch(url, path):
    subprocess.run(['curl', '--fail', '--location', '--retry', '2', '--silent', '--show-error', '--max-time', '180', url, '-o', str(path)], check=True)


def download_archive(label, url, checksum, algorithm, suffix):
    target = DEST / (label + suffix)
    if not target.exists() or hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        print('Downloading', label, flush=True)
        fetch(url, target)
    if hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch for ' + label)
    print('Verified', label, flush=True)
    return target


def jdk():
    if (DEST / 'jdk/bin/javac').exists():
        return
    meta = DEST / 'jdk-metadata.json'
    fetch('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture='+JDK_ARCH+'&image_type=jdk&os='+JDK_OS+'&vendor=eclipse', meta)
    package = json.loads(meta.read_text())[0]['binary']['package']
    archive = download_archive('jdk17', package['link'], package['checksum'], 'sha256', '.tar.gz')
    unpacked = DEST / 'jdk-unpack'
    unpacked.mkdir(exist_ok=True)
    # Vendor archive is checksum-verified against official metadata.
    with tarfile.open(archive) as tf:
        for member in tf.getmembers():
            if member.name.startswith('/') or '..' in Path(member.name).parts:
                raise RuntimeError('Unsafe JDK archive path')
        tf.extractall(unpacked)
    home = next(unpacked.glob('*/Contents/Home')) if HOST=='Darwin' else next(p for p in unpacked.iterdir() if (p/'bin/javac').exists())
    shutil.move(str(home), DEST / 'jdk')
    print('JDK ready', flush=True)


def android():
    repository = DEST / 'android-repository.xml'
    fetch('https://dl.google.com/android/repository/repository2-1.xml', repository)
    root = ET.parse(repository).getroot()
    for package_path, label in [('platforms;android-35', 'platform'), ('build-tools;35.0.0', 'build-tools')]:
        if (DEST / label).exists():
            continue
        package = next(p for p in root.findall('remotePackage') if p.attrib.get('path') == package_path)
        archives = package.findall('./archives/archive')
        archive = next(a for a in archives if a.findtext('host-os') in (None, SDK_OS))
        complete = archive.find('complete')
        checksum = complete.find('checksum')
        path = download_archive(label, 'https://dl.google.com/android/repository/' + complete.findtext('url'), checksum.text, checksum.attrib.get('type', 'sha1'), '.zip')
        unpacked = DEST / (label + '-unpack')
        unpacked.mkdir(exist_ok=True)
        with zipfile.ZipFile(path) as zf:
            for member in zf.infolist():
                if member.filename.startswith('/') or '..' in Path(member.filename).parts:
                    raise RuntimeError('Unsafe SDK archive path')
                extracted = Path(zf.extract(member, unpacked))
                mode = member.external_attr >> 16
                if mode & 0o111:
                    extracted.chmod(extracted.stat().st_mode | 0o111)
        children = [p for p in unpacked.iterdir() if p.is_dir()]
        if len(children) != 1:
            raise RuntimeError('Unexpected SDK archive layout')
        shutil.move(str(children[0]), DEST / label)
        print(label, 'ready', flush=True)


with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    jobs = [pool.submit(jdk), pool.submit(android)]
    for job in jobs:
        job.result()
print('Workspace-local build toolchain ready', flush=True)
