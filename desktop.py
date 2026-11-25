"""桌面版入口：打包成独立应用后双击运行。

启动流程：
1. 读取本地配置（API Key 可在网页上填写，首次会引导）
2. 用本地 SQLite 存放聊天记录
3. 启动本地服务并自动打开浏览器
"""

import json
import os
import platform
import socket
import sys
import threading
import time
import webbrowser
from pathlib import Path


def data_dir() -> Path:
    """数据目录：聊天记录和配置都存在这里，不受打包后的只读限制。"""
    custom = os.environ.get('AICHAT_DATA_DIR')
    if custom:
        base = Path(custom)
    elif platform.system() == 'Windows':
        base = Path(os.environ.get('APPDATA', Path.home())) / 'AIChat'
    elif platform.system() == 'Darwin':
        base = Path.home() / 'Library' / 'Application Support' / 'AIChat'
    else:
        base = Path.home() / '.local' / 'share' / 'AIChat'
    base.mkdir(parents=True, exist_ok=True)
    return base


def resource_dir() -> Path:
    """程序自带资源目录（打包后为解压目录）。"""
    if getattr(sys, 'frozen', False):
        return Path(getattr(sys, '_MEIPASS', Path(sys.executable).parent))
    return Path(__file__).resolve().parent


def load_config(path: Path) -> dict:
    config = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            config[key.strip()] = value.strip()
    return config


def _parse_keys(raw: str) -> list:
    """解析 OPENAI_API_KEYS（JSON 数组），格式不对时当作没有。"""
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item).strip() for item in parsed if str(item).strip()][:3]


def pick_port(start=8765):
    """从 start 开始找一个空闲端口。"""
    for port in range(start, start + 30):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind(('127.0.0.1', port))
                return port
            except OSError:
                continue
    return start


def main():
    store = data_dir()
    config = load_config(store / 'config.env')
    # 打包时预置的 Key：安装包本身自带，用户无需填写
    preset = load_config(resource_dir() / 'preset.env')

    # 多 Key：配置文件里优先读 OPENAI_API_KEYS（JSON 数组），老配置回退到单个 Key
    keys = _parse_keys(
        os.environ.get('OPENAI_API_KEYS')
        or config.get('OPENAI_API_KEYS')
        or preset.get('OPENAI_API_KEYS')
    )
    if not keys:
        single = (
            os.environ.get('OPENAI_API_KEY')
            or config.get('OPENAI_API_KEY')
            or preset.get('OPENAI_API_KEY', '')
        )
        if single:
            keys = [single]

    os.environ['DJANGO_SETTINGS_MODULE'] = 'ai_django.settings'
    os.environ['DJANGO_USE_SQLITE'] = '1'
    os.environ['SQLITE_PATH'] = str(store / 'chat.db')
    os.environ['DJANGO_DEBUG'] = '0'
    os.environ['DJANGO_ALLOWED_HOSTS'] = '127.0.0.1,localhost'
    os.environ['OPENAI_API_KEYS'] = json.dumps(keys)
    os.environ['OPENAI_API_KEY'] = keys[0] if keys else ''
    os.environ['OPENAI_BASE_URL'] = (
        config.get('OPENAI_BASE_URL')
        or preset.get('OPENAI_BASE_URL')
        or 'https://api.deepseek.com'
    )
    # 旧版本把极速版写进了 config.env，升级后自动换成包里预置的新模型；
    # 用户自己配的其它模型名保持不动
    stored_model = config.get('OPENAI_MODEL') or ''
    if not stored_model or stored_model == 'deepseek-flash':
        stored_model = preset.get('OPENAI_MODEL') or 'deepseek-chat'
    os.environ['OPENAI_MODEL'] = stored_model

    import django
    from django.core.management import call_command

    django.setup()
    call_command('migrate', interactive=False, verbosity=0)

    port = pick_port()
    url = 'http://127.0.0.1:{}/'.format(port)

    def serve():
        call_command('runserver', '127.0.0.1:{}'.format(port), use_reloader=False, verbosity=0)

    threading.Thread(target=serve, daemon=True).start()
    time.sleep(2.5)
    webbrowser.open(url)

    while True:
        time.sleep(1)


if __name__ == '__main__':
    sys.exit(main())
