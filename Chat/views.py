import json
import os
from pathlib import Path

from django.conf import settings
from django.http import HttpResponse, JsonResponse
from django.shortcuts import render
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST
from openai import OpenAI, OpenAIError

from .models import ChatRecord


def chat_page(request):
    """GET / 或 /chat/：浏览器聊天页面，页面内调用 POST /api/chat/。"""
    return render(request, 'chat/chat.html', {'has_api_key': bool(settings.OPENAI_API_KEY)})


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


def _generate_reply(message, model):
    """调用 OpenAI 兼容接口，返回回复文本。"""
    client = OpenAI(
        api_key=settings.OPENAI_API_KEY,
        base_url=settings.OPENAI_BASE_URL or None,
    )
    completion = client.chat.completions.create(
        model=model,
        messages=[{'role': 'user', 'content': message}],
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

    try:
        reply = _generate_reply(message, model)
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

    try:
        reply = _generate_reply(message, model)
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
