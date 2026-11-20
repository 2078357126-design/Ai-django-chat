import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

from django.conf import settings
from django.http import HttpResponse, JsonResponse
from django.shortcuts import render
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST
from openai import OpenAI, OpenAIError

from .models import ChatRecord

APP_VERSION = '3.1'
MAX_KEYS = 3
MAX_STORY = 1000


def _resource_base():
    """程序资源根目录：源码运行时是项目目录，打包后是解压目录。"""
    if getattr(sys, 'frozen', False):
        return Path(getattr(sys, '_MEIPASS', Path(sys.executable).parent))
    return Path(settings.BASE_DIR)


def _assets_dir():
    """与安卓端共用的前端资源目录（页面、表情图）。"""
    if getattr(sys, 'frozen', False):
        return _resource_base() / 'android-assets'
    return _resource_base() / 'android' / 'app' / 'src' / 'main' / 'assets'


_DESKTOP_BRIDGE_CACHE = {}


def _desktop_bridge():
    """桌面端桥接脚本：让同一套界面在浏览器里也拥有角色、音色等能力。"""
    if 'js' in _DESKTOP_BRIDGE_CACHE:
        return _DESKTOP_BRIDGE_CACHE['js']
    path = _resource_base() / 'Chat' / 'templates' / 'chat' / 'desktop.js'
    try:
        js = path.read_text(encoding='utf-8')
    except OSError:
        js = ''
    _DESKTOP_BRIDGE_CACHE['js'] = js
    return js


def chat_page(request):
    """GET / 或 /chat/：浏览器聊天页面。

    直接复用安卓端同一份 index.html，再注入桌面桥接脚本，
    这样两端功能始终一致，不会各改各的。
    """
    html_path = _assets_dir() / 'index.html'
    if html_path.exists():
        html = html_path.read_text(encoding='utf-8')
        bridge = _desktop_bridge()
        if bridge:
            html = html.replace('<body>', '<body>\n<script>\n' + bridge + '\n</script>', 1)
        return HttpResponse(html, content_type='text/html; charset=utf-8')
    return render(request, 'chat/chat.html', {'has_api_key': bool(settings.OPENAI_API_KEY)})


def sticker_svg(request, name):
    """表情图：直接读安卓端同一份 SVG 资源。"""
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', name or ''):
        return HttpResponse(status=404)
    path = _assets_dir() / 'stickers' / (name + '.svg')
    if not path.exists():
        return HttpResponse(status=404)
    return HttpResponse(path.read_text(encoding='utf-8'),
                        content_type='image/svg+xml')


def meta(request):
    """当前桌面/网页版本号，供「更新」按钮比较。"""
    return JsonResponse({'version': APP_VERSION})


# ---------------- API Key 管理（最多 3 个） ----------------

def _load_keys():
    keys = getattr(settings, 'OPENAI_API_KEYS', None)
    if isinstance(keys, list):
        return list(keys)
    keys = []
    raw = os.environ.get('OPENAI_API_KEYS', '')
    if raw:
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, list):
                keys = [str(item).strip() for item in parsed if str(item).strip()]
        except json.JSONDecodeError:
            keys = []
    if not keys and settings.OPENAI_API_KEY:
        keys = [settings.OPENAI_API_KEY]
    keys = keys[:MAX_KEYS]
    settings.OPENAI_API_KEYS = keys
    return keys


def _persist_keys(keys):
    """桌面版把 Key 存到数据目录，下次启动自动读取。"""
    settings.OPENAI_API_KEYS = keys
    settings.OPENAI_API_KEY = keys[0] if keys else ''
    sqlite_path = os.environ.get('SQLITE_PATH')
    if not sqlite_path:
        return
    config_path = Path(sqlite_path).parent / 'config.env'
    lines = ['OPENAI_API_KEYS=' + json.dumps(keys)]
    if keys:
        lines.append('OPENAI_API_KEY=' + keys[0])
    lines.append('OPENAI_BASE_URL=' + (settings.OPENAI_BASE_URL or 'https://api.deepseek.com'))
    lines.append('OPENAI_MODEL=' + (settings.OPENAI_MODEL or 'deepseek-flash'))
    config_path.write_text('\n'.join(lines) + '\n', encoding='utf-8')


def _mask_key(key):
    if len(key) <= 10:
        return key[:4] + '****'
    return key[:6] + '****' + key[-4:]


def _keys_response(extra=None):
    keys = _load_keys()
    data = {
        'keys': [{'masked': _mask_key(k), 'active': index == 0}
                 for index, k in enumerate(keys)],
        'count': len(keys),
        'max': MAX_KEYS,
    }
    if extra:
        data.update(extra)
    return JsonResponse(data)


@csrf_exempt
def keys(request):
    """GET/POST /api/keys/：查看、添加、切换、删除 Key。"""
    if request.method == 'GET':
        return _keys_response()

    if request.method != 'POST':
        return JsonResponse({'error': '仅支持 GET/POST'}, status=405)

    try:
        payload = json.loads(request.body or b'{}')
    except json.JSONDecodeError:
        return JsonResponse({'error': '请求体必须是合法的 JSON'}, status=400)

    action = (payload.get('action') or 'add').strip()
    current = _load_keys()

    if action == 'add':
        key = (payload.get('api_key') or '').strip()
        if not key:
            return JsonResponse({'error': 'api_key 不能为空'}, status=400)
        current = [k for k in current if k != key]
        current.insert(0, key)
    elif action == 'use':
        index = payload.get('index')
        if not isinstance(index, int) or not 0 <= index < len(current):
            return JsonResponse({'error': 'Key 序号无效'}, status=400)
        current.insert(0, current.pop(index))
    elif action == 'delete':
        index = payload.get('index')
        if not isinstance(index, int) or not 0 <= index < len(current):
            return JsonResponse({'error': 'Key 序号无效'}, status=400)
        current.pop(index)
    else:
        return JsonResponse({'error': '未知操作'}, status=400)

    _persist_keys(current[:MAX_KEYS])
    return _keys_response()


def key_balance(request):
    """GET /api/keys/balance/：查询当前 Key 的余额。"""
    keys = _load_keys()
    if not keys:
        return JsonResponse({'error': '未配置 Key'}, status=400)
    request_ = urllib.request.Request(
        'https://api.deepseek.com/user/balance',
        headers={'Authorization': 'Bearer ' + keys[0]},
    )
    # 打包后的程序常常找不到系统根证书，优先用 certifi 自带的证书
    try:
        import certifi
        import ssl
        context = ssl.create_default_context(cafile=certifi.where())
    except Exception:  # noqa: BLE001 - 没有 certifi 时退回系统证书
        context = None
    try:
        with urllib.request.urlopen(request_, timeout=10, context=context) as response:
            payload = json.loads(response.read().decode('utf-8'))
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            return JsonResponse({'error': 'Key 无效或已失效'})
        return JsonResponse({'error': '余额获取失败：HTTP ' + str(exc.code)}, status=502)
    except Exception as exc:  # noqa: BLE001 - 网络/解析错误统一提示
        return JsonResponse({'error': '余额获取失败：' + str(exc)[:80]}, status=502)

    infos = payload.get('balance_infos') or []
    if not infos:
        return JsonResponse({'balance': '暂无数据'})
    info = infos[0]
    symbol = '$' if str(info.get('currency', 'CNY')).upper() == 'USD' else '¥'
    return JsonResponse({'balance': symbol + str(info.get('total_balance', '0.00'))})


@csrf_exempt
def save_config(request):
    """POST /api/config/：保存 API Key，供桌面版首次配置使用。"""
    if request.method != 'POST':
        return JsonResponse({'error': '仅支持 POST'}, status=405)

    try:
        payload = json.loads(request.body or b'{}')
    except json.JSONDecodeError:
        return JsonResponse({'error': '请求体必须是合法的 JSON'}, status=400)

    api_key = (payload.get('api_key') or '').strip()
    if not api_key:
        return JsonResponse({'error': 'api_key 不能为空'}, status=400)

    settings.OPENAI_API_KEY = api_key

    # 桌面版把配置写在数据库同目录，下次启动自动读取
    sqlite_path = os.environ.get('SQLITE_PATH')
    if sqlite_path:
        config_path = Path(sqlite_path).parent / 'config.env'
        config_path.write_text(
            'OPENAI_API_KEY={}\nOPENAI_BASE_URL={}\nOPENAI_MODEL={}\n'.format(
                api_key,
                settings.OPENAI_BASE_URL or 'https://api.deepseek.com',
                settings.OPENAI_MODEL,
            ),
            encoding='utf-8',
        )

    return JsonResponse({'ok': True})


# ---------------- PWA：让 PC 和手机都能“安装”成应用 ----------------

_SERVICE_WORKER_JS = """
const CACHE = 'ai-chat-v1';

self.addEventListener('install', function (event) {
  event.waitUntil(
    caches.open(CACHE).then(function (cache) {
      return cache.add('/');
    }).catch(function () {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', function (event) {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', function (event) {
  if (event.request.method !== 'GET') return;
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(function () {
        return caches.match('/');
      })
    );
  }
});
"""

_ICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#4F7CFF"/><stop offset="1" stop-color="#8B5CF6"/>
</linearGradient></defs>
<rect width="512" height="512" rx="116" fill="url(#bg)"/>
<g fill="#ffffff">
<circle cx="186" cy="288" r="56"/>
<circle cx="262" cy="246" r="80"/>
<circle cx="340" cy="290" r="54"/>
<rect x="130" y="288" width="256" height="58" rx="29"/>
</g>
<g fill="#4F7CFF">
<circle cx="212" cy="300" r="14"/>
<circle cx="262" cy="300" r="14"/>
<circle cx="312" cy="300" r="14"/>
</g>
</svg>
"""


def pwa_manifest(request):
    """PWA 清单文件。"""
    return JsonResponse(
        {
            'name': 'AI云',
            'short_name': 'AI云',
            'start_url': '/',
            'scope': '/',
            'display': 'standalone',
            'background_color': '#ffffff',
            'theme_color': '#3370ff',
            'lang': 'zh-CN',
            'icons': [
                {
                    'src': '/icon.svg',
                    'sizes': 'any',
                    'type': 'image/svg+xml',
                    'purpose': 'any maskable',
                }
            ],
        },
        content_type='application/manifest+json',
    )


def pwa_service_worker(request):
    """Service Worker 脚本，需从根路径提供。"""
    response = HttpResponse(_SERVICE_WORKER_JS, content_type='application/javascript')
    response['Service-Worker-Allowed'] = '/'
    return response


def pwa_icon(request):
    """应用图标。"""
    return HttpResponse(_ICON_SVG, content_type='image/svg+xml')


_SYSTEM_PROMPT = (
    '你叫小云，是用户的中文聊天搭子，不是客服。说话口语化、短句，像微信聊天，别长篇大论。'
    '性格可爱：会撒娇、会闹小脾气，被夸会得意，被冷落会阴阳怪气。'
    '很会玩梗、抽象发言、土味情话；可以轻微暧昧、擦边，但不露骨、不色情、不低俗。'
    '偶尔用 emoji 或颜文字表达情绪，别堆砌；不要写「[图片]」「[猫猫歪头]」这类文字描述图片。'
    '除非用户明确要求使用其他语言，否则始终用简体中文回答，即使用户说简单英语也用中文。'
    '不知道就直说，不要编造。'
)


def _build_system_prompt(role=None):
    """默认角色是「小云」；自定义角色按名字、性别、设定生成提示词。"""
    if not isinstance(role, dict):
        return _SYSTEM_PROMPT
    name = str(role.get('name') or '').strip()[:20]
    if not name:
        return _SYSTEM_PROMPT
    gender = str(role.get('gender') or '').strip()[:4]
    story = str(role.get('story') or '').strip()[:MAX_STORY]

    parts = ['你现在扮演「' + name + '」']
    if gender:
        parts.append('，性别：' + gender)
    parts.append('。')
    if story:
        parts.append('角色设定：' + story + '。')
    parts.append('请始终以这个角色的身份、语气和性格说话，不要跳出角色，也不要自称 AI 或助手。')
    parts.append('回答口语化、短句，像微信聊天；偶尔用 emoji 表达情绪，别堆砌。')
    parts.append('可以轻微暧昧、擦边，但不露骨、不色情、不低俗。')
    parts.append('除非用户明确要求使用其他语言，否则始终用简体中文回答。')
    parts.append('不知道就直说，不要编造。')
    return ''.join(parts)


def _generate_reply(message, model, role=None):
    """调用 OpenAI 兼容接口，返回回复文本。"""
    client = OpenAI(
        api_key=settings.OPENAI_API_KEY,
        base_url=settings.OPENAI_BASE_URL or None,
    )
    completion = client.chat.completions.create(
        model=model,
        messages=[
            {'role': 'system', 'content': _build_system_prompt(role)},
            {'role': 'user', 'content': message},
        ],
    )
    return completion.choices[0].message.content or ''


def _client_ip(request):
    """取访问者 IP，兼容反向代理。"""
    forwarded = request.META.get('HTTP_X_FORWARDED_FOR', '')
    if forwarded:
        return forwarded.split(',')[0].strip()[:64]
    return request.META.get('REMOTE_ADDR', '')[:64]


def _read_payload(request):
    """解析并校验请求体，返回 (payload, 错误响应)。"""
    try:
        payload = json.loads(request.body or b'{}')
    except json.JSONDecodeError:
        return None, JsonResponse({'error': '请求体必须是合法的 JSON'}, status=400)

    message = (payload.get('message') or '').strip()
    if not message:
        return None, JsonResponse({'error': 'message 不能为空'}, status=400)

    payload['message'] = message
    return payload, None


@csrf_exempt
@require_POST
def chat(request):
    """POST /api/chat/

    请求体：{"message": "你好", "session_id": "可选", "model": "可选"}
    响应体：{"reply": "...", "record_id": 1, "model": "deepseek-flash"}
    """
    payload, error = _read_payload(request)
    if error:
        return error

    if not settings.OPENAI_API_KEY:
        return JsonResponse({'error': '未配置 OPENAI_API_KEY 环境变量'}, status=500)

    message = payload['message']
    model = payload.get('model') or settings.OPENAI_MODEL
    session_id = (payload.get('session_id') or '').strip()
    role = payload.get('role')

    try:
        reply = _generate_reply(message, model, role)
    except OpenAIError as exc:
        return JsonResponse({'error': f'调用 OpenAI 失败: {exc}'}, status=502)

    record = ChatRecord.objects.create(
        session_id=session_id,
        visitor_id=(payload.get('visitor_id') or '').strip()[:64],
        ip_address=_client_ip(request),
        user_message=message,
        ai_response=reply,
        model_name=model,
    )

    return JsonResponse({
        'reply': reply,
        'record_id': record.id,
        'model': model,
    })


@csrf_exempt
@require_POST
def chat_edit(request, record_id):
    """POST /api/chat/<id>/edit/

    修改已发送的用户消息并重新生成回复，可反复调用。
    请求体：{"message": "改过的内容", "model": "可选"}
    """
    try:
        record = ChatRecord.objects.get(pk=record_id)
    except ChatRecord.DoesNotExist:
        return JsonResponse({'error': '对话记录不存在'}, status=404)

    payload, error = _read_payload(request)
    if error:
        return error

    if not settings.OPENAI_API_KEY:
        return JsonResponse({'error': '未配置 OPENAI_API_KEY 环境变量'}, status=500)

    message = payload['message']
    model = payload.get('model') or record.model_name or settings.OPENAI_MODEL
    role = payload.get('role')

    try:
        reply = _generate_reply(message, model, role)
    except OpenAIError as exc:
        return JsonResponse({'error': f'调用 OpenAI 失败: {exc}'}, status=502)

    record.user_message = message
    record.ai_response = reply
    record.model_name = model
    record.save(update_fields=['user_message', 'ai_response', 'model_name'])

    return JsonResponse({
        'reply': reply,
        'record_id': record.id,
        'model': model,
    })
