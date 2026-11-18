package com.aichat.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getApiKey().isEmpty()) {
            showKeySetup();
        } else {
            openChat();
        }
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
        title.setText("AI 对话");
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_API).apply();
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.destroy();
                    webView = null;
                }
                showKeySetup();
            });
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }
}
