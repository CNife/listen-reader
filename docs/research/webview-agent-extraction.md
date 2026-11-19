# Android WebView 网页正文提取与页内 Agent 调研（L1 / L2 / L3）

> 调研时间：2026-11-19。目标读者：`listen-reader`（本地优先、BYOK、无自建后端）的采集与朗读链路设计者。
> 本文所有「已验证」结论都附一手来源（仓库源码 / 官方文档 / 本地实测），推断性内容显式标注 `[INFERENCE]`。

---

## 0. 结论先行

1. **L1（本地 DOM 提取）在 Android WebView 里是完全可行的，而且比在任何 JVM/Node 环境里都"真"**：WebView 就是 Chromium，`@mozilla/readability`、`defuddle`、`turndown` 可以原样注入执行，并且能拿到**真实 layout**（`getComputedStyle`、`getBoundingClientRect`、`elementFromPoint`）——这是 JSDOM / linkedom 做不到的，而 Defuddle 的"隐藏元素剔除"与 page-agent 的"可点元素判定"正依赖这些 API。
2. **不要用 Readability4J**：它是 2018 年 Readability.js 的 Kotlin 移植（对齐 commit `834672e`，2018-02），仓库最后提交 2021-10，依赖 jsoup 1.11.2 / Kotlin 1.3。上游 Readability.js 已到 `0.6.0`（2025-03-03，含大量性能与解析修复）。用 JVM 移植等于放弃 7 年修复。
3. **单一提取器都不够**。生产级中文工具的做法是「站点模板 → 多提取器并行竞争 → 打分择优 → `<article>` 兜底」（Wechatsync 的 `reader/index.ts` 就是这么写的），再叠加 L3 兜底。Readability 家族的选择器启发式（`unlikelyCandidates` 正则、`<p>` 打分）是为英文博客调出来的；微信公众号正文是 177 个 `<section>` + 949 个内联 `style` 的嵌套结构，靠通用算法不稳。
4. **L2 的本质是"把 13.6 : 1 的 HTML/正文比压回 1 : 1"**。实测一篇微信公众号技术文章：整页 HTML **4.50 MB**，正文片段 **282 KB**，纯文本 **20.7 K 字符**（HTML : 文本 ≈ 13.6 : 1）。Jina Reader / Crawl4AI / Firecrawl 三家都在做同一件事：Readability 式裁剪 → 去隐藏元素/脚本/SVG → 转 Markdown → 按 token 预算截断。
5. **L3（页内 Agent）是唯一可行的交互兜底架构**。Playwright/Puppeteer 在独立 App 内不可用（Android 支持要 adb + Chrome flag），WebView 的 CDP 只以 `webview_devtools_remote_<pid>` 抽象 unix socket 形式存在且被 `CanUserConnectToDevTools` 门禁。而 `alibaba/page-agent`（MIT，v1.12.4）证明了"纯页内 JS + 索引化 DOM + LLM 循环"可行，本项目的落地形态是：**页内运行时（isolated world）+ Kotlin 侧 LLM 调用 + `WebMessage` 桥**，API Key 永不进入页面上下文。

推荐流水线（详见 §5、§6）：

```
URL ──► 站内 WebView 加载（复用登录态）
     ─► 等待 DOM 稳定（readyState + MutationObserver 静默 + postVisualStateCallback）
     ─► L1: 站点模板 selector ─┬─► 命中 → 正文 DOM
                              ├─► Defuddle（浏览器全量版）─┐
                              └─► Readability ────────────┴─► 打分择优 → 归一化
     ─► L2: 去噪 → Markdown → 分块（按标题）→ LLM 结构化 → Script/Segment
     ─► 若 L1 全部低分（<300 字 / 命中"展开全文""登录后查看"）→ L3 页内 Agent 补交互 → 回 L1
```

---

## 1. 分层模型与本项目约束

### 1.1 三层定义

| 层 | 职责 | 运行位置 | 输出 | 失败即降级到 |
|---|---|---|---|---|
| **L1 本地 DOM 提取** | 从已渲染 DOM 里切出正文主块，去掉导航/评论/侧栏，转 Markdown | WebView 内 JS | `Article{title, byline, published, markdown, html, textLength}` | L3 |
| **L2 LLM 就绪蒸馏** | 压 token、修结构、做语义清洗（分段/去重/修复断行/生成语段） | Kotlin 侧（网络） | `Script{segments[]}` | 纯规则结果 |
| **L3 页内 Agent** | 点击"展开全文"、关闭弹窗、滚动触发懒加载、切换回答、多步骤取文 | WebView 内 JS 运行时 + Kotlin 侧 LLM 循环 | 新的 DOM 状态 → 回到 L1 | 人工接管 |

### 1.2 本项目硬约束（决定了选型）

| 约束 | 影响 |
|---|---|
| 无自建后端 | 不能依赖 Jina Reader / Firecrawl / Crawl4AI 之类服务端管道；凡是"起个 headless Chrome"的方案一律排除 |
| BYOK（用户自带 key） | LLM 调用只能在 Kotlin 侧发起；**页面 JS 内不得出现 key**（页面脚本可读全局变量） |
| 需要登录态站点（知乎/雪球/公众号付费） | 只能靠**同一个 WebView 实例的 CookieManager 会话**渲染后取 DOM；裸 HTTP 拿不到（§2.7 实测） |
| 离线归档（Source 需要本地快照） | 需要 `saveWebArchive`（MHTML）或 `outerHTML + 资源本地化`，见 §2.9.4 |
| 移动端内存/性能 | 4.5 MB HTML 的页面在低端机解析要几百 ms 到秒级；提取器体积按 gzip 计要控制在 ~100 KB 量级 |

### 1.3 版本基线（本地 clone 校验，2026-11-19）

| 组件 | 版本 | 最后提交 | 备注 |
|---|---|---|---|
| `mozilla/readability` | `0.6.0`（2025-03-03） | 2026-07-09 | Apache-2.0；`Readability.js` 88.8 KB raw / **24.6 KB gzip** |
| `kepano/defuddle` | `0.19.7` | 2026-11-18 | MIT；core 预算 **92 KB gzip**、full **215 KB gzip**（CI 预算，含 ~10% 余量） |
| `dankito/Readability4J` | `1.0.8` | **2021-10-27** | Apache-2.0；对齐 Readability.js `834672e`（2018-02） |
| `mixmark-io/turndown` | `7.2.5` | 2026-11-04 | MIT；Node 侧用 `@mixmark-io/domino`，浏览器侧有 UMD 构建 |
| `jina-ai/reader` | 主干 | 2026-05-22 | 服务端 Node + headless Chrome |
| `unclecode/crawl4ai` | `0.9.5` | 2026-11-17 | Python，`PruningContentFilter` 等 |
| `firecrawl/firecrawl` | 主干 | 2026-11-19 | TS/Go/Rust，`onlyMainContent` 默认 true |
| `alibaba/page-agent` | `1.12.4` | 2026-11-18 | MIT；`@page-agent/page-controller` 可单独复用 |
| `browser-use/browser-use` | `1.0.6` | 2026-11-19 | 已转向 CDP + REPL；DOM 树 JS 停留在 `0.5.9` |

---

## 2. L1：本地 DOM / 阅读模式提取

### 2.1 候选总览

| 方案 | 语言/运行环境 | 体积 | 维护 | WebView 内可直接跑 | 中文/中文站点 | 输出 |
|---|---|---|---|---|---|---|
| `@mozilla/readability` | JS（需 DOM） | 24.6 KB gzip | 活跃（Firefox Reader View 同源） | ✅ 注入即用 | 一般（英文启发式） | HTML / 纯文本 / 元数据 |
| `defuddle` core | TS（需 DOM） | ≤92 KB gzip | 极活跃（Obsidian Web Clipper 内核） | ✅ 原生浏览器构建 | 较好（更"宽容"）+ 站点 extractor | HTML |
| `defuddle/full` | 同上 + turndown | ≤215 KB gzip | 同上 | ✅ | 同上 | HTML + Markdown |
| `Readability4J` | Kotlin/JVM + jsoup | 小 | **停滞 5 年** | ❌（JVM 侧，脱离渲染 DOM） | 一般 | HTML / 文本 |
| `turndown` | JS | 浏览器 UMD | 活跃 | ✅ | 通用（CJK 无特殊处理） | Markdown |
| `dom-to-semantic-markdown` | TS | 未知（1.5.0） | 中等（1k★） | ✅ | 未验证 | 语义 Markdown |
| `adbar/trafilatura` | Python（另有 Go/Rust/Node 绑定） | 不适用 | 很活跃（7.1k★，Apache-2.0） | ❌ 需 Python/原生绑定 | 好（规则式 + jusText/readability-lxml 兜底） | 文本 / JSON / XML / Markdown |
| `@postlight/parser` | Node（cheerio + request） | 不适用 | 低频（最后推送 2024-07，Apache-2.0） | ❌ Node 专用 | 一般 + 自定义站点 extractor | JSON |

**结论**：L1 用「站点模板 + Defuddle(full) + Readability 竞争」，turndown 作为唯一 Markdown 出口；Readability4J 排除。

---

### 2.2 `@mozilla/readability`

一手来源：<https://github.com/mozilla/readability>（README / `Readability.js` / `CHANGELOG.md`）。

**关键 API（原文核对）**

- `new Readability(document, options).parse()` 返回 `{title, content(HTML 字符串), textContent, length, excerpt, byline, dir, siteName, lang, publishedTime}`。
- 选项：`charThreshold`（默认 **500**，低于此字数返回 `null`）、`maxElemsToParse`（默认 0 = 无上限）、`nbTopCandidates`（5）、`keepClasses`（默认 false）、`classesToPreserve`、`disableJSONLD`、`serializer`（`el => el` 可直接拿 DOM 元素而不是字符串）、`linkDensityModifier`、`allowedVideoRegex`。
- `parse()` **会修改传入的 DOM**（文档明确写了 "works by modifying the DOM"），所以必须传 `document.cloneNode(true)`。
- `isProbablyReaderable(document, {minContentLength:140, minScore:20})` 是快速预判，文档直言"likely to produce both false positives and false negatives"。
- 安全：官方建议对输出用 DOMPurify + CSP 消毒，"Sanitizing unsafe content is explicitly not something we aim to do as part of Readability"。

**算法实质（源码）**

- 打分对象只有 `p / td / pre`，以及"内部没有块级子元素"的 `div`（会被就地 `_setNodeTag(node, "P")`）；**`<section>` 不在打分对象里**。单段得分 = `1 + 逗号数 + min(len/100, 3)`（短于 25 字符的段落直接跳过），再按祖先层级除以 `1 / 2 / level*3` 累加到候选容器（`Readability.js:_grabArticle`）。
- 删除阶段依赖类名/ID 正则：`unlikelyCandidates`（`-ad-|banner|breadcrumbs|combx|comment|community|...|sidebar|social|sponsor|...`）与 `okMaybeItsACandidate`（`and|article|body|column|content|main|...`），两份正则同步维护在 `Readability.js` 与 `Readability-readerable.js`。
- 这意味着：**类名全哈希（CSS-in-JS）、正文用 `<section><span>` 堆叠（微信公众号就是）、或类名是中文/无语义**的页面，打分与删除都会退化。

**在 Android WebView 的适配要点**

- 注入方式：把 `Readability.js` 打成 IIFE 资源（`assets/js/readability.iife.js`），通过 `addDocumentStartJavaScript` 或 `evaluateJavascript` 注入；在页面内 `new Readability(document.cloneNode(true), {charThreshold: 300, maxElemsToParse: 30000})` 执行。
- `maxElemsToParse` 一定要设：微信文章整页 4.5 MB，元素数可达数万；不设上限在低端机上是可感知的卡顿源。
- `charThreshold: 500` 对中文/短图文偏高（中文 500 字已是有分量的长文）；建议降到 200–300，并用自己的长度判断兜底。

**失败模式**

| 症状 | 机制 | 处置 |
|---|---|---|
| `parse()` 返回 `null` | 正文 < `charThreshold`，或最高分候选仍低于阈值 | 降 `charThreshold`；改走模板 selector |
| 正文被切成几段 / 只拿到片段 | 正文用 `<section>` 包裹（不在打分对象里）；只有"无块级子元素的 `div`"会被改名成 `p` | 预处理：把 `<section>` 归一成 `<p>`/`<div>`，或直接用模板 selector 定位容器 |
| 图片全丢 | 懒加载 `src` 是占位图，真图在 `data-src` | 预处理把 `data-src/data-original/data-actualsrc/_src` 提升为 `src` |
| 中文段落粘连 | 空白折叠 + 行内 `<span>` 换行 | 后处理按 CJK 标点/换行重建段落 |

---

### 2.3 `kepano/defuddle`（Obsidian Web Clipper 内核）

一手来源：<https://github.com/kepano/defuddle>（README、`src/defuddle.ts`、`src/markdown.ts`、`scripts/check-bundle-size.mjs`、`src/index.full.ts`、`AGENTS.md`）。

**定位（README 原文）**：Defuddle 是 Obsidian Web Clipper 的提取内核，可作为 Readability 的替代，差异是：

- **"More forgiving, removes fewer uncertain elements"**（更宽容、少删不确定元素）——对中文/复杂布局更友好；
- 统一脚注/数学/代码块的输出形态；
- **用页面的 mobile 样式猜测哪些元素是多余的**；
- 抽取更多元数据（含 schema.org）。

**能力清单（README 选项表核对）**

| 选项 | 默认 | 对本项目的意义 |
|---|---|---|
| `contentSelector` | — | **绕过自动检测，直接指定正文档容器**（站点模板的注入点） |
| `removeHiddenElements` | true | 用 `getComputedStyle` 判断隐藏（源码 `src/defuddle.ts:1013` "Remove hidden elements using computed styles"）→ **只有真实渲染环境才准，WebView 正好满足** |
| `removeSmallImages` | true | 去掉图标/像素埋点 |
| `removeLowScoring` | true | 按分数删导航/链接列表 |
| `markdown` / `separateMarkdown` | false | `defuddle/full` 才带 turndown 转换 |
| `standardize` | true | 脚注/标题/代码块规范化；与自有预处理冲突时可关掉 |
| `useAsync` | true | 允许站点 extractor 去第三方 API 取数（**离线 App 建议关**） |
| `debug` | false | 返回 `debug.removals` 与 `debug.contentSelector`，线上排障很有用 |

**工程事实**

- 三个构建：`defuddle`（core，无依赖，仅 HTML）、`defuddle/full`（含 turndown、数学转换）、`defuddle/node`（接受任意 DOM 实现）。
- CI 体积预算：core **92 KB gzip**、full **215 KB gzip**（`scripts/check-bundle-size.mjs`）。
- full 里 `mathml-to-latex` / `temml` 是 **externals**，缺依赖时数学回退**静默失败**（`src/markdown.ts:906` 注释 "Fails silently when mathml-to-latex is unavailable"）。
- 内置 `src/extractors/` 共 **28** 个站点 extractor（bilibili / github / medium / reddit / youtube / wikipedia / nytimes / substack / twitter …），**没有任何中文站点**（无 weixin / zhihu / xueqiu / weibo）→ 中文站点模板必须自己做。
- 安全约束（`AGENTS.md`）：内部禁用 `innerHTML`，走 template 解析；清洗 `javascript:`/`blob:`/非图片 `data:`；由 `_stripUnsafeElements()` 保证。

**隐藏内容恢复机制（值得抄）**：如果"剔除隐藏元素"后得到的词数远小于不剔除的版本（`hiddenRetry.wordCount > result.wordCount * 2`），Defuddle 会重跑一次并接受结果；再退化到"直接把最大的隐藏子树当正文"（`src/defuddle.ts:124-150`）。

---

### 2.4 `dankito/Readability4J`（不推荐）

一手来源：<https://github.com/dankito/Readability4J>（README / `build.gradle`）。

- 自述："a Kotlin port of Mozilla's Readability.js"，输出"与 Firefox Reader View 完全一致（除 Jsoup 空白差异）"。
- `build.gradle`：`version '1.0.8'`、`kotlin_version = '1.3.72'`、`jsoupVersion = '1.11.2'`、`sourceCompatibility = 1.7`。
- README 的兼容表只列到 `1.0.1 → 834672e (2018-02-27)`；仓库最后提交 **2021-10-27**。
- 有 `Readability4JExtended`，补了 `data-src` 懒图、`<base href>` 相对链接、更好的图片保留策略。

**淘汰理由（三条，按重要性）**

1. **上游差距**：Readability.js 0.6.0（2025-03）包含 10+ 项解析/性能修复（PR #894/#892/#893/#915 等），Readability4J 一个都没有。
2. **架构错位**：它是"HTML 字符串进 → HTML 字符串出"，吃不到 WebView 的真实 layout（无法用 computed style 判断隐藏、无法 `elementFromPoint`）。在已经有渲染 DOM 的前提下，多一次序列化 + 重新解析纯属浪费。
3. **依赖陈旧**：jsoup 1.11.2（2017）在 Android 上有已知的解析/内存问题，Kotlin 1.3 与 AGP 新版组合也有摩擦。

> 如果确实需要"无 WebView 的纯 JVM 提取"（例如后台批量重跑快照），更好的做法是：用 WebView 离线加载快照 → 注入 Readability.js。同一份代码、同一套行为，避免双实现漂移。

---

### 2.5 `mixmark-io/turndown`

一手来源：<https://github.com/mixmark-io/turndown>（README / `package.json` / `src/commonmark-rules.js`）。

- `7.2.5`，MIT；浏览器构建 `dist/turndown.js`（UMD），Node 侧依赖 `@mixmark-io/domino`。
- 接受**字符串或 DOM 节点**：`turndownService.turndown(document.getElementById('content'))` —— 在 WebView 里可以直接喂 Element，省一次序列化。
- 扩展：`addRule(key, {filter, replacement})`、`keep()`、`remove()`、`use(plugin)`；GFM 表格/删除线需要 `turndown-plugin-gfm`。
- 关键选项：`headingStyle`（默认 `setext`，**建议改 `atx`**，中文小标题用 `##` 更稳）、`codeBlockStyle`（默认缩进，**建议 `fenced`**）、`bulletListMarker`、`linkStyle`、`preformattedCode`。

**已知不足与对策**

| 不足 | 对策 |
|---|---|
| 默认不处理表格（需插件） | 引入 GFM 插件；或自定义 `table` 规则（Defuddle 的 `src/markdown.ts:250` 就是这么干的，还处理了 `colspan/rowspan` 降级） |
| `<br>` 与连续空行语义丢失 | 自定义规则把 `<br><br>` 映射成 `\n\n` |
| 代码块里的行号/高亮 span 会污染文本 | 转换前把 `<pre>` 内文本取 `innerText`（Wechatsync 的 `backupAndSimplifyCodeBlocks` 就是这个思路，且强调"`innerText` 只在真实 DOM 上正确"） |
| 数学公式变成一堆 `<span>` 文本 | 数学单独预处理（KaTeX/MathML → LaTeX），不要让 turndown 直接面对 |
| CJK 空格 | 后处理清理 CJK 之间的多余空格（`(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])`） |

---

### 2.6 `dom-to-semantic-markdown`

一手来源：<https://github.com/romansky/dom-to-semantic-markdown>（npm `dom-to-semantic-markdown@1.5.0`，MIT，~1k★，周下载 32K）。

卖点：面向 LLM 的语义 Markdown（保留 `header/nav/footer` 语义）、元数据（OG/Twitter Card/JSON-LD）、**URL 归一化以省 token**、**主内容检测**、表格列标识（`<!-- col-0 -->`）便于模型跨行对齐。

评价：

- 表格列标识与 URL 归一化是它相对 turndown 的真实增量，但如果 L1 已经用 Defuddle 切出正文，turndown + 自定义规则足够，多引一个库收益有限。
- 它对"语义结构"的保留（保留 nav/footer）与本项目"只要正文"的目标相反，需要再裁剪。
- 结论：**作为 turndown 的备选，不作为主路径**。

#### 非 JS 生态的两个对照：trafilatura 与 postlight/parser

**`adbar/trafilatura`**（<https://github.com/adbar/trafilatura>）

- Python 包 + CLI，**Apache-2.0**，7.1k★，最近推送 2026-11-18；论文：ACL 2021 demo（`10.18653/v1/2021.acl-demo.15`）。
- README 自述：自有**规则式**主文本提取器，并以 `jusText` 与 `readability-lxml` 作为回退；输出支持 `txt/json/xml/markdown`，带 `title/author/date/site name/categories/tags` 元数据；设计目标是在 precision（少噪声）与 recall（不漏内容）之间取平衡。
- **没有任何官方 JS/JVM 实现**：社区移植只有 Go（`markusmobius/go-trafilatura`，154★）、Rust（`Murrough-Foley/rs-trafilatura`，71★）以及一个 Node N-API 绑定（`gorango/napi-rs-trafilatura`）——三者都**不能**注入 WebView（原生二进制 / 非 JS 运行时）。
- 对 Android 的唯一用法是"打包 Python 运行时（Chaquopy 等）"——为了一个提取器引入完整 Python 运行时，体积与启动成本都不可接受。

**`@postlight/parser`**（<https://github.com/postlight/parser>，前身 `mercury-parser`）

- Node 模块（cheerio + request），**Apache-2.0**，最后推送 2024-07；驱动 Postlight Reader 浏览器扩展。
- 值得抄的是它的**站点定制模型**：每个域名一个 JS 模块，用 CSS selector 描述 `title/author/date/content`（`src/extractors/custom/*`），这与本项目"模板 JSON"是同一思路的不同形态。
- 但它依赖 Node 内部能力（HTTP 抓取、cheerio 解析），**不能直接注入 WebView**；在浏览器侧它走的是扩展的 fetch 权限。

> 这两个项目的价值是**方法论对照**（规则式打分 / 每域定制 selector），不是可直接复用的依赖。选型上应明确排除，避免"为了一个提取器塞一个 Python 运行时"。

---

### 2.7 中文站点：渲染 DOM vs 裸 HTTP（2026-11-19 实测）

用 Android Chrome 130 UA、无 Cookie 的裸 `curl` 探测（结果仅代表当日、该 UA、无登录态）：

| 站点 | 结果 | 证据 |
|---|---|---|
| 微信公众号 `mp.weixin.qq.com/s/...` | **200，4,501,834 字节**；`id="js_content"` 存在但 `style="visibility: hidden; opacity: 0;"`，由页面内联脚本 `document.getElementById('js_content').style.removeProperty('visibility')` 解除；`data-src=` 出现 22–30 次（懒加载图片） | 本地抓取分析 |
| 知乎 `www.zhihu.com/question/...` | **403，650 字节**反爬壳（含 `zh-zse-ck` meta），正文完全拿不到 | 本地抓取分析 |
| 雪球 `xueqiu.com` | **200，27,932 字节**，`<title>Loading interface...</title>` ×6，内容是 JS 注入的骨架；未登录态无文章数据 | 本地抓取分析 |

**对设计的直接结论**

1. **裸 HTTP 提取对中文平台不可靠**：知乎直接 403，雪球给骨架，微信虽然给全量 HTML 但 4.5 MB 且正文初始不可见。→ "服务端抓取 + Readability" 这条路线在本项目里直接不成立，**必须以已渲染 WebView 为唯一 DOM 来源**。
2. **微信文章的 DOM 成本结构**：整页 4.50 MB；`#js_content` 之后到 `rich_media_tool` 之间的正文片段 **281,619 字符**，其中纯文本 **20,720 字符**（CJK 4,535 字），HTML : 文本 ≈ **13.6 : 1**；正文区含 **177** 个 `<section>`、**949** 个 `style="..."`、22 个 `data-src`、19 个 `<img>`、13 个 `<svg>`。
   - 这个结构正好打中 Readability 的两个弱点：没有 `<p>` 只有 `<section>`；类名/ID 无英文语义。
   - 也说明 L2 的压缩空间巨大：**13.6 : 1 的 HTML 冗余**，去 style/属性 + 转 Markdown 后 token 可降一个数量级。
3. **登录态是资产**：知乎/雪球必须在用户已登录的 WebView 里取 DOM。设计上要保证"用户浏览用的 WebView"和"提取用的 WebView"共享同一 `CookieManager`（默认 profile 即可），且不要在提取时清 cookie。

**站点模板（site adapter）是最有效的第一跳**，这也是所有中文工具的共识：

- 简悦 SimpRead README 明确写"**逐一适配了数百种类型的网站**，自动提取标题/描述/正文/媒体资源，生成符合中文阅读的页面"（<https://github.com/Kenshin/simpread>）——即：通用算法不够，靠站点规则补齐。
- Obsidian Web Clipper 用"模板 + `selector:` 变量 + 触发器（URL/schema）"实现同样的能力（§2.8）。
- Wechatsync 在 content script 里先按域名分流：`mp.weixin.qq.com` 直接 `#activity-name` / `#js_content`，其余走通用竞争（§2.8）。

**本项目可以立刻写死的模板（已验证存在）**

| 站点 | 标题 | 正文 | 备注 |
|---|---|---|---|
| 微信公众号 | `#activity-name` | `#js_content` | 需先 `style.removeProperty('visibility')`（页面脚本正常加载后它会自己做，但注入脚本不能假设）；`og:image` / `og:description` 取封面与摘要 |
| 知乎 | `document.title` | 未验证（历史上的 `RichText`/`ztext` 随改版变动） | 未登录裸取 403；必须在登录态 WebView 内探测，建议配"多候选 selector + 打分" |
| 雪球 | `document.title` | 未验证 | 同上；且内容由 JS 注入，需等 DOM 稳定 |

> 模板要以**数据文件**形式随 App 更新（可内置 JSON，字段：`hostPattern`、`titleSelector`、`contentSelector`、`removeSelectors`、`waitForSelector`、`version`），而不是硬编码在代码里——站点改版时能热修。

---

### 2.8 站点模板 / 主内容选择器：三家怎么做的

#### Obsidian Web Clipper（<https://github.com/obsidianmd/obsidian-clipper>）

- 提取内核：`src/api.ts` 里 `new DefuddleClass(doc, {url})` → `defuddleResult.content` → `createMarkdownContent()` 转 Markdown。
- 模板变量：`{{title}} / {{content}} / {{selector:.foo}} / {{selectorHtml:.foo?attr}} / {{schema:@Article:...}} / {{prompt:"..."}}`（`src/utils/resolver.ts:77`、`src/utils/template-compiler.ts:98`）。
- 模板触发器：先按 URL 匹配，再按 schema 匹配（`src/api.ts` 中 `matchSchemaPattern`），一个页面命中最具体的模板。
- **`{{prompt:"..."}}` 是 BYOK 的 LLM 变量**：`providers.json` 内置 anthropic / azure-openai / deepseek / google-gemini 等 provider（各自 `baseUrl` + `popularModels`），`src/utils/interpreter.ts` 里 `max_tokens` 取 1600 / 8000，并对 `finishReason === 'max_tokens'` 显式报"输出被截断"而不是静默保存半截。

> 对我们的启发：**"模板 + selector 变量 + LLM 变量"三件套可以直接照搬成"采集规则 + 清洗 prompt"**；这也是"BYOK 客户端做 LLM 后处理"的成熟先例。

#### Defuddle

- `contentSelector` 选项：显式指定主内容元素，跳过自动检测（README 选项表）；`debug: true` 会回显最终使用的 selector。
- `src/extractors/*`：按域名 `matches` 注册，先于通用算法执行（`extractor-registry.ts`）。
- `src/content-boundary.ts`：用"标题锚点 + 向后找第一个散文块"的方式裁掉正文起始之前的样板（byline/日期/导语噪声），并用 `PROSE_MIN_WORDS = 7`、链接文本占比 > 0.7 判负、`DIV` 内无 `<p>` 判负等规则避免误判。

#### SimpRead

- 站点适配规则是**云端规则库**（`simpread.ksria.cn/sites/`，README 提到"数百种"），本地仓库只有运行时代码与 turndown 等 vendor；说明其模式是"规则外包 + 客户端执行"，与本项目"内置规则 + 可更新"一致。

#### Wechatsync（最贴近我们要做的事）

`packages/extension/src/lib/reader/index.ts` 的策略（源码原文）：

1. 平台特化：`mp.weixin.qq.com` → `#activity-name` + `#js_content`；
2. 通用：**Safari `ReaderArticleFinder` / Defuddle / Readability 三者并行执行，按 score 择优**；
3. `<article>` 标签兜底；
4. 在页面内用 Turndown 直接转 Markdown（"Service Worker 只需处理 Markdown，无需 DOM 解析"）。

打分函数（`scoreResult`，可直接照抄的工程启发式）：

```
len = textContent.length
score += 20 if len > 200;  += 10 if len > 500;  -= 10 if len > 50000   // 太长可能带评论/侧栏
score += min(round(textLength/htmlLength * 50), 30)                    // 文本密度
score += 10 if /<h[1-3]/; += 5 if /<pre/; += 5 if /<img/                // 结构完整性
score += 5 if title !== document.title; += 3 if excerpt; += 2 if byline
```

> 这套打分的价值不在精确，而在**同一次页面里横向比较**：三个提取器对同一 DOM 的偏差方向不同，取分最高者能显著降低"整篇丢正文"的概率。

---

### 2.9 在 Android WebView 内执行 L1 的三种注入方式

#### 2.9.1 `WebView.evaluateJavascript(script, callback)`

官方定义（<https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E)>）：

> "Asynchronously evaluates JavaScript in the context of the currently displayed page. If non-null, `resultCallback` will be invoked with any result returned from that execution. This method must be called on the UI thread and the callback will be made on the UI thread."

配套的**兼容性备注**（同一文档，非常关键）：

> "Applications targeting N or later, JavaScript state from an empty WebView is no longer persisted across navigations like `loadUrl`. For example, global variables and functions defined before calling `loadUrl` will not exist in the loaded page. Applications should use `addJavascriptInterface` instead to persist JavaScript objects across navigations."

→ 结论：**每次导航后运行时都会消失**，必须在 `onPageStarted/onPageFinished` 或 document-start 阶段重新注入。

其他实操约束：

- 返回值只有字符串（对象会被序列化成 `{}` 之类），必须自己 `JSON.stringify`。
- **不 await Promise**：`evaluateJavascript("Promise.resolve(1)")` 不会给你 `1`。`[INFERENCE]`（来自实现行为与社区一致报告；官方文档未承诺 Promise 语义）→ 异步动作必须"立刻返回 ack，结果走消息桥"。
- 单次返回值不宜过大：JS→Java 的字符串要跨 JNI 拷贝，几 MB 的返回值有 OOM/丢回调风险。`[INFERENCE]` 建议**单次 < 512 KB，超出分片**（`snapshot` 返回 `chunkIndex/total`）。
- 在 UI 线程调用；高频调用会卡 UI（`snapshot` 应该节流，而不是每帧跑）。

#### 2.9.2 `WebViewCompat.addDocumentStartJavaScript`（推荐）

官方文档（<https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript>）：

> "Adds a JavaScript script to the WebView which will be executed in any frame whose origin matches `allowedOriginRules` when the document begins to load. Note that the script will run before any of the page's JavaScript code and the DOM tree might not be ready at this moment. **It will block the loading of the page until it's finished, so should be kept as short as possible.**"

→ 用法：只注入一个**极小的 bootstrap**（注册消息通道 + 懒加载大 bundle），大 bundle 通过 `WebViewAssetLoader` 以 `https://appassets.androidplatform.net/...` 提供，由 bootstrap `import()`。这样 document-start 不阻塞首屏。

#### 2.9.3 消息桥：`addWebMessageListener` + `WebMessageCompat`

- `WebViewCompat.addWebMessageListener(webView, jsObjectName, allowedOriginRules, listener)`（androidx.webkit 1.3.0+，feature `WEB_MESSAGE_LISTENER`）：向匹配 origin 的 frame 注入 `jsObjectName`，页面侧 `myObject.postMessage(str)`，App 侧回调；App 侧可 `WebMessageCompat` 回发。**比 `addJavascriptInterface` 安全**：origin 可限定。
- `addJavascriptInterface` 官方警告（<https://developer.android.com/reference/android/webkit/WebView#addJavascriptInterface>)："The object is injected into **all frames** of the web page, including all the iframes... There is no way to tell the calling frame's origin from the app side"。→ 只用来做"最后兜底"，且只暴露无副作用的 `postMessage(String)` 一个方法。
- androidx.webkit **1.16.0** 起有 `JS_INJECTION_IN_FRAME_AND_WORLD`：`addJavaScriptOnEvent(..., INJECTION_EVENT_DOCUMENT_START, allowedOriginRules, world)` 与 `addWebMessageListener(..., JavaScriptExecutionWorld)`，可注入 **isolated world**（页面脚本看不到）→ **Agent 运行时与消息桥应当放这里**（key 始终只在 Kotlin 侧）。
- 特性必须探测：`WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)`，不支持时 `UnsupportedOperationException`。

#### 2.9.4 离线快照的两种存法

| 方式 | API | 优点 | 缺点 |
|---|---|---|---|
| MHTML | `WebView.saveWebArchive(basename, autoname, callback)`（API 11+） | 一条调用拿到整页（含资源引用），重放容易 | 体积大；含原始脚本；不同 WebView 版本兼容性不保证；不利于后续再提取 |
| 自取 DOM | `document.documentElement.outerHTML` + 资源本地化 + `loadDataWithBaseURL` | 完全可控、可直接再跑 L1/L2、可裁剪脚本 | 需自己下载/改写资源；相对 URL 需 `<base>`；CSP/跨域资源会漏 |

> 建议：**两者都存**——MHTML 作为"用户可见的原始快照"，`outerHTML`（裁剪后）作为"可再提取的干净输入"。`Source` 实体里区分 `snapshotMhtml` 与 `extractedHtml`。

#### 2.9.5 等待"DOM 真的稳了"

`onPageFinished` 只表示主文档加载完成，SPA 内容往往还没渲染。可靠组合：

1. `WebViewClient.onPageCommitVisible(view, url)`（API 23+）：HTTP 响应体开始进入 DOM；
2. `WebView.postVisualStateCallback(requestId, callback)`（API 23+）：文档说"called when the current state of the WebView is ready to be drawn"，且"updates to the DOM are processed asynchronously"——这是判断"当前 DOM 已反映到绘制"的官方钩子；
3. 页面内 `MutationObserver` 静默检测（比如 500 ms 内无新增节点/无 `offsetHeight` 变化）+ `document.readyState === 'complete'` + 模板的 `waitForSelector` 出现；
4. 最长等待超时（例如 8 s）后强制采样，并把 `domStable=false` 记进提取结果的置信度。

> `postVisualStateCallback` 还隐含一个前提：**WebView 必须处于可绘制状态（attach 到窗口并完成 layout）**，否则 `getBoundingClientRect()` 全是 0、`elementFromPoint` 失效。`[INFERENCE]` 工程实践：把提取用 WebView 放在 1dp×1dp 或 `alpha=0` 的容器里 attach 到 Activity，而不是"完全不 attach"。

---

### 2.10 L1 失败模式总表

| 失败模式 | 触发条件 | 检测信号 | 处置 |
|---|---|---|---|
| 提取为空 | 正文 < 阈值；DOM 结构非 `<p>` | `markdown.length < 300` | 换模板 selector → Defuddle → L3 |
| 提取到评论区 | 评论比正文长、DOM 更"密" | 文本中"回复/赞/展开"密度高；同一段重复 | 模板 `removeSelectors`；按"标题之后、首个 `h2/h3` 之前"截断 |
| 只拿到摘要/导语 | 付费墙/折叠 | 文本以"继续阅读/展开全文/登录后查看"结尾 | 判定 → L3 点击展开 |
| 懒图全空 | `data-src` 未提升 | `img[src]` 全是 `data:image/svg` | 预处理提升 `data-*` |
| 数学/代码被毁 | KaTeX 多 span、代码高亮 span | 出现 `katex` 类名 / `<pre>` 内有大量 `<span>` | 转换前特化处理（`innerText` / LaTeX 抽取） |
| 中文段落粘连 | 空白折叠 + 行内换行 | 段落平均长度异常大 | 按 CJK 句末标点重切 |
| DOM 未稳 | SPA / 懒加载 | `domStable=false` | 延长等待 / 触发滚动后重试 |
| 跨域 iframe 内容缺失 | 正文在第三方 iframe | 主文档文本极短 | Same-Origin 限制无法绕过（§4.5），提示用户或走"打开原页" |

---

## 3. L2：压到 LLM 能吃的体积

### 3.1 Jina Reader（`jina-ai/reader`）

一手来源：<https://github.com/jina-ai/reader>（`architecture.md`、`README.md`）。

**架构（`architecture.md` 原文要点）**

- Node.js 多线程服务；网页用 **headless Chrome（puppeteer）** 渲染，PDF 用 PDF.js，Office 用 LibreOffice。
- 三种取页引擎：`browser`（Chrome）、`curl`（`curl-impersonate`，不执行 JS）、`cf-browser-rendering`；默认 `auto` 按内容特征组合。
- **多套 HTML→Markdown 配置**：
  - `@mozilla/readability` —— 先清洗 HTML 再转 Markdown；
  - 自研规则引擎 —— "inspired by the `turndown` library, with custom rules and plugins"；
  - `ReaderLM v2` —— 用专门训练的小模型做 HTML→Markdown；
  - `ReaderLM v3 / JinaOCR / VLM` —— WIP，视觉路线。
- 滥用缓解里有一条很实用：**"Excessive HTML nodes/depth: fall back to HTML-to-text instead of markdown"**——节点过多/过深时降级为纯文本（而不是死掉）。这正是移动端也该有的策略。

**请求参数（README，对"压缩"直接相关）**

| header | 作用 |
|---|---|
| `x-respond-with` | `markdown`（**不过 readability**）/ `html`（`documentElement.outerHTML`）/ `text`（`document.body.innerText`）/ `screenshot` / `pageshot` / `frontmatter` |
| `x-target-selector` | CSS selector，只返回该元素内内容（**自动提取失败时的显式兜底**） |
| `x-wait-for-selector` | 等某元素渲染出来再返回 |
| `x-respond-timing` | `html` / `visible-content` / `mutation-idle`（DOM 变更静默 ≥0.2s）/ `resource-idle` / `media-idle` / `network-idle` |
| `x-max-tokens` | 超过即**截断**（≥500） |
| `x-token-budget` | 超过即**拒绝**（成本控制） |
| `x-retain-links` | `all` / `none` / `text`（只留锚文本）/ `gpt-oss`（引用格式） |
| `x-retain-images` | `all` / `none` / `alt`（只留 alt，省 token） |
| `x-retain-media` | `link` / `none` / `text` / `image` / `html`（剥掉 class/id/style/data-*/aria-*） |
| `x-with-links-summary` | 把去重后的 URL 列表追加到文末（正文里只留锚文本） |

> 这套 header 实际上是一份**"LLM 输入预算控制"的完整设计清单**，可以直接映射成本项目的 `ExtractionPolicy`（保留链接/图片/媒体、token 上限、截断 or 拒绝）。

### 3.2 Crawl4AI（`unclecode/crawl4ai`）

一手来源：<https://github.com/unclecode/crawl4ai>（`crawl4ai/content_filter_strategy.py`、`content_filter_strategy_lxml.py`、`markdown_generation_strategy.py`）。

**`PruningContentFilter`（打分剪枝）**

文档字符串给出的流程：取元数据 → 从 `body` 抽文本块 → 按块打分 → 低于阈值丢弃 → 按分排序 → 取 top N。

权重（源码）：

```
metric_weights = {text_density: 0.4, link_density: 0.2, tag_weight: 0.2,
                  class_id_weight: 0.1, text_length: 0.1}
tag_weights   = {article: 1.5, section: 1.0, p: 1.0, div: 0.5, span: 0.3, li: 0.5, ...}
threshold = 0.48（fixed）, threshold_type ∈ {fixed, dynamic}
```

**`BM25ContentFilter`**：`bm25_threshold = 1.0`，需要 `user_query`（无 query 时退化为标题/首段），用 `rank_bm25` + 词干化——本质是"按查询相关性保留块"，用于"我只想喂和问题有关的部分"。

**`DefaultMarkdownGenerator`**：产出 `raw_markdown` + `markdown_with_citations` + `fit_markdown`（经 content filter 过滤后的"瘦身版"），并可选择 `content_source ∈ {cleaned_html, raw_html, fit_html}`。

> 可借鉴点：**把"字节数"换成"分数"来决定删什么**（文本密度/链接密度/标签权重），比 Readability 的隐式启发式更可解释、更可调；`fit_markdown` 与 `raw_markdown` 双份保留，便于 A/B 与回退。

### 3.3 Firecrawl（`firecrawl/firecrawl`）

一手来源：<https://github.com/mendableai/firecrawl>（`apps/api/src/lib/html-to-markdown.ts`、`apps/api/src/controllers/v1/types.ts`）。

- `onlyMainContent: z.boolean().prefault(true)` —— **默认开启主内容提取**（v1 types:489）。
- 转换链路：HTTP 服务优先 → **Go 共享库**（`koffi` 加载 `ConvertHTMLToMarkdown`）→ 失败回退 **Turndown + `joplin-turndown-plugin-gfm`**；之后统一 `postProcessMarkdown`（Rust 绑定 `@mendable/firecrawl-rs`）。
- 相关选项：`removeBase64Images`（默认应开，避免 base64 图把 token 吃光）等。

> 可借鉴点：**转换器要有降级链**（Go → Turndown → 纯文本），并且**后处理（postProcess）独立成一步**，便于统一修 Markdown 的常见畸形。

### 3.4 体积与 token 预算（实测基线）

以 §2.7 那篇微信公众号技术文章为例：

| 形态 | 体积 | 相对正文文本 | 估算 token `[INFERENCE]` |
|---|---|---|---|
| 整页 HTML | 4.50 MB | ~217× | 不可直送（>500k） |
| 正文片段 HTML（`#js_content` 区间） | 282 KB | 13.6× | ~70k–110k |
| 纯文本（去标签） | 20.7 K 字符（CJK 4,535） | 1× | ~8k–15k |
| Markdown（去属性/样式，保留结构） | 估 22–26 KB | ~1.1× | ~9k–16k |

估算口径：英文 ≈ 4 字符/token；中文 ≈ 0.6–1.5 token/字（依分词器）。**结论：把 HTML 变 Markdown 是收益最大的一步（10× 以上），而 Markdown→纯文本几乎没有收益，反而丢结构。**

### 3.5 推荐的 L2 压缩流水线（Kotlin 侧，纯规则）

```
[正文 DOM]
 1. 结构清洗：删 script/style/svg/canvas/noscript/iframe(非视频)/form/nav/aside/footer
    （白名单式：只保留 p,h1-h6,ul,ol,li,blockquote,pre,code,table,thead,tbody,tr,th,td,img,figure,figcaption,a,strong,em,hr,br,sup,sub,ruby）
 2. 属性白名单：只留 href,src,alt,title,colspan,rowspan,start（其余全删，含 style/class/id/data-*）
 3. 图片：懒加载属性提升；过滤 <32px 或 1x1；可选下载到本地并改写为 asset:// 相对路径
 4. 链接：可选"只留锚文本"（省 token）或"锚文本 + 文末去重 URL 清单"（Cite 需要）
 5. 空白归一：连续空行 → 单个空行；CJK 间多余空格删除；段落内换行合并
 6. 数学/代码：KaTeX/MathML → LaTeX（`$...$`/`$$...$$`）；代码块取 innerText，语言从 class 推断
 7. 转 Markdown（turndown：atx 标题 + fenced 代码 + GFM 表格）
 8. 分块：按 h2/h3 切，单块 ≤ 8K 字符，超长块按段落切并带 "（接上）" 标记
 9. 预算闸门：总 token 估 > 预算（如 24k）时，先丢图片、再丢表格行、最后拒绝并提示
```

关键取舍：

- **不要过早退化到 `innerText`**：Jina Reader 的"节点过多 → 纯文本"是最后的兜底，不是默认。
- **保留标题层级**：L2 的 LLM 清洗与 TTS 分段都依赖章节边界。
- **链接策略要在"省 token"和"可溯源"之间选**：朗读场景下锚文本足够，URL 清单只在引用时用。

### 3.6 L2 的"LLM 清洗"（把 Markdown 变成 Script/Segment）

先例：Obsidian Web Clipper 的 `{{prompt:"..."}}`（`providers.json` 支持 anthropic/azure-openai/deepseek/gemini，BYOK），`src/utils/interpreter.ts` 里 `max_tokens` 1600/8000 并对截断显式报错。

**设计建议**

1. **只让 LLM 做规则做不了的事**：语义分段、修复断行/错切、去重复导语、识别"本文是访谈/清单/教程"以便调整朗读节奏、生成标题/摘要。**不要**让 LLM 重新做正文提取（成本高、易幻觉）。
2. **结构化输出而非自由文本**：要求 JSON（`{"segments":[{"text":"...","kind":"heading|para|quote|code|caption","level":2}]}`），并用 JSON Schema / 严格解析兜底；解析失败重试 1 次，再失败就用规则分段。
3. **分块喂，块间带上下文**：每块带 `title` 与"前一节最后一段"的尾部 100 字，避免跨块语义断裂；块大小 4k–8k 字符（中文 ≈ 4k–8k token）。
4. **两段式**：第一段"清理"（去噪、修断行）→ 第二段"分段+语段化"（Segment 切分，按句末标点与朗读停顿）。合并成一次调用在长文上会超 token 且质量下降。
5. **成本闸门**：单篇 token 预算（如输入 ≤ 32k、输出 ≤ 8k），超预算先切块而非截断；记录 `usage` 到本地，供用户查看。

---

## 4. L3：页内 Agent 兜底

### 4.1 为什么 Playwright / Puppeteer / CDP 在独立 Android App 里不可用

**（1）Playwright 的 Android 支持需要 adb 与宿主机**

官方文档（<https://playwright.dev/docs/api/class-android>）：

> "Playwright has **experimental** support for Android automation. This includes Chrome for Android and Android WebView."
> Requirements: Android device or AVD Emulator；**ADB daemon running and authenticated**；**Chrome 105 or newer**；**"Enable command line on non-rooted devices" enabled in `chrome://flags`**。
> Known limitations: "Raw USB operation is not yet supported, so you need ADB."

一个装在手机里的普通 App 没有 adb、不能改 `chrome://flags`、也不允许别人通过 USB 控制它。**结构性不可行**，不是工程难度问题。

**（2）WebView 的 CDP 只以"进程内抽象 unix socket"形式存在**

Chromium 源码 `android_webview/browser/aw_devtools_server.cc`（<https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/browser/aw_devtools_server.cc>）关键事实：

- socket 名格式：`"webview_devtools_remote_%d"`（`%d` = 进程 pid）；
- 默认绑定 **`UnixDomainServerSocket(..., use_abstract_namespace = true)`**；
- 仅当命令行带 `--remote-debugging-port`（且 1024 ≤ port < 65535）时，才改为 `TCPServerSocketFactory("127.0.0.1", port)`；
- 启动受 `CanUserConnectToDevTools` 门禁，由 `JNI_AwDevToolsServer_SetRemoteDebuggingEnabled`（即 `WebView.setWebContentsDebuggingEnabled`）开关；
- 该服务器是**进程级单例**（`g_is_debugging_started_`）。

对开发者的可见形态是 `chrome://inspect`（<https://developer.chrome.com/docs/devtools/remote-debugging/webviews>）：需要在 App 内调 `WebView.setWebContentsDebuggingEnabled(true)`，再在**宿主机** Chrome 的 `chrome://inspect` 里 attach；常规手法是 `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`。

`[INFERENCE]` 由此推出：

- App 内没有任何公开 API 能拿到这个 CDP 端点；即使自己实现 HTTP/WebSocket over unix socket 去连抽象 socket，也依赖调试开关、SELinux、WebView 版本，属于**未受支持的黑客路径**，不能作为产品架构。
- 即使连上，`Page.navigate` 之类操作会与 App 自己的 WebView 控制流争抢，状态不可预期。
- WebView **不支持浏览器扩展**（page-agent 的多标签页能力依赖 Chrome 扩展），所以"装个扩展来驱动"也不成立。

**（3）`browser-use` 也证明这条路是"桌面/CDP 专属"**

`browser-use` 1.0.6 的 README 明说其模型：`task → model writes a cell → REPL runs it on Chrome (CDP) → output back`。**"The model, a REPL and CDP. That's the whole agent."** 而它早期的页内 DOM 树方案（`browser_use/dom/dom_tree/index.js`，0.5.9 时 49,642 字节，commit `3f7d88b`）已被移除/演进——桌面端用 CDP 更省事，**但在 Android 上没有 CDP，页内 JS 反而是唯一解**。

### 4.2 唯一可行的架构

```
┌──────────────────────────── Android App (Kotlin) ────────────────────────────┐
│  AgentLoop: 计划 → 调用 LLM（BYOK，OkHttp） → 解析 tool_call → 执行          │
│      ▲                                              │                        │
│      │ 观察结果（JSON 文本）                         │ evaluateJavascript    │
│      │                                              ▼                        │
│  WebMessageListener ◄──── postMessage ──── 页内运行时 (JS, isolated world)   │
│                                              ├─ DOM 索引（flatTree/selectorMap）
│                                              ├─ 动作执行（click/input/scroll）
│                                              └─ 文本抽取（复用 L1 代码）     │
└──────────────────────────────────────────────────────────────────────────────┘
```

要点：

1. **观察（observe）= 页内生成的"压缩 DOM 文本"**，不是截图。page-agent 的立论就是 "Text-based DOM manipulation — No screenshots. No multi-modal LLMs or special permissions needed."
2. **动作（act）按索引寻址**：`[12]<button>展开全文</button>`，LLM 只需输出 `{"action":"click","index":12}`。索引→元素映射留在页内（`selectorMap`），**不进 LLM 上下文**。
3. **LLM 调用在 Kotlin**：页内只发"我需要一次推理，输入是这些文本"，Kotlin 拿 key 调 API，把 tool_call 回传。key 永不进页面。
4. **每一步都重新索引**（DOM 会变），并在提示里标注"上一轮之后新增的可点元素"（page-agent 用 `*[` 前缀标记）。

**为什么不用 Android 无障碍树（AccessibilityNodeInfo）代替 DOM 索引？**

- 无障碍树是**为屏幕阅读器设计的**：它把 `<div>` 拍平成"可聚焦节点"，丢失段落边界、标题层级、链接 URL、图片 alt 之外的语义，正好是 TTS 语段化最需要的那些信息。
- 走这条路必须实现 `AccessibilityService` 并让用户在系统设置里手动开启，`Google Play` 对无障碍权限的用途有严格审核；同时无障碍事件是**异步推送**，做"点第 3 个按钮"这种索引寻址要自己维护快照与漂移，复杂度不低于 DOM 索引。
- 结论：**a11y 树适合"读屏"这种线性朗读场景的兜底，不适合"精确取正文 + 索引化动作"**。DOM 索引在 WebView 里既精确又零权限，没有理由绕路。

### 4.3 `alibaba/page-agent` 拆解（1.12.4，MIT）

一手来源：<https://github.com/alibaba/page-agent>。

**定位**：README 原文 "No need for `browser extension` / `python` / `headless browser`. Just in-page javascript."；并明确致谢 "DOM processing components and prompt are derived from browser-use"。

**包结构**

| 包 | 作用 | 可复用性 |
|---|---|---|
| `@page-agent/page-controller` | DOM 索引 + 动作执行（**不依赖 LLM，可单测**） | ★★★ 直接复用（MIT） |
| `@page-agent/core` | ReAct 循环、工具集、system prompt | ★★ 需改 LLM 适配层 |
| `@page-agent/llms` | LLM 客户端（fetch，含 baseURL/apiKey） | ✗ 与"key 不进页面"冲突 |
| `@page-agent/ui` | 页内悬浮面板 | 可选 |
| `extension/mcp` | 多标签页/MCP | ✗ WebView 无扩展 |

**`PageController`：状态压缩的关键**

- `updateTree()` → `dom.getFlatTree()` 生成扁平树（每个节点有 `isVisible / isInteractive / isTopElement / isNew / highlightIndex`）→ `flatTreeToString()` 生成给 LLM 的 `simplifiedHTML` → 再生成 `selectorMap: Map<index, element>` 与 `elementTextMap`。
- 输出格式（system prompt 原文）：

```
[33]<div>User form</div>
    *[35]<button aria-label='Submit form'>Submit</button>
```
  - 只有带 `[index]` 的元素可交互；`\t` 缩进表示父子关系；`*[` 表示"上一轮之后新出现"。

- **属性白名单**（`DEFAULT_INCLUDE_ATTRIBUTES`）：`title,type,checked,name,role,value,placeholder,data-date-format,alt,aria-label,aria-expanded,data-state,aria-checked,id,for,target,aria-haspopup,aria-controls,aria-owns,contenteditable` —— 其余属性全部丢弃（这就是"压缩"的主要来源之一）。
- **文本截断**：`capTextLength(text, maxLength)`，超长加 `...`。
- **去重**：多个属性值相同且长度 > 5 时只保留一个（减少重复噪声）。
- **可点判定**：标签集合（`a,button,input,select,textarea,details,summary,label,option,optgroup,fieldset,legend`）+ ARIA role 集合（`button,menu,menuitem,radio,checkbox,tab,switch,slider,combobox,searchbox,textbox,listbox,option,scrollbar`）+ `contenteditable` + `[aria-haspopup=true]` + `.button/.dropdown-toggle`，以及一条很实用的启发：**`getComputedStyle(el).cursor === 'pointer'` 就当作可点**（源码注释 "Genius fix for almost all interactive elements"）。中文站点大量用 `<div onclick>` / `<span>` 做按钮，这条命中率很高。
- **视口**：`viewportExpansion` 默认 `-1`（整页不过滤）；`isTopElement` 依赖 `elementFromPoint`，所以"只取视口"在 page-agent 里被视为"only differ between -1 and 0"。
- **可选高亮遮罩**（`enableMask`）：把要点的元素框出来给用户看——移动端这个交互很有价值（用户知道 agent 在点什么）。

**`PageAgentCore`：ReAct 循环**

- 循环：`observe`（取 `BrowserState`）→ `think`（LLM，含 reflection/memory）→ `act`（执行工具）→ 重复；`maxSteps` 默认 **40**。
- 结构化的历史：每步 `Evaluation of Previous Step / Memory / Next Goal / Action Results`，作为持久记忆；另有只用于 UI 的 transient `activity` 事件（不进上下文）。
- 工具集：`done(text, success)`、`wait(seconds 1-10)`、`ask_user`、`click`、`input_text`、`scroll`、`select_option` 等。
- 提示词里的"能力边界"（`system_prompt.md`）：只处理单页 SPA、不点 `target=_blank`、遇 CAPTCHA 直接告诉用户、同一动作不重复 3 次以上。

**`actions.ts`：动作实现（Android 上要重点看）**

- 点击**不是** `el.click()` 了事，而是按 W3C 顺序合成一整串事件：`pointerover/enter → mouseover/enter → pointerdown → mousedown → focus → pointerup → mouseup → click`，并用 `elementFromPoint` 做命中测试，最后 `target.click()` 触发默认行为（导航/表单提交）。
- 文本输入：优先 `getNativeValueSetter(element).call(element, text)` + `dispatchEvent(new Event('input', {bubbles:true}))`（React 受控组件需要这个）；`contenteditable` 走 `beforeinput` 合成 → 校验 → 失败回退 `execCommand('delete'/'insertText')`。
- 选择框：直接设 `value` + `change` 事件。
- 滚动：`scrollIntoViewIfNeeded` + 目标元素/窗口滚动。

**官方限制（`website/.../introduction/limitations/page.tsx`）**

| 支持 | 不支持 |
|---|---|
| 点击、文本输入、选择 | 悬停、拖拽、右键菜单 |
| 页面滚动（垂直/水平） | 键盘快捷键 |
| 表单提交、焦点切换 | 坐标定位操作 |
| 同源 iframe（**仅单层**） | 嵌套 iframe、跨域 iframe |
| 执行 JavaScript（可选） | 绘图（canvas） |
| — | Monaco/CodeMirror 等需 JS 实例的编辑器 |

> 这几乎就是"页内 JS Agent"的能力上界，Android WebView 完全一致（甚至更严：没有扩展、没有多标签页）。

### 4.4 在 Android WebView 上的落地设计（本项目版）

**（1）运行时放 isolated world**

- 用 `WebViewFeature.isFeatureSupported(JS_INJECTION_IN_FRAME_AND_WORLD)` + `addJavaScriptOnEvent(..., INJECTION_EVENT_DOCUMENT_START, origins, isolatedWorld)` 注入运行时，`addWebMessageListener(..., isolatedWorld)` 建桥。
- 好处：页面脚本读不到运行时的全局对象；页面改 `window.__agent` 也影响不到我们；桥的入口只有 `postMessage(string)`。
- 老 WebView 回退：主 world 注入 + 随机命名（`__lr_<uuid>`）+ 只读消息桥 `[INFERENCE]`（安全性下降，可接受，因为消息内容由 Kotlin 校验）。

**（2）contract：页内暴露的方法必须是"同步返回 + 异步回调"混合**

```js
// 注入的 bootstrap（document start，极小）
window.__lr = {
  // 同步：返回 JSON 字符串（evaluateJavascript 能直接拿）
  snapshot(opts) { /* 索引 DOM，返回 {url,title,header,tree,footer,stable} */ },
  // 异步：立刻返回 ack，结果通过 postMessage 回传
  act(action, args) { /* click/input/scroll/select/wait/extract */ },
  // 同步：跑 L1 提取（模板/Readability/Defuddle），返回 {markdown, html, score, extractor}
  extract(templateId) {}
}
```

Kotlin 侧：

```kotlin
// 同步取值：必须限流 + 限制大小（建议 <512KB，超出分片）
webView.evaluateJavascript("JSON.stringify(__lr.snapshot())") { json -> /* ... */ }

// 异步动作
webView.evaluateJavascript("__lr.act('click', {index: 12})") { ack -> /* 等待消息桥 */ }
```

**（3）索引表的生命周期**

- `selectorMap` 只存在于页内；每轮 `snapshot` 重建。
- LLM 返回的 `index` 必须校验"是否为本次快照的 index"，否则拒绝执行（page-agent 的 `assertIndexed()` 就是这个思路）。
- 动作执行后自动 re-snapshot（或等 `MutationObserver` 静默），把新状态与本轮结果一起回传。

**（4）真实输入事件（`isTrusted` 问题）**

页内 `dispatchEvent` 产生的事件 `isTrusted === false`，少数站点（含部分风控组件）会据此拒绝。Android 侧有一个页内 JS 做不到的能力：`webView.dispatchTouchEvent(MotionEvent)` 会走原生输入管线，产生 **trusted** 事件。`[INFERENCE]`（机制合理，需在目标站点实测）。

建议：默认用合成事件（快、无坐标依赖）；连续两次失败后，升级为"取 `getBoundingClientRect` → 换算成屏幕坐标 → `dispatchTouchEvent` 点击"。

**（5）步数与成本护栏**

| 护栏 | 建议值 | 理由 |
|---|---|---|
| 单次任务最大步数 | 8–12（提取场景）/ 40（通用，page-agent 默认） | 提取任务 90% 在 3 步内完成 |
| 单步 DOM 文本上限 | 12k–20k 字符 | 控制输入 token |
| 单动作超时 | 5 s + 等待 DOM 稳定 | 避免卡死 |
| 重复动作检测 | 同 index 同 action 连续 3 次 → 中断并上报 | page-agent 提示词里也这么要求 |
| 危险动作 | 默认工具集**只读**（click 展开/关闭、scroll、wait、extract）；输入、提交、导航需用户确认 | 降低误操作风险 |
| 登录/CAPTCHA | 立即停机 + 交还用户 | page-agent 明确"tell user you can not solve captcha" |

**（6）视觉反馈**

`enableMask` 那套高亮遮罩在移动端很有用：把"即将点击的 `[12]`"框出来 200ms 再点，用户能感知 agent 在做什么；也便于排障。实现上是一个 `position: fixed` 的 overlay + `pointer-events: none`，注意别被页面 CSS 影响（用 Shadow DOM 宿主最稳）。

### 4.5 L3 失败模式与不可逾越的边界

| 失败模式 | 原因 | 能否解决 |
|---|---|---|
| 跨域 iframe 内容 | Same-Origin Policy，页内脚本无法读 | ❌ 不能（需要站点配合或原生层代理） |
| `target=_blank` 链接 | `setSupportMultipleWindows` **默认 false**，新窗口请求会当作顶层导航**替换当前页**（官方原文），Agent 会丢失原任务上下文 | ⚠️ 可规避：提示词禁止点 `target=_blank`；点击后检测 URL 跳变并 `goBack()`；或开启多窗口 + `onCreateWindow` 里复用同一 WebView |
| canvas/WebGL 内容 | 无 DOM 可索引 | ❌ 只能截图 + VLM（另开一路） |
| 键盘快捷键/长按菜单 | 合成事件不触发浏览器 UI | ❌ |
| 登录墙 | 需要凭证 | ⚠️ 交还用户人工登录 |
| 风控/CAPTCHA | 人机验证 | ❌ 交还用户 |
| 页面篡改运行时 | 页面脚本改写全局对象 | ✅ isolated world + 消息校验 |
| WebView 版本差异 | 老设备缺 `DOCUMENT_START_SCRIPT` 等 feature | ✅ `isFeatureSupported` 探测 + 降级到 `evaluateJavascript` |
| 内存（大页面 + 索引 + 遮罩） | 4.5 MB 页面 + 数万节点 | ⚠️ 限制 `maxElems`、分片快照、任务结束 `destroy()` 提取用 WebView |

---

## 5. 三层协同：路由与混合策略

### 5.1 决策树（建议实现为 `ExtractionRouter`）

```
1) 模板命中（hostPattern）且 selector 找到正文：
     text >= 300 字 且 段落数 >= 3   → 接受（高置信）
2) 并行跑 Defuddle + Readability（同一 DOM，各一份 clone）：
     取 score 高者；score 差距 < 10% 时偏向 Defuddle（更宽容、少删）
3) 接受条件（全部满足）：
     - markdown 长度 >= 300
     - 链接文本占比 < 0.35
     - 不含"展开全文/继续阅读/登录后查看/剩余xx%"
     - 与页面可见文本的覆盖率 >= 0.4（可见文本 = body.innerText 去导航后）
4) 不满足 → L3：一次"取文任务"（目标：让正文完整可见），最多 6 步
5) L3 后重新执行 1)-3)；仍失败 → 保存快照 + 标记 needs_review，让用户手动划选
```

### 5.2 为什么"竞争 + 打分"优于"选一个最好的库"

- Readability 与 Defuddle 的失败方向不同：Readability 更激进（删得多、易空），Defuddle 更保守（留得多、易带噪）。同一页面上两者分数差异本身就是**置信度信号**。
- Wechatsync 在真实中文站点上用"三者竞争 + 打分"，这是可查证的工程选择（§2.8）。
- 成本可控：两者都是纯 JS，在同一 DOM 上串行跑，通常 < 200 ms（4.5 MB 页面首轮 `[INFERENCE]` 可能到 1 s，需要放后台线程/加 loading 态）。

### 5.3 三层的成本/时延量级（估算 `[INFERENCE]`）

| 路径 | 典型耗时 | 网络成本 | 失败后的用户感知 |
|---|---|---|---|
| 模板命中（L1 only） | 加载 + 50–200 ms 提取 | 0（本地） | 秒级出文稿 |
| 模板未命中（L1 竞争） | 加载 + 0.3–1.5 s 提取 | 0 | 1–2 s |
| + L2 LLM 清洗（20k 字符） | + 3–15 s | 1–2 次调用，约 10k–20k 输入 token | 有"正在整理"进度 |
| + L3（3–6 步） | + 5–30 s | 3–6 次调用，每步 2k–8k token | 需要进度与可取消；要有"交给用户接管"出口 |

> 取舍：**L3 应该是显式降级而不是默认路径**。用户点击"提取失败，试试自动展开"时再进 L3，比默默烧掉 6 次 LLM 调用更符合直觉，也更省 key。

### 5.4 L2 与 L3 的耦合点

- L3 产出的"完整 DOM"必须能回流 L1（同一个 `extract()` 入口），否则会出现两套提取逻辑漂移。
- L2 的"低质量判定"（例如 LLM 返回 `{"quality":"low","reason":"content looks like a summary"}`）应触发 L3，而不是让用户读到半截文章。

---

## 6. 对本项目的具体选型建议

### 6.1 L1 选型（结论）

| 决策 | 选择 | 理由 |
|---|---|---|
| 主提取器 | **站点模板 selector 优先**，其次 **Defuddle(full) + Readability 竞争打分** | 模板解决中文站点；竞争降低整篇失败率；Defuddle 更宽容且能吃真实 layout |
| 注入方式 | `addDocumentStartJavaScript` 注入小 bootstrap；大 bundle 走 `WebViewAssetLoader` | 不阻塞首屏；asset 有缓存；避免每次拼 100KB 字符串 |
| JVM 侧 | **不用 Readability4J**；需要离线重跑就用离线 WebView 加载快照 + 同一份 JS | 单一实现，行为一致 |
| Markdown | turndown（`atx` + `fenced` + GFM），自定义 `table/br/pre` 规则 | 体积小、可控、Defuddle full 也用它 |
| 输出 | `Article{title, byline, published, markdown, html, textLength, extractor, score, confidence}` | 便于路由与排障 |

### 6.2 L2 选型

- 边界要划清：**DOM 侧动作（§3.5 第 1–7 步：清洗、属性白名单、懒图、数学/代码特化、转 Markdown）在 WebView 内完成**（省一次序列化，且能用真实 layout）；**第 8–9 步（分块、token 预算）与持久化在 Kotlin**（可测试、可缓存、不依赖页面）。
  - 另一种切法：页内只输出 `outerHTML`，全部裁剪在 Kotlin。两种都行，但**只允许一种**，避免两处都做一半裁剪导致行为不一致。
- LLM 清洗：**两段式**（清理 → 分段语段化），JSON 输出 + Schema 校验，块 ≤ 8k 字符，超预算切块不截断。
- 预算：输入 ≤ 32k token / 输出 ≤ 8k token；记录 usage；超预算先丢图/表再拒绝。

### 6.3 L3 选型

| 方案 | 评价 |
|---|---|
| **复用 `@page-agent/page-controller`（MIT）+ 自写 Kotlin 侧循环** | 推荐。DOM 索引/动作实现成熟，且 controller 不依赖 LLM，容易塞进 isolated world；LLM 调用留在 Kotlin |
| 整包用 `page-agent` | 不推荐：`@page-agent/llms` 在页面内 fetch，key 会暴露给页面上下文 |
| 自研 DOM 索引 | 可行但没必要：`flatTreeToString` + 属性白名单 + `cursor:pointer` 判定这些细节踩坑多 |
| browser-use 0.5.9 的 `dom_tree/index.js` | 可作为参考实现（page-agent 就是它的移植 + 修 bug），但版本老、无 isolated world 适配 |

**必须自己补的三件事**（page-agent 没替你做）：

1. **消息桥**：`snapshot()` 同步返回 / `act()` 异步回调，结果分片；
2. **isolated world 注入**与老 WebView 降级；
3. **Android 原生 touch 升级路径**（`dispatchTouchEvent`）应对 `isTrusted` 校验。

### 6.4 明确不做（避免走弯路）

- ❌ 在 App 内嵌 Puppeteer/Playwright/CDP 客户端去驱动 WebView；
- ❌ 用 `addJavascriptInterface` 暴露业务对象（官方明确"注入所有 frame，无法判断来源"）；
- ❌ 用 Readability4J 做 JVM 侧提取；
- ❌ 把 API key 传给页内 JS（哪怕"只是发一次请求"）；
- ❌ 依赖服务端 Reader（Jina/Firecrawl/Crawl4AI）——与无后端 + 登录态 + 隐私三条约束都冲突；
- ❌ 为了 trafilatura 之类 Python 提取器塞一个 Python 运行时（Chaquopy）——体积/启动成本远超收益；
- ❌ 用 `innerText` 做默认提取（丢结构、丢标题层级，TTS 分段会退化）。

### 6.5 里程碑（建议）

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 | WebView 提取器骨架：模板 + Readability + Defuddle + 打分 + Markdown | 20 个真实 URL（含 5 个中文）提取成功率 ≥ 80%，人工抽检正文完整 |
| M2 | L2 规则压缩 + 分块 + LLM 清洗 | 单篇 token 降 ≥ 8×；Script 分段可直接朗读 |
| M3 | L3 只读 Agent（展开/滚动/关闭弹窗/extract） | 对"展开全文""懒加载"场景补全成功率 ≥ 70% |
| M4 | 快照归档（MHTML + outerHTML）与离线重提取 | 断网可重跑提取，结果一致 |

---

## 7. 未验证项与风险（诚实清单）

1. `evaluateJavascript` 单次返回值的安全上限**没有官方数字**；本文的 512 KB 建议来自工程经验。必须自己压测（1KB/64KB/256KB/1MB/4MB 的 JSON 往返）。
2. `evaluateJavascript` 对 Promise 的语义：官方只承诺"result returned from that execution"。本文按"不 await"设计，需在目标 WebView 版本上实测确认。
3. 知乎/雪球的正文 selector **未验证**（本次裸请求被 403 / 只拿到 JS 骨架）。上线前必须在登录态 WebView 里用 `document.querySelectorAll` 探测并固化模板。
4. WebView 必须 attach 到窗口才有 layout 这一点，是从 `postVisualStateCallback`（"ready to be drawn"）与 `getBoundingClientRect` 语义推断的 `[INFERENCE]`，需实测"1dp 容器 vs 完全 detach"两种情形的差异。
5. `dispatchTouchEvent` 产生的 `isTrusted` 事件是否被目标站点接受，未实测。
6. Defuddle 的 `useAsync` 默认开启（README："Allow async extractors to fetch from third-party APIs"）；源码里体现为 YouTube 提取器会额外抓 player data / caption XML。离线 App 必须显式传 `useAsync: false`，否则会出现"用户没网时提取卡住"。
7. 中文 token 估算区间较宽（0.6–1.5 token/字），预算实现应按**上限**留余量。
8. 4.5 MB 页面在低端机上的提取耗时未实测；建议加"提取中"进度与可取消。

---

## 8. 参考来源

### L1

- `@mozilla/readability`：<https://github.com/mozilla/readability>（README 的 API/选项/安全说明；`Readability.js` 的打分与 `REGEXPS.unlikelyCandidates`；`CHANGELOG.md` 0.6.0 = 2025-03-03）
- `kepano/defuddle`：<https://github.com/kepano/defuddle>（README 选项表与三 bundle 说明；`scripts/check-bundle-size.mjs` 的 92/215 KB gzip 预算；`src/defuddle.ts` 的隐藏元素剔除与 retry；`src/markdown.ts` 的 turndown 用法与数学 externals；`src/extractors/` 28 个站点）
- `dankito/Readability4J`：<https://github.com/dankito/Readability4J>（README 兼容表止于 2018-02 的 `834672e`；`build.gradle` 的 1.0.8 / Kotlin 1.3.72 / jsoup 1.11.2）
- `mixmark-io/turndown`：<https://github.com/mixmark-io/turndown>（README 选项/规则/插件；`package.json` 7.2.5 与 domino 依赖）
- `dom-to-semantic-markdown`：<https://github.com/romansky/dom-to-semantic-markdown>（npm 1.5.0，主内容检测/表格列标识/URL 归一化）
- `adbar/trafilatura`：<https://github.com/adbar/trafilatura>（Apache-2.0；规则式提取 + jusText/readability-lxml 回退；仅 Python，社区移植为 Go/Rust/Node 原生绑定）
- `postlight/parser`：<https://github.com/postlight/parser>（Apache-2.0，Node/cheerio；`src/extractors/custom/*` 的每域 selector 定制模型）
- `obsidianmd/obsidian-clipper`：<https://github.com/obsidianmd/obsidian-clipper>（`src/api.ts` 的 Defuddle→Markdown 流程与模板触发器；`src/utils/resolver.ts` 的 `selector:` 变量；`src/utils/interpreter.ts` 的 LLM 调用与截断处理；`providers.json` 的 BYOK provider 列表）
- `Kenshin/simpread`：<https://github.com/Kenshin/simpread>（README：适配数百种站点、生成符合中文阅读的页面）
- `wechatsync/Wechatsync`：<https://github.com/wechatsync/Wechatsync>（`packages/extension/src/lib/reader/index.ts` 三提取器竞争 + `scoreResult`；`content/extractor.ts` 的 `#activity-name`/`#js_content` 平台特化；`lib/content-processor.ts` 的懒加载属性列表 `data-src/data-original/data-actualsrc/_src`）

### L2

- `jina-ai/reader`：<https://github.com/jina-ai/reader>（`architecture.md`：headless Chrome + readability + 自研 turndown 规则 + ReaderLM；节点过多降级纯文本；README 的 `x-*` 请求头全表）
- `unclecode/crawl4ai`：<https://github.com/unclecode/crawl4ai>（`crawl4ai/content_filter_strategy.py`：`PruningContentFilter` 权重/阈值、`BM25ContentFilter`；`markdown_generation_strategy.py`：`raw_markdown` / `fit_markdown`）
- `firecrawl/firecrawl`：<https://github.com/mendableai/firecrawl>（`apps/api/src/controllers/v1/types.ts` 的 `onlyMainContent` 默认 true；`apps/api/src/lib/html-to-markdown.ts` 的 Go→Turndown 降级链与 `postProcessMarkdown`）

### L3 / Android

- `alibaba/page-agent`：<https://github.com/alibaba/page-agent>（README；`packages/page-controller/src/PageController.ts` 的 `updateTree/selectorMap/simplifiedHTML`；`dom/index.ts` 的 `flatTreeToString` 与属性白名单；`dom/dom_tree/index.js` 的 `isInteractiveElement` 与 `cursor:pointer` 判定；`core/src/prompts/system_prompt.md` 的 `[index]<tag>text</tag>` 约定与规则；`page-controller/src/actions.ts` 的合成指针事件序列与 native value setter；`website/.../introduction/limitations/page.tsx` 的能力矩阵）
- `browser-use/browser-use`：<https://github.com/browser-use/browser-use>（1.0.6 README：REPL + CDP 架构）；页内 DOM 树的历史实现：<https://github.com/browser-use/browser-use/blob/0.5.9/browser_use/dom/dom_tree/index.js>（49,642 字节，commit `3f7d88b`）
- Playwright Android（实验性，需 adb + Chrome flag）：<https://playwright.dev/docs/api/class-android>
- Chromium WebView DevTools socket 实现：<https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/browser/aw_devtools_server.cc>（`webview_devtools_remote_%d`、abstract unix socket、`--remote-debugging-port`、`CanUserConnectToDevTools`）
- Debug WebViews：<https://developer.chrome.com/docs/devtools/remote-debugging/webviews>
- `WebView`：<https://developer.android.com/reference/android/webkit/WebView>（`evaluateJavascript` 的异步/UI 线程/N+ 状态不持久；`addJavascriptInterface` 的"注入所有 frame"警告；`saveWebArchive`；`postVisualStateCallback`；`setWebContentsDebuggingEnabled`）
- `WebViewClient`：<https://developer.android.com/reference/android/webkit/WebViewClient>（`onPageCommitVisible`、`shouldInterceptRequest`）；新窗口拦截在 `WebChromeClient.onCreateWindow` 与 `WebSettings.setSupportMultipleWindows`
- `WebViewCompat`：<https://developer.android.com/reference/androidx/webkit/WebViewCompat>（`addDocumentStartJavaScript`、`addWebMessageListener`、`addJavaScriptOnEvent`、`POST_WEB_MESSAGE`）
- `WebViewFeature`：<https://developer.android.com/reference/androidx/webkit/WebViewFeature>（`DOCUMENT_START_SCRIPT`、`WEB_MESSAGE_LISTENER`、`JS_INJECTION_IN_FRAME_AND_WORLD`）
- `WebSettings`：<https://developer.android.com/reference/android/webkit/WebSettings>（`setSupportMultipleWindows` 默认 `false`，`target=_blank`/`window.open` 会当作顶层导航替换当前页）
- `WebViewAssetLoader`：<https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader>（把内置 JS/资源以 `https://appassets.androidplatform.net/...` 提供给页面，避免 `file://` 与跨域问题）
- `AccessibilityNodeInfo`：<https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo>（无障碍树；与 DOM 索引的取舍见 §4.2）

### 本地实测（2026-11-19，Android Chrome 130 UA，无 Cookie）

- `https://mp.weixin.qq.com/s/64YQb_YF6l1fVHv57w38MA`：200，4,501,834 字节；`#js_content` 命中 1 次且 `style="visibility: hidden"`；正文片段 281,619 字符 / 纯文本 20,720 字符（CJK 4,535）；177 `<section>`、949 个 `style="`、22 个 `data-src`、19 `<img>`、13 `<svg>`。
- `https://www.zhihu.com/question/19550225`：403，650 字节反爬壳（含 `zh-zse-ck`）。
- `https://xueqiu.com/`：200，27,932 字节，`<title>Loading interface...</title>` ×6（JS 骨架）。
