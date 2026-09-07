# Android 音乐 Agent：真实搜索与整组播放修复

2026-09-06，工作区基于 `876f026`，保留现有未提交改动。已修复本轮实际复现的问题，并完成两次真实服务连续复测。新 APK 包含此前整组、首曲、重试修复及当前动画、播放和缓存改动；未提交或推送 Git。

## 失败原因及路径比较

在相同匿名条件下，旧搜索路径 `WEAPI cloudsearch/get/web` 返回业务错误 `50000005`，新的 `EAPI cloudsearch/pc` 返回 `200` 和歌曲。这是收到服务响应后的业务失败，不是手机网络超时。修复前的四轮真实验证共调用 DeepSeek 18 次、网易云搜索 60 次，队列提交为 0。

Android 的 Agent 与系统媒体搜索均经 `PipoGraph.repository → RustBridgeRepository → JsonRustBridge` 使用相同搜索实现和应用目录，未发现 Agent 单独使用另一套账号。资料库/歌曲选择界面的搜索框过滤本地列表，因此本地搜索成功不能证明在线接口正常。桌面端有自己的会话目录；用户未指定“其他工具”的具体入口，不能把其成功归因为已经证实的登录或会员差异。

另一层错误是 Kotlin 将搜索异常降为“没有歌曲”，让模型反复换关键词，并推测版权或未收录。现在服务异常明确传递为 `music_search_unavailable`，同一轮停止重复联网搜索；已有本地结果仍可使用。无提交的失败任务保留原请求供手动重试，不把服务故障伪装为成功或曲库为空。

## 改动文件与行为

| 文件 | 变化 |
| --- | --- |
| `android-native/native-bridge/src/netease/mod.rs`、`src/lib.rs` | Android JNI 搜索改走现有客户端的 EAPI 搜索接口，校验返回业务码。桌面 Tauri 搜索实现未改动。 |
| `data/MusicSearchException.kt`、`data/RustBridgeRepository.kt` | 搜索方法保留取消信号，向上返回明确、安全的服务失败；其他仓库方法保留既有行为。 |
| `data/agent/resolve/MusicResolver.kt`、`TrackResolver.kt` | 多查询保留成功结果，全部服务失败时向上传递异常；首曲、必含曲和末曲独立解析，避免把其歌手错误地限制到整组候选。 |
| `data/agent/runtime/AgentToolLoop.kt` | 不再吞掉搜索服务错误；普通单步提交完成后及时结束；“我要听原唱的”实际提交对应原唱歌曲。保留模型提供的整组查询；含指定首曲的开放推荐必须先展示草稿给模型核对，再允许提交，禁止绕过核对自动执行。 |
| `data/agent/normalize/CommandTextSignals.kt`、`queue/QueueValidator.kt` | 基础歌名可匹配同歌手的 Live/重制后缀，明确指定版本仍须匹配；不会放宽到伴奏、翻唱或混音版。 |
| `scripts/agent-reliability/`、`native-bridge/examples/reliability_netease.rs` | 增加搜索失败传播、真实查询召回、版本匹配和真实服务验证入口。验证替身与 probe 不进入 App 业务源码或 APK。 |

表中 Kotlin 路径位于 `android-native/app/src/main/java/app/pipo/nativeapp/`。此前已修复的整组数量保护、本地不足时联网补齐、首曲约束、普通失败后完整重试及失败回复替换继续保留。

## 最终验证

使用真实 DeepSeek Flash、生产 Kotlin `AgentToolLoop` / `MusicResolver` / `CandidateRecall`、生产 Android Rust 网易云搜索，以及实际 `PlayerAgentExecutor` 的播放 URL 解析。网易云为匿名会话，本地库为空；Android 持久化和播放器提交回调受控，没有填充假的在线搜索或歌曲结果。

| 连续输入 | 第一次队列数量 | 第二次队列数量 |
| --- | ---: | ---: |
| 我要听加州旅馆 | 1 | 1 |
| 我要听原唱的 | 1 | 1 |
| 我要听rnb | 12 | 12 |
| 来一点安静的中文歌，第一首要易烊千玺的粉雾海 | 12 | 12 |

两次共 18 次真实模型调用、40 次真实搜索，搜索全部成功，8 轮各提交一次队列。前两轮均选中 Eagles《Hotel California (2013 Remaster)》。第四轮分别以易烊千玺《粉雾海 (浴池Live版)》和《粉雾海 (Live)》开头；后续包含毛不易、陈奕迅、周深等歌曲，没有把整组限定成首曲歌手。这里验证的是实际返回的现场版本，不能宣称取得录音室版。

| 证据层级 | 结果与边界 |
| --- | --- |
| 本地 JVM | **147/147** 通过，覆盖生产解析器、任务 Store、播放执行器和新增搜索异常传播回归；外部 Android/服务边界受控。 |
| 真实模型与音乐服务 | 上述两次四轮验证通过。此前受控曲库 137/137 和 Flash 58/58 的旧结果不作为这次真实接口成功的依据。 |
| URL 与 HTTP | 两次队列涉及的 **29 首不同歌曲均取得 URL**；逐首读取 4096 字节，HTTP 200/206、音频头检查通过，无 HTML 错误页。匿名响应中 **9 首标记为试听片段**。未做完整音频解码或听感验证。 |
| Android 构建 | ARM64 native bridge 已重建；`:app:assembleRelease` 成功，1 分 45 秒。 |
| APK | 包名、版本、ARM64、v2 签名、ZIP CRC、16 KiB 对齐通过；包内 native 与 release 中间产物一致，包含新搜索接口与服务异常标记，交付副本与构建产物逐字节一致。 |
| 设备 | 未安装到用户手机，未做真机播放；未操作真实收藏或歌单。 |

[本地结果](/Volumes/soft/Claudio/scripts/agent-reliability/results.json)、[真实服务结果](/Volumes/soft/Claudio/scripts/agent-reliability/real-service-results.json) 和 [产物记录](/Volumes/soft/Claudio/scripts/agent-reliability/release-result.json) 为当前交付记录。原始真实服务追踪位于 `android-native/app/build/agent-reliability/real-service/final-live-1.json` 与 `final-live-2.json`；同目录保留修复前失败及 URL/HTTP 证据。凭据仅经验证子进程环境传入，最终精确扫描结果见产物记录。

## 范围与手测路径

普通流派、语言与安静程度依赖搜索结果和模型选择，未取得逐首权威分类或听感认证。服务或账号只提供试听时，不能据此宣称可播放完整版。有限次数的成功也不能保证第三方服务永不失败。

安装本次 APK，在对话依次输入上表四句话，核对前两轮原唱单曲、后两轮各 12 首、第四轮《粉雾海》首播和后续歌手。继续切到下一首核对设备实际播放。普通失败后输入“再试试”，核对保留原条件并在成功后替换失败回复。手机账号会员状态、完整播放与后台持续播放仍需真机验证。

## 安装包

[下载 Claudio-PIPO-v0.4.3-32-release-20260906-003217.apk](/Volumes/soft/Claudio/android-native/release-apks/Claudio-PIPO-v0.4.3-32-release-20260906-003217.apk)

- 包名与版本：`app.pipo.nativeapp`，`0.4.3 (32)`，仅 `arm64-v8a`，10,719,754 bytes。
- APK SHA-256：`7480639e15e1174b3f0e66b78d96d794c5a04a1ed3dcf0534d782da09eff49f3`。
- 签名证书 SHA-256：`c18626989d71387e0232da98aef662bd144b68ef1009f26b7d2ab7a0e72c4d1f`，与既有 release 一致。
