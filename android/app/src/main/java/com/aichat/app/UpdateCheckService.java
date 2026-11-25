package com.aichat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 后台定时检查新版本：App 没打开也能通过系统通知提醒。
 * 系统对周期任务的最小间隔是 15 分钟，省电模式下可能更晚。
 */
public class UpdateCheckService extends JobService {

    static final int JOB_ID = 3105;
    private static final String CHANNEL_ID = "update_notice";
    private static final String PREFS = "aichat";
    private static final String KEY_NOTIFIED = "push_notice_code";
    private static final String[] VERSION_URLS = {
            "https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/version.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/version.json",
            "https://cdn.jsdelivr.net/gh/2078357126-design/Ai-django-chat@main/version.json",
    };

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                check();
            } catch (Exception ignored) {
                // 网络或解析失败时等下一次任务
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private void check() {
        JSONObject info = fetchVersion();
        if (info == null) {
            return;
        }
        int remoteCode = info.optInt("versionCode", 0);
        if (remoteCode <= currentVersionCode()) {
            return;
        }
        // 版本明确标注 notify=false 时不推送
        if (!info.optBoolean("notify", true)) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_NOTIFIED, 0) == remoteCode) {
            return;
        }

        String versionName = info.optString("versionName", "");
        String notes = info.optString("notes", "").trim();
        if (notifyUpdate(versionName, notes)) {
            prefs.edit().putInt(KEY_NOTIFIED, remoteCode).apply();
        }
    }

    private JSONObject fetchVersion() {
        for (String url : VERSION_URLS) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
                return new JSONObject(sb.toString());
            } catch (Exception ignored) {
                // 换下一条线路
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return null;
    }

    private int currentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28
                    ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private boolean notifyUpdate(String versionName, String notes) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "版本更新", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("有新版本时提醒");
            manager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = notes.isEmpty() ? "点开查看并更新" : notes;
        if (text.length() > 120) {
            text = text.substring(0, 120) + "…";
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_update)
                .setContentTitle("AI云 有新版本 " + versionName)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending);
        try {
            manager.notify(JOB_ID, builder.build());
            return true;
        } catch (Exception e) {
            // 没给通知权限时会失败，下次启动 App 内仍会提示
            return false;
        }
    }
}
