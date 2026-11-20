# 自验证手册

Agent 在本仓库证明「改对了」的三条通道。从最便宜的通道开始，只有触及真实平台链路时才升级：
`紧循环（JVM）` → `视觉快照（JVM 渲染）` → `免改源码 E2E（真机/模拟器）`。

## 1. 视觉快照（Roborazzi，数秒）

改动 Compose 布局、样式、主题或状态文案时，除了语义断言，还要看真实渲染像素。

```bash
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} \
  ./gradlew :app:testDebugUnitTest --tests "com.cnife.listenreader.ui.UiSnapshotTest" --console=plain
```

产物在 `app/build/outputs/roborazzi/`（由 `gradle.properties` 的 `roborazzi.test.record=true` 触发）：

| 文件 | 界面 |
| --- | --- |
| `library-empty.png` | 空资料库引导页 |
| `library-populated.png` | 继续收听卡片 + 资源列表 |
| `library-empty-dark.png` | 深色主题空资料库引导页 |
| `library-error.png` | 资料库加载失败错误态 |
| `library-update-notice.png` | 资料库顶部的发现新版本内联提示 |
| `capture-url-idle.png` | 网页采集页空 URL 态（左对齐说明，URL 栏不被遮挡） |
| `preview-l2-success.png` | 采集预览页，L2 清洗成功状态条 + 分段草稿 |
| `player-active.png` | 播放页，当前语段用字重与深浅标出 |
| `player-no-key.png` | 播放页无音频状态 |
| `voice-sheet.png` | 音色选择抽屉 |
| `settings-top.png` | 设置页顶部：外观与朗读服务 |
| `settings-dark.png` | 深色主题设置页（预取档位与 BYOK 状态） |
| `settings-update.png` | 设置页版本区块（自动检查开关、远端版本信息与检查结果） |
| `player-font-scale-2x.png` | 播放页在 2.0× 字体缩放下的 dock 与正文 |

用 `read` 直接打开 PNG（多模态模型能看见布局、留白、深浅色对比与溢出）。快照不进版本库：
每次运行重新生成，避免跨平台字体差异把 CI 拖成假失败。

## 2. 免改源码的实机/模拟器 E2E

真实 WebView 提取、Media3 后台播放、真实 TTS 合成链路只能在设备上验证。**不要**为了测试去改
`build.gradle.kts`、`StepFunDefaults.BASE_URL` 或注入系统根证书；仓库已提供三条通道：

- `-PappIdSuffix`：隔离包名，测试包与常用手机上的正式版并存
- `scripts/mock-server.py`：本地 HTTP mock（StepFun / 千问 TTS、OpenAI 兼容 LLM、GitHub Release、fixtures 网页）
- `DebugControlReceiver`：debug 构建里的 `adb broadcast` 控制口，注入配置与抓取状态快照

### 2.1 构建并安装隔离包

```bash
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ./gradlew :app:assembleDebug -PappIdSuffix=.nmtest --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`app/build/outputs/apk/debug/output-metadata.json` 的 `applicationId` 应为
`com.cnife.listenreader.nmtest`。不加 `-PappIdSuffix` 时仍产出 `com.cnife.listenreader`，
工作区保持干净（隔离包名来自构建参数，不是源码改动）。

无显示器环境用 `emulator -avd <name> -no-window -no-audio &` 起 AVD；等
`adb devices` 里出现 `device`（不是 `offline`）再继续。

### 2.2 启动 mock 与端口映射

```bash
python3 scripts/mock-server.py --port 18080 --tts-delay-ms 300   # 延迟放大，便于观察预取并发
adb reverse tcp:18080 tcp:18080
curl -sf http://127.0.0.1:18080/healthz
```

`--tts-delay-ms` 控制合成耗时；`GET /healthz` 返回 `active_tts` / `max_active_tts` /
`tts_requests` / `llm_requests` / `release_requests`，是「预取是否真的并行」「更新检查是否
真的在冷却期出站」的直接证据。服务端日志逐行打印 `START active=… max=…`、
`END active=…` 与 `RELEASE 0.3.0 apk=…`。

`/releases/latest` 是 GitHub Release 替身，默认返回 `v0.3.0` 与附件
`listen-reader-0.3.0.apk`；`--release-apk-name ""` 让它不带附件，用来驱动「附件缺失回退
`html_url`」这条只能靠真实仓库权限才能验证的边界。

可提供的网页 fixture（来自 `app/src/androidTest/assets/fixtures/`）：

```bash
curl -sf http://127.0.0.1:18080/mp.weixin.qq.com/s/wechat-article.html   # 公众号模板 + 隐藏正文 + 懒加载图片
curl -sf http://127.0.0.1:18080/mp.weixin.qq.com/s/wechat-expand.html   # L3 展开全文
curl -sf http://127.0.0.1:18080/fixtures/article.html                   # 通用网页
```

路径里保留 `mp.weixin.qq.com/s/` 是为了命中 `templates.json` 的 `urlPattern`；URL 的 host
仍是 `127.0.0.1`，所以**不需要**改 `/system/etc/hosts`。

### 2.3 用广播注入配置 / 状态

Action `com.cnife.listenreader.DEBUG_CONTROL`，命令通过 `--es cmd <命令>` 指定。
用 `-p com.cnife.listenreader.nmtest` 把广播限定到测试包（避免命中其他调试构建）。

```bash
PKG=com.cnife.listenreader.nmtest
ADB="adb shell am broadcast -a com.cnife.listenreader.DEBUG_CONTROL -p $PKG"

# 指向本地 mock（空串可复原生产端点；千问 mock 端点与 StepFun 同为 /v1）
$ADB --es cmd configure \
  --es stepfun_endpoint http://127.0.0.1:18080/v1 \
  --es stepfun_key mock-stepfun-key \
  --es qwen_endpoint http://127.0.0.1:18080/v1 \
  --es qwen_key mock-qwen-key \
  --es llm_endpoint http://127.0.0.1:18080/v1 \
  --es llm_model mock-model \
  --es llm_key mock-llm-key \
  --es update_endpoint http://127.0.0.1:18080/repos/CNife/listen-reader/releases/latest \
  --ei prefetch_count 6 --es theme dark

# 预置一条可直接打开的朗读资源（默认 4 句，open=true 直接进播放页）
$ADB --es cmd seed_resource --es title "调试测试文章" --ez open true

# 抓当前状态（单行 JSON）
$ADB --es cmd dump_state
adb logcat -d -s ListenReaderDebug:I | tail -n 1

# 清空数据库 / 音频 / 快照 / 调试端点覆写
$ADB --es cmd clear_state
```

命令清单：

| `cmd` | 作用 | 关键 extra |
| --- | --- | --- |
| `configure` | 覆写 StepFun / 千问 / 更新源端点并写设置 | `stepfun_endpoint`、`stepfun_key`、`qwen_endpoint`、`qwen_key`、`llm_endpoint`、`llm_model`、`llm_key`、`update_endpoint`、`tts_model`、`voice`、`theme`、`prefetch_count`(int)、`playback_speed`(float) |
| `seed_resource` | 落一条 Resource + 规则分段 | `title`、`text`、`source_kind`（`TEXT`/`URL`）、`source_url`、`open`(bool) |
| `dump_state` | 输出单行 JSON 状态快照 | — |
| `clear_state` | 清库、清音频/快照/试听目录、复原端点 | — |

所有命令都输出一行 JSON（`TAG=ListenReaderDebug`），失败也只会是
`{"ok":false,"cmd":"…","error":"…"}`，不会让应用崩溃。

`dump_state` 字段：`stepFunEndpoint`、`qwenEndpoint`、`updateEndpoint`、`library`（`isLoading`/`itemCount`/`continueId`/`error`）、
`player`（`resourceId`/`title`/`currentIndex`/`isPlaying`/`isPreparing`/`segmentCount`/
`readyClipCount`/`generatingClipCount`/`failedClipCount`/`error`）、`capture`（`mode`/`url`/
`isExtracting`/`l2Status`/`previewSegmentCount`/`savedResourceId`/`error`）、
`settings`（主题、TTS 模型、音色、预取档位、BYOK 配置态，以及 `update`：`currentVersion`/
`autoCheck`/`releaseVersion`/`releaseUrl`/`ignored`/`check`）、`segments`。

### 2.4 驱动 UI

优先用 `hs`（handsets），比裸 `adb` 省事：

```bash
hs use                  # 选择设备
hs ui                   # 列可点节点
hs see /tmp/screen.png  # 截图存盘；本机没有 handsets-viewer 时裸 `hs see` 会失败
hs tap "添加内容"     # 按文字点击
hs logs --follow
```

### 2.5 清理

```bash
adb uninstall com.cnife.listenreader.nmtest
adb reverse --remove-all
pkill -f "[s]cripts/mock-server.py"   # 方括号模式不匹配自身命令行，避免 pkill 杀掉当前 shell
./gradlew --stop
adb shell am force-stop com.cnife.listenreader
git status --porcelain   # 应无遗留的构建脚本或源码改动
```

## 证据怎么给

在 PR / 报告里贴可直接复核的原始输出，而不是结论词：

- 快照：PNG 路径 + 你看图得出的具体判断（哪个区域、什么不对/正常）
- mock：`GET /healthz` 的 JSON、`START/END` 日志行（并发与峰值）
- 广播：`dump_state` 的单行 JSON
- 设备：`hs see` 截图，或 `hs ui` 中目标节点的文本与 `bounds`

测试步骤的完整说明另见 `.no-mistakes.yaml` 的 `test.instructions`。

## 附录：手工驱动网页提取宿主

`JsExtractorContractTest` 背后是 `scripts/js-extract-harness.mjs`：把 `readability.js`、`l1.js`、
`l3.js` 和 `templates.json` 加载进 jsdom，所以三条提取路径（模板、Readability、密度）都在覆盖内，
且 fixture 里的 `onclick` 会真的执行。

前置：Node 24+。jsdom 是 devDependency，由 `app/build.gradle.kts` 的 `installJsTestDeps`
任务自动 `npm ci`（依赖未变时 UP-TO-DATE）；新 worktree 首次跑测试不再需要手工装。

调试提取逻辑时可以绕过 Gradle 直接开会话（stdin 一行命令、stdout 一行结果）：

```bash
node scripts/js-extract-harness.mjs --fixture wechat-article.html --url https://mp.weixin.qq.com/s/demo
{"op":"meta"}                                  # readability.js 是否加载、解析出多少正文
{"op":"extract"}                               # 完整 L1 结果：extractor / title / markdown / needsExpand
{"op":"collect","phrases":["展开全文","继续阅读"]}  # L3 候选，安全性由 Kotlin 的 L3Rules 判定
{"op":"click","index":0}                       # 点第 0 个候选，返回 "true"/"false"
```

页面几何与 `innerText` 由脚本内的最小垫片提供（jsdom 没有布局）；改垫片前先确认是宿主差异而不是提取脚本的问题。
fixture 在 `app/src/androidTest/assets/fixtures/`，`connectedDebugAndroidTest` 也共用它们。
