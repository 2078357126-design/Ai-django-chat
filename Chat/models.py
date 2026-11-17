from django.db import models


class ChatRecord(models.Model):
    """一次完整的对话记录（用户提问 + 模型回复）。"""

    session_id = models.CharField('会话标识', max_length=64, blank=True, db_index=True)
    user_message = models.TextField('用户消息')
    ai_response = models.TextField('AI 回复')
    model_name = models.CharField('模型名称', max_length=100, blank=True)
    created_at = models.DateTimeField('创建时间', auto_now_add=True)

    class Meta:
        db_table = 'chat_record'
        ordering = ['-created_at']
        verbose_name = '对话记录'
        verbose_name_plural = '对话记录'

    def __str__(self):
        return f'{self.session_id or "-"}: {self.user_message[:30]}'
