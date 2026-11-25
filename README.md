---
title: AI Django Chat
emoji: 💬
colorFrom: indigo
colorTo: purple
sdk: docker
app_port: 7860
pinned: false
---

# AI Django Chat

Django + OpenAI 兼容接口的网页聊天应用，带对话记录存储与消息编辑功能。

- 页面：`/`
- 接口：`POST /api/chat/`、`POST /api/chat/<id>/edit/`

部署时在 Space 的 `Settings → Variables and secrets` 中添加：

- `OPENAI_API_KEY`（必填）
- `OPENAI_BASE_URL`（可选，默认 `https://api.deepseek.com`）
- `OPENAI_MODEL`（可选，默认 `deepseek-chat`）
