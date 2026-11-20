## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues at `CNife/listen-reader`. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context domain docs: root `GLOSSARY.md` and `docs/adr/`. See `docs/agents/domain.md`.

## 自验证阶梯

每次改动按需升级，不要跳过便宜的阶梯去跑设备：

- **紧循环（每次必跑）**：`ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ./gradlew :app:testDebugUnitTest --console=plain`。一次运行同时覆盖业务逻辑、Compose 语义、`ArchitectureContractTest`（架构契约）、`JsExtractorContractTest`（经 jsdom 真实执行 `readability.js` + `l1.js` + `l3.js`）、`DebugControlReceiverTest`。首次需要 `npm ci`（Node 24+，装 jsdom）；改了接口或数据模型时加跑 `:app:compileDebugAndroidTestKotlin`。
- **视觉快照（改 Compose 布局/样式/主题时）**：`./gradlew :app:testDebugUnitTest --tests "com.cnife.listenreader.ui.UiSnapshotTest"`，再用 `read` 打开 `app/build/outputs/roborazzi/` 下对应 PNG 检查真实渲染。
- **免改源码 E2E（触及 WebView / Media3 / 真实 TTS 播放链路时）**：见下方上下文指针。

## 架构不变量

- **单一状态编排**：`service/` 是唯一状态编排层，默认运行在 `Dispatchers.Main.immediate`。数据层、网络与文件 IO 的异常必须在 `service/` 捕获并写入用户当时可见的 `State.error`；初始化、打开资源、刷新资料库、设置写入等兄弟入口共用同一兜底。
- **IO 走 suspend**：阻塞 API 改为 suspend，由协程调度；`service/` 不使用 `runBlocking`。
- **推导状态同步**：新增「当前预览/当前资源」这类推导字段时，所有生成新预览或切换资源的路径都同步更新它。
- **最小端口表面**：`service/ports/` 只声明生产代码实际调用的成员。
- **Schema 同步**：改 Room 实体或数据库版本时，重新运行 `:app:kspDebugKotlin` 并提交 `app/schemas/` 下的导出 JSON。

「最小端口表面」与「Schema 同步」由 `ArchitectureContractTest` 在每个单测运行中校验；前三条由 `.no-mistakes.yaml` 的服务层审阅规则把关。

## 上下文指针

- **视觉截图、L1/L3 提取调试，或实机/模拟器端到端验证**（`hs` 驱动设备、`-PappIdSuffix=.nmtest` 隔离安装、`DebugControlReceiver` 广播注入、`scripts/mock-server.py` 本地 mock）→ 先读 `docs/agents/verification.md`。
- **领域建模、命名新类型/测试、做架构取舍** → 先读 `GLOSSARY.md`、`docs/adr/` 与 `docs/agents/domain.md`，严格使用术语表词汇（`Resource`、`Source`、`Script`、`Segment`、`AudioTrack`、`Voice`、`Capture`）。
- **查询、创建、认领、阻塞关联或关闭需求/缺陷单（含 Wayfinder 地图）** → 先读 `docs/agents/issue-tracker.md`（GitHub Issues @ `CNife/listen-reader`）。

## 调试

- 优先用 `hs`（handsets）驱动真机/模拟器，比裸 `adb` 更方便。命令与清理步骤见 `docs/agents/verification.md`。

## No-mistakes 发布流程

发布分支时：

1. 在功能分支上提交改动。
2. 运行 `no-mistakes axi run --intent "<本次要达成的目标>"`：依次执行 intent → review → test → document → lint → push → PR → CI。
3. PR 由潭渊审阅批准后合并。
