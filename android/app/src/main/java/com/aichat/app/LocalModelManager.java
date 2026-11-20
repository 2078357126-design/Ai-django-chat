package com.aichat.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地模型：清单读取、下载（支持断点续传）、删除与当前选择。
 *
 * 模型文件不进安装包，全部下载到应用私有目录 files/models。
 */
class LocalModelManager {

    private static final String PREFS = "aichat";
    private static final String KEY_ACTIVE = "local_model";
    static final String CLOUD = "cloud";

    interface Listener {
        void onProgress(String id, int percent, boolean done, String error);
    }

    private final Context context;
    private final File dir;
    private final Map<String, Integer> progress = new HashMap<>();
    private String downloadingId;
    private volatile boolean cancelFlag;

    LocalModelManager(Context context) {
        this.context = context.getApplicationContext();
        this.dir = new File(context.getFilesDir(), "models");
    }

    File dir() {
        return dir;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读取打包进 assets 的模型清单。 */
    private JSONArray manifest() {
        try (InputStream in = context.getAssets().open("models.json")) {
            byte[] buffer = new byte[8192];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, "UTF-8"));
            }
            return new JSONObject(text.toString()).optJSONArray("models");
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONObject findModel(String id) {
        JSONArray models = manifest();
        for (int i = 0; i < models.length(); i++) {
            JSONObject item = models.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                return item;
            }
        }
        return null;
    }

    private List<JSONObject> filesOf(JSONObject model) {
        List<JSONObject> list = new ArrayList<>();
        JSONArray files = model.optJSONArray("files");
        if (files == null) {
            return list;
        }
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null) {
                list.add(file);
            }
        }
        return list;
    }

    File fileFor(String name) {
        return new File(dir, name);
    }

    boolean isInstalled(JSONObject model) {
        for (JSONObject file : filesOf(model)) {
            File target = fileFor(file.optString("name"));
            if (!target.isFile() || target.length() != file.optLong("size")) {
                return false;
            }
        }
        return true;
    }

    boolean isInstalled(String id) {
        JSONObject model = findModel(id);
        return model != null && isInstalled(model);
    }

    /** 没有云端 Key 时，优先用一个已经下载好的本地模型。 */
    String firstInstalledId() {
        JSONArray models = manifest();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && isInstalled(model)) {
                return model.optString("id");
            }
        }
        return CLOUD;
    }

    String activeId() {
        return prefs().getString(KEY_ACTIVE, CLOUD);
    }

    void select(String id) {
        prefs().edit().putString(KEY_ACTIVE, id == null ? CLOUD : id).apply();
    }

    String mainPath(String id) {
        JSONObject model = findModel(id);
        return model == null ? null : fileFor(model.optString("main")).getAbsolutePath();
    }

    String mmprojPath(String id) {
        JSONObject model = findModel(id);
        if (model == null) {
            return null;
        }
        String name = model.optString("mmproj", "");
        return name.isEmpty() ? null : fileFor(name).getAbsolutePath();
    }

    boolean isVision(String id) {
        JSONObject model = findModel(id);
        return model != null && model.optBoolean("vision", false);
    }

    String listJson() {
        JSONArray out = new JSONArray();
        JSONArray models = manifest();
        String active = activeId();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null) {
                continue;
            }
            String id = model.optString("id");
            try {
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("name", model.optString("name"));
                item.put("desc", model.optString("desc"));
                item.put("ram", model.optString("ram"));
                item.put("vision", model.optBoolean("vision", false));
                long size = 0;
                for (JSONObject file : filesOf(model)) {
                    size += file.optLong("size");
                }
                item.put("size", size);
                item.put("installed", isInstalled(model));
                item.put("active", id.equals(active));
                item.put("downloading", id.equals(downloadingId));
                Integer p = progress.get(id);
                item.put("progress", p == null ? 0 : p);
                out.put(item);
            } catch (Exception ignored) {
            }
        }
        try {
            JSONObject cloud = new JSONObject();
            cloud.put("id", CLOUD);
            cloud.put("name", "DeepSeek 云端");
            cloud.put("desc", "需要 API Key，回答质量最好");
            cloud.put("ram", "不占内存");
            cloud.put("vision", false);
            cloud.put("size", 0);
            cloud.put("installed", true);
            cloud.put("active", CLOUD.equals(active));
            cloud.put("downloading", false);
            cloud.put("progress", 100);
            JSONArray withCloud = new JSONArray();
            withCloud.put(cloud);
            for (int i = 0; i < out.length(); i++) {
                withCloud.put(out.opt(i));
            }
            return withCloud.toString();
        } catch (Exception e) {
            return out.toString();
        }
    }

    void download(final String id, final Listener listener) {
        if (downloadingId != null) {
            listener.onProgress(id, progressOf(id), false, "已有下载任务在进行");
            return;
        }
        final JSONObject model = findModel(id);
        if (model == null) {
            listener.onProgress(id, 0, false, "模型不存在");
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            listener.onProgress(id, 0, false, "无法创建模型目录");
            return;
        }
        downloadingId = id;
        cancelFlag = false;
        progress.put(id, 0);
        new Thread(() -> runDownload(id, model, listener)).start();
    }

    void cancel(String id) {
        if (id.equals(downloadingId)) {
            cancelFlag = true;
        }
    }

    private int progressOf(String id) {
        Integer value = progress.get(id);
        return value == null ? 0 : value;
    }

    private void runDownload(String id, JSONObject model, Listener listener) {
        List<JSONObject> files = filesOf(model);
        long totalBytes = 0;
        for (JSONObject file : files) {
            totalBytes += file.optLong("size");
        }
        long doneBytes = 0;
        String error = null;

        for (JSONObject file : files) {
            if (cancelFlag) {
                break;
            }
            String name = file.optString("name");
            long size = file.optLong("size");
            File target = fileFor(name);
            if (target.isFile() && target.length() == size) {
                doneBytes += size;
                continue;
            }
            long before = doneBytes;
            String message = downloadFile(file, size, doneBytes, totalBytes, listener, id);
            if (message != null) {
                error = message;
                break;
            }
            doneBytes = before + size;
            progress.put(id, (int) (doneBytes * 100 / Math.max(1, totalBytes)));
            listener.onProgress(id, progressOf(id), false, null);
        }

        downloadingId = null;
        if (cancelFlag) {
            cancelFlag = false;
            listener.onProgress(id, progressOf(id), false, "已暂停");
        } else if (error != null) {
            listener.onProgress(id, progressOf(id), false, error);
        } else {
            progress.put(id, 100);
            listener.onProgress(id, 100, true, null);
        }
    }

    /** 下载单个文件；返回 null 表示成功，否则为错误提示。 */
    private String downloadFile(JSONObject file, long size, long doneBytes,
                                long totalBytes, Listener listener, String id) {
        String name = file.optString("name");
        File target = fileFor(name);
        File part = fileFor(name + ".part");
        JSONArray urls = file.optJSONArray("urls");
        if (urls == null || urls.length() == 0) {
            return "缺少下载地址";
        }
        String lastError = "下载失败";
        for (int i = 0; i < urls.length(); i++) {
            String url = urls.optString(i);
            try {
                long existing = part.isFile() ? part.length() : 0;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "AIChat/3.2");
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existing + "-");
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    existing = 0; // 服务器不支持断点，重头下载
                } else if (code != 206 && code != 200) {
                    lastError = "下载失败 HTTP " + code;
                    conn.disconnect();
                    continue;
                }
                long contentLength = conn.getContentLengthLong();
                if (contentLength > 0 && existing == 0) {
                    // 以服务端大小为准，避免清单过期
                    size = contentLength;
                }
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(part, existing > 0)) {
                    byte[] buffer = new byte[64 * 1024];
                    long written = existing;
                    int read;
                    long lastUpdate = 0;
                    while ((read = in.read(buffer)) > 0) {
                        if (cancelFlag) {
                            break;
                        }
                        out.write(buffer, 0, read);
                        written += read;
                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 400) {
                            lastUpdate = now;
                            int percent = (int) (((doneBytes + written) * 100)
                                    / Math.max(1, totalBytes));
                            progress.put(id, Math.min(99, percent));
                            listener.onProgress(id, progress.get(id), false, null);
                        }
                    }
                    out.flush();
                } finally {
                    conn.disconnect();
                }
                if (cancelFlag) {
                    return null;
                }
                if (part.length() == size) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    if (part.renameTo(target)) {
                        return null;
                    }
                    lastError = "保存失败，请检查存储空间";
                } else {
                    lastError = "文件不完整，请重试";
                }
            } catch (Exception e) {
                lastError = "网络错误：" + e.getMessage();
            }
        }
        return lastError;
    }

    /** 删除模型；被其它已安装模型共用的文件会保留。 */
    void delete(String id) {
        JSONObject model = findModel(id);
        if (model == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (JSONObject file : filesOf(model)) {
            names.add(file.optString("name"));
        }
        for (String name : names) {
            if (referencedByOther(name, id)) {
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            fileFor(name).delete();
            //noinspection ResultOfMethodCallIgnored
            fileFor(name + ".part").delete();
        }
        progress.remove(id);
        if (id.equals(activeId())) {
            select(CLOUD);
        }
    }

    private boolean referencedByOther(String name, String exceptId) {
        JSONArray models = manifest();
        for (int i = 0; i < models.length(); i++) {
            JSONObject item = models.optJSONObject(i);
            if (item == null || exceptId.equals(item.optString("id")) || !isInstalled(item)) {
                continue;
            }
            for (JSONObject file : filesOf(item)) {
                if (name.equals(file.optString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
