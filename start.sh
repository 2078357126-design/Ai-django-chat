#!/usr/bin/env bash
# 一键启动：./start.sh
# 可直接修改下面的默认值，也可在命令前用环境变量覆盖，例如 PORT=8080 ./start.sh
set -euo pipefail
cd "$(dirname "$0")"

# ---------------- OpenAI 配置 ----------------
ENV_FILE=".env"

# 已有 .env 就直接读取，无需重复输入
if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
fi

# 首次运行：粘贴一次 Key，保存到 .env，之后自动读取
if [ -z "${OPENAI_API_KEY:-}" ]; then
  if [ -t 0 ]; then
    echo "首次运行，请粘贴一次 DeepSeek API Key（输入内容不会显示）"
    printf 'Key: '
    read -r -s api_key || api_key=""
    echo
    if [ -n "$api_key" ]; then
      OPENAI_API_KEY="$api_key"
      {
        printf 'OPENAI_API_KEY=%s\n' "$OPENAI_API_KEY"
        printf 'OPENAI_BASE_URL=%s\n' "${OPENAI_BASE_URL:-https://api.deepseek.com}"
        printf 'OPENAI_MODEL=%s\n' "${OPENAI_MODEL:-deepseek-flash}"
      } > "$ENV_FILE"
      chmod 600 "$ENV_FILE"
      echo "已保存到 .env，以后启动不再询问。"
    fi
  fi
fi

export OPENAI_API_KEY="${OPENAI_API_KEY:-}"
export OPENAI_BASE_URL="${OPENAI_BASE_URL:-https://api.deepseek.com}"
export OPENAI_MODEL="${OPENAI_MODEL:-deepseek-flash}"

HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8000}"

# 局域网 IP：手机 App / 网页版需要用它访问
LAN_IP="${LAN_IP:-$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')}"
export DJANGO_ALLOWED_HOSTS="127.0.0.1,localhost${LAN_IP:+,$LAN_IP}"

if [ -z "$OPENAI_API_KEY" ]; then
  echo "警告: 没有可用的 OPENAI_API_KEY，调用接口会认证失败。重新运行本脚本可粘贴 Key。"
fi

# ---------------- 端口顺延 ----------------
while lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1; do
  echo "端口 ${PORT} 已被占用，改用 $((PORT + 1))"
  PORT=$((PORT + 1))
done

# ---------------- 数据库 ----------------
# 聊天记录统一存到 SQLite 文件，位置即“数据库”文件夹
export SQLITE_PATH="${SQLITE_PATH:-$HOME/Documents/项目集/cloud computing/数据库/chat.db}"
export DJANGO_USE_SQLITE=1
mkdir -p "$(dirname "$SQLITE_PATH")"
echo "数据库: $SQLITE_PATH"
python3 manage.py migrate --noinput

echo "本机访问: http://127.0.0.1:${PORT}/"
if [ -n "$LAN_IP" ]; then
  echo "手机访问: http://${LAN_IP}:${PORT}/   (手机需连同一 WiFi)"
fi
exec python3 manage.py runserver "${HOST}:${PORT}"
