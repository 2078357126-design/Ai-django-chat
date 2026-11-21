package com.aichat.app;

/**
 * 本地 llama.cpp 推理的 Java 入口，JNI 实现在 cpp/llama_jni.cpp。
 *
 * 调用方负责放到后台线程执行，并自行切回主线程。
 */
class LlamaBridge {

    interface TokenCallback {
        void onToken(byte[] data);
    }

    private static final Object LOAD_LOCK = new Object();
    private static boolean libraryLoaded;
    private static String libraryError = "";
    private static long handle;
    private static String loadedModelId = "";

    static String libraryError() {
        return libraryError;
    }

    static boolean ensureLibrary() {
        synchronized (LOAD_LOCK) {
            if (libraryLoaded) {
                return true;
            }
            try {
                System.loadLibrary("aicpp");
                libraryLoaded = true;
            } catch (Throwable e) {
                libraryError = e.getMessage() == null ? "本地推理引擎加载失败" : e.getMessage();
            }
            return libraryLoaded;
        }
    }

    static boolean isLoaded(String id) {
        return id != null && id.equals(loadedModelId) && handle != 0;
    }

    /** 加载模型；已有同名模型时直接复用。 */
    static void load(String id, String modelPath, String mmprojPath) {
        synchronized (LOAD_LOCK) {
            if (isLoaded(id)) {
                return;
            }
            releaseLocked();
            if (!ensureLibrary()) {
                throw new IllegalStateException(libraryError);
            }
            int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
            handle = nativeLoad(modelPath, mmprojPath == null ? "" : mmprojPath, 2048, threads);
            if (handle == 0) {
                throw new IllegalStateException("模型加载失败，文件可能不完整");
            }
            loadedModelId = id;
        }
    }

    static void chat(String prompt, TokenCallback callback) {
        long current;
        synchronized (LOAD_LOCK) {
            current = handle;
        }
        if (current == 0) {
            throw new IllegalStateException("本地模型尚未加载");
        }
        nativeChat(current, prompt, callback, 256);
    }

    static void stop() {
        long current;
        synchronized (LOAD_LOCK) {
            current = handle;
        }
        if (current != 0) {
            nativeStop(current);
        }
    }

    static void release() {
        synchronized (LOAD_LOCK) {
            releaseLocked();
        }
    }

    private static void releaseLocked() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
        loadedModelId = "";
    }

    private static native long nativeLoad(String modelPath, String mmprojPath,
                                          int contextSize, int threads);

    private static native void nativeChat(long handle, String prompt,
                                          TokenCallback callback, int maxTokens);

    private static native void nativeStop(long handle);

    private static native void nativeFree(long handle);
}
