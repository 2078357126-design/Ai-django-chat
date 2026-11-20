"""
URL configuration for ai_django project.

The `urlpatterns` list routes URLs to views. For more information please see:
    https://docs.djangoproject.com/en/6.1/topics/http/urls/
Examples:
Function views
    1. Add an import:  from my_app import views
    2. Add a URL to urlpatterns:  path('', views.home, name='home')
Class-based views
    1. Add an import:  from other_app.views import Home
    2. Add a URL to urlpatterns:  path('', Home.as_view(), name='home')
Including another URLconf
    1. Import the include() function: from django.urls import include, path
    2. Add a URL to urlpatterns:  path('blog/', include('blog.urls'))
"""
from django.contrib import admin
from django.urls import include, path

from Chat.views import chat_page, pwa_icon, pwa_manifest, pwa_service_worker, sticker_svg

urlpatterns = [
    path('admin/', admin.site.urls),
    path('', chat_page, name='chat_page'),
    path('chat/', chat_page),
    path('api/', include('Chat.urls')),
    # 表情图与安卓端共用同一份资源
    path('stickers/<str:name>.svg', sticker_svg, name='sticker'),
    # PWA：可安装为桌面/主屏应用
    path('manifest.webmanifest', pwa_manifest, name='pwa_manifest'),
    path('sw.js', pwa_service_worker, name='pwa_sw'),
    path('icon.svg', pwa_icon, name='pwa_icon'),
]
