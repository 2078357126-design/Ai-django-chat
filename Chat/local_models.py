"""本地模型：清单读取、按需下载、llama-server 进程管理。

桌面版第一次选择本地模型时才下载权重文件，下载到数据目录下的 models/。
推理用 llama.cpp 的 llama-server，暴露 OpenAI 兼容接口。
"""

import atexit
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

from django.conf import settings

ACTIVE_FILE = 'active_model.txt'
_manifest_cache = {}
_progress = {}
_progress_lock = threading.Lock()
_download_threads = {}
_cancel_flags = {}

CLOUD = 'cloud'


def resource_base() -> Path:
    if getattr(sys, 'frozen', False):
        return Path(getattr(sys, '_MEIPASS', Path(sys.executable).parent))
    return Path(settings.BASE_DIR)


def data_dir() -> Path:
    sqlite_path = os.environ.get('SQLITE_PATH')
    if sqlite_path:
        return Path(sqlite_path).parent
    return resource_base()


def models_dir() -> Path:
    path = data_dir() / 'models'
    path.mkdir(parents=True, exist_ok=True)
    return path


def manifest():
    """模型清单（与安卓共用根目录的 models.json）。"""
    path = resource_base() / 'models.json'
    key = str(path)
    if key in _manifest_cache:
        return _manifest_cache[key]
    try:
        data = json.loads(path.read_text(encoding='utf-8'))
        models = data.get('models') or []
    except (OSError, json.JSONDecodeError):
        models = []
    _manifest_cache[key] = models
    return models


def find(model_id):
    for model in manifest():
        if model.get('id') == model_id:
            return model
    return None


def is_installed(model) -> bool:
    if not model:
        return False
    for item in model.get('files') or []:
        target = models_dir() / item['name']
        if not target.is_file() or target.stat().st_size != item.get('size'):
            return False
    return True


def active_id() -> str:
    try:
        value = (data_dir() / ACTIVE_FILE).read_text(encoding='utf-8').strip()
    except OSError:
        value = ''
    if value and (value == CLOUD or is_installed(find(value))):
        return value
    return CLOUD


def select(model_id: str) -> str:
    if model_id != CLOUD and not is_installed(find(model_id)):
        raise ValueError('模型还没下载完成')
    (data_dir() / ACTIVE_FILE).write_text(model_id, encoding='utf-8')
    return model_id


def list_models():
    active = active_id()
    result = [{
        'id': CLOUD,
        'name': 'DeepSeek 云端',
        'desc': '需要 API Key，回答质量最好',
        'ram': '不占内存',
        'vision': False,
        'size': 0,
        'installed': True,
        'active': active == CLOUD,
        'downloading': False,
        'progress': 100,
    }]
    for model in manifest():
        model_id = model.get('id')
        with _progress_lock:
            state = dict(_progress.get(model_id, {}))
        size = sum(item.get('size', 0) for item in model.get('files') or [])
        result.append({
            'id': model_id,
            'name': model.get('name', model_id),
            'desc': model.get('desc', ''),
            'ram': model.get('ram', ''),
            'vision': bool(model.get('vision')),
            'size': size,
            'installed': is_installed(model),
            'active': active == model_id,
            'downloading': bool(state.get('downloading')),
            'progress': int(state.get('percent', 0)),
            'error': state.get('error') or '',
        })
    return result


def _set_progress(model_id, **values):
    with _progress_lock:
        state = _progress.setdefault(model_id, {'percent': 0, 'downloading': False})
        state.update(values)


def progress_state(model_id):
    with _progress_lock:
        return dict(_progress.get(model_id, {}))


def start_download(model_id):
    model = find(model_id)
    if not model:
        raise ValueError('模型不存在')
    if is_installed(model):
        return
    with _progress_lock:
        if _download_threads.get(model_id) and _download_threads[model_id].is_alive():
            return
        _cancel_flags[model_id] = False
        _progress.setdefault(model_id, {'percent': 0, 'downloading': True})
    thread = threading.Thread(target=_download_worker, args=(model_id, model), daemon=True)
    _download_threads[model_id] = thread
    thread.start()


def cancel_download(model_id):
    with _progress_lock:
        if model_id in _cancel_flags:
            _cancel_flags[model_id] = True


def _download_worker(model_id, model):
    files = model.get('files') or []
    total = sum(item.get('size', 0) for item in files) or 1
    done_bytes = 0
    _set_progress(model_id, downloading=True, percent=0, error=None)
    try:
        for item in files:
            if _cancel_flags.get(model_id):
                break
            name = item['name']
            size = item.get('size', 0)
            target = models_dir() / name
            if target.is_file() and target.stat().st_size == size:
                done_bytes += size
                continue
            part = models_dir() / (name + '.part')
            _download_file(model_id, item, part, done_bytes, total)
            if part.is_file() and part.stat().st_size == size:
                if target.exists():
                    target.unlink()
                part.rename(target)
                done_bytes += size
            elif _cancel_flags.get(model_id):
                break
            else:
                raise RuntimeError('文件不完整，请重试')
        if _cancel_flags.get(model_id):
            _set_progress(model_id, downloading=False)
        else:
            _set_progress(model_id, downloading=False, percent=100)
    except Exception as exc:  # noqa: BLE001 - 下载失败统一提示
        _set_progress(model_id, downloading=False, error=str(exc)[:120])


def _download_file(model_id, item, part, done_bytes, total):
    urls = item.get('urls') or []
    last_error = '没有可用的下载地址'
    for url in urls:
        try:
            existing = part.stat().st_size if part.is_file() else 0
            request = urllib.request.Request(url, headers={'User-Agent': 'AIChat/3.2'})
            if existing:
                request.add_header('Range', 'bytes=%d-' % existing)
            try:
                import certifi
                import ssl
                context = ssl.create_default_context(cafile=certifi.where())
            except Exception:  # noqa: BLE001 - 没有 certifi 时用系统证书
                context = None
            with urllib.request.urlopen(request, timeout=30, context=context) as response:
                if response.status == 200:
                    existing = 0
                with open(part, 'ab' if existing else 'wb') as out:
                    written = existing
                    last_update = 0.0
                    while True:
                        if _cancel_flags.get(model_id):
                            return
                        chunk = response.read(256 * 1024)
                        if not chunk:
                            break
                        out.write(chunk)
                        written += len(chunk)
                        now = time.time()
                        if now - last_update > 0.4:
                            last_update = now
                            percent = int((done_bytes + written) * 100 / total)
                            _set_progress(model_id, percent=min(99, percent))
            return
        except Exception as exc:  # noqa: BLE001 - 换下一个镜像
            last_error = str(exc)[:120]
    raise RuntimeError(last_error)


def delete(model_id):
    model = find(model_id)
    if not model:
        return
    for item in model.get('files') or []:
        name = item['name']
        if _referenced_by_other(name, model_id):
            continue
        for path in (models_dir() / name, models_dir() / (name + '.part')):
            if path.is_file():
                path.unlink()
    with _progress_lock:
        _progress.pop(model_id, None)
    try:
        current = (data_dir() / ACTIVE_FILE).read_text(encoding='utf-8').strip()
    except OSError:
        current = ''
    if current == model_id:
        select(CLOUD)


def _referenced_by_other(name, except_id):
    for model in manifest():
        if model.get('id') == except_id or not is_installed(model):
            continue
        for item in model.get('files') or []:
            if item.get('name') == name:
                return True
    return False


def server_binary() -> Path:
    name = 'llama-server.exe' if os.name == 'nt' else 'llama-server'
    # 打包后是 llama/；源码运行时用 vendor/llama/
    for path in (resource_base() / 'llama' / name, resource_base() / 'vendor' / 'llama' / name):
        if path.is_file():
            return path
    return resource_base() / 'llama' / name


def _free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class LlamaServer:
    """常驻一个 llama-server 子进程，换模型时重启。"""

    def __init__(self):
        self.process = None
        self.model_id = ''
        self.port = 0
        self.lock = threading.Lock()

    def base_url(self, model_id):
        with self.lock:
            if (self.process and self.process.poll() is None
                    and self.model_id == model_id):
                return 'http://127.0.0.1:%d' % self.port
            self._stop_locked()
            model = find(model_id)
            if not model or not is_installed(model):
                raise RuntimeError('模型还没下载完成')
            binary = server_binary()
            if not binary.is_file():
                raise RuntimeError('本地推理组件缺失，请重新安装桌面版')
            if os.name != 'nt':
                # PyInstaller 可能丢掉执行位
                try:
                    binary.chmod(0o755)
                except OSError:
                    pass
            port = _free_port()
            args = [
                str(binary),
                '-m', str(models_dir() / model['main']),
                '-c', '2048',
                '-t', str(max(2, min(6, (os.cpu_count() or 4) - 1))),
                '--host', '127.0.0.1',
                '--port', str(port),
            ]
            if model.get('mmproj'):
                args += ['--mmproj', str(models_dir() / model['mmproj'])]
            log_path = data_dir() / 'llama-server.log'
            log = open(log_path, 'ab')
            creation = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            self.process = subprocess.Popen(
                args, stdout=log, stderr=subprocess.STDOUT, cwd=str(binary.parent),
                creationflags=creation,
            )
            self.model_id = model_id
            self.port = port
            self._wait_ready(port, self.process)
            return 'http://127.0.0.1:%d' % port

    @staticmethod
    def _wait_ready(port, process, timeout=180):
        deadline = time.time() + timeout
        url = 'http://127.0.0.1:%d/health' % port
        while time.time() < deadline:
            if process.poll() is not None:
                raise RuntimeError('本地推理启动失败，详见数据目录里的 llama-server.log')
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    if response.status == 200:
                        return
            except Exception:  # noqa: BLE001 - 还没起来
                time.sleep(0.5)
        raise RuntimeError('本地推理启动超时')

    def stop(self):
        with self.lock:
            self._stop_locked()

    def _stop_locked(self):
        if self.process and self.process.poll() is None:
            try:
                self.process.terminate()
                self.process.wait(timeout=8)
            except Exception:  # noqa: BLE001 - 强制结束
                try:
                    self.process.kill()
                except Exception:  # noqa: BLE001
                    pass
        self.process = None
        self.model_id = ''
        self.port = 0


_server = LlamaServer()


def server():
    return _server


@atexit.register
def _shutdown():
    try:
        _server.stop()
    except Exception:  # noqa: BLE001
        pass
