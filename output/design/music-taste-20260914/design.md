# Claudio 音乐口味页候选设计

状态：v3 已确认，用户“OK，改吧”授权应用源码实现。执行范围：Android 原生音乐口味页；PWA 未同步。
日期：2026-09-14；方向：私人唱片档案。

## 依据与取舍

- 当前原生 owner：android-native/app/src/main/java/app/pipo/nativeapp/ui/TasteScreen.kt；PWA 镜像：src/components/pwa/Taste.tsx。
- 保留画像 summary、来源歌单/样本数、流派权重、moods、topArtists、明确偏好与忘记动作、近期行为、收藏艺人；重排视觉层级。近期行为和收藏艺人可继续向下滚动，不把两种来源混为常听排行。
- 原始资产：/Volumes/soft/Claudio/android-native/app/src/main/assets/artwork/login-music-classics.png。现有页面仍使用它；候选稿在顶部复用。
- [Spotify Taste Profile，2026-03-13](https://newsroom.spotify.com/2026-03-13/taste-profile-beta-announcement/)：借鉴系统理解可查看与可纠正，以及艺人/流派/习惯关联。官方头图已查看，仅为宣传图，不称为实机截图。不引入播客、有声书或新的推荐控制操作。
- [Apple Music Replay，支持页 2026-06-02](https://support.apple.com/en-ie/109356)：借鉴数据分类与来源清楚的层级，不套用没有数据支持的时长、年度榜或月度变化。只读公开说明，未登录。
- [YouTube Music 2024 Recap，2024-11-25](https://blog.youtube/news-and-events/2024-music-recap-youtube/)：借鉴一句听歌描述配合音乐视觉。查看官方文章及首图中的卡片，不照搬年报故事流、分享或音乐人格测评；这是历史视觉案例。

## 单页视觉约束（v3 已确认，真实数据按语义布局适配）

- 逻辑 viewport 432 × 960；24px 水平边距；状态栏约24px；顶部导航48px；底部双入口导航64px加手势区；主内容语义纵向滚动。
- 背景 #080F12；面 #111C20；主文 #E9EFFF；次文 #94A4AA；薄荷 #9BE3C6（延续项目）；辅色 #D4B77A、#9BB7CF。暖色来自既有原始图。
- 现代中文无衬线；主画像34–36/42px、页面标题17/24px、节标题18/26px、正文14–16/21px、来源12/18px。
- 节间16–24px；少量16px圆角；48px动作热区；1px细分隔线；不使用霓虹/大面积玻璃卡/重阴影。
- 流派图是每项独立0–100权重的同心圆弧，不是总和100%的占比饼图。示例86/72/58互不相加。
- 数据视觉与字排是新设计建议，竞品仅为参考；不存在已批准的Figma图层源，也不从参考位图反推坐标。
- 保留首页/资料库两个底部导航。设计稿不展示mini player；实现时核对到共享BrowseChrome原先会在口味页插入mini player，已仅对Route.Taste隐藏，复用其余导航行为。

## 内容与状态

- 本图为有画像、有偏好记录的完整主屏候选；所有歌手、分值、日期、样本数和画像文字为示例，不是读取用户实际账户得出的结果。
- 上屏重点：画像→流派→常听艺人→明确偏好；近期聆听在余量可用时露出；收藏艺人沿用来源区块位于下方。
- 空画像、偏好空态、加载/错误在后续实现时须沿用真实状态；本轮未生成这些附加状态。
- 完整生图提示词：generation-prompt.txt。

## 交付边界

设计阶段交付竞品调研与Image 2效果图。用户已批准v3并要求实现；本次修改原生TasteScreen.kt、PipoDesign.kt中的口味页专用值，以及PipoNativeApp.kt中仅对Route.Taste隐藏mini player的条件。不创建Figma、HTML或执行Manifest。设计图的目视检查不代表应用运行验收。

首版候选：/Volumes/soft/Claudio/output/design/music-taste-20260914/claudio-music-taste.png；内置 image_gen 生成并定点补齐顶部页名。实际输出841×1870，逻辑比例约432×960。已目视检查页面层次、原封面主题、流派图与示例值对应、偏好来源/忘记入口、底部双导航；这是视觉候选，不是可交互成品。未获得用户视觉批准，不冻结，不计算设计hash。

用户反馈：整体仍一般，特别质疑“你明确说过”的圆角卡片和左侧彩条。该彩条并无状态语义，不保留。

v2局部候选：/Volumes/soft/Claudio/output/design/music-taste-20260914/claudio-music-taste-v2.png。内置 image_gen 定点移除偏好区的圆角容器、描边、独立填充与左侧彩条，改为页面底色上的两行记录与一条弱分隔线。已目视确认上述装饰移除。其余构图沿用；这只是针对具名问题的局部修订，不代表用户已经认可整体设计。提示词见generation-prompt-v2.txt。

v3局部候选：/Volumes/soft/Claudio/output/design/music-taste-20260914/claudio-music-taste-v3.png。用户截图指出v2去掉卡片后仍保留行内容缩进。内置 image_gen 定点去掉这组记录残留的水平内边距；已目视确认两条主文、来源与标题共用左边距，忘记动作及中间分隔线对齐页面右边距。其余设计不重新确认，仍为未批准候选。提示词见generation-prompt-v3.txt。

当前批准覆盖上面的v3候选及无卡片、无彩条、统一左右边距的修正。源码接入现有summary、moods、genres、topArtists与userTaste；不写入图中示例值。画像较长时使用28/38字号/行高并自然延展，短画像36/44；主要流派圆弧与旁侧图例共享排序后的前三项，其余流派继续展示。艺人按真实亲和度排序并全部展示；小宽度和大字体时改为两列或一列。每条偏好仍调用原forget并保留来源、空态、近期信号说明。

验证结果：用户随后要求Release包。已修正GenreProfile嵌套Row中读取maxWidth的Compose作用域编译错误；arm64原生桥release构建、Gradle assembleRelease、目标diff检查均通过。APK v2签名、16KB ZIP对齐、压缩数据完整性和包元数据核验通过。交付版本0.4.4(33)，包名app.pipo.nativeapp，arm64-v8a，包含当前未提交改动。未新增或运行自动化测试、未操作模拟器或真机。待运行核验路径：账号与设置→音乐口味；检查有/无画像、长文案、多流派/艺人、大字体下滚动与对齐，点击一条偏好的“忘记”后重新进入确认删除结果，再返回原页面。

Release APK：/Volumes/soft/Claudio/android-native/release-apks/Claudio-PIPO-v0.4.4-33-release-taste-20260914-123338.apk。
