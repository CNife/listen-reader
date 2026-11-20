# 听我读 (Listen Reader)

本地优先的 Android 朗读应用：采集网页或粘贴文本，用 LLM 清洗分段，再通过 StepFun 或千问 TTS 合成语音，交给 Media3 后台连续播放。所有数据（采集快照、朗读文稿、音频）保存在本地，Key 仅以加密形式存储。

## 功能

- **采集**：输入网页 URL 或粘贴纯文本；自动提取正文（Readability），微信公众号文章有专用模板，支持展开折叠正文。
- **清洗分段**：调用 OpenAI 兼容的 LLM 把正文整理成适合朗读的语段；LLM 不可用时自动回退规则分段。
- **语音合成**：双供应商 TTS——StepFun `step-tts-mini`（默认，27 个官方音色）与千问 `qwen-audio-3.0-tts-flash`（备选，12 个系统音色）；切换模型时当前音色自动归一化到目标模型的合法音色，音频按语段本地缓存，缓存后可离线播放。
- **播放**：语段级进度切换、音色切换、睡眠定时器、后台播放与通知控制；资料库支持继续收听与历史归档管理。
- **检查更新**：以 GitHub Release 为准；设置页可开关自动检查（冷启动且距上次成功检查满 24 小时才请求）与随时手动检查，发现新版本时在资料库顶部提示并可跳转浏览器下载 APK。

## BYOK 配置

在应用内「设置」页配置：

- **LLM（清洗）**：填写 OpenAI 兼容的端点、模型与 API Key。默认端点 `https://openrouter.ai/api/v1`，默认模型 `openai/gpt-4.1-mini`，可改为任意兼容服务。
- **StepFun（朗读）**：填写 StepFun 的 API Key；朗读模型与音色在设置页选择。
- **千问（朗读备选）**：填写千问平台的 API Key，端点固定为官方端点，无需配置。

各配置区均提供「测试连接」以验证配置。Key 保存后仅显示「已加密保存」，不会明文回显。

## 构建

需要 Android SDK（compileSdk 35，minSdk 26）：

```bash
./gradlew assembleDebug
```

## 检查更新

更新源是公开仓库 `CNife/listen-reader` 的 GitHub Release：只读取正式版（`/releases/latest`，排除 Draft 与 Pre-release），版本号按 `vX.Y.Z` 与 `versionCode` 同一套规则比较，仅当远端严格更高时提示。

- 自动检查默认开启，冷启动时若距上次**成功**检查已满 24 小时才请求；离线或限流失败静默忽略，不刷新冷却。
- 手动检查无视冷却，若该版本曾被「忽略」也会恢复资料库提示；失败只在设置页版本区展示原因。
- 「去更新」优先打开 Release 附件中的 `listen-reader-<version>.apk` 直链，找不到 APK 附件时回退到 Release 页面，由系统浏览器完成下载与安装。
- 最近一次检查到的远端版本会落盘，断网时也能看到已知更新；安装新版本后旧提示自动消失。

## 发版

推送形如 `v1.2.3` 的 tag 会触发 `.github/workflows/release.yml`：构建已签名 APK，校验签名、对齐、包名与版本，然后创建 GitHub Release 并附带 `listen-reader-<version>.apk` 与 `SHA256SUMS`。

`versionName` 取 tag 去掉 `v`；`versionCode = major*1000000 + minor*1000 + patch`，所以 minor 与 patch 都必须小于 1000，且 tag 只能递增。三段数字不允许前导零（如 `v1.02.3` 会被拒绝），保证 versionCode 与 versionName 一一对应。

首次发版前，在仓库 Settings → Secrets and variables → Actions 配置四个 secrets：

- `RELEASE_KEYSTORE_BASE64`：release keystore 的 base64（`base64 -w0 release.jks`）
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

keystore 必须妥善备份且绝不能提交进仓库（`.gitignore` 已忽略 `*.jks`、`*.keystore`、`keystore.properties`）。丢失它就无法再发布可覆盖升级的同包名安装包。

本地构建已签名包时设置 `RELEASE_KEYSTORE`（keystore 路径）、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 后运行 `./gradlew :app:assembleRelease`；不设置签名变量时仍会产出未签名的 `app-release-unsigned.apk`。

## 术语

产品与代码使用的领域术语（Source、Script、Segment、AudioTrack 等）见 [GLOSSARY.md](GLOSSARY.md)。

## 许可

[MIT](LICENSE)。内嵌的第三方提取脚本署名见 `app/src/main/assets/extract/NOTICE`。
