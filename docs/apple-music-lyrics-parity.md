# Apple Music Lyrics Parity Checklist

This file freezes the local evidence used for the desktop lyrics parity work.
Only values with an Apple source mapping below should survive in the Apple
desktop path.

## Source Bundle

| Apple source | Evidence | Claudio mapping |
| --- | --- | --- |
| `/tmp/applemusic-audit-20260629-104622/index~41cd7adb51.css` | Fullscreen lyrics layout, typography, line transitions, colors, overbleed, blend mode, backdrop positioning | `/Volumes/soft/Claudio/src/components/PlayerCard.tsx` layout tokens, cover/backdrop composition, `lineFrame`, `lineInner`, `companionLyricStyle` |
| `/tmp/applemusic-audit-20260629-104622/index~299c09aac6.js` | Current fullscreen mounts `<amp-lyrics>` with `offset-ratio = .4` (`uBe = .4`); sidebar uses `.15` and mobile uses `.25`. The same bundle exposes `easeInOutQuad` | `APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO = 0.4`; `MeasuredLyricColumn` retains the Apple fullscreen spacer ratio, while the user-required native-style cut uses Claudio's shared retained-velocity spring |
| `/tmp/applemusic-audit-20260629-104622/components/p-fc88e401.entry.js` | Time-synced scroll logic: `currentPlaybackMillis + 250`, `scrollTopMargin = 55`, default `topOffset = 75`, fullscreen `topOffset = hostHeight * offsetRatio`, bottom spacer `hostHeight - 2 * topOffset`, `350ms easeInOutQuad`; `showTranslation` / `showPronunciation` watchers call `scheduleUpdateScroll()` and then `updateScroll({ force: true })`; `handleCurrentPlaybackMillisChange()` calls `updateCurrentIndex()` and, when the playback jump is over `1000ms`, immediately calls another `updateScroll()` without cancelling the first rAF writer | `APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC`, `APPLE_LYRIC_SCROLL_TOP_MARGIN_PX`, `MeasuredLyricColumn` dynamic top/bottom spacers and supplementary force-scroll key retain the Apple target geometry. Desktop motion intentionally replaces overlapping web writers with one native-style spring writer whose velocity survives retargets |
| `/tmp/applemusic-audit-20260629-104622/components/p-7a71c233.entry.js` + `/tmp/applemusic-audit-20260629-104622/index~41cd7adb51.css` | Timed syllable / slow emphasis animation; word groups render as `span.group > div.main + rt.supplementary`; `createSyllable()` renders `content.replace(/[()]/g, "")` into both `data-content` and text while `shouldBeEmphasized()` still checks raw `content.length <= 7`; `.group.show-supplementary:has(.supplementary)` adds `margin-bottom: .4em`; `.main` transitions height/width `0.4s linear`; token `rt.supplementary` default `15px` / `max-height:24px`; fullscreen supplementary CSS variables: `.secondary` uses `line-font-size * .54` at 28px and `*.42` from 38px up; `.static-supplementary` uses `*.64` at 28px and `*.5` from 38px up; `.secondary--background` remains `12px`; `getBackgroundVocals()` renders background words whenever the current line has `bg.words`, then current-line CSS switches `.background-vocals` / `.secondary--background` to `display:block`; collapsible lines animate outer height `0 -> 80px -> auto` / `80px -> 0` over `300ms ease-in-out` and their `.line` transform transitions between `scale(0.1)` and the current-line scale | `YrcActiveLine`, `YrcTokenGroup`, `YrcSlowEmphasisToken`, `appleSyllableContent()`, `appleSlowLetterState`, token lift helpers, `companionLyricStyle()` supplementary font ratios, immediate current-row companion rendering, `InterludeRow` collapsible class/keyframe path |
| `/tmp/applemusic-audit-20260629-104622/components/p-0ac7537e.js` | Supplementary reveal runtime: `Wi.revealTranslations()` / `Wi.revealPronunciations()` use `max-height 0 -> 50px`, opacity `.45` / `1`, and `y -10 -> 0` over `Hi = 600ms`; pronunciation also samples old `.main/.primary-vocals/.secondary` rects before `show-supplementary` | `companionLyricStyle()`, `appleSupplementaryKind()`, `appleSupplementaryRevealState`, verifier supplementary style sampling, `APPLE_SUPPLEMENTARY_FORCE_SCROLL_DELAY_MS = 600 + 150` |
| `/tmp/applemusic-audit-20260629-104622/components/p-115f12df.entry.js` | Outer `amp-lyrics` auto-scroll wrapper: `autoScrolling` defaults true, `scrollBehaviorChange("auto")` keeps it true, wrapper style sets `--inactive-gaussian-blur: 2px`, `.lyrics__lyrics.auto-scrolling` sets `--lyrics-display-synced-line-opacity: 0` | `appleDesktopLyricFrame` sets the same desktop Apple auto-scroll CSS variables so old lines blur and fade through Apple `fade-out` |

Source fingerprints:

| Apple source | Bytes | SHA-256 |
| --- | ---: | --- |
| `index~299c09aac6.js` | 3287362 | `2becd9d8c963068b936a52db330016f02ffffab66815f4e0c51256080b24ce30` |
| `index~41cd7adb51.css` | 898989 | `41cd7adb519f8983486a9357d0215571355df2b1e49194bd1e2e3af7bee0e63b` |
| `p-fc88e401.entry.js` | 17047 | `3830cc3be21c2d03b029f5497074209b90ccf7e67ded552db3d57862835b22f2` |
| `p-7a71c233.entry.js` | 36417 | `1515cf436cbe483feb025d958d3bc5a3e838891ec72c9c10e0102940490af8a9` |
| `p-0ac7537e.js` | 96262 | `a75b97699937d7aa3ec5a038975b77d35d5fd7c8dba4a05a9706151cf59523e1` |
| `p-115f12df.entry.js` | 30706 | `b84d594b397583c5af2aef28f934defa8980a1a95b1ec83b5209606230592fb9` |

## Apple-Evidence Values

| Area | Apple value | Claudio mapping |
| --- | --- | --- |
| Fullscreen grid | `grid-template-columns: 32vw 40vw`, `padding: 0 10vw` | `computeLayout()` desktop: cover column `32vw`, lyrics at `left: 50vw`, `width: 40vw` |
| Wide fullscreen grid | `grid-template-columns: 31vw 38vw`, `padding: 0 12vw` from 1680px; `grid-template-columns: 30vw 38vw`, `padding: 0 13vw` from 2000px; `grid-template-columns: calc(30% + 199.68px) calc(38% + 252.928px)` from 2561px | `.appleLyricsFullscreen` CSS variables switch at `min-width: 1680px`, `min-width: 2000px`, and `min-width: 2561px`; the 2561px rule is converted to content-box viewport math (`22.2vw + 199.68px`, `28.12vw + 252.928px`) so lyrics left remains `100vw - pagePadding - lyricsColumnWidth`, matching Apple grid spacing |
| Lyrics max width | `max-width: 972.8px` | immersive lyrics container `maxWidth: 972.8` |
| Blend mode | `mix-blend-mode: plus-lighter` on Apple fullscreen lyrics column | Desktop lyrics column carries `mixBlendMode: plus-lighter`; the inner text layer remains normal so the verifier can distinguish Apple column blending from child-level glow hacks. The old black column veil is kept as a zero-opacity sentinel only, and must not become a visible card again |
| Background base | Apple bundle exposes `background-color: var(--nowPlayingBackdropBG)`, `background-position: 50%`, `background-size: cover` | Desktop backdrop derives an opaque song-specific base from cover samples, keeps Apple `50% / cover`, overlays a blurred same-cover artwork layer plus a restrained dark veil, and verifies the backdrop is not pure black. This preserves the user's required song-reactive background while avoiding the earlier transparent/noisy lyric column |
| Lyrics mask while scrolling | Default fullscreen `--lyrics-linear-gradient: linear-gradient(180deg,transparent,#000 80px,#000 50%,transparent)`; `.app-container.is-scrolling .lyrics__container` switches it to `linear-gradient(180deg,transparent,#000 80px,#000 calc(100% - 80px),transparent)` | Desktop Apple mode keeps the default mask while idle, then sets `data-apple-lyrics-scrolling="true"` while the shared line-cut spring is active so the viewport uses the Apple scrolling mask throughout motion. The verifier gates the sampled transition frames on `calc(100% - 80px)` and requires the mask to return to the default `50%` stop after the spring settles |
| Artwork shadow | clear artwork `box-shadow: 0 4px 10px rgba(0,0,0,.1)`; radiosity shadow values `0 20px 25px rgba(0,0,0,.1), 0 10px 25px rgba(0,0,0,.1)`; radiosity filter `blur(20px) saturate(2)`, opacity `.4`, scale `.92` | `APPLE_DESKTOP_ARTWORK_SHADOW`, `APPLE_DESKTOP_ARTWORK_RADIOSITY_FILTER`, cover halo uses `drop-shadow()` with the Apple shadow values so the glow follows the masked artwork alpha instead of rendering as a square box |
| Font stack | Apple system stack plus PingFang CJK families | `APPLE_LYRIC_FONT_FAMILY` |
| Font sizes | `28 / 38 / 50 / 72px` | User-requested larger desktop scale: `38 / 48 / 62 / 84px` through `appleLyricMetrics()`; animation structure and breakpoints stay unchanged |
| Line heights | `1.2142857143 / 1.2105263158 / 1.2 / 1.1947644444` | Enlarged scale uses `1.2105263158 / 1.2083333333 / 1.1935483871 / 1.1904761905` through `appleLyricMetrics()` and `appleLineHeightForFontSize()` |
| Line margins | `34 / 42 / 54 / 82px` bottom margin on `.line`; outer `.display-synced-line` keeps `margin: 0` | Denser user-requested spacing uses `26 / 32 / 40 / 58px`; `appleLyricMetrics().marginBottomPx` -> `rowGap` -> `lineInner().marginBottom`, while `lineFrame()` keeps the row margin at `0` |
| Current line | `scale(1.05)`, current padding block default `12px`, transition `100ms` | `APPLE_LYRIC_CURRENT_SCALE`, `APPLE_LYRIC_CURRENT_PADDING_BLOCK_PX`, `lineInner()`; desktop keeps the Apple `100ms` padding/reflow transition, while scale and the primary sung/unsung alpha are sampled from one retained-velocity spatial spring so the three visual cues cannot drift apart |
| Line transition/origin | `render()` emits `ruby.display-synced-line > button.line`; `.display-synced-line` uses `display:block`, `line-height:0`; `.line` uses `display:inline-block`, `color 0.1s`, `transform 0.1s ease-in-out`, `padding 0.1s ease-in-out`, `height 0.4s linear`, `margin-top 0.4s linear`; `.display-synced-line.is-duet .line` falls back to `width: 60%`; secondary vocalist uses `transform-origin: right center` | Desktop Apple rows render through `LyricRowShell` as `ruby.display-synced-line > button.line`; compact/mobile keep the original `div > div`. `lineFrame()` keeps the outer row at Apple `display:block` and `line-height:0`; `lineInner(... alignment, appleIsDuet)` keeps the animated `.line` as Apple `inline-block`. Rows also carry a stable explicit `min-height` base (`lineBox + Apple margin + supplementary gap`, never the active 12px padding). The Apple viewport always applies `max(--apple-lyric-row-min-height, --apple-lyric-row-webkit-min-height)` to non-collapsible synced rows, so Tauri/WebKit or narrow transition frames cannot under-reserve wrapped lyric/rt content. Token-level `rt.supplementary` reserves the Apple `24px` max-height plus its Apple `.2em` top margin only when no whole-line supplementary box is present. Desktop scale and main vocal color no longer run separate CSS tweens: `performAppleScrollTop()` writes shared spatial-focus CSS variables, while padding, height, and margin retain their Apple layout transitions. Global duet mode gives every synced line 60% of the row content box after the 5% overbleed padding while right vocalist rows keep right origin/alignment |
| Row filter layer | `amp-lyrics-display-synced-line { transition: filter 250ms linear }`; inactive rows use `filter: blur(var(--inactive-gaussian-blur, 0))`; `amp-lyrics` auto-scroll wrapper sets `--inactive-gaussian-blur: 2px` | `appleDesktopLyricFrame` sets `--inactive-gaussian-blur: 2px`; `lineFrame()` applies the same inactive-row filter and transition |
| Animating rows | `willAnimate: s===e || s===e-1`, where `s` is Apple `currentIndex` and `e` is the row index; this marks the current row and the next row. `.display-synced-line.is-animating` sets `will-change: transform, opacity, color, top, background-image`; `.line` itself has no `will-change` hint | `useLyricView()` sets `willAnimate` for `safeIdx` and `safeIdx + 1`, and `lineFrame()` places the Apple `will-change` only on the outer row. The previous row still shrinks through the `.line` transform/padding transition after losing current state, but it is not part of Apple's `is-animating` set, and the animated `button.line` keeps computed `will-change:auto` |
| Old-line animation | `amp-lyrics-display-synced-line:has(~[is-current]) { --line-animation-name: fade-out }`; fullscreen `amp-lyrics` sets `--line-animation-play-state: running`; `.line` reads `animation-name: var(--line-animation-name, none)`, `animation-play-state: var(--line-animation-play-state, paused)`, `1s`, `linear`, `forwards`; `.lyrics__lyrics.auto-scrolling` sets `--lyrics-display-synced-line-opacity: 0` | `lineFrame()` writes the local `appleLyricFadeOut` keyframe name into `--line-animation-name` only on rows before the current row, and `lineInner()` reads `animation-name: var(--line-animation-name, none)` exactly like Apple's host-variable path. `appleDesktopLyricFrame` sets the auto-scroll fade target to `0` and `--line-animation-play-state: running`, so previous lines continue fading after the 100/400ms line reflow through Apple-style CSS variables |
| Natural line-switch phase | `updateCurrentIndex()` assigns `currentIndex` and immediately calls `updateScroll()`; `updateScroll()` first verifies the old DOM still has an `is-current` row, then reads the target row from `querySelectorAll(...)[currentIndex]`. Stencil applies the new `is-current` class after that, so scroll leads the `.line` CSS transform/padding/reflow by roughly one frame. `.line` transform/padding itself is still `0.1s ease-in-out` | Desktop Apple mode keeps a logical current index for scroll and a delayed visual current index for row classes. `MeasuredLyricColumn()` captures row anchors before motion, starts the shared spring from the current scroll position and retained velocity, then commits the visual current row on the following frame. Scroll, row scale, and main lyric alpha therefore advance from the same spring position; padding/reflow keeps its Apple `100ms` layout transition |
| Layout transition | `.line` exposes height/margin `400ms linear`, while current-line padding/scale settles over `100ms`; no extra outer-row custom release animation exists in the Apple bundle | `APPLE_LYRIC_LAYOUT_MS`, `lineInner()` and the native row reflow from `.line` padding. The row's spatial scale is driven by the shared spring instead of an independent release animation; the combined visual top still consists of spring scroll motion plus the old current row's native layout release |
| Collapsible / first-line class transition | `render()` always includes `"is-first": 0===lineIndex` on the `ruby.display-synced-line`; `.display-synced-line.collapsible` collapses the outer row to height `0` with `overflow:hidden`; current collapsible rows run `height-expand 300ms ease-in-out`; inactive collapsible rows run `height-collapse 300ms ease-in-out`; `.display-synced-line.is-current.collapsible.is-first` disables the first collapsible expand animation; `.collapsible:not(.is-current) .line` uses `scale(0.1)`, so entering a collapsible row scales `0.1 -> 1.05` and leaving it scales back `1.05 -> 0.1` through the normal `100ms` line transform transition | All desktop Apple rows now carry `is-first` exactly when `rowIndex === 0`, not only for interludes. `InterludeRow` keeps Apple `display-synced-line collapsible` classes and the global `appleLyricHeightExpand` / `appleLyricHeightCollapse` keyframes. The inactive collapsible `.line` is also clipped to zero-height in Claudio so WebKit cannot paint a scaled child out of a collapsed row. `sampleCollapsibleInterludeSwitch()` and the song-level collapsed-row gate verify 0 / 100 / 300 / 350ms expand/collapse frames plus no visible child content after collapse; viewport samples verify one and only one row carries `is-first` |
| Current index | `currentPlaybackMillis + 250`; choose the last line whose `begin <= position <= end`, otherwise fall back to the line before the next `begin` | Desktop Apple mode uses `appleDisplayIndexAt()` over display item `startSec/endSec`; the dev fixture includes `overlap-current` to prove an expired short line does not win over a still-active long line |
| Timed line DOM | time-synced rows keep syllable/group elements and switch state through current classes rather than swapping to a plain text node; `willAnimate` is applied to the current row and next row; `manageAnimations()` only adds timed letters while current and `disconnectedCallback()` destroys animations, so losing current state does not rebuild the row into an unsung line | Desktop Apple YRC rows keep timed token DOM for previous/current/next rows. Adjacent old-current release rows keep the inactive-colored timed component during the 400ms line release, and already-passed inactive rows render at their audio end position so completed syllables stay lifted instead of resetting after the release flag expires |
| Timed animation start | `componentDidLoad()` / `isCurrent` watcher calls `manageAnimations()` only when the synced-line component is current; the line's word animation data uses `data-delay = word.begin - firstWord.begin`, so animation start is tied to the current-line DOM state, not the earlier parent `currentIndex` calculation | Desktop Apple mode creates the local timeline anchor only when the delayed visual current row actually becomes active. During the one-frame scroll lead, the logical target row is scroll target / next row but does not start its token timeline early |
| Timed token layout | `.line` directly contains `.primary-vocals`, `.secondary` / `.static-supplementary`, `.background-vocals`, and `.secondary--background`; `.primary-vocals` / `.background-vocals` contain `.group -> .main -> .syllable`; `createSyllable()` strips `()` from rendered text and `data-content`; word-level pronunciation adds `rt.supplementary` next to `.main`; `.group { display:inline-block; vertical-align:top; transition:height 0.4s linear, margin 0.4s linear }`, `.group.show-supplementary:has(.supplementary) { margin-bottom: .4em }`, `.main { display:inline-block; transition:height 0.4s linear, width 0.4s linear }`, `.supplementary { font-size:15px; line-height:1.2em; max-height:24px }`, `.syllable { margin-top:-5px; padding-top:5px; line-height:normal; white-space:pre }` | Desktop YRC tokens now render direct Apple line children first, then `primary-vocals/background-vocals -> group -> main + rt.supplementary -> syllable` instead of a flat span list or anonymous wrapper, so line switches preserve Apple-like group/main/supplementary layout transitions. `appleSyllableContent()` strips parenthesis characters and `appleVisibleSyllableText()` also removes the separately represented trailing word gap before slow-emphasis glyph splitting. TTML `x-roman` with timed child spans attaches to `YrcChar.supplementaryText`; if it cannot be matched it falls back to the existing full-line romaji companion |
| Trailing whitespace | `.group.trailing-whitespace > *::after` adds `0.3ch` on the inline end instead of making the syllable text carry the visual space; `data-content` and `.letter` contain only the visible word | Desktop ordinary and slow-emphasis syllables remove trailing whitespace from rendered/animated content and put the spacing only on `.main::after` under `.group.trailing-whitespace`; right-aligned vocalist rows mirror the margin to the left. Slow-emphasis stagger therefore divides by Apple-visible `data-content.length`, not by an extra animated space |
| Current lookahead | `currentPlaybackMillis + 250ms` | `APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC`; desktop Apple mode feeds the same lookahead from the component rAF clock (`appleClockPositionSec`) because Claudio's global `positionSec` is intentionally quantized at about 50ms for UI updates. The dev fixture has a `clock=coarse` gate to verify Apple-like line switching still starts from a continuous local playback clock |
| Scroll target | Current fullscreen `<amp-lyrics>` receives `offset-ratio = .4`; `updateScroll()` first guards that an `is-current` row exists, then reads the row at `currentIndex` from the full row list before the new current line has fully reflowed. The target formula is row top minus top spacer minus `55px` margin plus current `scrollTop` | `MeasuredLyricColumn()` renders a dynamic top spacer at `viewportHeight * .4`, bottom spacer at `viewportHeight - 2 * topOffset`, does not scroll before a visual current row exists, and desktop Apple mode scrolls to the logical current row by `data-apple-lyric-row-index`, not by the delayed visual `data-active` row. The scroll target follows Apple exactly: `targetRowTop - topSpacerHeight - 55 + scrollTop`. No extra previous-line padding compensation is applied; the previous row's 400ms height release naturally pulls the final current-line top to `topSpacerHeight + 55 - releaseDelta` |
| Scroll animation | Apple Web uses `350ms easeInOutQuad`; current-index changes call `updateScroll()` once, while the delayed forced scroll is wired to translation/pronunciation visibility changes. The native Apple Music-style behavior requested for Claudio is a velocity-preserving spring rather than stacked web tweens | `performAppleScrollTop()` owns one rAF writer and one physical state: stiffness `140`, damping `24`, position threshold `.5px`, velocity threshold `5px/s`. A retarget cancels the old writer but retains its current velocity, so rapid cuts remain continuous without competing `scrollTop` writes. The same spring position drives scroll, spatial row scale, and lyric alpha; supplementary metadata can still schedule one delayed live-layout force scroll |
| Secondary/static supplementary | Fullscreen `.secondary` uses `line-font-size * .54` at the 28px viewport and `*.42` from 38px up; `.static-supplementary` uses `*.64` at 28px and `*.5` from 38px up; `.secondary--background` uses `12px`; reveal: `margin-top: .2em`, `text-wrap: balance`, `max-height: 50px`; `Wi` reveal tween uses `max-height 0 -> 50px`, opacity `.45` / `1`, `y -10 -> 0` over `.6s` | `companionLyricStyle()` derives translation/romaji font sizes from the active Apple line font, keeps the Apple reveal height/opacity/transform contract, and tags `secondary`, `static-supplementary`, and `secondary secondary--background` for verification. Claudio keeps the Apple 50px reveal as an upper bound, but clips final always-visible supplementary text to whole line-height boxes within that bound so long translations cannot leave half-line remnants that visually stack into the next lyric. When supplementary metadata appears after initial render, a new supplementary key renders hidden first (`max-height:0`, `opacity:0`, `y:-10`) before the 600ms reveal; this prevents the row from first mounting at full height and visually stacking into neighboring lyrics. Desktop rows add `50px + Apple .2em margin-top` per visible supplementary row as `padding-bottom` on `ruby.display-synced-line`; inactive supplementary rows also clip overflow at the row box so WebKit cannot paint long translations into neighboring lyrics. Apple `.line` margin / transition values and the `ruby > button.line` DOM remain unchanged |
| Background vocals | `.background-vocals` / `.secondary--background` default to `display:none`; current line switches both to `display:block`. `.background-vocals` uses `14px`, `margin-top: 20px`; background translation uses `12px`, `margin-top: 0`, opacity `.45`; fullscreen bg vocal gradient alpha is `.35/.175`; Apple renders the background vocal container for the current line immediately, not only after the background words' own begin time; `getBackgroundVocals()` and `createSyllable()` remove parenthesis characters from background-vocal rendered content | `YrcActiveLine(appleVocalClassName="background-vocals")` renders timed companion vocals directly as the Apple `.background-vocals` box with the bg vocal `.35/.175` token colors; `shouldRenderCompanionInActiveRow()` now returns true for role `companion`, so the current row has the Apple background-vocal box from the first line-switch frame while token progress still follows each background word time; `companionLyricStyle(role="background-translation")` keeps the background translation as `.secondary--background`; verifier keeps parenthesized raw fixture tokens but rejects rendered `(` / `)` |
| Ordinary syllable | delay `+0.1s`, y `0 -> -2`, gradient `-20 -> 100`, linear | `regularTokenLiftProgress()`, `YrcActiveLine()` |
| Slow emphasis | duration `>=1000ms`, raw visible-word `content.length <= 7` for eligibility; rendered `data-content` excludes parenthesis and trailing spacing; scale `1 -> 1.05 -> 1`, y `0 -> -2.05 -> -2`, blur/opacity source values | `shouldUseSlowEmphasis()` checks raw word length without the separately represented trailing gap. `appleSlowLetterState()` keeps the overlapping per-letter lift/glow motion, but desktop color uses `appleSlowLetterColorProgress()` as one word-wide linear front: completed letters are fully sung, the next letters remain unsung, and at most one visible glyph can hold a partial gradient at any instant |
| Syllable edge | margin `-0.5px -0.75px`, padding `0.5px 0.75px`, clip `inset(0.5px 0.75px 0.5px 0.75px)` | `appleCurrentSyllableEdge` |

## Current Classification

Apple-evidence implementation:
- Desktop lyrics layout, max width, blend mode, base background, font/line metrics, current line scale/padding, fullscreen `offset-ratio=.4` top spacer, visible translation/romaji/static supplementary rows with Apple `.secondary` / `.static-supplementary` semantics, token-level `rt.supplementary` word-group romanization, background vocal translation role, ordinary syllable lift, slow emphasis, CJK weight.
- Desktop ordinary syllable lift uses `regularTokenLiftProgress()` directly.
  The old `regularLyricLiftProgress()` continuity carry / `WORD_FLOAT_EASE_BLEND`
  path is kept only for compact/mobile rendering and must not be reintroduced
  into `desktopAppleMotion`.

Apple runtime-composition / user-required implementation:
- The desktop backdrop is an opaque, song-derived Apple color field from `appleBackdropBaseRgb()` with `background-image: none`, so it is neither transparent nor locked to black. The same-cover blurred artwork node stays mounted only to preserve the transition structure and is disabled at steady state (`APPLE_DESKTOP_BACKDROP_ARTWORK_OPACITY = 0`).
- The desktop veil is transparent and the masked cover halo uses Apple radiosity opacity (`APPLE_DESKTOP_COVER_HALO_OPACITY = 0.4`), keeping a restrained edge glow without turning the full screen into a blurred poster wallpaper.
- The lyric column and its sentinel veil remain transparent. The verifier rejects a visible black card while still sampling screenshot pixels to ensure the background is controlled and not visually noisy behind lyrics.
- Because Claudio keeps translation/romaji visible on every row, rows with always-visible supplementary text add `50px + Apple .2em margin-top` per visible supplementary row as row `padding-bottom`, matching the Apple supplementary reveal box plus its visible spacing. Supplementary text itself is capped to complete line-height increments no taller than Apple `50px`, and inactive supplementary rows clip overflow inside their row box, preventing WebKit overpaint from reading as overlapping lyrics. This is a user-required readability guard; verifier still requires Apple `.line` margin, transition values, and the `ruby > button.line` child structure to remain unchanged.
- WKWebView can undercount native `ruby/rt` content height even when Chrome reports correct row geometry. Claudio now sets per-row base and WebKit guard variables; every Apple lyrics viewport applies `max(--apple-lyric-row-min-height, --apple-lyric-row-webkit-min-height)` for non-collapsible synced rows. The guard estimates primary lyric wrapping from the actual Apple `.line` width (`100%`, or `60%` in duet mode) and includes current-line padding only while the row is current. Leaving rows must release through the Apple `.line` padding transition so the old-current row contributes the visible cut-line residual instead of being pinned by a non-Apple outer guard.
- Because Claudio's desktop app can run over transparent Tauri/WebKit surfaces, the full-screen lyrics root keeps an opaque song-derived backdrop. The Apple `plus-lighter` blend is applied at the lyrics column, and the child text layer remains normal; the verifier samples both computed blend layers and screenshot pixels.
- Desktop `ImmersiveLyrics` opening/closing keeps the Apple lyrics column at its final fullscreen grid rect and fades it by opacity only. The cover still uses FLIP. This prevents the full-size Apple lyric stack from being temporarily squeezed into the compact lyric button rect, which caused opening-frame text overlap and made the background look transparent through blend-mode text.
- Desktop Apple lyrics disables browser `overflow-anchor` on the scroll viewport. This is a runtime guard, not an Apple CSS constant: Claudio's shared spring owns the whole `scrollTop` write plus spatial scale/alpha focus, and browser scroll anchoring otherwise mutates `scrollTop` when supplementary rows/reflow change height.
- Native-style line cuts use one retained-velocity spring (`stiffness=140`, `damping=24`) for scroll, scale, and main lyric alpha. This is an intentional native-app parity override of Apple Web's fixed-duration scroll tween; it prevents a new line from snapping visually current while the viewport is still catching up.
- Slow-word color uses one word-wide sweep front. Letter lift and glow can overlap to keep the fluid emphasis, but multiple letters may not be partially color-filled at the same time.
- Poster dissolve mask is retained for the user-required "poster dissolve" behavior. The clear artwork shadow and `0.4` radiosity halo opacity are Apple CSS evidence; the extra cover mask itself is not claimed as a fixed Apple fullscreen CSS constant. Verification gates that the cover settles against the opaque Apple color field without reintroducing unsupported black overlay gradients or a full-screen artwork layer.
- Cover halo uses the Apple radiosity shadow numbers via `drop-shadow()` rather than `box-shadow`; this keeps the same blur/shadow constants while avoiding the WebKit artifact where a square artwork layer leaves a visible rectangular glow block.

Removed / should not return:
- Independent elastic residual hooks, per-row springs, and overlapping `scrollTop` writers. The allowed spring is the single shared physical state in `performAppleScrollTop()`; scale and lyric alpha must derive from it instead of starting their own motion clocks.
- Desktop Apple lyrics must not use the compact/mobile `useCompactSpringNumber` path.
- Extra desktop black directional overlay gradients on top of the backdrop. No matching Apple fullscreen lyrics CSS evidence was found.

## Dev Verification Fixture

Local dev-only route:
- `/dev/apple-lyrics`
- Query songs: `song=mixed`, `song=pure-yrc`, `song=translated-romaji`, `song=long-supplementary`, `song=crowded-lines`, `song=companion-duet`
- Query mode: default fullscreen fixture; `mode=transition` renders the real
  `ImmersiveLyrics` opening/closing fixture with compact source rects.
- Component loader: `src/app/dev/apple-lyrics/AppleLyricsVerificationScene.tsx`
- Real fixture: `src/app/dev/apple-lyrics/AppleLyricsVerificationScene.fixture.tsx`
- Production stub: `src/app/dev/apple-lyrics/AppleLyricsVerificationScene.stub.tsx`
- Production behavior: `src/app/dev/apple-lyrics/page.tsx` only dynamically
  imports the dev loader inside the `NODE_ENV !== "production"` branch. The
  production branch calls `notFound()`, so the exported route is a 404 payload
  without dev loader or fixture markers in the JS chunks.

Fixture coverage:
- `mixed`: layout, scroll, current line, translation, romaji, companion/background vocal, right-aligned duet, English slow word, Chinese slow word, short word with trailing space, ordinary fast word.
- `pure-yrc`: pure YRC timed words without supplementary rows.
- `translated-romaji`: primary lyric + translation + romaji.
- `long-supplementary` / `crowded-lines`: always-visible translation and romaji rows in narrow windows, including text longer than the Apple 50px supplementary reveal box.
- `companion-duet`: background vocal + background translation + right-aligned duet.
- `interlude-gap`: Apple collapsible instrumental/interlude row between two timed lyric rows.

Gate commands:
- `pnpm typecheck` / `npm run typecheck`
- `pnpm build`
- `git diff --check`
- `node scripts/verify-apple-lyrics.mjs`
- `pnpm build:tauri` / `npm run build:tauri`
- `cargo check --manifest-path src-tauri/Cargo.toml`
- `pnpm-workspace.yaml` explicitly approves the existing `sharp` build script so
  the pnpm gates do not fail before reaching TypeScript/Next.

Browser verification:
- Uses local Chrome DevTools Protocol directly; no Playwright dependency is required.
- Starts a temporary Next dev server and headless Chrome. The verifier picks
  open ports by default and only uses fixed ports when
  `APPLE_LYRICS_NEXT_PORT` / `APPLE_LYRICS_CHROME_PORT` are set, so it does not
  accidentally verify an unrelated dev server.
- Captures 1180 / 1440 / 1728 / 2560 screenshots to `/tmp`.
- Captures one additional screenshot for each song category: pure YRC,
  translated/romaji, companion/duet.
- Captures scroll-transition frame screenshots at 0 / 50 / 100 / 175 / 350 / 400ms
  as `apple-lyrics-scroll-*.png`.
- Captures collapsible interlude switch samples at 0 / 100 / 300 / 350ms for both
  expand and collapse. The gate checks Apple `height-expand` / `height-collapse`
  animation names, `300ms ease-in-out` duration, and the line scale path
  `0.1 -> 1.05 -> 0.1`; song-level scenarios also fail if a collapsed row keeps
  a non-zero child line rect or visible text.
- Samples token animation frames at fixed fixture positions:
  - ordinary syllable: before lift, mid lift, finished
  - slow emphasis: before, first-letter rise midpoint, peak, release midpoint,
    first-letter complete while the final visible glyph starts; trailing space
    must remain outside the `.letter` list
  - CJK slow emphasis: Chinese active row font weight, single-glyph slow word
    scale/y/gradient/shadow curve
  - Slow glow peak screenshots:
    `apple-lyrics-token-slow-peak.png`,
    `apple-lyrics-token-cjk-peak.png`
- Captures real `ImmersiveLyrics` transition screenshots:
  `apple-lyrics-transition-opening-120ms.png`,
  `apple-lyrics-transition-open-800ms.png`,
  `apple-lyrics-transition-closing-140ms.png`,
  `apple-lyrics-transition-closed-860ms.png`.
- Asserts computed background, absence of extra backdrop gradients, dark backdrop veil, capped artwork/halo opacity, poster dissolve
  mask/halo presence, layout variables, lyrics column position, blend mode,
  typography, current scale/padding, auto-scroll inactive blur/fade target, scroll viewport `overflow-anchor:none`, translation, companion vocal, right-aligned duet,
  duet row right-side transform origin,
  Apple `.secondary` / `.static-supplementary` / `.secondary--background`
  visible-state markers, whole-line capped supplementary overflow within the
  Apple 50px reveal ceiling, `max-height 0.6s` + `transform 0.6s`
  reveal transition terminal state,
  ordinary token delay/y/gradient, slow-emphasis scale/y/gradient/shadow/stagger,
  CJK active weight/slow-emphasis curve, background translation `12px` / `0px`
  margin / `.45` opacity, glow layer without background/foreground
  shadow boxes, slow/CJK peak screenshot corner pixels without a rectangular
  glow block, ordinary token `group trailing-whitespace` spacing, background
  vocal rendered syllables with Apple parenthesis stripping, raw
  `content.length` slow-emphasis eligibility, stripped `data-content.length`
  stagger, 0 / 50 / 100 / 175 / 350 / 400ms
  `scrollTop` samples, scripted and natural rAF playback line-switch phase gates with scroll leading line transition, `transitionrun`-clock current-line 50ms in-flight and 100ms settled
  scale/padding, a 10.5s continuous rAF playback window that spans
  `You see through -> That I just wanna get with you -> You right I -> Got my guy`,
  including the Apple pre-current frame where the scroll target advances one
  row before visual `is-current` catches up,
  adjacent and non-adjacent playback jumps over 1000ms both starting the Apple
  extra `updateScroll()` writer, transition opening frames keeping the lyrics
  column at the final Apple grid rect with a transparent backing and no visible
  text overlap,
  current row height equal to Apple `.line` layout plus the
  `50px * visible supplementary count` row padding when that row has visible
  secondary/static supplementary text, and the Apple `24px` token-level
  `rt.supplementary` reserve when no whole-line supplementary box is present,
  absence of
  unsupported custom outer release rows, old-current timed syllable state
  preservation during release and after the release flag expires, and the actual
  opening/open/closing/closed `ImmersiveLyrics` phases.
- Latest passing browser artifact:
  `/var/folders/gv/y_6ml9zd1fgdpt1yggmb04140000gn/T/claudio-apple-lyrics-verify-1782703936146`
- Latest desktop backdrop sample in `report.json`:
  - 1180 / 1440 / 1728 / 2560 / 3000px widths all compute backdrop artwork
    opacity `0`, fullscreen veil color `rgb(0, 0, 0)`, lyric-column layer
    background `rgb(0, 0, 0)`, unblended column backing `normal`, column
    isolation `isolate`, lyric text layer `plus-lighter`, and an unblended
    column veil that covers both column edges. Busy covers do not show through
    the lyric column.
- Latest real transition sample in `report.json`:
  - `apple-lyrics-transition-opening-120ms.png` now samples the lyric column at
    `left=590px`, `width=472px`, `height=820px` while the cover is still mid
    FLIP. The opening frame computes backdrop color `rgb(0,0,0)`, column
    backing `rgb(0,0,0)`, column blend `normal`, text blend `plus-lighter`, and
    no visible text overlap. Pixel probes at the column left veil, right edge,
    and lower edge are all `rgb(0,0,0)` with luminance `0`.
  - `apple-lyrics-transition-open-800ms.png` repeats the same opaque pixel
    probes after the cover lands, proving the entry animation does not expose
    cover texture behind lyrics.
- Current line-transition implementation contract:
  - `.line` keeps `padding 0.1s ease-in-out, height 0.4s linear, margin-top 0.4s linear`; scale and main vocal alpha are written from the shared spatial spring and therefore have no independent `transform` or `color` tween.
  - Timed syllables retain the stable fixed-gradient/CSS-variable paint layer. Slow-word color progresses through one word-wide front while its lift/glow timing remains per letter.
  - Outer row computes to `block` with `line-height: 0px` in the local
    `ruby` wrapper guard, matching Apple evidence; animated `.line` remains `inline-block`,
    matching Apple `display-synced-line` / `button.line` row geometry while
    preventing collapsed row boxes in the desktop shell.
  - The `.line` direct-child gate now samples `primary-vocals`, `secondary`,
    `static-supplementary`, `background-vocals`, and `secondary--background`
    classes directly under the button, so the line switch reflow is no longer
    hidden behind a custom content wrapper.
  - The row class gate now verifies Apple `is-first` semantics: exactly the row
    whose `lineIndex` / `data-apple-lyric-row-index` is `0` carries
    `is-first`; non-first rows do not.
  - The `will-change` gate now verifies Apple placement: current and next
    `ruby.display-synced-line.is-animating` rows compute to
    `transform, opacity, color, top, background-image`; previous/future rows
    and every `button.line` compute to `auto`.
  - The play-state source gate verifies fullscreen
    `--line-animation-play-state: running`, while current/previous
    `button.line` elements compute `animation-play-state: running` from inline
    `var(--line-animation-play-state, paused)`.
  - The old-line fade source gate verifies previous rows carry
    `--line-animation-name: appleLyricFadeOut`, while current/next rows keep the
    variable empty and every `button.line` reads inline
    `animation-name: var(--line-animation-name, none)`.
  - The verifier now records both React line-switch timestamps and the browser
    `transitionrun` timestamp from the actual `.line` CSS transition. Latest
    adjacent switch: 50ms CSS-clock frame reports current scale `1.03706`,
    padding `8.89px`, previous release `17.81px`; 100ms CSS-clock frame
    reports current scale `1.05`, padding `12px`, previous release `22.34px`.
  - Ordinary line switch, natural playback switch, and non-adjacent seek all
    report `active index == scroll target index` on every sampled frame. This
    keeps Apple `currentIndex + is-current` as the only scroll target and
    prevents applying the 250ms lookahead again through an independent scroll
    marker.
  - Duet current row: `text-align: right`, content-box raw `.line` width
    `328.31px` against expected `328.32px` after the Apple 5% overbleed
    padding, `transform-origin: 328.312px 46.1719px`, with active
    `scale(1.05)`.
  - Auto-scroll wrapper variables: `--inactive-gaussian-blur: 2px`,
    `--lyrics-display-synced-line-opacity: 0`; previous/next inactive rows
    compute to `filter: blur(2px)`. The scroll viewport computes
    `overflow-anchor: none` at 1180 / 1440 / 1728 / 2560 / 3000px so browser
    scroll anchoring cannot mutate Apple's own `scrollTop` curve during line
    reflow.
  - Previous-row fade-out now has a direct Apple keyframe gate: opacity follows
    the 1s linear `fade-out` curve toward `--lyrics-display-synced-line-opacity: 0`.
    Latest ordinary switch samples: 0ms line-clock `3.4ms / opacity 0.972795`,
    100ms line-clock `126.4ms / opacity 0.847295`, 400ms line-clock
    `411.7ms / opacity 0.561695`.
  - Row blur now has a direct Apple `filter 250ms linear` gate. Latest ordinary
    switch samples: 0ms line-clock `8.9ms` current/previous blur
    `1.78587 / 0.214128`, 100ms line-clock `110.8ms` blur
    `0.905072 / 1.09493`, 175ms line-clock `188.9ms` blur
    `0.281872 / 1.71813`, and 350ms reaches `0 / 2`.
    Natural playback can sample a pre-transition 0ms frame, so the blur gate
    anchors to the first in-flight filter sample before checking the Apple
    250ms linear curve.
  - Combined top motion now has a direct gate for ordinary, natural,
    background-vocal, and non-adjacent cut lines. For each sampled frame the
    expected current-line top is computed from first-frame top minus
    `scrollTop` delta minus old-current release delta; this guards the
    Apple-like stretch/reflow component separately from pure scroll.
  - Visible text-center motion now has the same direct gate for ordinary,
    natural/coarse playback, background-vocal, and non-adjacent cut lines.
    It computes the expected visible text center from scroll delta,
    old-current release delta, and the active `.line` internal text offset,
    so a line cannot pass by scrolling the container while the text itself
    drifts or snaps. Latest ordinary center samples:
    `581.22 -> 542.94 -> 507.91 -> 436.91 -> 387.91 -> 387.91`;
    latest natural samples: `585.94 -> 576.89 -> 544.01 -> 477.67 -> 387.12`.
  - Adjacent sibling visible-text motion is now gated separately. The previous
    visible text center must follow scroll plus its own current-line
    padding/scale release; the next visible text center must follow scroll
    plus old-current release plus new-current expansion. Latest ordinary
    previous samples: `380.82 -> 343.19 -> 308.59 -> 237.59 -> 188.59`;
    latest ordinary next samples: `771.55 -> 743.55 -> 709.59 -> 638.59 -> 589.59`.
  - Current/previous `.line` scale now has a direct Apple `0.1s ease-in-out`
    gate in ordinary, natural, coarse-clock, background-vocal, and
    non-adjacent cut-line samples. The gate starts at the first real
    in-flight transform frame so coarse playback samples cannot mistake the
    pre-transition frame for an easing failure.
- Latest glow pixel samples in `report.json`:
  - English slow peak: average corner delta `1.60`, bright corners `0`.
  - CJK slow peak: average corner delta `18.94`, bright corners `1`.
- Latest companion/background samples in `report.json`:
  - Companion vocal: font `14px`, margin-top `20px`.
  - Timed companion vocal is the direct `.background-vocals` element: `data-companion-role=companion` and `data-apple-vocals=background-vocals` share the same top and height.
  - Translation: `appleKind=secondary`, fullscreen ratio fonts `15.12px`
    at 1180 / `15.96px` at 1440 / `21px` at 1728 /
    `30.24px` at 2560+, `overflow=hidden`,
    `transform=matrix(..., 0, 0)`, transition includes `max-height 0.6s`
    and `transform 0.6s`.
  - Romaji: `appleKind=static-supplementary`, fullscreen ratio font `19px`
    on the 1440 translated-romaji fixture, `overflow=hidden`, same reveal
    terminal transform.
  - Background translation: `appleKind=secondary secondary--background`, font `12px`, margin-top `0px`, opacity `0.45`, `overflow=hidden`.
  - Background-vocal cut-line sample (`You right I -> Got my guy`) verifies
    that `.background-vocals` and `.secondary--background` exist from the
    first scroll frame rather than waiting for the companion word begin. Latest
    run: CSS transition phase `9.7ms`, target row index `3`, frame 0 already
    has `background-vocals` `14px / 20px / block`, background translation
    `12px / 0px / 0.45`, and row supplementary safety gap `50px`; 50ms reports
    scale `1.03062`, padding `7.3497px`, previous release `14.719px`; 100ms
    reports scale `1.05`, padding `12px`, previous release `24.000px`.
    All sampled 0 / 50 / 100 / 175 / 350 / 400ms frames pass visible text
    overlap and readable-gap gates.
- Latest timed-token structure sample:
  - Active synced row is `RUBY.display-synced-line.is-current` with inner `BUTTON.line`; current and next rows carry `is-animating`, matching Apple `willAnimate: s===e || s===e-1`.
  - Active ordinary token keeps raw `data-yrc-token="That "`, but rendered syllable text is `That`; parent group is `group trailing-whitespace` with zero group margin, and `.main::after` supplies the Apple `0.3ch` visual space. This mirrors Apple spacing without letting the trailing space ride inside the animated syllable glyph.
  - The translated-romaji fixture now verifies Apple word-level pronunciation:
    token supplementary is `RT.supplementary` with text `ki mi`,
    `font-size: 15px`, `max-height: 24px`, and transition
    `width/height/margin-top 0.4s linear`; its parent is
    `SPAN.group.show-supplementary.trailing-whitespace` with
    `margin-bottom: 15.2px` at the 38px viewport (`.4em`), and `.main`
    remains a `DIV.main` with the Apple height/width transition.
  - The token-supplementary-only fixture verifies the WebKit/Tauri row-height
    guard: no active translation/romaji line is present, token `RT.supplementary`
    still reports `max-height: 24px`, the active row carries
    `data-apple-lyric-supplementary-safety-gap="24"`, row padding-bottom is
    `24px`, and the primary vocals apply Apple's `-0.5em` squeeze when there is
    no `.secondary.is-visible`.
- Latest crowded-lines readability sample:
  - `apple-lyrics-song-crowded-lines.png` keeps long translation/romaji clipped
    to whole-line boxes inside the Apple 50px reveal ceiling, and the active row
    with both static supplementary lines reports
    `data-apple-lyric-supplementary-safety-gap="107"`, row
    `padding-bottom: 107px`, Apple base min-height var
    `calc(34px * 1 + 34px + 107px)`, and computed mac/Tauri row
    `min-height: 233px` from guard
    `calc(34px * 2 + 34px + 107px + 24px)`. The base deliberately excludes the
    active 12px `.line` padding so it cannot add a second, non-Apple outer snap
    during line switches; the computed guard gives WKWebView enough space when
    it undercounts ruby/rt contents. `line margin-bottom` still equals the Apple
    margin.
  - `apple-lyrics-song-crowded-lines-720.png` covers the narrow desktop edge
    where the lyrics column is only `288px`; the same fixture reports
    translation `max-height: 36.288px` and romaji `max-height: 43.008px`, both
    complete line-height multiples under the Apple 50px cap, plus the same
    `107px` supplementary safety gap and transparent lyrics-column backing.
  - Latest visible text rects keep the active long line bottom at `521.73px`
    and the next line top at `670.56px`, so translation/romaji no longer
    visually stack into the following lyric.
  - `apple-lyrics-supplementary-force-scroll.png` starts with supplementary rows hidden, reveals translation/romaji, then verifies the Apple watcher equivalent: hidden frame reports translation `max-height:0px`, `opacity:0`, `y:-10`; 300ms reports in-flight translation `29.1124px` / `0.361017` / `y:-1.97741`; 620ms reports translation `36.288px` / `.45` / `y:0` and romaji `43.008px` / `1`; 750ms verifies the revealed state is still visible. The delayed force scroll then reports `force=true`, source `live-layout`, duration `350ms`, fullscreen offset ratio `.4`, top margin `55px`, target row index `1`, and no visible text overlap.
- Latest 2560px layout sample in `report.json`:
  - CSS vars: page padding `13vw`, cover column `30vw`, lyrics column `38vw`.
  - Lyrics column left `1254.39px` (`49vw`), width `972.80px`, font `72px`,
    margin-bottom `82px`.
- Latest 3000px layout sample in `report.json`:
  - CSS vars: page padding `13vw`, cover column `calc(22.2vw + 199.68px)`,
    lyrics column `calc(28.12vw + 252.928px)`.
  - Expected lyrics column left is `1513.472px`; browser sample reports
    `1513.46875px`. Used width remains capped at `972.80px` by Apple
    `max-width: 972.8px`.
- Latest switch-frame samples in `report.json`:
  - Scroll timeline samples now track viewport `scrollTop`; after removing the unsupported previous-padding compensation, the active line follows Apple raw scroll target plus the previous row's release delta.
  - Scroll mask samples now match Apple fullscreen scrolling state: before switch
    the viewport mask contains the default `50%` stop; 0 / 50 / 100 / 125 /
    175 / 250ms scroll frames carry `data-apple-lyrics-scrolling="true"` and
    compute `#000 calc(100% - 80px)`; 350 / 400 / 425ms and the final sample
    return to `data-apple-lyrics-scrolling="false"` and the default `50%` stop.
  - CSS line timeline 0ms: scale `1.00072`, padding-top `.17px`, previous
    height `187.97px`.
  - Scroll-clock 50ms: scale `1.03706`, padding-top `8.89px`, previous
    row release `17.81px`, residual `-17.44px`; verifier accepts this as an
    early two-clock frame because Apple calls `updateScroll()` before Stencil
    has finished applying the new current-line CSS state.
  - Scroll-clock 100ms: scale `1.05`, padding-top `12px`, previous row
    release `22.34px`, residual `-21.97px`.
  - Scroll-clock 125ms keeps scale/padding settled at `1.05` / `12px`, while
    the scroll tween and row-filter transition continue.
  - CSS line timeline 100ms: scale `1.05`, padding-top `12px`, previous height
    `166px`, residual `-21.97px`; the residual matches the previous row's
    release delta.
  - CSS line timeline 175ms / 350ms / 400ms / 425ms keep that `-21.97px` residual,
    proving the visible line displacement is Apple scrollTop easing plus
    row-release reflow rather than plain scrolling.
  - CSS line timeline 425ms: scale `1.05`, padding-top `12px`, previous height
    `166px`, previous opacity remains in the Apple auto-scroll fade-out window
    toward `0`.
  - Row-filter gate now samples Apple `filter 250ms linear`: at 0ms
    current/previous/next blur is `1.931 / 0.069 / 2`; at 50ms
    `1.482 / 0.518 / 2`; at 100ms `0.908 / 1.092 / 2`; at 175ms
    `0.491 / 1.509 / 2`; at 250ms current reaches `0` and previous/next are
    `2`, remaining stable through 350 / 400 / 425ms.
  - Playback jumps now mirror Apple `handleCurrentPlaybackMillisChange()` by
    raw playback delta over `1000ms`, not by lyric row distance. Adjacent seek
    `6.70s -> 7.76s` moves only row `0 -> 1` but records serial `3` at
    `805.7ms` and serial `4` at `805.8ms`; both use source
    `previous-layout`, target row index `1`, target `223.344px`, and
    `activeCountAtStart` `0 -> 1`. Non-adjacent seek `8.20s -> 25.20s`
    records serial `3` at `808.2ms` and serial `4` at `808.3ms`; both target
    row index `5`, target `854.625px`, with `activeCountAtStart` `0 -> 1`.
    This proves the extra Apple seek scroll is keyed to the `>1000ms`
    playback jump and is not collapsed into a single tween.
  - Narrow 1180px switch samples now run through the same gate and capture separate screenshots:
    `apple-lyrics-scroll-1180-000ms.png` through `apple-lyrics-scroll-1180-425ms.png`,
    including the `050ms` half-stretch frame plus the newer `125ms`, `250ms`,
    and `425ms` timing gates.
    At 1180px, the dynamic top spacer is `262.39px`, exactly `656px * .4`, the
    Apple top margin is `55px`, and the final active top is derived from
    `topSpacerHeight + 55 - previousRowReleaseDelta`.
  - Wide 1728px and 2560px dynamic switch samples now run through the same
    non-screenshot gate. The latest 1728px sample reports `scrollTop`
    `48 -> 76 -> 111 -> 267`, previous-row release
    `0 -> 20.33 -> 22.73px`, and active residual
    `0 -> -20.33 -> -22.73px`. The latest 2560px sample reports previous-row
    release `0 -> 20.30 -> 23.52px` and matching active residual, proving the
    large-font line cut is not pinned by the WebKit row-height guard.
  - Natural rAF playback switch now carries the same residual gate and CSS phase gate: latest CSS line transition starts `34.9ms` after scroll and finishes at the same `321px` active top.
  - Coarse-clock natural playback also keeps the Apple phase: latest CSS line transition starts `19.9ms` after scroll and finishes at the same `321px` active top.
  - Seek and adjacent playback-jump samples use the same Apple
    previous-layout residual model as natural adjacent switches. At 1440px, the
    top spacer is `288px`, offset ratio `.4`, top margin `55px`, latest CSS
    line transition starts within the same one-to-three-frame window after
    scroll, and the old-active release contributes about `24px`, so the
    visible active top is derived from scrollTop plus row release instead of
    the old fixed `130px` top anchor.
  - Continuous rAF playback sample runs 85 frames at 125ms intervals over
    10.5s. It records natural switches at `500ms`, `5375ms`, and `9375ms`,
    all with source `previous-layout` and an active scroll writer. The sampled
    pre-current frames at `375ms`, `5375ms`, and `9375ms` allow scroll target
    to point one row ahead while visual current remains on the old row, which
    matches Apple `updateScroll()` running before the new `.is-current` CSS
    state lands. The same run captures `apple-lyrics-continuous-playback.png`
    and verifies lyric-column left/right/lower pixels are `rgb(0,0,0)`.
  - Rapid switch uses one retained-velocity writer: both the second and third
    retarget report `activeCountAtStart: 1`, start from the current in-flight
    `scrollTop`, preserve velocity, and invalidate the previous rAF serial so
    stale completion cannot poison the React-side previous-layout cache.
  - Old-current release token `You ` stays timed DOM (`kind=ordinary`) at 0 / 100 / 175 / 350 / 400ms, with `y=-2`, no gradient, and visible text fill following `color` rather than a fixed `-webkit-text-fill-color`: latest alpha samples are `0.816 -> 0.300 -> 0.250 -> 0.250`. The post-release sample also keeps the same completed lift after the local release flag expires. This is the Apple `manageAnimations`/`disconnectedCallback` behavior: losing current state does not rebuild the row into static or unsung text during release.

Desktop/static verification:
- `node scripts/build-tauri.mjs` builds the same static `out/` directory used by
  Tauri packaging.
- The build script uses the local `node_modules/.bin/next` binary explicitly so
  it works when Tauri runs it without a shell PATH containing local binaries.
- Static export keeps `/dev/apple-lyrics` as a 404 page. `out/dev/apple-lyrics.html`
  and `.txt` are expected to exist, but `scripts/build-tauri.mjs` requires them
  to contain the Next `NEXT_HTTP_ERROR_FALLBACK;404` payload and no fixture
  markers such as `data-apple-lyrics-fixture`.
- `scripts/build-tauri.mjs` also scans the exported production files and fails if
  dev fixture markers such as `Apple Lyrics Fixture` or
  `__setAppleLyricsFixturePosition` / `__openAppleLyricsTransitionFixture`
  appear in `out/`. The scan also rejects `AppleLyricsVerificationScene` and
  `[apple-lyrics-verify]` so a 404 route cannot silently ship the dev loader
  chunk.
- `next.config.js` maps `@apple-lyrics-fixture` to the real fixture in dev and
  to the stub in production. TypeScript uses
  `src/types/apple-lyrics-fixture.d.ts` for that virtual module so production
  webpack resolution can make the final choice.
- `src-tauri/tauri.conf.json` uses `pnpm dev` and `pnpm build:tauri` for the
  desktop shell. `pnpm-workspace.yaml` approves the existing `sharp` build
  script so these commands reach the actual Next/Tauri work instead of failing
  at dependency script approval.
- Dedicated `./node_modules/.bin/tauri dev --config '{"build":{"devUrl":"http://localhost:4321/dev/apple-lyrics?..."} }'`
  launches opened the source-tree debug process `target/debug/pipo` and Next
  logged 200 responses for the three fixture songs required by the final gate:
  - `/tmp/claudio-tauri-apple-lyrics-desktop-retry-20260626160350/tauri-pure-yrc.log`:
    `GET /dev/apple-lyrics?playing=0&position=8.2&song=pure-yrc 200`.
  - `/tmp/claudio-tauri-apple-lyrics-desktop-final-20260626160732/tauri-translated-romaji.log`:
    `GET /dev/apple-lyrics?playing=0&position=8.2&song=translated-romaji 200`.
  - `/tmp/claudio-tauri-apple-lyrics-desktop-final-20260626160732/tauri-companion-duet.log`:
    `GET /dev/apple-lyrics?playing=0&position=5.45&song=companion-duet 200`.
  The logs contain `Running target/debug/pipo`; no `error`, `failed`, or
  `panic` markers were found in the inspected lines.
- A debug/env-gated desktop probe was then run inside the real Tauri WebView
  (`PIPO_APPLE_LYRICS_DESKTOP_PROBE=1`). It writes only in debug builds and only
  when `PIPO_APPLE_LYRICS_DESKTOP_PROBE_OUT` is set; release builds compile a
  disabled stub. Latest probe directory:
  `/tmp/claudio-tauri-apple-lyrics-desktop-probe-20260626162201`.
  Scripted assertions passed with no failures:
  - `pure-yrc`: active text `Hold on slowly now`, lyrics column
    `mix-blend-mode: plus-lighter`, column left `590px` at the real
    `1180x820` desktop window.
  - `translated-romaji`: active text `That I just wanna stay`, translation
    opacity `0.45`, margin-top `3.015625px` (`.2em` at the sampled font).
  - `companion-duet`: active text `Got my guy`, companion vocal `14px` /
    `20px`, background translation `12px` / `0px` / `0.45`.
  - All three WebView samples reported the older Apple sampled backdrop color
    `rgb(135, 135, 135)` before the current opaque-black readability pass;
    the current verifier now requires `rgb(0, 0, 0)` plus backdrop position
    `50% 50%`, backdrop size `cover`, and current-line
    transition containing `height 0.4s linear`.
- A clean mixed-song desktop motion probe also passed:
  `/tmp/claudio-tauri-apple-lyrics-desktop-motion-20260626171914`.
  It runs in the same Tauri/WKWebView debug shell and samples the real DOM after
  programmatic fixture time jumps:
  - English slow word `wanna ` first letter transform:
    `matrix(1.05, 0, 0, 1.05, 0, -2.05)`.
  - CJK slow word `慢` first letter transform:
    `matrix(1.05, 0, 0, 1.05, 0, -2.05)`.
  - The same sample keeps the desktop lyrics column on
    `mix-blend-mode: plus-lighter`.
  Line-switch timing remains gated by the browser/CDP verifier above because
  long multi-seek eval sequences inside WKWebView are not stable enough for
  frame-accurate assertions.
- Automated real-window screenshots were not retained because this macOS
  session denies screen capture. `screencapture` returned
  `could not create image from display`, consistent with the earlier
  ScreenCaptureKit TCC denial. Do not treat the installed
  `/Applications/Pipo.app` screenshot as verification of this source tree.
