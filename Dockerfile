FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DJANGO_DEBUG=0 \
    DJANGO_USE_SQLITE=1 \
    DJANGO_ALLOWED_HOSTS=* \
    DJANGO_CSRF_TRUSTED_ORIGINS=https://*.hf.space

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN python manage.py collectstatic --noinput

EXPOSE 7860

CMD ["sh", "-c", "python manage.py migrate --noinput && python -m gunicorn ai_django.wsgi:application --bind 0.0.0.0:7860 --workers 2 --timeout 120"]
