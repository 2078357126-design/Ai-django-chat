package com.aichat.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 独立的手机聊天客户端：直接调用 AI 接口，不依赖电脑。
 * API Key 在打包时预置到 app-config.properties。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-flash";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadConfig();

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "Android");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/mobile.html");
    }

    private void loadConfig() {
        Properties props = new Properties();
        try (InputStream is = getAssets().open("app-config.properties")) {
            props.load(is);
        } catch (Exception ignored) {
            // 没有配置文件时保持默认值，页面会提示未配置 Key
        }
        apiKey = props.getProperty("OPENAI_API_KEY", "").trim();
        baseUrl = props.getProperty("OPENAI_BASE_URL", "https://api.deepseek.com").trim();
        model = props.getProperty("OPENAI_MODEL", "deepseek-flash").trim();
    }

    private class Bridge {
        @JavascriptInterface
        public void chat(String text, int requestId) {
            new Thread(() -> {
                try {
                    callJs(requestId, request(text), null);
                } catch (Exception e) {
                    callJs(requestId, null, readableError(e));
                }
            }).start();
        }
    }

    private void callJs(int id, String reply, String error) {
        String script = "window.__onReply(" + id + ","
                + (reply == null ? "null" : JSONObject.quote(reply)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        if (message.length() > 200) {
            message = message.substring(0, 200) + "…";
        }
        return "请求失败：" + message;
    }

    private String request(String userMessage) throws Exception {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("安装包未配置 API Key");
        }

        String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", userMessage);

        JSONArray messagesArray = new JSONArray();
        messagesArray.put(message);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messagesArray);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(90000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);

        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = (code >= 200 && code < 300)
                ? connection.getInputStream()
                : connection.getErrorStream();

        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line);
                }
            }
        }

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + text);
        }

        JSONObject json = new JSONObject(text.toString());
        return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
