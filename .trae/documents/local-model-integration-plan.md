# 本地小模型内置方案（Qwen3.5 + MiniCPM-V）

## 背景

现在「AI云」所有聊天都走 DeepSeek 云端 API，用户必须填 Key 且按量付费。目标是增加**可选**的本地离线模型：不填 Key 也能聊，图片也能看懂，同时不牺牲云端模式的质量。

模型按用户选择：
- **Qwen3.5-2B**（Q4_K_M，约 1.4GB，内存约 1.5–2GB）：主力本地聊天
- **MiniCPM-V 4.7-1B**（约 1GB + 视觉投影器，内存约 2–3GB）：看图、截图、OCR
- **Qwen3.5-0.8B**（约 0.5GB，内存约 1GB）：低配手机兜底档，与 2B 共用同一引擎，只是多一个下载项

依据：Qwen3.5 小尺寸系列为 Apache 2.0，中文口语和角色扮演在同尺寸里最强；MiniCPM-V 4.7 已合入 llama.cpp 上游（PR #29416），支持 mmproj 视觉。代价要提前说明：本地 2B 的中文质量明显低于 DeepSeek，手机上生成速度约 5–15 字/秒。

## 总体设计

一个引擎，三种运行形态：

- **安卓**：llama.cpp 通过 NDK 编进 APK（只 arm64-v8a），JNI 封装加载/流式生成；模型不进安装包，首次在「模型」面板里下载到应用私有目录。
- **桌面**：PyInstaller 打包时放入各平台的预编译 `llama-server` 可执行文件，运行时由 `desktop.py` 拉起本地服务；Django 用现成的 OpenAI 客户端指向 `http://127.0.0.1:PORT/v1`，流式转发给前端。
- **共用 UI**：仍只用一份 `android/app/src/main/assets/index.html`；桌面端在 `Chat/templates/chat/desktop.js` 补桥接。所有新按钮必须两端同时可用。

模型清单放根目录 `models.json`（名称、文件名、大小、sha256、下载地址 + ModelScope 镜像、是否视觉、建议内存、推荐档位），Android 打进 assets、桌面打进 PyInstaller datas，避免两端写死。

## 分阶段实施

### P0 基础（不改推理）
1. 新增 `models.json`。
2. `index.html`：顶部新增「模型」入口；面板显示 云端 DeepSeek / 本地三档，含大小、建议内存、下载进度、删除、当前使用中；本地模式下隐藏 Key 依赖。
3. `MainActivity`：没有 Key 时不再强制进 Key 页，可先选本地模型进入聊天。
4. 下载器：先做安卓 `DownloadManager` + 桌面线程下载；`.part` 临时文件 + sha256 校验后改名；支持 ModelScope/直连镜像回退。
5. 版本 3.2 先发布「模型管理 + 下载」骨架。

### P1 桌面本地文本（先跑通，风险最低）
1. 新增 `Chat/local_models.py`：清单读取、下载/校验、`LlamaServer` 单例（随机端口、`/health` 就绪、切模型重启、退出回收、日志落数据目录）。
2. `Chat/views.py`：`/api/chat/` 增加 `model` 参数；本地模型走 `OpenAI(base_url=本地端口, api_key="local")`，跳过 `OPENAI_API_KEY` 校验；`/api/models/*` 提供列表、下载进度、选择、删除。
3. `desktop.js`：`chat()` 带上 `model`；用 fetch 流式读取，回填到 `window.__onDelta`，结束时 `__onResult`。
4. `ai_chat.spec` + `build-desktop.yml`：按平台下载 llama.cpp release 的 `llama-server` 二进制放入包内；macOS 构建后补 `codesign -s -`。
5. 验证：桌面直接对话、切模型、断网可用、删除模型后回到云端。

### P2 安卓本地文本（发布 3.2）
1. `android/app/build.gradle`：开启 `externalNativeBuild`，ABI 只保留 `arm64-v8a`；AGP 升到 8.6.x、NDK r27+、链接参数 `-Wl,-z,max-page-size=16384`（Android 15 的 16KB 页要求）。
2. 新增 `cpp/CMakeLists.txt`、`cpp/llama_jni.cpp`：`nativeLoad` / `nativeChat(messagesJson, tokenCallback)` / `nativeCancel` / `nativeFree`；chat template 读 GGUF 内嵌模板，缺失回退 ChatML，并剥离 Qwen3.5 的思考块。
3. 新增 `LlamaBridge.java`（单线程 executor + 取消）、`LocalModelManager.java`（下载/校验/删除/选择）。
4. `MainActivity.Bridge.chat()` 按当前模型分流：本地走 `LlamaBridge` 流式回调 `window.__onDelta`，云端保持原逻辑。
5. 内存策略：`ActivityManager.isLowRamDevice` 或总内存 < 4GB 时默认推荐 0.8B；上下文默认 2048；同时只允许一个生成任务。
6. 本地模式下关闭「主动消息」（省电，避免后台生成）。
7. CI：`android-apk.yml` 安装 NDK/CMake、clone 固定版本 llama.cpp、缓存 `.cxx`、调大 JVM 内存。

### P3 视觉（MiniCPM-V，发布 3.3）
1. 桌面：`llama-server` 加 `--mmproj`，请求按 OpenAI 图片格式传 base64；输入框增加「图片」按钮。
2. 安卓：JNI 增加 mtmd 接口，Bitmap 缩放（首版最长边 768）后转 RGB 传入；聊天记录里图片只存缩略图。
3. 视觉模型独立于文本模型，选择「MiniCPM-V」时自动加载投影器；内存不足时提示改用 2B。

## 关键文件

- `Chat/views.py`、`Chat/urls.py`、`desktop.py`、`ai_chat.spec`
- `Chat/templates/chat/desktop.js`、`android/app/src/main/assets/index.html`
- `android/app/src/main/java/com/aichat/app/MainActivity.java`（新增类拆出去，避免继续膨胀）
- `android/app/build.gradle`、`android/build.gradle`、`android/app/src/main/cpp/*`
- `.github/workflows/android-apk.yml`、`.github/workflows/build-desktop.yml`

## 主要风险

- **体积/内存**：APK 因引擎增加 10–20MB；2B 运行时约 2GB，低端机可能被系统杀，需要低配档和内存提示。
- **速度与质量**：手机 2B 约 5–15 字/秒，中文质量低于 DeepSeek，这一点会在模型面板明确标注。
- **构建链**：本机没有 JDK/NDK，安卓原生部分只能靠 CI 迭代；16KB 页、ABI、NDK 版本是最容易踩的坑。
- **视觉权重来源**：MiniCPM-V 4.7 的 GGUF + mmproj 需要确认官方/可信发布；若缺失，先用 4.5 的 GGUF 顶替，并在面板标注版本。
- **下载源**：HuggingFace 在国内不稳，优先 ModelScope 镜像，失败自动回退。

## 验证

1. 桌面：无网/无 Key 状态下用 2B 聊 5 轮；切 0.8B；删除模型后回到云端；看图模型发一张截图能描述内容。
2. 安卓：真机（≥6GB 内存）安装 3.2，下载 2B，聊 3 轮不卡死；低内存模拟器默认推荐 0.8B；中途取消生成正常。
3. 回归：云端 DeepSeek、角色切换、朗读、壁纸、表情、主动消息（云端模式）均不受影响。

## 版本节奏

- 3.2：模型面板 + 桌面本地文本 + 安卓本地文本
- 3.3：MiniCPM-V 看图（桌面 + 安卓）
- 版本号仍按既有规则递增，五端同步发布。
