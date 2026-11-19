# 听我读 (Listen Reader)

本地优先的网页与文本内容提取及 BYOK TTS 语音朗读归档应用。

## Language

**Resource（资源）**:
资源库中的一条顶层归档记录，聚合了一个采集源（Source）、其派生的朗读文稿（Script）以及对应的音轨（AudioTrack）。
_Avoid_: Item, Article, Record, Document

**Source（采集源）**:
用户导入的原始输入及其本地离线快照，形态包括网页（URL 与离线页面快照）或原始纯文本。
_Avoid_: Input, RawData, Origin

**Script（朗读文稿）**:
由采集源（Source）提取并经清洗后形成的待朗读正文，由按顺序排列的语段（Segment）组成。
_Avoid_: Content, Body, Transcript, CleanText

**Segment（语段）**:
朗读文稿（Script）按自然句切分（并将极短句读并入相邻句以保证语调连贯）出的最小原子单元；一语段对应一行歌词、一次增量合成与一个独立本地音频片段。
_Avoid_: Chunk, Sentence, Paragraph, Line, Slice

**AudioTrack（音轨）**:
一份朗读文稿（Script）在当前激活的语音配置下生成的唯一本地持久化音频集合及其各语段（Segment）的时间轴索引。
_Avoid_: AudioFile, VoiceCache, Recording, Stream

**Voice（音色）**:
TTS 服务商提供的具体发声风格；同一 Script 切换 Voice 时，AudioTrack 按当前 Voice 重新生成。
_Avoid_: Speaker, Timbre, Model

**Capture（采集）**:
把一个 Source 提取、清洗并切分为可朗读 Script 的过程。
_Avoid_: Import, Scrape, Crawl
