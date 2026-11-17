#!/usr/bin/env bash
# 部署平台构建脚本：安装依赖、收集静态文件、执行迁移
set -o errexit

if command -v python3 >/dev/null 2>&1; then
  PY=python3
else
  PY=python
fi

"$PY" -m pip install -r requirements.txt
"$PY" manage.py collectstatic --noinput
"$PY" manage.py migrate --noinput
