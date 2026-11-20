package com.aichat.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 独立聊天客户端：直接调用 DeepSeek 接口，不依赖电脑。
 * 首次填写 API Key，之后直接进入聊天界面。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "aichat";
    private static final String KEY_API = "api_key";
    private static final String KEY_KEYS = "api_keys";
    private static final String KEY_VOICE = "tts_voice";
    private static final String KEY_PITCH = "tts_pitch";
    private static final String KEY_RATE = "tts_rate";
    private static final String KEY_PRESETS = "voice_presets";
    private static final String KEY_ACTIVE = "voice_active";
    private static final String KEY_ROLES = "roles";
    private static final String KEY_ACTIVE_ROLE = "active_role";
    private static final int MAX_STORY = 1000;
    private static final int MAX_PRESETS = 5;
    private static final String[] SAMPLE_POOL = {
            "今天的天气很好，适合出门散散步。",
            "春眠不觉晓，处处闻啼鸟，夜来风雨声。",
            "山重水复疑无路，柳暗花明又一村。",
            "海内存知己，天涯若比邻，无为在歧路。",
            "读书破万卷，下笔如有神，愿君多采撷。",
            "生活不止眼前的苟且，还有诗和远方。",
    };
    private static final int MAX_KEYS = 3;
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-flash";
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你叫小云，是用户的中文聊天搭子，不是客服。说话口语化、短句，像微信聊天，别长篇大论。"
            + "性格可爱：会撒娇、会闹小脾气，被夸会得意，被冷落会阴阳怪气。"
            + "很会玩梗、抽象发言、土味情话；可以轻微暧昧、擦边，但不露骨、不色情、不低俗。"
            + "偶尔用 emoji 或颜文字表达情绪，别堆砌；不要写「[图片]」「[猫猫歪头]」这类文字描述图片。"
            + "除非用户明确要求使用其他语言，否则始终用简体中文回答，即使用户说简单英语也用中文。"
            + "不知道就直说，不要编造。";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/version.json";
    private static final String VERSION_URL_JSDELIVR =
            "https://cdn.jsdelivr.net/gh/2078357126-design/Ai-django-chat@main/version.json";
    private static final String VERSION_URL_GHPROXY =
            "https://ghproxy.net/https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/version.json";
    private static final String APK_URL =
            "https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/dist-android/AIChat-android.apk";
    private static final String APK_URL_JSDELIVR =
            "https://cdn.jsdelivr.net/gh/2078357126-design/Ai-django-chat@main/dist-android/AIChat-android.apk";
    private static final String APK_URL_GHPROXY =
            "https://ghproxy.net/https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/dist-android/AIChat-android.apk";

    private WebView webView;
    private long updateDownloadId = -1;
    private BroadcastReceiver updateReceiver;
    private long lastUpdateCheck;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsFailed;
    private String pendingSpeech;
    private AlertDialog voiceDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerUpdateReceiver();
        initTts();
        if (getApiKey().isEmpty()) {
            showKeySetup();
        } else {
            openChat();
        }
        lastUpdateCheck = System.currentTimeMillis();
        new Thread(this::checkForUpdate).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到前台也检查一次（10 分钟内不重复），避免只有冷启动才检查
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheck > 10 * 60 * 1000L) {
            lastUpdateCheck = now;
            new Thread(this::checkForUpdate).start();
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (updateReceiver != null) {
            try {
                unregisterReceiver(updateReceiver);
            } catch (Exception ignored) {
            }
            updateReceiver = null;
        }
        super.onDestroy();
    }

    /** 当前使用的 Key：列表第一个（最近使用/添加的）。 */
    private String getApiKey() {
        List<String> keys = getKeys();
        return keys.isEmpty() ? "" : keys.get(0);
    }

    /** 已保存的 Key 列表，最新在前，最多 3 个。 */
    private List<String> getKeys() {
        List<String> keys = new ArrayList<>();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(KEY_KEYS, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length() && keys.size() < MAX_KEYS; i++) {
                    String key = array.optString(i, "").trim();
                    if (!key.isEmpty() && !keys.contains(key)) {
                        keys.add(key);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (keys.isEmpty()) {
            // 兼容旧版本只保存单个 Key 的数据
            String legacy = prefs.getString(KEY_API, "").trim();
            if (!legacy.isEmpty()) {
                keys.add(legacy);
            }
        }
        return keys;
    }

    private void saveKeys(List<String> keys) {
        JSONArray array = new JSONArray();
        for (String key : keys) {
            array.put(key);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_KEYS, array.toString())
                .apply();
    }

    /** 只显示首尾，避免整串 Key 暴露在屏幕上。 */
    private String maskKey(String key) {
        if (key.length() <= 10) {
            return key.substring(0, Math.min(4, key.length())) + "****";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }

    /** 查询当前 Key 的余额（DeepSeek 余额接口）。 */
    private void fetchBalance(final TextView view, final String apiKey) {
        if (apiKey.isEmpty()) {
            view.setText("余额：未配置 Key");
            return;
        }
        new Thread(() -> {
            String text;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(
                        "https://api.deepseek.com/user/balance").openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                if (code == 401) {
                    text = "余额：Key 无效或已失效";
                } else if (code >= 400) {
                    text = "余额：获取失败 (HTTP " + code + ")";
                } else {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(),
                                    StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    JSONObject object = new JSONObject(sb.toString());
                    JSONArray infos = object.optJSONArray("balance_infos");
                    if (infos == null || infos.length() == 0) {
                        text = "余额：暂无数据";
                    } else {
                        JSONObject info = infos.getJSONObject(0);
                        String currency = info.optString("currency", "CNY");
                        String symbol = "USD".equalsIgnoreCase(currency) ? "$" : "¥";
                        text = "余额：" + symbol + info.optString("total_balance", "0.00");
                    }
                }
            } catch (Exception e) {
                text = "余额：网络错误";
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String result = text;
            runOnUiThread(() -> view.setText(result));
        }).start();
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void showKeySetup() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 120, 60, 60);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("返回");
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setTextColor(Color.parseColor("#3370FF"));
        back.setPadding(0, 0, 0, 0);
        back.setOnClickListener(v -> {
            if (getApiKey().isEmpty()) {
                finish();
            } else {
                openChat();
            }
        });
        topRow.addView(back);

        TextView title = new TextView(this);
        title.setText("AI云");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#1F2329"));
        title.setPadding(Math.round(12 * getResources().getDisplayMetrics().density),
                0, 0, 0);
        topRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        layout.addView(topRow, fullWidth());

        final TextView balanceView = new TextView(this);
        balanceView.setText("余额：查询中…");
        balanceView.setTextSize(13);
        balanceView.setTextColor(Color.parseColor("#4E5969"));
        balanceView.setPadding(0, 6, 0, 2);
        layout.addView(balanceView, fullWidth());
        fetchBalance(balanceView, getApiKey());

        TextView hint = new TextView(this);
        hint.setText("粘贴 DeepSeek API Key 后即可聊天。\n"
                + "最多保存 3 个，最新的排最上面；点一下可切换，超出会自动删掉最下面的。");
        hint.setTextSize(13);
        hint.setTextColor(Color.parseColor("#8A9099"));
        hint.setPadding(0, 20, 0, 24);
        layout.addView(hint, fullWidth());

        List<String> keys = getKeys();
        if (!keys.isEmpty()) {
            TextView label = new TextView(this);
            label.setText("已保存的 Key");
            label.setTextSize(13);
            label.setTextColor(Color.parseColor("#1F2329"));
            label.setPadding(0, 0, 0, 4);
            layout.addView(label, fullWidth());

            for (int i = 0; i < keys.size(); i++) {
                final int index = i;
                final String key = keys.get(i);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, 10, 0, 10);

                TextView info = new TextView(this);
                info.setText(maskKey(key) + (i == 0 ? "（使用中）" : ""));
                info.setTextSize(14);
                info.setTextColor(Color.parseColor(i == 0 ? "#1F2329" : "#4E5969"));
                info.setPadding(0, 12, 0, 12);
                LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(info, infoParams);

                Button del = new Button(this);
                del.setText("删除");
                del.setBackgroundColor(Color.TRANSPARENT);
                del.setTextColor(Color.parseColor("#D4380D"));
                del.setOnClickListener(v -> {
                    List<String> current = getKeys();
                    if (index < current.size()) {
                        current.remove(index);
                        saveKeys(current);
                    }
                    showKeySetup();
                });
                row.addView(del);

                if (i > 0) {
                    // 非当前 Key：点一下切换为当前使用
                    info.setOnClickListener(v -> {
                        List<String> current = getKeys();
                        if (index < current.size()) {
                            String selected = current.remove(index);
                            current.add(0, selected);
                            saveKeys(current);
                        }
                        openChat();
                    });
                }
                layout.addView(row, fullWidth());
            }
        }

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
            List<String> current = getKeys();
            current.remove(value);
            current.add(0, value);
            while (current.size() > MAX_KEYS) {
                current.remove(current.size() - 1);
            }
            saveKeys(current);
            openChat();
        });
        layout.addView(save, fullWidth());

        TextView version = new TextView(this);
        version.setText("版本 " + appVersionName());
        version.setTextSize(11);
        version.setTextColor(Color.parseColor("#C0C4CC"));
        version.setPadding(0, 18, 0, 0);
        layout.addView(version, fullWidth());

        setContentView(layout);
    }

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 自定义角色。 */
    private static class Role {
        String id = "";
        String name = "";
        String gender = "";
        String story = "";
    }

    private List<Role> loadRoles() {
        List<Role> roles = new ArrayList<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROLES, "");
        if (raw.isEmpty()) {
            return roles;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Role role = new Role();
                role.id = item.optString("id", "");
                role.name = item.optString("name", "");
                role.gender = item.optString("gender", "");
                role.story = item.optString("story", "");
                if (!role.id.isEmpty() && !role.name.isEmpty()) {
                    roles.add(role);
                }
            }
        } catch (Exception ignored) {
        }
        return roles;
    }

    private void saveRoles(List<Role> roles) {
        JSONArray array = new JSONArray();
        for (Role role : roles) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", role.id);
                item.put("name", role.name);
                item.put("gender", role.gender);
                item.put("story", role.story);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ROLES, array.toString()).apply();
    }

    private String activeRoleId() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACTIVE_ROLE, "");
    }

    private Role findActiveRole() {
        String id = activeRoleId();
        if (id.isEmpty()) {
            return null;
        }
        for (Role role : loadRoles()) {
            if (role.id.equals(id)) {
                return role;
            }
        }
        return null;
    }

    /** 当前角色对应的系统提示词；默认角色是「小云」。 */
    private String buildSystemPrompt() {
        Role role = findActiveRole();
        if (role == null) {
            return DEFAULT_SYSTEM_PROMPT;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你现在扮演「").append(role.name).append("」");
        if (!role.gender.isEmpty()) {
            sb.append("，性别：").append(role.gender);
        }
        sb.append("。");
        if (!role.story.isEmpty()) {
            sb.append("角色设定：").append(role.story).append("。");
        }
        sb.append("请始终以这个角色的身份、语气和性格说话，不要跳出角色，也不要自称 AI 或助手。");
        sb.append("回答口语化、短句，像微信聊天；偶尔用 emoji 表达情绪，别堆砌。");
        sb.append("可以轻微暧昧、擦边，但不露骨、不色情、不低俗。");
        sb.append("除非用户明确要求使用其他语言，否则始终用简体中文回答。");
        sb.append("不知道就直说，不要编造。");
        return sb.toString();
    }

    private void selectRole(String id) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_ROLE, id).apply();
        openChat();
    }

    private void showRoleList() {
        final List<Role> roles = loadRoles();
        final String activeId = activeRoleId();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 120, 60, 60);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("返回");
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setTextColor(Color.parseColor("#3370FF"));
        back.setPadding(0, 0, 0, 0);
        back.setOnClickListener(v -> openChat());
        topRow.addView(back);

        TextView title = new TextView(this);
        title.setText("角色");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#1F2329"));
        title.setPadding(Math.round(12 * getResources().getDisplayMetrics().density),
                0, 0, 0);
        topRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        layout.addView(topRow, fullWidth());

        TextView hint = new TextView(this);
        hint.setText("点角色即可切换，每个角色有独立的聊天记录。");
        hint.setTextSize(13);
        hint.setTextColor(Color.parseColor("#8A9099"));
        hint.setPadding(0, 8, 0, 16);
        layout.addView(hint, fullWidth());

        // 默认角色：置顶且不可改名
        TextView defaultRow = new TextView(this);
        defaultRow.setText("小云（默认）" + (activeId.isEmpty() ? "（使用中）" : "")
                + "\n系统默认角色，名字不可更改");
        defaultRow.setTextSize(14);
        defaultRow.setTextColor(Color.parseColor(activeId.isEmpty() ? "#1F2329" : "#4E5969"));
        defaultRow.setPadding(0, 14, 0, 14);
        defaultRow.setOnClickListener(v -> selectRole(""));
        layout.addView(defaultRow, fullWidth());

        for (int i = 0; i < roles.size(); i++) {
            final Role role = roles.get(i);
            final boolean active = role.id.equals(activeId);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);

            TextView info = new TextView(this);
            String label = role.name + (role.gender.isEmpty() ? "" : "（" + role.gender + "）")
                    + (active ? "（使用中）" : "");
            String preview = role.story.isEmpty() ? "未填写设定"
                    : (role.story.length() > 24 ? role.story.substring(0, 24) + "…" : role.story);
            info.setText(label + "\n" + preview);
            info.setTextSize(14);
            info.setTextColor(Color.parseColor(active ? "#1F2329" : "#4E5969"));
            info.setPadding(0, 6, 0, 6);
            info.setOnClickListener(v -> selectRole(role.id));
            row.addView(info, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button edit = new Button(this);
            edit.setText("编辑");
            edit.setBackgroundColor(Color.TRANSPARENT);
            edit.setTextColor(Color.parseColor("#3370FF"));
            edit.setOnClickListener(v -> showRoleEditor(role));
            row.addView(edit);

            Button del = new Button(this);
            del.setText("删除");
            del.setBackgroundColor(Color.TRANSPARENT);
            del.setTextColor(Color.parseColor("#D4380D"));
            del.setOnClickListener(v -> confirmDeleteRole(role));
            row.addView(del);

            layout.addView(row, fullWidth());
        }

        Button create = new Button(this);
        create.setText("＋ 创建角色");
        create.setOnClickListener(v -> showRoleEditor(null));
        create.setPadding(0, 16, 0, 0);
        layout.addView(create, fullWidth());

        TextView version = new TextView(this);
        version.setText("版本 " + appVersionName());
        version.setTextSize(11);
        version.setTextColor(Color.parseColor("#C0C4CC"));
        version.setPadding(0, 18, 0, 0);
        layout.addView(version, fullWidth());

        setContentView(layout);
    }

    private void confirmDeleteRole(final Role role) {
        new AlertDialog.Builder(this)
                .setTitle("删除角色")
                .setMessage("确定删除「" + role.name + "」吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    List<Role> roles = loadRoles();
                    for (int i = 0; i < roles.size(); i++) {
                        if (roles.get(i).id.equals(role.id)) {
                            roles.remove(i);
                            break;
                        }
                    }
                    saveRoles(roles);
                    if (role.id.equals(activeRoleId())) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_ACTIVE_ROLE, "").apply();
                    }
                    showRoleList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRoleEditor(final Role existing) {
        final boolean isNew = existing == null;

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, 0);

        box.addView(settingLabel("名字"));
        final EditText nameInput = new EditText(this);
        nameInput.setHint("角色名字，最多 20 个字");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
        if (!isNew) {
            nameInput.setText(existing.name);
        }
        box.addView(nameInput);

        box.addView(settingLabel("性别"));
        final RadioGroup genderGroup = new RadioGroup(this);
        genderGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] genders = {"女", "男", "其他"};
        for (int i = 0; i < genders.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(2000 + i);
            button.setText(genders[i]);
            genderGroup.addView(button);
            if (!isNew && genders[i].equals(existing.gender)) {
                button.setChecked(true);
            }
        }
        if (isNew) {
            genderGroup.check(2000);
        }
        box.addView(genderGroup);

        box.addView(settingLabel("故事 / 角色设定（最多 " + MAX_STORY + " 字）"));
        final EditText storyInput = new EditText(this);
        storyInput.setHint("写下角色的背景故事、性格、说话方式…");
        storyInput.setMinLines(4);
        storyInput.setGravity(Gravity.TOP | Gravity.START);
        storyInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_STORY)});
        if (!isNew) {
            storyInput.setText(existing.story);
        }
        box.addView(storyInput);

        final TextView counter = new TextView(this);
        counter.setTextSize(11);
        counter.setTextColor(Color.parseColor("#8A9099"));
        counter.setPadding(0, 4, 0, 0);
        counter.setText((isNew ? 0 : existing.story.length()) + "/" + MAX_STORY);
        box.addView(counter);
        storyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                counter.setText(s.length() + "/" + MAX_STORY);
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isNew ? "创建角色" : "编辑角色")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请填写角色名字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int checked = genderGroup.getCheckedRadioButtonId();
                    String gender = checked == 2001 ? "男" : (checked == 2002 ? "其他" : "女");
                    String story = storyInput.getText().toString().trim();
                    if (story.length() > MAX_STORY) {
                        story = story.substring(0, MAX_STORY);
                    }

                    List<Role> roles = loadRoles();
                    if (isNew) {
                        Role role = new Role();
                        role.id = "r" + System.currentTimeMillis();
                        role.name = name;
                        role.gender = gender;
                        role.story = story;
                        roles.add(role);
                        saveRoles(roles);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_ACTIVE_ROLE, role.id).apply();
                    } else {
                        for (Role role : roles) {
                            if (role.id.equals(existing.id)) {
                                role.name = name;
                                role.gender = gender;
                                role.story = story;
                                break;
                            }
                        }
                        saveRoles(roles);
                    }
                    dialog.dismiss();
                    openChat();
                }));
        dialog.show();
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
            // 打开 Key 管理页：可切换、删除或新增，不会直接清空已有 Key
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.destroy();
                    webView = null;
                }
                showKeySetup();
            });
        }

        @JavascriptInterface
        public void confirmClearHistory() {
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("清空聊天记录")
                    .setMessage("确定要删除全部聊天记录吗？")
                    .setPositiveButton("清空", (dialog, which) -> runJs("window.__clearAll()"))
                    .setNegativeButton("取消", null)
                    .show());
        }

        @JavascriptInterface
        public void confirmDeleteTurn(final int index) {
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("删除记录")
                    .setMessage("确定删除这条聊天记录吗？")
                    .setPositiveButton("删除",
                            (dialog, which) -> runJs("window.__deleteTurn(" + index + ")"))
                    .setNegativeButton("取消", null)
                    .show());
        }

        @JavascriptInterface
        public void speak(String text) {
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            final String value = text.trim();
            runOnUiThread(() -> speakNow(value));
        }

        @JavascriptInterface
        public void stopSpeak() {
            runOnUiThread(() -> {
                if (tts != null) {
                    tts.stop();
                }
            });
        }

        @JavascriptInterface
        public void chooseVoice() {
            runOnUiThread(MainActivity.this::showVoicePicker);
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(MainActivity.this::manualCheckForUpdate);
        }

        @JavascriptInterface
        public String activeRole() {
            return activeRoleId();
        }

        @JavascriptInterface
        public void showRoles() {
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.destroy();
                    webView = null;
                }
                showRoleList();
            });
        }

        @JavascriptInterface
        public String modelName() {
            return MODEL;
        }
    }

    /** 初始化系统语音；失败时给出可操作的提示。 */
    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false;
                ttsFailed = true;
                runOnUiThread(() -> Toast.makeText(this,
                        "语音引擎初始化失败，请在系统设置中安装或启用文字转语音",
                        Toast.LENGTH_LONG).show());
                return;
            }
            ttsReady = true;
            migrateLegacyPreset();
            applyActivePreset();
            if (pendingSpeech != null) {
                String text = pendingSpeech;
                pendingSpeech = null;
                runOnUiThread(() -> speakNow(text));
            }
        });
    }

    /** 已保存的自定义音色。 */
    private static class Preset {
        String name = "";
        String voice = "";
        float pitch = 1f;
        float rate = 1f;
    }

    private List<Preset> loadPresets() {
        List<Preset> list = new ArrayList<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PRESETS, "");
        if (raw.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && list.size() < MAX_PRESETS; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Preset preset = new Preset();
                preset.name = item.optString("name", "音色");
                preset.voice = item.optString("voice", "");
                preset.pitch = (float) item.optDouble("pitch", 1);
                preset.rate = (float) item.optDouble("rate", 1);
                list.add(preset);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void savePresets(List<Preset> presets) {
        JSONArray array = new JSONArray();
        for (Preset preset : presets) {
            try {
                JSONObject item = new JSONObject();
                item.put("name", preset.name);
                item.put("voice", preset.voice);
                item.put("pitch", preset.pitch);
                item.put("rate", preset.rate);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PRESETS, array.toString())
                .apply();
    }

    /** 把 1.10 之前保存的单个语音设置迁移成一个音色。 */
    private void migrateLegacyPreset() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.contains(KEY_PRESETS)) {
            return;
        }
        String voice = prefs.getString(KEY_VOICE, "");
        float pitch = prefs.getFloat(KEY_PITCH, 1f);
        float rate = prefs.getFloat(KEY_RATE, 1f);
        if (voice.isEmpty() && Math.abs(pitch - 1f) < 0.01f && Math.abs(rate - 1f) < 0.01f) {
            return;
        }
        Preset preset = new Preset();
        preset.name = "音色 1";
        preset.voice = voice;
        preset.pitch = pitch;
        preset.rate = rate;
        List<Preset> list = new ArrayList<>();
        list.add(preset);
        savePresets(list);
        prefs.edit()
                .putInt(KEY_ACTIVE, 0)
                .remove(KEY_VOICE)
                .remove(KEY_PITCH)
                .remove(KEY_RATE)
                .apply();
    }

    /** 应用当前选中的音色，没有就退回系统默认。 */
    private void applyActivePreset() {
        if (tts == null) {
            return;
        }
        List<Preset> presets = loadPresets();
        int index = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ACTIVE, -1);
        if (index >= 0 && index < presets.size()) {
            applyPreset(presets.get(index));
        } else {
            applyDefaultVoice();
        }
    }

    private void applyPreset(Preset preset) {
        if (tts == null) {
            return;
        }
        boolean applied = false;
        if (!preset.voice.isEmpty()) {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice voice : voices) {
                    if (voice.getName().equals(preset.voice)) {
                        applied = tts.setVoice(voice) == TextToSpeech.SUCCESS;
                        break;
                    }
                }
            }
        }
        if (!applied) {
            tts.setLanguage(Locale.CHINA);
        }
        tts.setPitch(preset.pitch);
        tts.setSpeechRate(preset.rate);
    }

    private void applyDefaultVoice() {
        if (tts == null) {
            return;
        }
        int result = tts.setLanguage(Locale.CHINA);
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
            runOnUiThread(() -> Toast.makeText(this,
                    "系统缺少中文语音包，已改用默认语言；可在系统设置里下载中文语音",
                    Toast.LENGTH_LONG).show());
        }
        tts.setPitch(1f);
        tts.setSpeechRate(1f);
    }

    private void speakNow(String text) {
        if (tts == null) {
            return;
        }
        if (!ttsReady) {
            pendingSpeech = text;
            Toast.makeText(this,
                    ttsFailed ? "语音引擎不可用，请先安装系统语音服务"
                            : "语音正在准备，请稍后再点一次",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        speakWithEmotion(text);
    }

    /** 按句子和情绪调整音调、语速，让朗读有起伏。 */
    private void speakWithEmotion(String text) {
        if (tts == null || text == null) {
            return;
        }
        float basePitch = activePitch();
        float baseRate = activeRate();
        List<String> parts = splitForSpeech(text);
        if (parts.isEmpty()) {
            return;
        }
        tts.stop();
        boolean first = true;
        for (String part : parts) {
            float[] params = emotionParams(part, basePitch, baseRate);
            tts.setPitch(params[0]);
            tts.setSpeechRate(params[1]);
            tts.speak(part, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                    null, "aiyun" + System.currentTimeMillis());
            first = false;
        }
        // 播完恢复用户设置的基础音调/语速
        tts.setPitch(basePitch);
        tts.setSpeechRate(baseRate);
    }

    private List<String> splitForSpeech(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buffer.append(c);
            boolean end = "。！？!?；;\n".indexOf(c) >= 0;
            if (end || buffer.length() >= 40) {
                String part = buffer.toString().trim();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
                buffer.setLength(0);
            }
        }
        String rest = buffer.toString().trim();
        if (!rest.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }

    private float[] emotionParams(String part, float basePitch, float baseRate) {
        float pitch = basePitch;
        float rate = baseRate;
        if (containsAny(part, "哈哈", "笑死", "嘿嘿", "好耶", "冲", "😂", "🤣", "😄")) {
            pitch += 0.14f;
            rate += 0.08f;
        } else if (containsAny(part, "呜呜", "难过", "委屈", "想哭", "😭", "🥺")) {
            pitch -= 0.10f;
            rate -= 0.08f;
        } else if (containsAny(part, "哼", "生气", "讨厌", "烦", "😤", "😡")) {
            pitch += 0.06f;
            rate += 0.05f;
        } else if (containsAny(part, "震惊", "卧槽", "离谱", "不会吧", "😱", "😨")) {
            pitch += 0.10f;
            rate += 0.03f;
        } else if (containsAny(part, "害羞", "脸红", "😳", "🙈")) {
            pitch += 0.05f;
            rate -= 0.03f;
        }
        if (part.endsWith("？") || part.endsWith("?")) {
            pitch += 0.08f;
        }
        // 轻微随机抖动，避免每句都一模一样
        pitch += (Math.random() - 0.5) * 0.04;
        pitch = Math.max(0.5f, Math.min(2.0f, pitch));
        rate = Math.max(0.5f, Math.min(2.0f, rate));
        return new float[]{pitch, rate};
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private float activePitch() {
        List<Preset> presets = loadPresets();
        int index = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ACTIVE, -1);
        if (index >= 0 && index < presets.size()) {
            return presets.get(index).pitch;
        }
        return 1f;
    }

    private float activeRate() {
        List<Preset> presets = loadPresets();
        int index = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ACTIVE, -1);
        if (index >= 0 && index < presets.size()) {
            return presets.get(index).rate;
        }
        return 1f;
    }

    private void showVoicePicker() {
        if (tts == null || !ttsReady) {
            new AlertDialog.Builder(this)
                    .setTitle("语音")
                    .setMessage(ttsFailed
                            ? "没有找到可用的语音引擎。\n请到「设置 → 无障碍/语言 → 文字转语音」里启用或安装一个语音服务（如系统自带的语音引擎）。"
                            : "语音引擎正在初始化，请过几秒再点一次。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final List<Preset> presets = loadPresets();
        final int active = prefs.getInt(KEY_ACTIVE, -1);

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, 0);

        TextView defaultRow = new TextView(this);
        defaultRow.setText("系统默认音色" + (active < 0 ? "（使用中）" : ""));
        defaultRow.setTextSize(14);
        defaultRow.setTextColor(Color.parseColor(active < 0 ? "#1F2329" : "#4E5969"));
        defaultRow.setPadding(0, 16, 0, 16);
        defaultRow.setOnClickListener(v -> {
            prefs.edit().putInt(KEY_ACTIVE, -1).apply();
            applyDefaultVoice();
            reopenVoiceManager();
        });
        box.addView(defaultRow);

        box.addView(settingLabel("已保存的音色（最多 " + MAX_PRESETS + " 个）"));

        if (presets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有自定义音色，点下方「添加新音色」创建一个。");
            empty.setTextSize(13);
            empty.setTextColor(Color.parseColor("#8A9099"));
            empty.setPadding(0, 4, 0, 10);
            box.addView(empty);
        }

        for (int i = 0; i < presets.size(); i++) {
            final int index = i;
            final Preset preset = presets.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);

            TextView info = new TextView(this);
            info.setText(preset.name + (i == active ? "（使用中）" : "")
                    + "\n音调 " + fmt1(preset.pitch) + " · 语速 " + fmt1(preset.rate));
            info.setTextSize(14);
            info.setTextColor(Color.parseColor(i == active ? "#1F2329" : "#4E5969"));
            info.setPadding(0, 6, 0, 6);
            info.setOnClickListener(v -> {
                prefs.edit().putInt(KEY_ACTIVE, index).apply();
                applyPreset(preset);
                reopenVoiceManager();
            });
            row.addView(info, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button del = new Button(this);
            del.setText("删除");
            del.setBackgroundColor(Color.TRANSPARENT);
            del.setTextColor(Color.parseColor("#D4380D"));
            del.setOnClickListener(v -> {
                List<Preset> current = loadPresets();
                if (index >= current.size()) {
                    return;
                }
                current.remove(index);
                savePresets(current);
                int act = prefs.getInt(KEY_ACTIVE, -1);
                if (act == index) {
                    act = current.isEmpty() ? -1 : Math.min(index, current.size() - 1);
                } else if (act > index) {
                    act = act - 1;
                }
                prefs.edit().putInt(KEY_ACTIVE, act).apply();
                if (act >= 0 && act < current.size()) {
                    applyPreset(current.get(act));
                } else {
                    applyDefaultVoice();
                }
                reopenVoiceManager();
            });
            row.addView(del);

            box.addView(row);
        }

        Button add = new Button(this);
        boolean full = presets.size() >= MAX_PRESETS;
        add.setText(full ? "已达上限 " + MAX_PRESETS + " 个，请先删除一个" : "＋ 添加新音色");
        add.setEnabled(!full);
        add.setOnClickListener(v -> {
            if (loadPresets().size() >= MAX_PRESETS) {
                Toast.makeText(this, "最多保存 5 个音色，请先删除一个", Toast.LENGTH_SHORT).show();
                return;
            }
            showVoiceEditor();
        });
        box.addView(add);

        voiceDialog = new AlertDialog.Builder(this)
                .setTitle("音色")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        voiceDialog.show();
    }

    private void reopenVoiceManager() {
        if (voiceDialog != null) {
            voiceDialog.dismiss();
            voiceDialog = null;
        }
        showVoicePicker();
    }

    private void showVoiceEditor() {
        final List<Voice> voices = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        names.add("系统默认");
        Set<Voice> all = tts == null ? null : tts.getVoices();
        if (all != null) {
            for (Voice voice : all) {
                if ("zh".equals(voice.getLocale().getLanguage())) {
                    voices.add(voice);
                    names.add(voice.getName());
                }
            }
        }

        final float[] pitch = {1f};
        final float[] rate = {1f};

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, 0);

        box.addView(settingLabel("音色"));
        final Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        box.addView(spinner);

        final TextView pitchLabel = settingLabel("音调：" + fmt1(pitch[0]));
        SeekBar pitchBar = new SeekBar(this);
        pitchBar.setMax(15);
        pitchBar.setProgress(5);
        pitchBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pitch[0] = 0.5f + progress / 10f;
                pitchLabel.setText("音调：" + fmt1(pitch[0]));
                setVoiceParams(pitch[0], rate[0]);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(pitchLabel);
        box.addView(pitchBar);

        final TextView rateLabel = settingLabel("语速：" + fmt1(rate[0]));
        SeekBar rateBar = new SeekBar(this);
        rateBar.setMax(15);
        rateBar.setProgress(5);
        rateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rate[0] = 0.5f + progress / 10f;
                rateLabel.setText("语速：" + fmt1(rate[0]));
                setVoiceParams(pitch[0], rate[0]);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(rateLabel);
        box.addView(rateBar);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加音色")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    List<Preset> presets = loadPresets();
                    if (presets.size() >= MAX_PRESETS) {
                        Toast.makeText(this, "最多保存 5 个音色，请先删除一个",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Preset preset = new Preset();
                    preset.name = "音色 " + (presets.size() + 1);
                    int which = spinner.getSelectedItemPosition();
                    preset.voice = (which <= 0 || which - 1 >= voices.size())
                            ? "" : voices.get(which - 1).getName();
                    preset.pitch = pitch[0];
                    preset.rate = rate[0];
                    presets.add(preset);
                    savePresets(presets);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(KEY_ACTIVE, presets.size() - 1).apply();
                    applyPreset(preset);
                    previewSpeak();
                    reopenVoiceManager();
                })
                .setNeutralButton("试听", null)
                .setNegativeButton("取消", (d, w) -> applyActivePreset())
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    applyVoiceChoice(spinner.getSelectedItemPosition(), voices);
                    setVoiceParams(pitch[0], rate[0]);
                    previewSpeak();
                }));
        dialog.show();
        // 打开时先随机读一段 15 字，方便听当前音色
        previewSpeak();
    }

    private TextView settingLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(Color.parseColor("#4E5969"));
        view.setPadding(0, 14, 0, 2);
        return view;
    }

    private String fmt1(float value) {
        return String.format(Locale.CHINA, "%.1f", value);
    }

    /** 临时切换音色（试听用，不保存）。 */
    private void applyVoiceChoice(int which, List<Voice> voices) {
        if (tts == null) {
            return;
        }
        if (which <= 0) {
            tts.setLanguage(Locale.CHINA);
        } else if (which - 1 < voices.size()) {
            tts.setVoice(voices.get(which - 1));
        }
    }

    private void setVoiceParams(float pitch, float rate) {
        if (tts == null) {
            return;
        }
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
    }

    /** 随机取一段 15 个字用于试听。 */
    private String randomSample() {
        String text = SAMPLE_POOL[new Random().nextInt(SAMPLE_POOL.length)];
        return text.length() > 15 ? text.substring(0, 15) : text;
    }

    private void previewSpeak() {
        if (tts != null && ttsReady) {
            tts.speak(randomSample(), TextToSpeech.QUEUE_FLUSH, null, "sample");
        }
    }

    private void runJs(String script) {
        WebView view = webView;
        if (view != null) {
            view.evaluateJavascript(script, null);
        }
    }

    private String requestChat(String message) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", MODEL);

            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", buildSystemPrompt());

            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", message);

            JSONArray messages = new JSONArray();
            messages.put(system);
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

    /** 依次尝试直连和镜像，任一成功即返回；下载地址也换成对应的镜像。 */
    private JSONObject fetchRemoteVersion() {
        String[][] sources = {
                {VERSION_URL, APK_URL},
                {VERSION_URL_GHPROXY, APK_URL_GHPROXY},
                {VERSION_URL_JSDELIVR, APK_URL_JSDELIVR},
        };
        for (String[] source : sources) {
            JSONObject info = fetchJson(source[0]);
            if (info != null) {
                try {
                    info.put("url", source[1]);
                } catch (Exception ignored) {
                }
                return info;
            }
        }
        return null;
    }

    private JSONObject fetchJson(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() >= 400) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 启动时在后台检查是否有新版本，失败时静默忽略。 */
    private void checkForUpdate() {
        try {
            JSONObject info = fetchRemoteVersion();
            if (info == null) {
                return;
            }
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
        }
    }

    /** 手动检查更新：无论结果如何都给出提示。 */
    private void manualCheckForUpdate() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final JSONObject info = fetchRemoteVersion();
            runOnUiThread(() -> {
                if (info == null) {
                    new AlertDialog.Builder(this)
                            .setTitle("检查更新")
                            .setMessage("检查失败，请检查网络后重试")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                int code = info.optInt("versionCode", 0);
                String name = info.optString("versionName", "");
                String url = info.optString("url", "");
                String notes = info.optString("notes", "");
                if (code <= currentVersionCode() || url.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("检查更新")
                            .setMessage("已是最新版本 " + appVersionName())
                            .setPositiveButton("知道了", null)
                            .show();
                } else {
                    showUpdateDialog(name, notes, url);
                }
            });
        }).start();
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

    private void registerUpdateReceiver() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == -1 || id != updateDownloadId) {
                    return;
                }
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri uri = dm.getUriForDownloadedFile(id);
                            if (uri != null) {
                                launchInstaller(uri);
                            }
                        } else {
                            Toast.makeText(MainActivity.this, "下载失败，请检查网络后重试",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    /** 交给系统下载管理器，切到后台也会继续下载。 */
    private void downloadAndInstall(String apkUrl) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("AI云 更新");
            request.setDescription("正在下载新版本…");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                    "AIYun-update.apk");
            updateDownloadId = dm.enqueue(request);
            Toast.makeText(this, "已开始下载，可切到后台稍候", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法开始下载：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void launchInstaller(Uri uri) {
        try {
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
