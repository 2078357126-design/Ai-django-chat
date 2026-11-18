package com.aichat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 独立聊天客户端：直接调用 DeepSeek 接口，不依赖电脑。
 * 首次填写 API Key，之后直接进入聊天界面。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "aichat";
    private static final String KEY_API = "api_key";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-flash";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/version.json";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getApiKey().isEmpty()) {
            showKeySetup();
        } else {
            openChat();
        }
        new Thread(this::checkForUpdate).start();
    }

    private String getApiKey() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "");
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void showKeySetup() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 200, 60, 60);

        TextView title = new TextView(this);
        title.setText("AI云");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#1F2329"));
        layout.addView(title, fullWidth());

        TextView hint = new TextView(this);
        hint.setText("首次使用，请粘贴 DeepSeek API Key。\n保存后即可直接聊天，不需要其他设置。");
        hint.setTextSize(13);
        hint.setTextColor(Color.parseColor("#8A9099"));
        hint.setPadding(0, 20, 0, 28);
        layout.addView(hint, fullWidth());

        EditText input = new EditText(this);
        input.setHint("sk-...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(input, fullWidth());

        Button save = new Button(this);
        save.setText("保存并开始");
        save.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                input.setError("请填写 API Key");
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_API, value).apply();
            openChat();
        });
        layout.addView(save, fullWidth());

        setContentView(layout);
    }

    private void openChat() {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    /** 暴露给网页的桥接方法。 */
    class Bridge {

        @JavascriptInterface
        public void chat(String message, String callbackId) {
            new Thread(() -> {
                final String result = requestChat(message);
                runOnUiThread(() -> {
                    if (webView != null) {
                        String script = "window.__onResult(" + JSONObject.quote(callbackId) + ", " + JSONObject.quote(result) + ")";
                        webView.evaluateJavascript(script, null);
                    }
                });
            }).start();
        }

        @JavascriptInterface
        public void resetKey() {
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("更换 API Key")
                    .setMessage("确定要重新填写 API Key 吗？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_API).apply();
                        if (webView != null) {
                            webView.destroy();
                            webView = null;
                        }
                        showKeySetup();
                    })
                    .setNegativeButton("取消", null)
                    .show());
        }

        @JavascriptInterface
        public String modelName() {
            return MODEL;
        }
    }

    private String requestChat(String message) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", message);
            JSONArray messages = new JSONArray();
            messages.put(msg);
            body.put("messages", messages);

            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }

            if (code >= 400) {
                return errorJson("调用失败 (HTTP " + code + ")，请检查 Key 是否有效");
            }

            JSONObject response = new JSONObject(sb.toString());
            String content = response.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            JSONObject ok = new JSONObject();
            ok.put("reply", content);
            return ok.toString();

        } catch (Exception e) {
            return errorJson("网络错误：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String errorJson(String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("error", message);
            return err.toString();
        } catch (Exception e) {
            return "{\"error\":\"未知错误\"}";
        }
    }

    /** 当前已安装版本的 versionCode。 */
    private int currentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 启动时在后台检查是否有新版本，失败时静默忽略。 */
    private void checkForUpdate() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(VERSION_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() >= 400) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject info = new JSONObject(sb.toString());
            final int remoteCode = info.getInt("versionCode");
            final String remoteName = info.optString("versionName", "");
            final String apkUrl = info.optString("url", "");
            final String notes = info.optString("notes", "");

            if (remoteCode <= currentVersionCode() || apkUrl.isEmpty()) {
                return;
            }
            runOnUiThread(() -> showUpdateDialog(remoteName, notes, apkUrl));
        } catch (Exception ignored) {
            // 网络异常或解析失败时不影响正常使用
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void showUpdateDialog(String versionName, String notes, String apkUrl) {
        String message = "发现新版本 " + versionName;
        if (!notes.isEmpty()) {
            message += "\n\n" + notes;
        }
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> downloadAndInstall(apkUrl))
                .setNegativeButton("以后再说", null)
                .show();
    }

    /** 下载新版 APK，完成后交给系统安装器覆盖安装。 */
    private void downloadAndInstall(String apkUrl) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("正在下载更新");
        progress.setMessage("请稍候…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setProgress(0);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                File apk = new File(getCacheDir(), "update.apk");
                if (apk.exists() && !apk.delete()) {
                    throw new IllegalStateException("无法清理旧的安装包");
                }

                conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(120000);
                conn.connect();

                int total = conn.getContentLength();
                runOnUiThread(() -> progress.setMax(total > 0 ? total : 100));

                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    int done = 0;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        done += len;
                        final int current = done;
                        runOnUiThread(() -> progress.setProgress(current));
                    }
                }

                runOnUiThread(progress::dismiss);
                installApk(apk);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "更新失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private void installApk(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动安装：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }
}
