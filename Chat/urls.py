from django.urls import path

from . import views

urlpatterns = [
    path('chat/', views.chat, name='chat'),
    path('chat/<int:record_id>/edit/', views.chat_edit, name='chat_edit'),
    path('config/', views.save_config, name='save_config'),
    path('keys/', views.keys, name='keys'),
    path('keys/balance/', views.key_balance, name='key_balance'),
    path('meta/', views.meta, name='meta'),
    path('models/', views.models, name='models'),
]
