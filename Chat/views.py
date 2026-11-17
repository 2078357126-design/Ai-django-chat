import json

from django.conf import settings
from django.http import JsonResponse
from django.shortcuts import render
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST
from openai import OpenAI, OpenAIError

from .models import ChatRecord


def chat_page(request):
    """GET / 或 /chat/：浏览器聊天页面，页面内调用 POST /api/chat/。"""
    return render(request, 'chat/chat.html')


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
