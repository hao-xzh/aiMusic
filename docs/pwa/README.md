# PIPO iPhone PWA 体验版

## 2026-09-22 已从 Chromdata 服务器下线

按用户要求，PIPO 的服务器部署及 AWS 访问入口已撤下。原 AWS 地址不再提供服务，下文的安装、重启和回退步骤仅作为历史记录，不适用于当前状态。本地源码、Android 安装包和 N1 局域网部署未在本次任务中删除或修改。

- `pipo-pwa` 已停止并取消开机启动，专属 systemd、Nginx 配置及 `/opt/pipo` 程序目录已移除；`4331`、`8081` 均不再监听，释放约 249 MB 程序文件。
- CloudFront 分发 `E2MTCX24Q26JN0`、访问函数 `pipo-device-access`、VPC 回源 `vo_7qGKrW715AWDjECUFi7PUQ` 和安全组 `sg-041bc9dcd2f0ccbb6` 已删除，并已从 AWS 查询确认不存在。PIPO 安全组已从共享 EC2 解绑，原安全组保留；CloudFront 托管安全组未手动删除。
- 配置及账号数据仅封存于 `/var/backups/pipo-retired-20260922`，约 120 KB，目录权限 `0700`、归档权限 `0600`；原运行数据目录与 `pipo` 服务账号已移除。
- 共用 EC2、IP、磁盘、RDS、IAM 配置和 S3 存储桶保留。S3 的 `pipo-deploy/` 临时前缀当前对象数为 0。
- Nginx 配置检查通过，Nginx、PHP-FPM 和两个 Chromdata Orchestrator 服务保持 active，原后台 `webus.bestchatuplines.com` 返回 HTTP 200，无可信回源头的直连请求仍被拒绝。`watchgleeonline.com` 在下线前后的本机检查均失败，服务器侧复核为 DNS 无法解析，因此本次没有宣称该站正常；未修改它的 DNS 或分发。

## 历史使用方式

在 iPhone Safari 打开 [PIPO AWS 体验入口](https://d1jmgaobv5genm.cloudfront.net/pwa)，选择 **分享 → 添加到主屏幕**。安装不需要登记 UDID 或签名。首次打开会转到 `/access`：输入一次访问口令后，这台设备在默认 180 天内无需重复输入。换设备、清除浏览器数据或会话到期后需要重新输入。

这是应用自己的设备访问页，不是 HTTP Basic Auth。访问会话使用 `Secure`、`HttpOnly` 和 `SameSite=Strict` Cookie，不绑定 IP。完成访问后，在 **设置** 中登录网易云、选择 AI 服务商并保存自己的 Key。

2026-09-13 起，上述 AWS 地址替代旧的 Mac Cloudflare Tunnel 临时入口。旧主屏幕图标仍指向旧地址，请用新地址重新添加到主屏幕。旧临时隧道和对应的 Mac 4333 预览进程已正常停止；N1 局域网入口保留。

本次按 Android 基线 `d1d81284435d726c89b5435c0e58de8ae73d1d70` 迁移。浏览器实现和本地验证已推进到下面的状态；尚不能认定 iPhone 的视觉、后台播放和硬件操作与 Android 完全一致。

## AWS 历史部署记录（2026-09-13，现已下线）

`https://d1jmgaobv5genm.cloudfront.net/pwa` 由 CloudFront 按量分发并使用默认免费证书。维护访问口令时，只从权限为 `0600` 的 `.pwa-data/aws-ec2/访问说明.md` 取得受限操作说明；不要把口令写入 README、提交记录、构建日志或聊天内容。

| 资源 | 下线前状态 |
| --- | --- |
| CloudFront 分发 | `E2MTCX24Q26JN0`，状态为 `Deployed` |
| VPC Origin | `vo_7qGKrW715AWDjECUFi7PUQ`，状态为 `Deployed` |
| 设备访问 Function | `pipo-device-access`，使用 `LIVE` 版本 |
| 应用主机 | EC2 `i-0846af81b6c296b45`，`us-west-1`，`t3.medium`；不升级实例规格 |
| 当前发布 | `/opt/pipo/current` → `/opt/pipo/releases/ec2-20260913-sms/app`；上一版 `ec2-20260913-device` 可用于回退 |

应用不公开新增端口。独立安全组 `sg-041bc9dcd2f0ccbb6` 只允许 CloudFront VPC Origins 服务安全组 `sg-027453220c7ff9f13` 访问 TCP `8081`，原安全组 `sg-015d341bf9e4d2071` 保留。PIPO 的 Nginx 入口只监听 `172.31.2.249:8081`，校验 CloudFront 附加的私有 `X-Origin-Site`；Next 只监听 `127.0.0.1:4331`。不要把端口 `4331` 或其他应用端口直接开放到公网。

应用以非 root 的 `pipo` systemd 服务运行，持久目录 `/var/lib/pipo` 权限为 `0700`。服务启用只读程序和 `ProtectSystem`，隔离 `/var/www`、`/etc/chromdata` 和 `/home`，并设定 CPU 75%、内存 768 MiB、Node 堆 384 MiB、worker 2，以及失败自动重启。已在实际服务的挂载命名空间内，以 `pipo` 身份验证无法读取 Chromdata 配置和上述业务目录。最终发布后服务内存单次采样约 88 MiB；这不是压力或长期运行测试。现有 Nginx、`php8.3-fpm`、`chromdata-ai-orchestrator-api` 和 worker 保持 active；`https://webus.bestchatuplines.com` 与 `https://bestchatuplines.com` 均再次返回 HTTP 200。

本地发布包 `.pwa-data/router-releases/ec2-20260913-device.tar.gz` 约 29 MB，是前端基包，包含 Next 15.5.25、React 19.0.8 和 Linux x86_64 静态 Rust bridge。当前完整服务由该基包和本次更新的 bridge 组成，不能把该归档说成已包含短信登录热修复。最终 Next 构建已通过编译、类型检查和页面生成；Next 对 API 的 client manifest 复制警告仍存在，但不是构建失败。相关部署真相来源是 `deploy/pwa/pipo-pwa.service`、`deploy/pwa/nginx.ec2.conf.tpl` 和 `deploy/pwa/cloudfront-access.js.tpl`；`scripts/package-pwa-router.mjs` 的第 3 个参数支持 `x86_64`。

当前已确认的访问链路是：未登录访问 `/pwa` 会 302 到 `/access`，API 返回 401；跨来源登录返回 403；正确口令返回 200，登录后 `/pwa`、会话接口均返回 200。Chrome 已完成一次首次登录；刷新页面和新开同一 URL 的标签页都直接进入 `/pwa`，不再输入口令。

Chrome 搜索 `Style Taylor Swift` 返回 17 条真实歌曲，首条 `Style` 可打开播放器并显示歌词。但游客会话中音频元素为 `time=0`、`readyState=0`、`paused=true`，页面明确提示“当前账号暂时无法播放这首歌。”返回 **设置** 后，网易云扫码登录已成功生成二维码，正等待用户本人扫码；扫码登录后的实际播放仍待验证。

美国 EC2 上，Rust 访客请求 `Style`（`29572502`）的 standard 和 lossless 音频 URL 都是 `null`。N1 的全新匿名会话可试听同曲约 30 秒。美国节点的曲库可用性和登录后的完整播放仍取决于用户账号、地区与音乐服务权限，不能承诺所有歌曲稳定可听。

本次通过项目的 SSM 运维入口部署。三份用于传包的私有 S3 临时对象和服务器预检目录已清理；当前发布、上一个发布与持久数据保留。未复制 Mac 或 N1 上的网易云登录、AI Key 到 AWS。

CloudFront 截至 9 月 13 日已有约 30.5 GB 的账户级用量。按量计划的免费额度是同一账户每月共用 1 TB 出站流量和 1,000 万请求，不是每个分发各有一份。按每天 2 小时、320 kbps 估算，单人每月约 9 GB，预期新增 CloudFront 费用接近 0 元；这只是估算，不含 AI API 费用，也不是费用保证。查看 [CloudFront 常见问题](https://aws.amazon.com/cloudfront/faqs/) 和 [CloudFront VPC Origins 公告](https://aws.amazon.com/about-aws/whats-new/2024/11/amazon-cloudfront-vpc-origins/) 了解现行计费与服务范围。

日常排查先运行：

```sh
sudo systemctl status pipo-pwa --no-pager
readlink -f /opt/pipo/current
```

需要重启服务时运行：

```sh
sudo systemctl restart pipo-pwa
```

仅在确认当前发布需要恢复，并且旧发布目录仍存在时，才切回已知的 `ec2-20260913-device` 符号链接：

```sh
sudo ln -sfn /opt/pipo/releases/ec2-20260913-device/app /opt/pipo/current
sudo systemctl restart pipo-pwa
sudo systemctl status pipo-pwa --no-pager
```

此回退只恢复该发布目录，不应把它当作降级到较早 Next 版本的方案。

## 2026-09-13 短信登录接口修正，真实复测未通过

用户明确反馈两端短信登录会提示风险，因此修复登录协议差异，而非只要求改用扫码。网易云官网当前脚本 [`core_42bdcddfea41cddc3b970c14b3d2f307.js`](https://s3.music.126.net/web/s/core_42bdcddfea41cddc3b970c14b3d2f307.js) 将验证码登录与密码登录分开：前者使用 `captcha-login` 和 `/api/login/cellphone`，后者使用 `mobile-login` 和 `/api/w/login/cellphone`。脚本 [`pt_frame_index_30a8de4fcd987858710f3bd9fc3d8aed.js`](https://s3.music.126.net/web/s/pt_frame_index_30a8de4fcd987858710f3bd9fc3d8aed.js) 的验证码 payload 包含 `phone`、`countrycode`、`captcha` 和 `rememberLogin=true`。

共用 Rust 业务代码 `src-tauri/src/netease/api.rs` 已将 `login_cellphone` 的请求从 `/weapi/w/login/cellphone` 改为 `/weapi/login/cellphone`，移除密码流程的 `type`、`https`、`remember` 和空 `secureCaptcha`，改用 `rememberLogin` 布尔值。PWA 与 Android 共用这段代码。官网还会获取 `checkToken`；本批没有伪造该值、接入设备验证 SDK 或绕过验证，也不能把账号风控完全归因于这一项协议差异，更不能承诺已经消除风险提示。

Linux musl release 在 8.59 秒内通过，Android arm64 native release 在 25.62 秒内通过，`:app:assembleDebug` 在 31 秒内通过。测试 APK 位于 `android-native/app/build/outputs/apk/debug/app-debug.apk`，约 36 MB，`applicationId` 为 `app.pipo.nativeapp.debug`、版本为 `0.4.3-debug`（`versionCode` 32）、最低 API 26、目标 API 36，包含 `arm64-v8a`，可与原版并存。没有进行生产签名、真机安装、短信发送或自动化测试。

测试 APK 的调试签名校验与 16 KB ZIP 对齐检查通过，包内 Rust 动态库与本次构建的 `stripped_native_libs` 产物字节一致。这些是产物检查，不能证明真实短信登录成功。

云端通过 SSM 仅替换共享 Rust 业务库，形成当前 `ec2-20260913-sms` 发布；它从 `ec2-20260913-device` 复制并保留 Next 产物、账号目录和安全配置，原四项服务均保持 active。

用户随后在云端完成一次短信登录尝试，仍失败。已在用户实际操作的 Chrome 页面确认提示：“当前登录存在安全风险，请稍后再试。”因此本批只证明接口差异已修正，短信登录功能复测未通过；Android 测试包也不能据此认定可用。现有诊断导出未取得这次请求的业务码或安全挑战详情，不能推定具体错误码，也不能断定账号、服务器出口或缺少 `checkToken` 中哪一项是此次拦截的原因。

官网的设备验证加载器和 `getToken` 调用可从其公开脚本确认，但尚未取得支持 PIPO 完成该验证的完整契约，也未确认服务端域名、令牌和环境绑定规则。没有继续加入推测性的设备令牌或修改服务器安全设置。浏览器工具安全策略阻止了官网页面对照访问；已询问用户同一账号近期在官方 App 中的短信登录结果，以缩小原因范围。恢复条件是取得可确认的官方登录对照结果或可完成的设备验证契约，再修复并复测原失败路径；登录后实际播放仍未验证。

## 已接入的功能与代码位置

| 功能 | 实现位置 |
| --- | --- |
| 首页推荐、资料库、搜索、歌单、收藏和歌曲菜单 | `src/components/pwa/Browse.tsx`、`src/lib/pwa/library.ts`、`src/lib/pwa/recommend.ts` |
| 全屏与迷你播放器、沉浸歌词、横屏、队列和五种播放模式 | `src/components/pwa/Player.tsx`、`Lyrics.tsx`、`src/lib/pwa/player.ts` |
| 编码音频流、预加载、切歌与原生分析结果的过渡策略 | `src/lib/pwa/streaming-audio.ts`、`transition.ts`、`music-analysis.ts` |
| 扫码、短信登录、AI 配置、偏好、口味和诊断 | `src/components/pwa/Account.tsx`、`Taste.tsx`、`src/lib/pwa/settings.ts`、`taste.ts` |
| AI 唤醒、对话、工具执行、插播和页面重开后的任务恢复 | `src/components/pwa/NativeAssistant.tsx`、`AssistantWake.tsx`、`src/lib/pwa/agent-*`、`assistant.ts` |
| 网易云和 AI 业务桥接、设备会话、音频与图片代理 | `services/pwa-bridge/`、`src/lib/pwa/bridge-server.ts`、`src/app/api/pwa/` |
| 安装、资源缓存、完整音频与 Range 请求缓存 | `public/pwa-sw.js`、`public/pwa/manifest.webmanifest` |
| 页面高度、安全区、缩放限制和页面导航 | `src/components/pwa/NativeApp.tsx`、`app.css`、`src/app/pwa/layout.tsx` |

`/pwa` 使用独立的浏览器播放器；其他桌面入口保留原有 Provider。旧的本地文件试听验证入口保留在 `/pwa/probe`，没有放入正式导航。原图、字体、图标和动效参数来自已读取的 Android 基线。

音频由浏览器媒体元素播放，不在手机上整曲解码成 PCM。完整编码音频写入 Service Worker 缓存，容量上限默认 4 GB，同时受浏览器可用空间约束。页面关闭后的 AI 搜索、整理工作可在服务器继续；需要控制播放器时，任务等待页面恢复并确认实际执行结果。

## 2026-09-11 已取得的验证结果

| 层级 | 已验证结果 | 证据边界 |
| --- | --- | --- |
| 源码与构建 | Rust 业务桥接 release 构建通过；最新 `pnpm build` 编译、类型检查和页面生成通过；Service Worker 语法、改动的 diff 检查通过 | 没有新增或运行自动化测试；构建不能代表 iPhone 实测 |
| 产物 | 最新 `/pwa` 页面构建报告为 71.6 kB，首次 JS 为 189 kB | 这是构建体积，不是手机帧率或耗电测试 |
| 真实账号 | 用户完成扫码登录；读取真实歌单、歌单内搜索、网格视图与排序持久化通过 | 短信验证码流程未实际发送；没有用游客数据冒充登录通过 |
| 收藏 | 喜欢歌曲后重开，状态保留；随后取消喜欢并还原原状态 | 已核验账号结果 |
| 歌单修改 | 现有空测试歌单添加 Yellow 后读回 1 首，移除并刷新后为 0 首 | 测试歌单已还原为空；新建歌单未实际创建外部对象 |
| 音频 | 真实音频传输和播放时钟持续推进；Carefree 自动切至 Monkeys Spinning Monkeys，顺序队列末尾停止 | 本地浏览器；未以计时或日志冒充听感、混音质量及锁屏稳定性 |
| AI | 真实模型执行两首精确点歌、改顺序、真实五首歌单和下一首插播；提交后重开仍可恢复队列；队尾无法跳过时明确失败 | 五首歌单实际顺序与来源一致；插播保留当前曲目与其余顺序，测试后移除插播曲；等待点击播放时不再宣称已在播放 |
| 会话隔离 | 用另一设备会话提交旧任务范围，服务器返回 409，目标会话任务数仍为 0 | 手动 API 核验，不含用户凭据输出 |
| 缓存 | 停止本地服务后重开页面，缓存音频持续播放；缓存资料库与歌单仍可访问；恢复服务后可重新同步 | 这是服务断开场景，不是 iPhone 飞行模式或浏览器存储被回收后的测试 |
| 最终回归 | 最终构建离线重开后，真实音频时钟从 0.24 推进到 39.45 秒，无媒体错误；连续关闭 AI、队列后一次返回浏览页，从设置展开播放器后一次返回设置 | 本地浏览器操作；复测使用包含最终修复的构建 |
| 本地布局 | 390 × 844 浏览器中，标题区域结束于 420，歌词区域从 422 延伸到 824；根容器覆盖完整 844 高度 | 浏览器安全区为 0，不能代替 iPhone 17 Pro 真机确认 |
| 体验链接 | HTTPS 页面返回 200，已包含最新 viewport 配置和完整布局修复 | 预览运行于下述固定产物；没有发布 IPA、App Store 或正式生产服务 |

账号、AI Key、会话文件只保存在忽略提交的 `.pwa-data/` 中。公开预览与本地测试采用独立数据目录，没有把本地登录凭据复制到体验版。

## 2026-09-12 页面适配修复与回归

用户继续反馈底部应由页面延伸使用、歌词应完整显示、歌曲条偶尔重复叠加，并要求各页面适配 iPhone 17 Pro。本批沿用同一功能范围，已更新至当时的临时体验链接。

| 改动 | 解决的问题 |
| --- | --- |
| 页面、背景铺满容器，底栏浮在页面上；滚动末尾按实际底栏和歌曲条高度预留空间 | 原来页面主体在底栏上方结束。现在背景和页面延伸到底边，底栏使用透底模糊，滚动文字不会直接干扰导航 |
| AppShell、页面根容器共用完整视口高度，主屏幕模式保留 `100vh` | 消除内外容器分别使用高度导致的裁切机会；实际 iOS 安全区仍待真机确认 |
| 歌曲条动画副本只在过渡期间显示；隐藏歌词行继承父层可见性 | 初始隐藏的父层曾被子节点的 `visibility: visible` 覆盖，导致歌曲条副本残留及隐藏歌词进入可访问树 |
| 歌词区填充标题下方直至页面底部；取消译文 50px 上限，超长词可换行，为高亮缩放预留宽度 | 长译文和长歌词不再被固定高度、单词禁止换行或缩放后的横向裁切限制 |
| 横屏收起按当前页面方向处理 | 从竖屏沉浸歌词旋转后，第一次收起曾只退出不可见的竖屏状态，需要第二次点击才返回 |
| 通用弹层避让键盘，可滚动的新建歌单表单保留名称输入焦点 | 弹层不再主动把已聚焦输入框的焦点移到面板；底部空间与现有键盘测量共用 |
| 设置菜单在可视范围内选择向上或向下展开，监听旋转和滚动；横屏压缩首页、歌单留白 | 低高度页面能显示菜单和主要内容，菜单尺寸受可视区域约束 |

改动文件：`src/components/AppShell.tsx`，以及 `src/components/pwa/` 中的 `NativeApp.tsx`、`Player.tsx`、`Lyrics.tsx`、`Shared.tsx`、`Account.tsx`、`app.css`、`player.css`。本轮没有修改 Android 源码、业务桥接、播放引擎或账号数据。

主屏幕高度处理参考已有 [WebKit 安全区高度问题](https://bugs.webkit.org/show_bug.cgi?id=254868)。这不是对用户设备根因的确认；本次已有可直接定位的页面高度分配与子层可见性缺陷。

| 验证层级 / 路径 | 本轮结果 |
| --- | --- |
| 构建与静态 | 最终 `pnpm build` 编译、类型检查、页面生成通过；`/pwa` 72.2 kB，首次 JS 190 kB。diff 检查通过。未新增或运行自动化测试 |
| 402 × 874 浏览器首帧与重开 | 根容器和页面主体均覆盖 874px；仅一条歌曲条可见，动画副本隐藏；隐藏歌词可见行数为 0 |
| 播放器 → 沉浸歌词 → 874 × 402 横屏 → 收起 | 使用真实 Style 歌曲；一次收起返回原设置页，歌曲条保持单一。最终源码批次已复测 |
| 歌词与译文 | 竖屏标题结束约 423px，歌词从约 425px 延伸至 874px；横屏标题、歌词、控制区分区。已显示译文的裁切计数及超宽词计数均为 0；曲尾最后一句可见 |
| 首页、资料库、歌单、搜索 | 真实资料库和歌单可进入；全局搜索返回真实歌曲；歌曲菜单在 402 × 874 中最终范围为 463–874px，无横向溢出 |
| 设置、AI 配置、音乐口味 | 横竖屏菜单均在视口内；长页面滚动到末尾后，末尾内容位于歌曲条上方。横屏口味页无横向溢出 |
| 队列与新建歌单 | 横屏队列范围为 24–402px，内容区可滚动；最终构建打开新建歌单后焦点为名称 INPUT。取消后无遗留弹层，没有创建外部歌单 |
| AI 输入 | 402 × 874 中，对话底部 786px，输入区从 798px 到 850px，焦点可进入输入框；没有为布局验证重新提交 AI 任务 |
| 登录与体验链接 | 新 HTTPS 地址可打开；402 × 874 登录页字段和按钮均在屏内，无横向溢出；viewport 保留最大比例 1 和禁止缩放配置。未实际发送短信 |

以上是浏览器实际操作与渲染结果。浏览器安全区为 0，没有 iPhone 软键盘，不能代替 iPhone 17 Pro 的安装模式、安全区、缩放手势或硬件验收。不同构建间仅重跑受后续修复影响的路径，未将旧包结果用于验证新改动。此前的真实播放、离线缓存、收藏和歌单写入结果未受本轮布局补丁影响，保留上表的原验证日期。

## 2026-09-12 流畅度与唤醒水波修复

用户继续要求更流畅，并反馈有时只看见彩色光、看不见水波。本批已更新至当时的临时体验链接，固定产物为 `20260912-180207`。

水波与彩色光覆盖整个页面，从底部向上推进；水波折射 AI 后方的真实页面，消息与输入框不参与折射。补齐原 Android 水面法线对应的高光、阴影，并将彩色光放到压暗层上方。背景模糊延后 650ms 开始，先给水面经过页面的可见窗口。动画按完整时间线结束，修复首帧强度很低就提前停帧的条件。

| 优化位置 | 本批变化 |
| --- | --- |
| `AssistantWake.tsx`、`assistant-shader.ts`、`assistant.css`、`app.css` | 水面高光与阴影进入现有 GPU 光效；SVG 滤镜明确使用页面尺寸。着色器和 uniform 位置复用，尺寸只在变化时测量，法线图中与横坐标有关的计算按列复用；结束后移除页面滤镜、停帧。保留真实页面折射，不采集页面截图 |
| `Player.tsx`、`src/lib/pwa/player.ts` | 播放进度用一条可见控制区的帧循环直接更新 range；时间与无障碍文案仅在显示秒变化时写入。去掉逐帧 React 状态更新，保留原生拖动、键盘、暂停及 seek 同步 |
| `Lyrics.tsx` | 隐藏歌词不建立帧循环和逐行测量；手动浏览仅处理视口及缓冲行，已唱完和未开始的稳定词不重复写样式；回退定位仍重新计算逐词高亮 |
| `NativeAssistant.tsx` | 隐藏对话不再因任务状态更新触发平滑滚动 |
| `src/lib/pwa/library.ts`、`recommend.ts` | 歌单缓存失效按 id 索引；曝光归因按歌曲分组并二分查最近记录，保留同曲、事件之前及一天内等原边界，避免按每个事件反复扫描和排序全部曝光记录 |

以上组件路径均位于 `src/components/pwa/`，另行注明者除外。此次没有修改 Android、Rust 桥接、外部账号或推荐规则。

| 验证层级 / 路径 | 结果及边界 |
| --- | --- |
| 最终构建、类型与静态 | `pnpm build` 通过，`/pwa` 73.4 kB、首次 JS 191 kB；空白检查通过。未新增或运行自动化测试 |
| 402 × 874 唤醒及重复打开 | 观察到清晰页面上的入场；光效画布覆盖 402 × 874，位移 scale 约 14.9 时背景仍为 `blur(0px)`。结束后画布透明、页面 filter 移除。没有重新发送 AI 请求 |
| 桌面 Safari | 最终构建中实际搜索 Style，进入播放器、双击唤醒、观察入场、Esc 退出通过。桌面 WebKit 结果不替代 iPhone Safari 或主屏幕模式，也不证明设备帧率 |
| 播放和进度 | 使用真实 As It Was；键盘调节后进度继续前进，拖动从约 22 秒定位至 106 秒；横屏控制条继续同步并可暂停 |
| 歌词与回退定位 | 手动滚至前段时，67 行中仅 12 行进入显示/动画范围；再次点选歌词可回退播放，已唱过词的高亮重新从当前进度开始。译文关闭/恢复、横竖屏切换通过；横屏超宽词和译文裁切计数均为 0 |
| 收起与资料库 | 收起后可见歌曲条为 1、隐藏歌词可见行为 0；资料库刷新和 Late Night Drive 五首歌单重新进入通过 |
| 公网产物 | 原地址重新载入成功，实际样式包含本批 650ms 模糊延迟；服务数据目录保持不变，更新前没有执行中的公网 AI 任务 |

推荐索引优化完成静态等价性核对，本批没有为此另发 AI 推荐请求，也没有伪造大规模历史来宣称性能数字。Safari 的 SVG 滤镜与合成效果存在已知限制，见 [WebKit 297770](https://bugs.webkit.org/show_bug.cgi?id=297770)；该记录只作为兼容背景，不能据此认定用户 iPhone 的具体根因。iPhone 上的波纹清晰度、快速反复唤醒、发热和持续帧率仍待实际设备反馈。

### 灵动岛返回到其他页面：待定位

用户本轮报告从灵动岛点入后打开别的网站，正在等待实际网站名称或域名。已核对 `src/lib/pwa/player.ts` 的 Media Session 只设置曲名、歌手、专辑、封面与播放操作；网易歌曲通过当前域名 `/api/pwa/audio/{id}` 播放，`bridge-server.ts` 在服务端内部跟随上游音频跳转，不把上游 Location 透传给浏览器。

旧 Mac 入口的 `/pwa` 与根路径 `/` 分别是 PWA 和现有桌面播放器；尚未证明该差异是灵动岛异常的原因。新 AWS 入口已将根路径 `/` 重定向到 `/pwa`，原桌面入口代码未修改，但灵动岛返回仍待 iPhone 实测。Media Session 标准没有供网页指定系统卡片点击返回网址的 handler，参见 [W3C Media Session](https://www.w3.org/TR/mediasession/)。若新入口仍出现异常，需要打开后的域名/路径，并区分 Safari 标签页播放与主屏幕安装版播放，才能继续定位。

## 剩余真机路径

| 路径 | 验收内容 |
| --- | --- |
| 主屏幕 → 任意有歌词歌曲 → 沉浸歌词 | 底部被实际内容布局使用；标题、作者和当前歌词分区；长歌词与译文不重叠；横竖屏后保持正确 |
| 首页、资料库、播放器、AI 输入 | 双指不能缩放；双击不触发浏览器放大；歌词双击定位仍可用；键盘不遮挡输入栏 |
| 播放真实多曲队列 → 返回桌面 → 锁屏跨过曲尾 | 当前歌曲、跨曲和队列末尾行为分别确认；记录是否停播或需要前台恢复 |
| 锁屏 / 灵动岛 / 蓝牙耳机 | 系统媒体标题、封面、播放暂停和切歌；这些依赖 iOS 的媒体会话表现 |
| 播放 → 电话或其他音频打断 → 返回 | 音频焦点、播放状态和恢复位置符合操作结果 |
| 完整缓存歌曲 → 飞行模式 → 完全关闭并重开 | 页面与曲目可用；未缓存曲目明确失败；不承诺浏览器永不回收缓存 |
| AI 语音按钮 | 真人语音、麦克风权限和识别结果；未取得这部分实测 |

PWA 已接入媒体会话，但没有实现原生 ActivityKit 自定义灵动岛实时活动。浏览器与 iOS 的后台权限不同，不能按 Android 前台播放服务承诺稳定性。推荐质量、复杂语义命令和手机性能也不能仅凭源码与构建认定完全相同。

## 本地运行与预览维护

在项目根目录，先由当前服务 owner 停止将要更新的本地实例，再运行：

```sh
pnpm build:pwa-bridge
pnpm build
pnpm exec next start -H 127.0.0.1 -p 4332
```

只有 Rust 业务源变化时需要重建 bridge。Service Worker 在生产构建和安全来源下注册；开发服务器不用于证明离线行为。

旧 Mac 预览产物保留在 `.pwa-data/preview-releases/20260912-180207`，数据目录是 `.pwa-data/preview-user-data`；对应的 4333 服务和 Cloudflared 隧道已停止。上方 AWS 地址直接使用独立 EC2 发布，不再依赖这台 Mac。旧产物采用更新前的依赖版本，不应重新开放到公网；后续云端维护使用本文件前面的 AWS 发布记录。

控制状态由 [execution-manifest.json](execution-manifest.json) 管理。本文件统一记录使用方法、验证结果和未完成的验收，不另建并行测试账本。

## N1 旁路由试部署（2026-09-12）

局域网入口：[PIPO](http://192.168.1.168:4331/pwa)。设备是 Phicomm N1，四核 ARMv8、系统可用总内存 1.76 GiB，ImmortalWrt 24.10.5、Docker 27.3.1。当前唯一容器为 `pipo-pwa-lan`，使用已拉取的 `node:22-alpine`（Node 22.23.2）。网易云登录及 AI 配置从新会话开始，本机账号数据没有放进部署包。

实际容器参数保存在 [compose.n1.yaml](../../deploy/pwa/compose.n1.yaml)，本次通过已登录的 LuCI Docker 页面设置，未在旁路由运行 Compose 命令。容器使用 host 网络，应用只监听 `192.168.1.168:4331`；初始 bridge 端口映射从局域网连接超时，因此采用这一监听方式。没有修改路由器的防火墙、DHCP 或转发配置。

- 程序只读挂载：`/mnt/mmcblk2p4/pipo/releases/n1-20260912/app` → `/app`。
- 持久数据：`/mnt/mmcblk2p4/pipo/data` → `/data`，权限 `0700`，运行用户 `1000:1000`。
- CPU 上限 1.5 核，内存上限 768 MiB，Node 堆上限 384 MiB，Tokio worker 2；日志最多两份、每份 2 MB。
- 重启策略 `unless-stopped` 已配置。未为验证开机恢复而重启旁路由。
- 本机最终安装包：`.pwa-data/router-releases/n1-20260912-http.tar.gz`，约 26 MB。远端安装目录保留 HTTP 修复前的 `app-before-http` 供回退。
- 两个临时安装容器及初始 bridge 容器已移除；本机传输服务已关闭。部署后 Docker 所在分区页面显示仍有 1.96 GB 可用。

构建入口：

```sh
PIPO_PWA_STANDALONE=1 pnpm build
CARGO_TARGET_DIR="$PWD/.pwa-data/router-cargo" cargo zigbuild --locked --release --target aarch64-unknown-linux-musl --manifest-path services/pwa-bridge/Cargo.toml
node scripts/package-pwa-router.mjs <新的发布名称>
```

需要安装 `cargo-zigbuild`、Zig 和 Rust `aarch64-unknown-linux-musl` target。本次本机工具环境使用 cargo-zigbuild 0.23.4、Zig 0.14.1。Next 独立构建写入 `.next-router`，不覆盖现有 `.next` 与对外预览；输出追踪排除 `.pwa-data` 和本机 Rust target。打包保留相对依赖链接，并拒绝指向包外的链接，防止 Mac 路径进入 Linux 运行环境。[Dockerfile](../../deploy/pwa/Dockerfile) 是安装包附带的镜像构建入口；本次采用官方 Node 基础镜像加只读程序挂载，未在 N1 编译应用。

验证及边界：

- ARM64 musl release 构建通过，产物是静态链接的 Linux ARM64 ELF。Next 构建与类型检查通过；Next 15 对 API route 的不存在的 client-reference-manifest 给出复制警告，已通过实际 HTTP 会话、搜索、音频与歌词调用核验相关运行路径。
- 旁路由 `/pwa` 与 `/api/pwa/session` 返回 200；Rust RPC `netease_account` 正常返回未登录状态。
- Chrome 从旁路由搜索 `Style Taylor Swift`、播放 `Style`、显示歌词、暂停、收起播放器、搜索返回、打开设置及回首页通过。音频 DOM 的实际来源是旁路由 `/api/pwa/audio/29572502`，播放时间推进至 19.58 秒、readyState 4；未登录提供 30 秒试听。完整付费歌曲播放需要用户自己的登录与音乐服务权限。
- 播放中一次 LuCI 采样：容器 CPU 0%，内存 59.875 MB；这是短时采样，不是压力测试或长期稳定性证明。
- 局域网 HTTP 下曾实际触发 `crypto.randomUUID is not a function`，已改用支持 HTTP 的强随机 UUID 回退，覆盖搜索/弹层返回、AI 请求 ID 和测试页导入 ID；修复部署后搜索、播放及设置复测通过，未新增该前端错误。服务端 UUID 逻辑未改变。
- HTTP 下 Service Worker、离线缓存等安全上下文能力仍不可用，完整 PWA 使用需配置 HTTPS。尚未配置外网入口、固定域名或证书；本次不构成 iPhone 真机、灵动岛返回、外网稳定性或长期运行验收。手机手测路径：连接同一局域网 → Safari 打开上述入口 → 搜索歌曲 → 播放/展开歌词/收起 → 登录自己的账号。
