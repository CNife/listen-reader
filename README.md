# 听我读 (Listen Reader)

本地优先的 Android 朗读应用：采集网页或粘贴文本，用 LLM 清洗分段，再通过 StepFun TTS 合成语音，交给 Media3 后台连续播放。所有数据（采集快照、朗读文稿、音频）保存在本地，Key 仅以加密形式存储。

## 功能

- **采集**：输入网页 URL 或粘贴纯文本；自动提取正文（Readability），微信公众号文章有专用模板，支持展开折叠正文。
- **清洗分段**：调用 OpenAI 兼容的 LLM 把正文整理成适合朗读的语段；LLM 不可用时自动回退规则分段。
- **语音合成**：StepFun TTS（`stepaudio-3-tts` 默认 / `step-tts-mini`），四种音色，音频按语段本地缓存，缓存后可离线播放。
- **播放**：语段级进度切换、音色切换、睡眠定时器、后台播放与通知控制；资料库支持继续收听与历史归档管理。

## BYOK 配置

在应用内「设置」页配置：

- **LLM（清洗）**：填写 OpenAI 兼容的端点、模型与 API Key。默认端点 `https://openrouter.ai/api/v1`，默认模型 `openai/gpt-4.1-mini`，可改为任意兼容服务。
- **StepFun（朗读）**：填写 StepFun 的 API Key，可选择 TTS 模型与音色。

两处均提供「测试连接」以验证配置。Key 保存后仅显示「已加密保存」，不会明文回显。

## 构建

需要 Android SDK（compileSdk 35，minSdk 26）：

```bash
./gradlew assembleDebug
```

## 术语

产品与代码使用的领域术语（Source、Script、Segment、AudioTrack 等）见 [GLOSSARY.md](GLOSSARY.md)。
