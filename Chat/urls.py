from django.urls import path

from . import views

urlpatterns = [
    path('chat/', views.chat, name='chat'),
    path('chat/<int:record_id>/edit/', views.chat_edit, name='chat_edit'),
    path('config/', views.save_config, name='save_config'),
]
