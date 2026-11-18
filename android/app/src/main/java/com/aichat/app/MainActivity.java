package com.aichat.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 聊天客户端：内嵌浏览器加载电脑上运行的 Django 服务。
 * 首次使用填写电脑地址，保存在本地，之后直接进入聊天界面。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "aichat";
    private static final String KEY_URL = "server_url";
    private static final String DEFAULT_URL = "http://10.122.73.9:8000/";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String url = prefs.getString(KEY_URL, "");
        if (url == null || url.isEmpty()) {
            showSetup();
        } else {
            openChat(url);
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void showSetup() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 200, 60, 60);

        TextView title = new TextView(this);
        title.setText("连接电脑上的 AI 对话");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#1F2329"));
        layout.addView(title, fullWidth());

        TextView hint = new TextView(this);
        hint.setText("手机需与运行服务的电脑连接同一 WiFi。\n填写电脑地址，例如 http://192.168.1.5:8000/");
        hint.setTextSize(13);
        hint.setTextColor(Color.parseColor("#8A9099"));
        hint.setPadding(0, 20, 0, 28);
        layout.addView(hint, fullWidth());

        EditText input = new EditText(this);
        input.setHint(DEFAULT_URL);
        input.setText(DEFAULT_URL);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(input, fullWidth());

        Button connect = new Button(this);
        connect.setText("连接");
        connect.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                input.setError("请填写地址");
                return;
            }
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "http://" + value;
            }
            if (!value.endsWith("/")) {
                value = value + "/";
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, value).apply();
            openChat(value);
        });
        layout.addView(connect, fullWidth());

        setContentView(layout);
    }

    private void openChat(String url) {
        if (webView == null) {
            webView = new WebView(this);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) {
                        runOnUiThread(MainActivity.this::handleConnectFailure);
                    }
                }
            });
        }
        setContentView(webView);
        webView.loadUrl(url);
    }

    private void handleConnectFailure() {
        Toast.makeText(this, "连接失败：请确认电脑已启动服务，且手机与电脑在同一 WiFi", Toast.LENGTH_LONG).show();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_URL).apply();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        showSetup();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
