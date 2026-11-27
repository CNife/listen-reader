# ADR-0001: 源代码不写注释，决策记录集中到 ADR

- 状态：已接受
- 日期：2026-11-21

## 背景

对第一方源码做了一次完整注释审计，共 128 个文件、约 545 行注释（占这些文件总行数的 2.5%）。分类结果只有少数注释记录了真正不显然的决策，其余是：

- **同义反复**：重复函数名、类型名或紧接着的代码，例如各段落的 `// --- 播放 ---` 和逐字段说明。
- **过时**：UI 规格已在 `docs/design/ui-v2.md` 定稿，代码里仍保留一份会漂移的摘要。
- **借口**：用注释为一处敷衍实现开脱，例如「忽略非法选择器」「失败返回 0」「与 RoomLibraryStore 一致」的手抄映射。这类注释会让下一位 agent 把同样的兜底继续扩散。

注释与代码没有强制同步机制，越晚删越难判断真假。决策记录应当只有一处归属。

## 决策

1. 第一方代码**不写注释**，包括 KDoc、行注释、块注释、XML 注释与 CI 配置注释。
2. 「是什么」由命名、类型和测试表达；「为什么」写进 `docs/adr/`。
3. 例外仅限功能性元数据与第三方/生成资产：Python shebang 与 PEP723 区块、`app/src/main/assets/extract/readability.js`、`app/schemas/`、测试 fixture。`.githooks/` 属于第一方脚本，同样纳入预算扫描，不在例外之列。
4. 用 `CommentPolicyTest` 执行注释预算：`app/comment-baseline.txt` 记录每个文件允许的注释行数，默认 0；新增注释即失败。删除注释后可执行
   `UPDATE_COMMENT_BASELINE=1 ./gradlew :app:testDebugUnitTest --tests "com.cnife.listenreader.CommentPolicyTest"`
   收紧预算。预算只允许单调下降。
5. 静默兜底不是可接受的实现，必须开 issue 修正，不能靠注释辩解。

## 从注释迁移的决策

### Capture 与网页提取

- 页内脚本只负责收集候选与点击；元素是否允许点击完全由 Kotlin 的 `L3Rules` 纯函数判定，使安全策略可做 JVM 测试，且不进入页面上下文。
- 只有模板显式声明 `revealSelectors` 时才解除隐藏；通用与纯文本路径保持页面原样，避免把菜单、弹层带进 Script。
- 解除隐藏只作用于提取副本，不改动用户正在浏览的页面。
- 公众号正文常以 `<section>` 堆叠，Readability 不为 `<section>` 打分；提取时把纯行内内容的 `section` 提纯为 `p`。
- 版权尾块只删除短块，且块内不得含长正文结构，避免误删正文。
- 登录、验证码、反爬提示出现时进入 `NeedsUserAction`，把页面交还用户；绝不自动登录、提交表单或付费。
- 提取后的站内 `http(s)` 链接留在 WebView；电话、邮件等外部 scheme 不交给系统，避免跳出与崩溃。
- 首次进入采集时，文本里的第一个可用 `http(s)` URL 走网页模式，否则走文本模式。

### L2 清洗

- L2 把 Markdown 按标题与段落切成不超过 8000 字符的块逐块请求；模型输出必须是 `{"segments":[{"text":"...","kind":"heading|paragraph|quote"}]}`。JSON 非法或字段缺失时重试一次，仍失败则回退规则分段。
- OpenAI 兼容客户端只负责 HTTP 与错误映射；prompt、分块与 JSON 解析在 `LlmScriptCleaner`。日志只记录方法与状态码，绝不记录 key 或正文。
- 端点去掉尾部斜杠；空端点回退到默认 OpenRouter 端点。

### TTS 与 AudioTrack

- StepFun 与千问都采用非流式接口：2xx 先写 `<destination>.tmp` 再原子改名；429、5xx、超时与 IO 失败重试 2 次并指数退避，4xx 不重试。
- 日志只记录状态码与重试次数，绝不记录 API key 或正文。
- 千问响应优先使用 Base64 `output.audio.data`；否则下载 `output.audio.url`。非本地回环的 `http://` 自动升级为 `https://`，兼容 Android release 的明文流量策略。
- 每个 Segment 一把合成锁：同一句不会重复请求，不同句可以并行。
- `trackEpoch` 在切轨时自增，旧轨上迟到的合成结果只落旧轨文件与数据库，不污染新轨状态。
- `L2` 每次运行有独立纪元，过期的清洗结果不得写回当前预览。
- 预取窗口每次调用都按当前 Segment 索引重算；已在进行中的索引跳过，窗口移动时旧任务跑完即可，不阻塞新窗口。
- 预取有独立并发上限，前台合成不受该上限约束。
- 已缓存音频直接离线播放，不查 key、不发起合成请求。
- 服务主动 `prepare`/`play` 期间的引擎暂停不算用户从通知栏暂停；只有用户意图变化才清除「应该正在播放」。
- 预取失败只把该 Segment 标为 ERROR，不打断当前播放，也不写全局错误。
- 冷启动更新检查失败时静默返回，不刷新冷却时间戳，下次启动继续尝试。

### Resource、Source 与持久化

- 音频路径为 `filesDir/audio/<resourceId>/<trackId>/<segmentIndex>.mp3`；Source 快照为 `filesDir/sources/<resourceId>.md`。
- 活动 AudioTrack 以 Room 为准；进程内缓存只用于同步的存储统计。
- API key 用 Android Keystore 的 AES/GCM 加密后存入 DataStore；解密失败等价于「未配置」，不崩溃。Key 只写不读，输入框留空表示保留原值。
- 更新检查的远端版本与「已忽略」状态是推导字段，每次设置变化统一重算；手动检查同版本会恢复被忽略的资料库提示。
- 采集会话 id 与预览 revision 自增，用于丢弃过期异步结果与 UI 导航去重。
- 删除 Resource 时级联清理 Script、缓存音频与原文快照。

### 版本与发布

- `versionCode = major * 1_000_000 + minor * 1_000 + patch` 与 `.github/workflows/release.yml` 完全一致；三段均不接受前导零，minor 与 patch 必须小于 1000，`0.0.0` 不合法，发版从 `v0.0.1` 起。
- 更新源只请求 GitHub `/releases/latest`，Draft 与 Pre-release 天然排除；下载优先取 `listen-reader-<version>.apk` 附件，缺失时回退 Release 详情页。
- PR 阶段只验证未签名的 `assembleRelease` 能编译；签名变量只注入一部分时构建直接失败，避免未签名包流入发布。
- 发版工作流解析 tag 时不用 `head`：`aapt2` 被提前关闭管道会触发 SIGPIPE，配合 `pipefail` 会误判失败。

### 构建与自验证

- pre-commit 门禁的服务层 `runBlocking` 检查在匹配前先剥离块注释与行注释，避免注释里的示例代码误报。
- jsdom 依赖下沉为 Gradle 增量前置 `installJsTestDeps`：新 worktree 自动 `npm ci`，依赖未变时 UP-TO-DATE。
- `-PappIdSuffix` 生成与正式版并存的隔离包，无需改源码即可做真机 E2E。
- debug 构建的 `DebugControlReceiver` 通过广播注入 mock 端点、预置 Resource 与状态快照；release 构建不包含该组件。
- 更新检查端点、StepFun 端点与千问端点只在 debug 下允许运行期覆写，默认回到生产值。

### 测试基础设施

- 服务层流程测试统一经 `TestReadingService` 装配；端口增加时只改这一处，避免 9 个流程测试各自漂移。
- `ArchitectureContractTest` 把 AI review 反复发现的规则（服务层禁 `runBlocking`、端口无死成员、Room Schema 同步、模板正则有效）固化为确定性 JVM 单测。
- 端口死成员检测必须排除实现类对自身成员的调用，并忽略私有帮助函数；否则会把死成员误判为有消费者。
- jsdom harness 以 `runScripts: "dangerously"` 真实执行页面脚本，并补齐 `innerText` 与几何信息；点击决策仍留在 Kotlin。

## 后果

- 阅读代码时不再需要在真假注释之间做判断；需要「为什么」时查 ADR。
- `CommentPolicyTest` 在每次 `:app:testDebugUnitTest` 与 CI 中执行，注释预算无法悄悄上涨。
- 审计中识别出的敷衍实现由以下 issue 跟踪修复，而不是继续用注释容忍：
  - #24 L1 静默吞掉模板非法选择器（已修复：非法选择器记入提取诊断 `diagnostics`，内置模板由 `JsExtractorContractTest` 经 jsdom 强制校验）
  - #25 jsdom harness 过滤所有 `Not implemented` 错误
  - #26 `readDurationMs` 失败静默返回 0（已修复：时长不可读时改抛可重试的 `SpeechException`）
  - #27 L3 点击前滚动失败被吞掉（已修复：scrollIntoView 失败用 scrollTo 兜底，仍失败返回 `scroll_failed` 不假装点击成功）
  - #28 `FakePorts` 手抄生产映射
- 视觉规格以 `docs/design/ui-v2.md` 为唯一来源（2026-11-27 起被 ADR-0002 的 Notion 设计语言取代），Compose 代码不再复述 dp、字号与配色理由。
