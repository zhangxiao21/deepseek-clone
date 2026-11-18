# 赛博大鲸鱼（DeepSeek 安卓客户端）

**赛博大鲸鱼**是一个用 Kotlin + Jetpack Compose 编写的 DeepSeek 手机端客户端，**使用 DeepSeek 官方开放 API（`api.deepseek.com`，OpenAI 兼容）**，通过 API Key 鉴权。实现同款核心体验：深度思考（CoT 思维链展示与耗时）、联网搜索、模型切换、会话管理、网页版对话包导入导出、文件上传、拍照/相册图片、语音输入与朗读、**长期记忆**、**上下文压缩**，以及可切换的浅色 / 深色 / 跟随系统主题。

> ⚠️ **非官方项目，仅供学习研究。** 调用 API 按官方计费，费用由你的 DeepSeek 账户承担。API Key 等价账户付费权限，本 App 将其加密存于 Android Keystore，绝不写日志、绝不上传第三方。

> 命名说明：App 显示名称为「赛博大鲸鱼」；`DeepSeek` 仅指所接入的官方服务与模型。工程目录名、包名 `com.deepseek.clone`、类名与 Gitee 仓库名属技术标识，保持不变。

---

## 一、编译（在你的云编译环境完成）

本机无 Android SDK 且 Google Maven 不可达，APK 需在云环境编译。工程带**完整的 Gradle wrapper**（`gradle-wrapper.jar` + `gradlew`），已对齐你提供的工具链：

| 组件 | 版本 |
|---|---|
| App 版本 | **1.1（versionCode 2）** |
| JDK | 11 / 17 均可（AGP 7.2.2 要求 ≥11；Gitee 流水线实际为 17） |
| Gradle | wrapper 锁定 **7.3.3**；Gitee 流水线用系统 Gradle 7.6，实测兼容 |
| Android Gradle Plugin | 7.2.2 |
| Kotlin | 1.7.20 |
| Compose Compiler | 1.3.2（官方配对 compiler 1.3.2 ↔ Kotlin 1.7.20） |
| compileSdk / targetSdk / minSdk | 33 / 33 / 26 |

### 命令行编译（推荐）

```bash
cd DeepSeekApp
export JAVA_HOME=/path/to/jdk-17

# 用工程自带 wrapper，会自动下载/复用 Gradle 7.3.3
./gradlew assembleDebug

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

若云环境已把 gradle 7.3.3 加入 PATH，也可直接用 `gradle assembleDebug`。

### Gitee 云编译流水线（当前实际环境）

仓库自带 `.workflow/流水线-202611181208.yml`，平台 `build@android` 步骤参数为 `jdkVersion: 17`、`gradleVersion: 7.6`，构建命令是 `gradle assembleRelease`（**用系统 Gradle，不走 `./gradlew`**）。所以日志里出现 Gradle 7.6.4 而非 wrapper 的 7.3.3，属正常。流水线为手动触发，**改完代码必须先 commit + push，再触发流水线**，否则 `git clone --depth=1` 拉到的还是旧代码。

平台会注入自己的镜像仓库，构建时可能打印：

> `The project declares repositories, effectively ignoring the repositories you have declared in the settings.`

这是非致命提示（构建会继续），不影响依赖解析。

> ⚠️ **历史坑**：Compose Compiler 版本必须与 Kotlin 严格配对。`androidx.compose.compiler:compiler:1.2.1` 这个 artifact **在 Google Maven 上根本不存在**（官方只有 `1.2.0 ↔ Kotlin 1.7.0`、`1.3.0/1.3.1 ↔ Kotlin 1.7.10`、`1.3.2 ↔ Kotlin 1.7.20`），误填即报 `Could not find androidx.compose.compiler:compiler:1.2.1`。当前正确配置为 **Kotlin 1.7.20 + Compiler 1.3.2**。Compose UI 库版本（1.2.1）与 compiler 版本已解耦，无需保持一致。

### 产物与安装（APK 签名）

`assembleRelease` 的产物是 `app/build/outputs/apk/release/app-release.apk`，**已签名、可直接安装**。

- 签名配置见 `app/build.gradle` 的 `signingConfigs.release`，使用仓库内 `app/debug.keystore`（测试密钥，`android` / alias `deepseek`）。它等价于 Android 官方 debug.keystore：自签名、密码固定、公开存放无安全影响，**仅用于自测与 CI，不可用于正式上架**；上架请换成自己保管的上架密钥并移除该文件。
- 若产物名是 **`app-release-unsigned.apk`**，说明没走签名配置，Android 会拒绝安装，手机常提示"安装包已损坏 / 解析软件包时出现问题"。这是**未签名**导致的，不是文件损坏（APK 本身的 ZIP 结构是完整的）。
- minSdk 26 / targetSdk 33：targetSdk ≥ 30 的 APK 在 Android 11+ 要求至少 v2 签名，AGP 默认已启用 v1 + v2。

### Android Studio 编译

用 Android Studio 打开 `DeepSeekApp` 目录，等待 Gradle Sync 完成，`Build > Build APK(s)`。

> 需联网访问 `dl.google.com`（Google Maven）与 `repo.maven.org`。所有依赖均来自官方仓库，**无 JitPack 等第三方源**。

---

## 二、登录（API Key）

1. 前往 <https://platform.deepseek.com/api_keys> 创建 API Key（`sk-` 开头）。**需账户有余额**。
2. App 启动后输入 API Key，点"验证并登录"。App 会调用 `GET /models` 验证密钥有效性。
3. 密钥保存在 Android Keystore 加密存储中；可在 设置 → API 接口 快速跳转管理页面。

---

## 三、已实现功能

| 模块 | 功能 | 官方 API 对应 |
|---|---|---|
| 核心对话 | 流式输出、深度思考开关、推理强度、模型切换、停止生成、Markdown 渲染、**CoT 思维链（耗时/预览/展开）**、重新生成 | `POST /chat/completions` (`stream:true`, `thinking.type`, `reasoning_effort`)，思考内容在 `reasoning_content` |
| 联网搜索 | 内置联网工具，自动搜索并附引用；支持边思考边检索 | `tools: [{type:deepseek_web_search}, {type:deepseek_read_url}]`，引用在 `annotations`，过程在 `server_tool_event` |
| 会话管理 | 列表/搜索/重命名/置顶/删除；**仅首轮自动命名，手动/导入标题永不覆盖** | 本地 JSON 存储 + `title.enabled`（按需请求） |
| 导入导出 | 导入网页版 `deepseek_data-*.zip`，按 `parent_id` 摊平；导出本地会话；兼容 ISO 时间、大写角色、JsonNull、附件占位 | `ChatZipManager` |
| **长期记忆** | 跨会话积累用户事实/偏好，带最近 6 轮上下文抽取、去重合并，最多 120 条并注入 system prompt；设置页可查看/编辑/清空、可**从最近对话重新提炼** | `ContextEngine.learn()` / `rebuildFromHistory()`（后台轻量非流式调用） |
| **上下文压缩** | 历史过长时把较旧轮次压成摘要随会话缓存，只回传最近若干轮原文；增量压缩、可开关 | `ContextEngine.compressInBackground()` / `prepareHistory()` |
| 外观 | 浅色 / 深色 / 跟随系统三态切换，即时生效并持久化；退出登录保留偏好；聊天页与会话抽屉对齐官方布局 | 设置 → 外观（`ThemeMode` + 动态 `AppPalette`） |
| 文件/图片 | **应用内相册选择器（网格缩略图 + 一次最多 9 张多选）**、「+」面板最近图片横条 + 拍照/相册/文件三宫格、相机拍照、PDF/Word/PPT 解析、附件名胶囊与移除 | Files API `POST /files` + `messages[].content` 多模态数组 |
| 语音 | 语音输入（系统 SpeechRecognizer；无识别服务时自动降级到系统语音界面，可随时结束）、朗读（TextToSpeech） | `SpeechHelper`、`ReadAloudHelper` |
| 安全 | **多个 API Key 加密保存，设置页/登录页随时添加、编辑、删除、切换**；旧单 Key 自动迁移；鉴权失败只临时登出、不删其它 Key | `EncryptedSharedPreferences` + `SecureStore.apiKeys()` |

### 官方模型（2026-11）

- `deepseek-flash`：V4.1 Flash，快速，**支持图片输入**
- `deepseek-v4-pro`：V4 Pro，强推理

> 官方 API 是无状态的：不提供服务端会话对象。本 App 把会话历史存在本地（SharedPreferences + Gson JSON），每次请求按 OpenAI `messages` 数组回传（最近 40 条）。
>
> 本地存储刻意不用 Room：其 kapt 注解处理在部分离线构建环境会因注解解析失败（`NonExistentClass`）导致编译中断。改用纯 Kotlin 实现后无任何注解处理器，编译链更简单稳定。

---

## 四、关键技术说明

### 4.1 思考模式

```json
{
  "model": "deepseek-flash",
  "messages": [...],
  "stream": true,
  "thinking": {"type": "enabled"},
  "reasoning_effort": "high",
  "stream_options": {"include_usage": true}
}
```

思考内容通过 SSE chunk 的 `delta.reasoning_content` 返回，正文在 `delta.content`，流以 `data: [DONE]` 结束。

### 4.2 联网工具

内置服务端工具（客户端无需执行）：

```json
"tools": [
  {"type": "deepseek_web_search", "name": "deepseek_web_search"},
  {"type": "deepseek_read_url", "name": "deepseek_read_url"}
]
```

引用在最后一个 chunk 的 `delta.annotations[].url_citation`（含 `title`/`url`/`start_index`/`end_index`）；服务端工具调用过程在 `delta.server_tool_event`（`kind` 为 search/deep_search/read_link/image_search 等）。

### 4.3 图片与文件

- 内联图片：`content` 数组中 `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`
- Files API：`POST /files`（multipart，`purpose` 字段在 `file` 之前），返回 `file_id`，消息用 `{"type":"file","file_id":"..."}` 引用
- purpose：图片/文本用 `user_data`，PDF/Word/PPT 用 `rich_layout_recognition`（图文混排）或 `text_recognition`

> ⚠️ **HTTP 400 坑（已修）**：引用已上传文件时，content part **只能**带 `type` + `file_id`，
> **绝不能同时带 `filename`**。否则服务端报
> `messages[n].content[m]: file cannot have filename when file_id is present`。
> 旧实现把文件名一起塞进 `ContentPart`，还把该 part 落库进 `fileRefsJson`，
> 导致下一轮回传历史时必失败。现在：
> - 上传成功只构造 `ContentPart(type="file", fileId=id)`；文件名另存 `attachmentNamesJson`，仅本地 UI 用；
> - `Repository.sanitizeParts()` 在每次 `buildMessages` 前清洗所有 part（含历史落库的旧脏数据），
>   保证发往 API 的 part 永远合法。

### 4.4 SSE 流式输出

> ⚠️ **「发送后无响应 / 全部生成完才一次性吐出」坑（已修）**：`SseParser.parse` 用
> `BufferedReader.readLine()` 做**阻塞式网络读**，而 flow 默认在 collector 的协程上下文执行 ——
> `viewModelScope.launch` 是主线程，于是整个生成期间主线程被占死，Compose 无法重组。
> 现在 `parse()` 末尾加 `.flowOn(Dispatchers.IO)`，阻塞读在 IO 线程，主线程只负责更新 StateFlow。

- 流式请求头：`Accept: text/event-stream`、`Cache-Control: no-cache`、
  `Accept-Encoding: identity`（禁用 OkHttp 透明 gzip，避免中间层缓冲整个响应）；
  `AuthInterceptor` 只在请求未自带 `Accept` 时才补 `application/json`，不再覆盖流式头。
- `readTimeout(0)` 永不超时，服务端静默时 UI 会永远卡在「生成中」。加两段式看门狗：
  首个 SSE 事件 60s 未到、或事件中静默超过 120s，主动中断并提示「服务长时间无响应」。
- `stop()` / 超时会 `close()` 响应体，让 IO 上阻塞的 `readLine()` 立刻抛错退出；
  收尾用 `withContext(NonCancellable)` 包住，保证已生成的部分内容仍会落库、连接被释放。

### 4.5 长期记忆与上下文压缩

官方 API 无状态，每轮都要把"该让模型知道的东西"重新发一遍。这两项由 `ContextEngine` 负责：

- **system prompt** = 人设 + 长期记忆（跨会话用户事实）+ 本会话较早轮次的压缩摘要
- **长期记忆**：每轮结束后用一次轻量非流式 `chat/completions`（`stream:false`）抽取稳定事实，
  JSON 数组解析 + 去重，最多 40 条，持久化在加密存储
- **上下文压缩**：历史 > 30 条时，把「缓存摘要之后、最近 24 条之前」的新增旧消息压成 ≤600 字摘要，
  按会话缓存并增量更新，只回传最近 24 条原文
- **失败静默降级**：所有辅助调用带 25s 超时、全程 try/catch，网络/解析失败一律忽略，
  绝不阻断主对话；压缩结果刻意滞后一轮使用，保证首字不被拖慢
- 设置 → 记忆与上下文：三个开关（长期记忆 / 上下文压缩 / 显示思维链）+ 记忆查看/编辑/清空

### 4.6 导入 zip

网页版 `deepseek_data-*.zip`（`manifest.json` 的 `export_format_version:3`）：按 `current_message_id` 沿 `parent_id` 回溯摊平，按 `id` 升序排列（不用 `inserted_at`，非单调），过滤 `CONTENT_FILTER`/失败态。

**实测格式（用用户真实导出包 82 个会话 / 4068 条消息校准）**：

```json
{
  "title": "闲聊4",
  "current_message_id": 228,
  "messages": [{
    "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
    "inserted_at": "2026-11-15T16:13:25.628Z",
    "fragments": [
      {"type": "FILE", "files": [{"file_name": "x.webp"}]},
      {"type": "REQUEST", "content": "……"},
      {"type": "THINK", "content": "……", "elapsed_secs": 3.2},
      {"type": "RESPONSE", "content": "……"}
    ]
  }]
}
```

三个曾导致「标题有、内容没有」的坑：

| 坑 | 现象 | 处理 |
|---|---|---|
| `inserted_at` 是 **ISO 字符串**，旧代码 `asDouble` 抛异常且被 `catch` 静默吞掉 | 每条消息都被丢弃，只导入会话标题 | `parseTimestamp` 兼容 ISO / 秒 / 毫秒，统一存 epoch 秒 |
| `role` 是 `USER` / `ASSISTANT` **大写** | 气泡全判成 AI、历史不进上下文 | `normalizeRole` 归一化为 `user` / `assistant` |
| `content` 可能缺失 / `JsonNull` / 类型漂移；`CONTENT_RISK` 等 fragment 带文本 | 正文混入风险提示或抛 `UnsupportedOperationException` | 全部走 `asText` 安全访问器；正文只取白名单 `REQUEST/RESPONSE/TEMPLATE_RESPONSE/...` |

`FILE` fragment 对应的实体图片在 `*_files/` 目录，APK 内无文件，导入时转为 `📎 文件名` 占位文本，避免用户以为内容丢失。`THINK` 的 `elapsed_secs` 存入 `thinkingElapsed`。导入结果会显示跳过条数，便于排查异常包。

校准结果：82 个会话导入 3992 条消息，正文非空率 100%，0 个空会话，当前分支覆盖率 98.2%。

---

## 五、已知限制与故障排查

1. **计费**：联网搜索、图片、长文本都按 token 计费，注意账户余额。`usage` 会统计 `server_tool_use` 次数。
2. **401/402/403**：App 会自动提示"API Key 无效/余额不足/无权限"并跳回登录页。
3. **图片模型**：仅 `deepseek-flash` 系支持图片；专家模型 `deepseek-v4-pro` 不支持。
4. **上下文长度**：开启上下文压缩时只回传最近 24 条原文 + 缓存摘要（关闭时最多 60 条）；超长会触发 API 的长度错误。记忆/压缩/摘要都是"尽力而为"，失败不影响对话。长期记忆上限 120 条，超过时保留最新事实。
5. **语音**：`SpeechRecognizer` 离线能力取决于设备；设备无识别服务时自动降级到系统语音识别界面（会短暂跳出 App）；朗读需系统装有中文语音包。DeepSeek 官方开放 API **不提供语音转文字端点**，语音输入只能用设备本地能力。
6. **相册选择器**：需要 `READ_MEDIA_IMAGES`（Android 13+）/ `READ_EXTERNAL_STORAGE`（旧版）权限；网格只加载最近 300 张，单次最多选 9 张，逐张走 Files API 上传。
6. **停止生成**：官方 API 无独立 stop 端点，App 通过取消本地收集/关闭连接实现。
7. **Material3 版本锁定**：依赖固定 `material3:1.0.0`（云编译为离线镜像，不宜升级）。代码使用 1.0.0 的 API：`smallTopAppBarColors`、`TextFieldDefaults.outlinedTextFieldColors`、`Divider`、`Icons.Filled.*`，会话操作菜单用 `AlertDialog`（1.0.0 无 `ModalBottomSheet` 与新版 `ListItem`）。**不要单独升级 Material3**——它会连带提升 Compose runtime，破坏 Kotlin 1.7.20 + Compiler 1.3.2 的严格配对。同理 `security-crypto` 固定 1.0.0，用 `MasterKeys` 旧签名而非 1.1+ 的 `MasterKey`。

---

## 六、工程结构

```text
DeepSeekApp/
├── gradlew / gradlew.bat / gradle/wrapper/*   # Gradle 7.3.3 wrapper
├── settings.gradle / build.gradle / gradle.properties
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/deepseek/clone/
    │   ├── DeepSeekApp.kt / MainActivity.kt    # 应用与导航
    │   ├── core/ApiConfig.kt                   # 官方模型/工具/常量
    │   ├── data/
    │   │   ├── model/Dtos.kt                   # OpenAI 兼容 DTO
    │   │   ├── remote/                         # Retrofit/OkHttp/SSE
    │   │   ├── local/                          # JSON存储/Keystore
│   │   ├── importer/ChatZipManager.kt      # zip 导入导出
│   │   └── repository/
│   │       ├── Repository.kt                # 业务编排 / 请求构造 / part 清洗
│   │       └── ContextEngine.kt             # 长期记忆 + 上下文压缩 + system prompt
    │   └── ui/                                 # Compose 界面
    │       ├── chat/                           # 聊天页（胶囊输入栏 / CoT / 三宫格附件面板）
    │       ├── picker/PhotoPickerScreen.kt    # 应用内相册多选 + 最近图片横条
    │       ├── sessions/                      # 官方抽屉风格会话列表（时间分组）
    │       ├── settings/ / login/ / theme/
    │   └── util/                               # SpeechHelper / FileHelper / ReadAloudHelper
    └── res/
```

私有接口时代的 PoW 计算（`DeepSeekPow.kt`）、网页版流式接口（`StreamApi.kt`）及 Room/kapt 注解处理已彻底移除；登录页已改为官方 API Key 输入。


---

## 七、免责声明

本项目为个人学习研究用途的 interoperability 实现，与 DeepSeek 官方无关。使用官方 API 需遵守 DeepSeek 服务条款与计费规则。请妥善保管你的 API Key。
