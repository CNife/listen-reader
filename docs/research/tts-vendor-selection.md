# TTS 服务商选型：价格与速度横评

> 调研时间：2026-11-20。目标读者：`listen-reader` 的 TTS 与 AudioTrack 管线设计者。
> 文中的价格/速度数字全部来自当日真实调用实测，听感结论来自潭渊本人的试听。推断性内容显式标注 `[INFERENCE]`。

---

## 0. 结论先行

1. **默认用 `step-tts-mini`（阶跃，直连）**：¥0.097/千汉字、2.9× 实时，数十个官方音色，支持 19 种情绪/语速标签（`voice_label`）。它是「便宜 + 可控」这一侧的最优解。
2. **要吞吐时换 `Qwen-Audio-3.0-TTS Flash`（经 OpenRouter）**：¥0.115/千汉字、5.9× 实时，二十万字从 4.7 小时压到 2.2 小时，代价是只有 2 个音色。
3. **折中是 `Grok Voice TTS 1.0`（经 OpenRouter）**：¥0.115/千汉字、4.5× 实时、5 个音色。
4. **`Kokoro 82M` 出局，尽管它便宜到 ¥0.008/千汉字（第二名的 1/12）**：潭渊听感结论是「太生硬，跟其他模型完全不在一个层次」。便宜不能补偿音质断层。
5. **`Fish Audio S2.1 Pro` 出局**：报价与 Qwen/Grok 相同，但它在 OpenRouter 上没有音色目录，音色只能靠参考音频复刻，不带参考时是模型自由生成、无法复现（§4）。
6. **`Eleven Flash v2.5` 与 `Gemini 3.8 Flash TTS` 出局**：贵 2.7–2.9 倍，且 Gemini 还是最慢的一档（19.2s / 428 字）。
7. **`DeepSeek TTS` 出局**：¥0.600/千汉字，是 `step-tts-mini` 的 6.2 倍；原本的「试试更便宜的」假设不成立。`stepaudio-3-tts`（¥0.285）同样被 mini 以 1/3.2 的价格压倒。

---

## 1. 测试口径

| 项 | 定义 |
|---|---|
| 文本 | 两段中文：150 字小说叙述（`02`）、200 汉字 / 428 字符的议论文长段落（`04`）；七家同一文本 |
| 音色 | 每模型两个音色（同一性别对照优先）；Fish 无音色可选，只有一个「默认」 |
| 音频 | 24 kHz / 16 bit / 单声道 WAV，未经任何后期（响度差异原样保留） |
| 速度 | 从发请求到收完整个音频的墙上时间；长段落打两次取较短的一次，压低网络抖动 |
| 实时倍率 | 音频时长 ÷ 生成耗时；`7×` = 一小时音频七分钟生成 |
| 价格 | 官方单价 × 汇率 1 USD = 6.6667 CNY（2026-11-19, frankfurter）；每千汉字 = 该段文本实际报价 ÷ 汉字数 × 1000（标点计入） |
| 20 万字 | 按实测语速折算约 12.5 小时音频，再按实时倍率推算生成耗时 |

> 耗时含 OpenRouter 排队与网络往返，是「当日机房」的数字，换时段会变。要做容量规划，应用真实调用量重跑（§7）。

---

## 2. 数据

### 2.1 主榜（七家，同一段 428 字符文本）

| 模型 | 计价方式 | ¥/千汉字 | ¥/音频小时 | 428 字耗时 | 实时倍率 | 20 万字成本 | 20 万字生成 | 音色 |
|---|---|---|---|---|---|---|---|---|
| Kokoro 82M | $0.00000099/字符 | **0.008** | ¥0.12 | 7.5s | 5.9× | ¥2 | 2.1 h | 58 个（8 中文） |
| step-tts-mini | 90 元/百万字符，汉字记 1 字 | 0.097 | ¥1.43 | 17.1s | 2.9× | ¥19 | 4.7 h | 数十个 |
| Qwen-Audio-3.0-TTS Flash | $0.000015/字符 | 0.115 | ¥1.76 | **8.8s** | 5.9× | ¥23 | 2.2 h | 2 个 |
| Grok Voice TTS 1.0 | $0.000015/字符 | 0.115 | ¥1.75 | 10.5s | 4.5× | ¥23 | 2.9 h | 5 个 |
| Fish Audio S2.1 Pro | $0.000015/字符 | 0.115 | ¥1.85 | 19.5s | 2.3× | ¥23 | 5.4 h | 无目录 |
| Eleven Flash v2.5 | $0.00004/字符 | 0.307 | ¥4.83 | 6.3s | **7.8×** | ¥61 | 1.6 h | 21 个 |
| Gemini 3.8 Flash TTS | 25 音频 token/秒 × $9/百万 | 0.339 | ¥5.40 | 19.2s | 2.3× | ¥68 | 5.3 h | 30 个 |

### 2.2 对照（第一轮，DeepSeek / 阶跃三代）

| 模型 | 计价方式 | ¥/千汉字 | ¥/音频小时 | 20 万字成本 | 音色 |
|---|---|---|---|---|---|
| DeepSeek TTS | 3 元/万计费字符（限时 5 折 1.5），汉字记 2 字符 | **0.600**（5 折 0.300） | ¥10.2 | ¥120（5 折 ¥60） | 2 个（`stella`/`tide`） |
| stepaudio-3-tts | 95 元/百万字符，汉字记 3 字符 | 0.285 | ¥4.1 | ¥57 | 数十个 |
| step-tts-mini | 90 元/百万字符，汉字记 1 字符 | 0.090 | ¥1.4 | ¥18 | 数十个 |

> 注：这一组用的是另一段 150 字叙述，每千汉字会有 ±10% 的口径差（标点占比不同）。DeepSeek 与 stepaudio-3-tts 的单价高到不需要再用长文本复核。

### 2.3 三个关键事实

- **计费口径比单价更重要**：`stepaudio-3-tts` 95 元/百万 vs `step-tts-mini` 90 元/百万，单价只差 5%；但前者 1 个汉字记 3 个字符、后者记 1 个，落到每千汉字就是 **¥0.285 vs ¥0.090（3.2 倍）**。任何跨厂商比价都必须按汉字折算。
- **Gemini 按输出音频计费**，不按字数：同一段文字它读出 44.9s 就按 44.9s 收钱，长文本上直接变成最贵的一档，还最慢。
- **实时倍率比「单次耗时」更有意义**：所有模型都远快于实时（2.3×–7.8×），所以一本书的瓶颈是「总吞吐 + 失败重试」，不是单请求延迟。

---

## 3. 听感结论（潭渊，2026-11-20）

| 模型 | 判定 | 原话/依据 |
|---|---|---|
| Kokoro 82M | 出局 | 「太生硬了，跟其他的模型完全不在一个层次」 |
| step-tts-mini | 可用 | 「不错，可以作为可选项」 |
| Qwen-Audio-3.0-TTS Flash | 可用 | 同上 |
| Grok Voice TTS 1.0 | 可用 | 同上 |
| Fish Audio S2.1 Pro | 不推荐 | 音色不可控（§4） |
| Eleven / Gemini | 不推荐 | 价格与速度无优势 |

---

## 4. Fish Audio S2.1 Pro 的音色：为什么点不出名字

一手来源：[Fish TTS 接口文档](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)（`TTSRequest` 定义）。

Fish 的 TTS 接口只有两条定音色的路，两条默认都是 `null`：

| 字段 | 含义 | 默认 |
|---|---|---|
| `reference_id` | Fish 音色库（或自建模型）里的 voice model id | null |
| `references` | 1 段参考音频，做零样本克隆（多说话人时为数组的数组） | null |

OpenRouter 把它包成 `voice`（映射 `reference_id`）与 `input_references`（映射 `references`）。我们的请求两者都没传，于是模型按文本自行决定「谁来读」——**不是从某个官方音色目录里选**。这解释了三个可观测现象：

1. OpenRouter 的 TTS 目录里 `fish-audio/s2.1-pro` 的 `supported_voices` 为 `null`，模型元数据里 `voice` 的类型是 `unknown`（非枚举）。
2. 模型元数据 `input_modalities` 中音频参考的约束是 `references: {type: integer, min: 1, max: 1}` —— OpenRouter 这一层只给 1 段参考音频。
3. 同一句文本连续请求 5 次，输出字节与时长（2.72 / 2.98 / 2.77 s）都不同，基频中位数在 115–153 Hz 间摆动：低沉偏男声，但不可复现。

**结论**：Fish 是「复刻型」TTS，不是「点选音色型」。要进产品必须自带参考音频（OpenRouter 上限：base64 ≤20 MiB、解码 ≤15 MiB、transcript ≤10,000 字符），或用 Fish 自己的 `reference_id`。这与 `listen-reader` 的 `Voice` 概念（可切换、可复现的发声风格）不兼容——除非我们愿意管理一批参考音频素材。

---

## 5. 对后续实现的约束

| 约束 | 影响 |
|---|---|
| OpenRouter 多一跳 | Qwen/Grok/Fish/Eleven/Gemini 都经 OpenRouter 转发；延迟含其排队。需要直连能力时，Qwen 可走 DashScope，Grok 可走 xAI |
| 单请求文本上限 | StepFun 全系 1,000 字符/请求（Segment 级合成天然满足）；DeepSeek 10,000；Qwen/Grok 上限未验证 |
| 并发与限速 | 阶跃并发上限未查；DeepSeek 50 并发；OpenRouter 未查。二十万字 = 数千个 Segment，必须并发 + 退避重试 |
| 音色数量 | Qwen 只有 2 个（`longanhuan_v3.6` / `loongjohn`），做多角色朗读不足；mini 数十个、Grok 5 个 |
| 响度不一致 | 实测 RMS 均值差 3–4 dB（mini 最响），跨厂商切换时必须做响度归一，否则 A/B 与用户体验都会被响度误导 |
| 格式 | StepFun 支持 mp3/wav/flac/opus/pcm；DeepSeek 只有 wav/裸 pcm；OpenRouter 走 mp3/pcm。AudioTrack 落盘前统一转码 |
| 时间戳 | 阶跃可返回词级时间戳（`timestamp=true`，需 `return_url` 或 SSE），做跟读/高亮只能指望它 |

---

## 6. 未决问题

1. **Qwen / Grok 的中文长文稳定性**：目前只验证了 2 段文本，没有覆盖数字、专名、多角色对话。`[INFERENCE]` 这些是中文 TTS 最容易翻车的地方，定案前应补一组压力文本。
2. **并发上限与单价折扣**：StepFun、OpenRouter 的 QPS/TPS 上限未查，批量生成一本书的真实耗时可能远高于实时倍率推算。
3. **是否需要多音色**：`listen-reader` 的对话体书籍需要音色区分；Qwen 的 2 个音色可能直接卡死这条路。
4. **OpenRouter 的 `s2.1-pro-free` 免费档**：目录里有免费模型（`fish-audio/s2.1-pro-free:free`、`heygen-voice-1-turbo:free`），未测，可能对非商用/试听场景有意义。

---

## 7. 复现

实测脚本未入库，位于 `~/tmp/tts-demo/`（一次性工作区，不随仓库发布）：

| 文件 | 作用 |
|---|---|
| `ttslib.py` | DeepSeek / StepFun 直连封装、WAV 解析、RMS、计费折算 |
| `gen.py` + `template.html` | 第一轮（DeepSeek vs StepAudio 3 vs mini）样本与页面 |
| `or.py` + `or_build.py` + `or.template.html` | 第二轮（七个模型价格/速度）样本、榜单、页面 |
| `selfcontained.py` | 打包成单文件（CSS 内联 + 音频 base64） |

```bash
cd ~/tmp/tts-demo
python3 or.py        # 重新合成并计时（会重新产生 API 费用）
python3 or_build.py  # 只重算榜单/渲染，用 or.data.json
```

API Key 从 magpie 读取（`~/.config/magpie/providers.json`：`deepseek`、`stepfun-cn`、`openrouter`）。

---

## 8. 来源

- OpenRouter TTS 目录与定价：<https://openrouter.ai/api/v1/models?output_modalities=speech>
- OpenRouter TTS 指南（参数、计费、Fish/Gemini 特殊行为）：<https://openrouter.ai/docs/guides/overview/multimodal/tts>
- Fish Audio TTS 接口（`reference_id` / `references` 语义）：<https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech>
- 阶跃 API 定价（StepAudio 3 新价自 2026-11-23 12:00 生效）：<https://platform.stepfun.com/docs/zh/guides/pricing/details>
- 阶跃官方音色与 `voice_label` 支持矩阵：<https://platform.stepfun.com/docs/zh/guides/developer/tts>
- DeepSeek 语音合成（音色、计费、限制）：<https://api-docs.deepseek.com/zh-cn/guides/tts>
