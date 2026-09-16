# ISSUE-011 拼报缺字母：有道对部分大写字母返回 WAV 伪装 mp3，播放器解码失败静默跳段

**前置依赖**：ISSUE-008（TTS 装配）、ISSUE-012 无关本单；2026-09-16 15:53 用户实机反馈。
**现象**：播放拼写时字母缺失——apple 只听到 a、l、e，两个 P 全缺；A/L/E 正常。
**权威依据**：PRD §4.4（失败静默跳段语义）、§2.3.3（拼报段结构）；修订 #29 不涉及本缺陷。

## 定性结论（2026-09-16 匠人沙箱实测，证据 shared/vocabu-patches/issue011-evidence/）

1. **根因在 TTS 源数据，与修订 #29（LETTER_PAUSE_MS 300→0）无关**——fetch/解码失败在任何停顿值下都会跳段，300ms 时代同缺陷只是未察觉。
2. 有道 dictvoice 对**大写 G、P** 返回 **RIFF/WAVE 格式 body，且 Content-Type 谎报 `audio/mpeg`**：
   - 26 大写字母全表扫描：G(64,052B)、P(110,158B) 为 WAV；其余 24 个为正常 MPEG（复测两次 md5 一致，服务端响应稳定）
   - `curl -D -`：P 的响应头 `Content-Type: audio/mpeg`，body 前 4 字节 `RIFF`——**响应头不可信，必须按字节魔数嗅探**
   - P 的 WAV = 48kHz/16bit/mono PCM，编码器 Lavf58.76.100，**开头约 0.34s 静音**（前 32.4KB 几乎全 0x00）
3. 应用侧死亡链路：字母段文本 = `ch.uppercaseChar().toString()`（SpeechScript）→ `fetch("P", AMERICAN)` 拿到 WAV 字节 → 按 sha256("P|AMERICAN") 落 letters/ 永久缓存（数据本身没坏，只是格式不对）→ 播放时 JLayer `Bitstream` 在 32KB 内找不到 MPEG 帧同步 → 解码失败 → `playSegment` catch **静默跳段**（PRD §4.4 本意是网络失败兜底，此处成了吞字母）→ 同词两个 P 段同一缓存字节 → **两 P 齐缺**；A/L/E 大写响应是正常 MP3 → 正常播。
4. **影响面**：凡拼写含 G/P 的词，对应字母必缺（同缓存字节）；后续任何 TTS 源都可能再混流，播放层应有格式兜底。

## 修法（TDD 划界，一次一个变量）

1. **core 纯逻辑（可单测）**：`AudioSeam.kt`（或 TtsCachePolicy 旁）加 `object AudioFormatSniff { fun isWav(bytes: ByteArray): Boolean }`——魔数判定 `bytes[0..3] == "RIFF" && bytes[8..11] == "WAVE"`（<12 字节返回 false）。**不用响应头**。
2. **desktop 薄壳**：`DesktopAudioPlayer.playSegment` 按嗅探分支——WAV 走新私有 `playWav`：遍历 RIFF chunk 找 `fmt ` 与 `data`；fmt 取 sampleRate/channels/bitsPerSample（实测 G/P 均 PCM 16bit）→ 复用现有 `openLine` 直写 data 字节（16bit 小端 PCM，与 JLayer 输出路径一致）；`data` chunk 按 byte0/byte1 修正位深偏移防御。**一期只支持 PCM 16bit**：其余（float=3 / extensible=0xFFEE / 8bit）stderr 留痕跳段，观察实际面再扩。
3. **顺手项（P2，建议同单）**：`playSegment` 现有 catch 静默吞掉解码失败，本次排障全程无本地痕迹——跳段前补一行 stderr 留痕（`[vocabu-audio] segment decode FAIL ...`，与 TTS 客户端 `[vocabu-tts]` 前缀风格对齐），不改控制流。
4. **不动**：SpeechScript（字母仍大写）、缓存键、LETTER_PAUSE_MS=0 口径、用户端缓存（**无需清理**，WAV 缓存文件修复后可直接播放）。

## 修复记录（2026-09-16 阿斯克米实现，匠人走查）

- **core**：`AudioFormatSniff.isWav`（魔数判定，不信任响应头）+ commonTest 4 用例（WAV/RIFF 非 WAVE/MP3 不误判/短流）。
- **desktop**：`DesktopAudioPlayer` 按 isWav 分流；手写 RIFF chunk 解析 `parseWav`（纯函数，超界防御、奇数 pad、LIST 前置跳过）+ `playWav` 一期仅 PCM 16bit（fmt=3/extensible/8bit → SKIP 留痕）+ 复用 openLine 直写 data；`playSegment` 异常留痕 `[vocabu-audio] segment decode FAIL`（0013 ASCII 口径）+ MP3 零有效帧坏数据检测。
- **走查两轮**：第一版（javax.sound 方案）P2 打回（未校验 format，非 16bit 会播出噪声而非留痕跳段）→ 第二版对齐 issue 划界（嗅探进 core + 手写解析 + 守卫 SKIP），通过。留档偏差：WavInfo/parseWav 置于 companion（Kotlin 合法），缩进由走查方修正。
- 测试：desktopApp 25 + core 166 全绿（走查方独立复核 BUILD SUCCESSFUL）。

## 已知听感代价（留档，耳验）

- 修复后 P 音前带 0.34s 服务端静音，背靠背节奏中 P「迟到」——字母名完整为先，不为裁静音过度工程；用户耳验不可接受再议。

## 验收

- [x] core 嗅探单测：WAV 魔数 → true；正常 MP3（ID3/0xFFE）→ false；短字节流 → false
- [x] apple 拼报五字母全出（P 两声），G 含词（如 go）拼报 G 出声（用户实机）
- [x] 全量单测绿（`:core:jvmTest` + `:app:desktopApp:test`，`sh gradlew`）
- [x] stderr 无 decode FAIL（正常路径）；人为喂坏 MP3 时有留痕（零有效帧检测实现核验）
- [x] 用户实机复验：apple 拼报完整、节奏符合 #29 预期

**关单补记（2026-09-16 16:52）**：用户实机复验通过（apple 五字母全出、节奏正常）。G 与 P 走同一 WAV 嗅探分支（G 同为服务端 WAV 混流），链路一致性由 apple 验证覆盖；G 侧未单独耳验，后续涉及 G 的词（如 go）实机留意即可，不阻塞关单。P 音前带 0.34s 服务端静音未见用户反馈异常，听感代价接受。
