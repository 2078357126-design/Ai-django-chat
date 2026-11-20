#!/usr/bin/env python3
"""把 llama.cpp 官方预编译的 llama-server 下载到 vendor/llama（桌面打包用）。

不下载模型权重，只下载推理可执行文件和配套动态库。
"""

import json
import os
import platform
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

RELEASES = 'https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=10'
MIRROR = 'https://ghproxy.net/'


def asset_pattern():
    system = platform.system()
    machine = platform.machine().lower()
    if system == 'Darwin':
        return 'macos-arm64' if machine in ('arm64', 'aarch64') else 'macos-x64'
    if system == 'Windows':
        return 'win-cpu-x64' if machine in ('amd64', 'x86_64') else 'win-cpu-arm64'
    if system == 'Linux':
        return 'ubuntu-x64' if machine in ('x86_64', 'amd64') else 'ubuntu-arm64'
    raise SystemExit('不支持的构建平台：%s %s' % (system, machine))


def fetch(url, timeout=60):
    request = urllib.request.Request(url, headers={'User-Agent': 'AIChat-build'})
    try:
        import certifi
        import ssl
        context = ssl.create_default_context(cafile=certifi.where())
    except Exception:  # noqa: BLE001 - 没有 certifi 时用系统证书
        context = None
    return urllib.request.urlopen(request, timeout=timeout, context=context)


def download(url, target):
    errors = []
    for candidate in (url, MIRROR + url):
        try:
            with fetch(candidate, timeout=300) as response, open(target, 'wb') as out:
                shutil.copyfileobj(response, out)
            return
        except Exception as exc:  # noqa: BLE001 - 换镜像重试
            errors.append(str(exc))
    raise SystemExit('下载 llama-server 失败：' + '；'.join(errors))


def main():
    root = Path(__file__).resolve().parent.parent
    vendor = root / 'vendor' / 'llama'
    binary_name = 'llama-server.exe' if os.name == 'nt' else 'llama-server'
    if (vendor / binary_name).exists():
        print('llama-server 已存在，跳过下载')
        return

    pattern = asset_pattern()
    with fetch(RELEASES, timeout=60) as response:
        releases = json.load(response)

    asset = None
    for release in releases:
        for item in release.get('assets', []):
            name = item['name']
            if pattern in name and (name.endswith('.zip') or name.endswith('.tar.gz')):
                asset = item
                break
        if asset is not None:
            break
    if asset is None:
        raise SystemExit('没有找到 %s 对应的构建产物' % pattern)
    print('下载', asset['name'])

    with tempfile.TemporaryDirectory() as temp:
        suffix = '.zip' if asset['name'].endswith('.zip') else '.tar.gz'
        archive = Path(temp) / ('llama' + suffix)
        download(asset['browser_download_url'], archive)
        unpacked = Path(temp) / 'unpacked'
        unpacked.mkdir()
        if suffix == '.zip':
            with zipfile.ZipFile(archive) as bundle:
                bundle.extractall(unpacked)
        else:
            with tarfile.open(archive, 'r:gz') as bundle:
                bundle.extractall(unpacked)

        binary = next(unpacked.rglob(binary_name), None)
        if binary is None:
            raise SystemExit('压缩包里没有找到 llama-server')
        source_dir = binary.parent
        if vendor.exists():
            shutil.rmtree(vendor)
        shutil.copytree(source_dir, vendor)

    if os.name != 'nt':
        for item in vendor.iterdir():
            if item.is_file():
                try:
                    item.chmod(0o755)
                except OSError:
                    pass
    print('已准备', vendor)


if __name__ == '__main__':
    sys.exit(main())
