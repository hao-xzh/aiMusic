#!/usr/bin/env node

import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { inflateSync } from "node:zlib";

const cwd = process.cwd();
const nextPort = process.env.APPLE_LYRICS_NEXT_PORT
  ? Number(process.env.APPLE_LYRICS_NEXT_PORT)
  : await findOpenPort(4321);
const chromePort = process.env.APPLE_LYRICS_CHROME_PORT
  ? Number(process.env.APPLE_LYRICS_CHROME_PORT)
  : await findOpenPort(9333);
const baseUrl = `http://127.0.0.1:${nextPort}`;
const defaultSongId = "mixed";
const fixtureUrl = fixtureUrlFor(defaultSongId, 8.2);
const chromePath =
  process.env.CHROME_BIN ??
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const outDir = process.env.APPLE_LYRICS_VERIFY_OUT ??
  join(tmpdir(), `claudio-apple-lyrics-verify-${Date.now()}`);

const viewportSpecs = [
  { width: 1180, height: 820, font: 38, lineHeight: 1.2105263158, lineBox: 46, margin: 26, pagePaddingCss: "10vw", coverColumnCss: "32vw", columnCss: "40vw", pagePaddingPx: 118, columnLeftPx: 590, columnTrackPx: 472 },
  { width: 1440, height: 900, font: 48, lineHeight: 1.2083333333, lineBox: 58, margin: 32, pagePaddingCss: "10vw", coverColumnCss: "32vw", columnCss: "40vw", pagePaddingPx: 144, columnLeftPx: 720, columnTrackPx: 576 },
  { width: 1728, height: 1000, font: 62, lineHeight: 1.1935483871, lineBox: 74, margin: 40, pagePaddingCss: "12vw", coverColumnCss: "31vw", columnCss: "38vw", pagePaddingPx: 207.36, columnLeftPx: 864, columnTrackPx: 656.64 },
  { width: 2560, height: 1280, font: 84, lineHeight: 1.1904761905, lineBox: 100, margin: 58, pagePaddingCss: "13vw", coverColumnCss: "30vw", columnCss: "38vw", pagePaddingPx: 332.8, columnLeftPx: 1254.4, columnTrackPx: 972.8 },
  { width: 3000, height: 1400, font: 84, lineHeight: 1.1904761905, lineBox: 100, margin: 58, pagePaddingCss: "13vw", coverColumnCss: "calc(22.2vw + 199.68px)", columnCss: "calc(28.12vw + 252.928px)", pagePaddingPx: 390, columnLeftPx: 1513.472, columnTrackPx: 1096.528 },
];
const APPLE_LYRIC_TOP_OFFSET_PX = 75;
const APPLE_LYRIC_SCROLL_TOP_MARGIN_PX = 55;
const APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO = 0.4;
const APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC = 0.25;
const APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX = 50;
const APPLE_LYRIC_TOKEN_SUPPLEMENTARY_BOX_PX = 24;
const APPLE_LYRIC_SUPPLEMENTARY_MARGIN_TOP_EM = 0.2;
const APPLE_LYRIC_TOKEN_SUPPLEMENTARY_FONT_PX = 15;
const APPLE_BG_VOCAL_GRADIENT_ACTIVE_ALPHA = "0.35";
const APPLE_BG_VOCAL_GRADIENT_UNSUNG_ALPHA = "0.175";
const APPLE_BG_VOCAL_GRADIENT_UNSUNG_ALPHA_RENDERED = "0.176";

const failures = [];
const warnings = [];
const report = {
  generatedAt: new Date().toISOString(),
  fixtureUrl,
  outDir,
  viewportSamples: [],
  roleSamples: {},
  songSamples: {},
  scrollSamples: [],
  narrowScrollSamples: null,
  wideScrollSamples: [],
  tokenFrameSamples: [],
  cjkTokenFrameSamples: [],
  heldClockSample: null,
  supplementaryForceScrollSample: null,
  collapsibleSwitchSample: null,
  continuousPlaybackSample: null,
  pixelSamples: [],
  failures,
  warnings,
};

let nextProc;
let chromeProc;
let chromeUserDataDir;

async function main() {
try {
  await mkdir(outDir, { recursive: true });
  nextProc = await startNextDev();
  chromeUserDataDir = await mkdtemp(join(tmpdir(), "claudio-apple-lyrics-chrome-"));
  chromeProc = await startChrome(chromeUserDataDir);
  const tab = await createChromeTab("about:blank");
  const cdp = await CdpClient.connect(tab.webSocketDebuggerUrl);
  try {
    await cdp.send("Page.enable");
    await cdp.send("Runtime.enable");
    await cdp.send("DOM.enable");
    await cdp.send("Log.enable").catch(() => {});

    for (const spec of viewportSpecs) {
      await setViewport(cdp, spec.width, spec.height);
      await navigate(cdp, fixtureUrl);
      await waitForFixture(cdp);
      await hideNextDevOverlay(cdp);
      await settle(cdp, 520);
      const sample = await sampleStyles(cdp);
      report.viewportSamples.push({ spec, sample });
      assertViewportSample(spec, sample);
      const screenshot = await cdp.send("Page.captureScreenshot", {
        format: "png",
        captureBeyondViewport: false,
      });
      const screenshotBuffer = Buffer.from(screenshot.data, "base64");
      await writeFile(join(outDir, `apple-lyrics-${spec.width}.png`), screenshotBuffer);
      assertScreenshotHasOpaqueLyricBackground(`viewport ${spec.width}`, screenshotBuffer, sample);
    }

    await setViewport(cdp, 1440, 900);
    await navigate(cdp, fixtureUrl);
    await waitForFixture(cdp);
    await hideNextDevOverlay(cdp);
    await settle(cdp, 120);
    report.roleSamples.translationAndSlowWord = await sampleAtPosition(cdp, 8.2, 520);
    assertTranslationAndTokenSample(report.roleSamples.translationAndSlowWord);
    report.roleSamples.companion = await sampleAtPosition(cdp, 17.45, 520);
    assertCompanionSample(report.roleSamples.companion);
    report.roleSamples.duet = await sampleAtPosition(cdp, 21.55, 520);
    assertDuetSample(report.roleSamples.duet);
    report.tokenFrameSamples = await sampleTokenAnimationFrames(cdp);
    assertTokenAnimationFrames(report.tokenFrameSamples);
    report.cjkTokenFrameSamples = await sampleCjkTokenAnimationFrames(cdp);
    assertCjkTokenAnimationFrames(report.cjkTokenFrameSamples);
    report.heldClockSample = await sampleHeldClockSmoothing(cdp);
    assertHeldClockSmoothing(report.heldClockSample);
    report.scrollSamples = await sampleScrollTransition(cdp);
    assertScrollSamples(report.scrollSamples);
    await setViewport(cdp, 1180, 820);
    report.narrowScrollSamples = await sampleScrollTransition(cdp, {
      screenshotPrefix: "apple-lyrics-scroll-1180",
    });
    assertScrollSamples(report.narrowScrollSamples);
    for (const spec of viewportSpecs.filter((entry) => entry.width === 1728 || entry.width === 2560)) {
      await setViewport(cdp, spec.width, spec.height);
      const sample = await sampleScrollTransition(cdp, {
        captureScreenshots: false,
      });
      report.wideScrollSamples.push({ spec, sample });
      assertScrollSamples(sample, { labelPrefix: `${spec.width} dynamic ` });
    }
    await setViewport(cdp, 1440, 900);
    report.naturalPlaybackSwitchSample = await sampleNaturalPlaybackLineSwitch(cdp);
    assertNaturalPlaybackLineSwitch(report.naturalPlaybackSwitchSample);
    report.coarsePlaybackSwitchSample = await sampleNaturalPlaybackLineSwitch(cdp, {
      clock: "coarse",
    });
    assertNaturalPlaybackLineSwitch(report.coarsePlaybackSwitchSample, {
      labelPrefix: "coarse natural switch",
      fixturePositionMin: 7.68,
    });
    report.backgroundVocalSwitchSample = await sampleBackgroundVocalLineSwitch(cdp);
    assertBackgroundVocalLineSwitch(report.backgroundVocalSwitchSample);
    report.continuousPlaybackSample = await sampleContinuousPlaybackWindow(cdp);
    assertContinuousPlaybackWindow(report.continuousPlaybackSample);
    report.rapidScrollOverlapSample = await sampleRapidScrollOverlap(cdp);
    assertRapidScrollOverlapSample(report.rapidScrollOverlapSample);
    report.collapsibleSwitchSample = await sampleCollapsibleInterludeSwitch(cdp);
    assertCollapsibleInterludeSwitch(report.collapsibleSwitchSample);
    report.adjacentSeekJumpSample = await sampleAdjacentSeekJump(cdp);
    assertAdjacentSeekJumpSample(report.adjacentSeekJumpSample);
    report.nonAdjacentSeekSample = await sampleNonAdjacentSeek(cdp);
    assertNonAdjacentSeekSample(report.nonAdjacentSeekSample);

    report.songSamples.pureYrc = await verifySongScenario(cdp, {
      id: "pure-yrc",
      positionSec: 9.3,
      screenshotName: "apple-lyrics-song-pure-yrc.png",
      assert(sample) {
        assertEqual("pure-yrc fixture song id", sample.fixtureSongId, "pure-yrc");
        assertIncludes("pure-yrc active text", sample.active.text ?? "", "Hold on slowly now");
        assertEqual("pure-yrc companion role count", sample.companionRoles.length, 0);
        assertTruthy("pure-yrc ordinary token present", sample.ordinaryToken.text);
        assertTruthy("pure-yrc slow token present", sample.slowToken.text);
        assertIncludes("pure-yrc slow token", sample.slowToken.text ?? "", "slowly");
        assertNotEqual("pure-yrc slow letter moving", sample.slowToken.firstLetterStyle.transform, "none");
      },
    });
    report.songSamples.tokenSupplementary = await verifySongScenario(cdp, {
      id: "token-supplementary",
      positionSec: 9.48,
      screenshotName: "apple-lyrics-song-token-supplementary.png",
      assert(sample) {
        assertEqual("token-supplementary fixture song id", sample.fixtureSongId, "token-supplementary");
        assertIncludes("token-supplementary active text", sample.active.text ?? "", "Kimi no soba");
        assertEqual("token-supplementary no active translation", sample.translation, null);
        assertEqual("token-supplementary no active romaji line", sample.romaji, null);
        assertEqual("token-supplementary token supplementary tag", sample.tokenSupplementary?.tagName, "RT");
        const tokenSupplementaryGap = appleTokenSupplementarySafetyGapPx();
        assertEqual("token-supplementary active supplementary safety gap", sample.active.supplementarySafetyGap, String(tokenSupplementaryGap));
        assertEqual("token-supplementary Apple line box model", sample.active.innerStyle.boxSizing, "content-box");
        assertEqual("token-supplementary row keeps Apple shell padding", cssPx(sample.active.style.paddingBottom), 0);
        assertApprox(
          "token-supplementary primary squeeze without secondary",
          cssPx(sample.primaryVocals?.marginBottom),
          -sample.numeric.activeFontSize * 0.5,
          0.25,
        );
        assertNoVisibleTextOverlap("token-supplementary visual text stack", sample.rowStack);
        assertVisibleTextGap("token-supplementary readable text gap", sample.rowStack, 39);
      },
    });
    report.songSamples.translatedRomaji = await verifySongScenario(cdp, {
      id: "translated-romaji",
      positionSec: 13.45,
      screenshotName: "apple-lyrics-song-translated-romaji.png",
      assert(sample) {
        assertEqual("translated-romaji fixture song id", sample.fixtureSongId, "translated-romaji");
        assertIncludes("translated-romaji active text", sample.active.text ?? "", "Kimi no soba");
        assertTruthy("translated-romaji romaji visible", sample.companionRoles.includes("romaji"));
        assertTruthy("translated-romaji translation visible", sample.companionRoles.includes("translation"));
        assertTruthy("translated-romaji active romaji element", sample.romaji);
        assertEqual("translated-romaji Apple kind", sample.romaji?.appleKind, "static-supplementary");
        assertEqual("translated-romaji Apple visible state", sample.romaji?.appleVisible, "true");
        assertApprox(
          "translated-romaji static supplementary font ratio",
          cssPx(sample.romaji?.fontSize),
          appleStaticSupplementaryFontPx(sample.numeric.activeFontSize),
          0.05,
        );
        assertApprox(
          "translated-romaji whole-line capped reveal height",
          cssPx(sample.romaji?.maxHeight),
          appleSupplementaryVisibleBoxPx("romaji", sample.numeric.activeFontSize),
          0.05,
        );
        assertEqual("translated-romaji stays clipped inside Apple reveal box", sample.romaji?.overflow, "hidden");
        assertIncludes("translated-romaji reveal transition max-height", sample.romaji?.transition ?? "", "max-height 0.6s");
        assertIncludes("translated-romaji reveal transition transform", sample.romaji?.transition ?? "", "transform 0.6s");
        assertApprox("translated-romaji reveal final y", transformTranslateY(sample.romaji?.transform), 0, 0.1);
        assertEqual("translated-romaji token supplementary tag", sample.tokenSupplementary?.tagName, "RT");
        assertEqual("translated-romaji token supplementary class", sample.tokenSupplementary?.className, "supplementary");
        assertIncludes("translated-romaji token supplementary text", sample.tokenSupplementary?.text ?? "", "ki mi");
        assertEqual("translated-romaji token group supplementary marker", sample.tokenSupplementaryGroup?.showSupplementary, "true");
        assertIncludes("translated-romaji token group show class", sample.tokenSupplementaryGroup?.className ?? "", "show-supplementary");
        assertApprox(
          "translated-romaji token group Apple supplementary margin",
          cssPx(sample.tokenSupplementaryGroup?.style?.marginBottom),
          sample.numeric.activeFontSize * 0.4,
          0.2,
        );
        assertEqual("translated-romaji token supplementary max-height", sample.tokenSupplementary?.style?.maxHeight, "24px");
        assertEqual("translated-romaji token supplementary font size", sample.tokenSupplementary?.style?.fontSize, "15px");
        assertIncludes(
          "translated-romaji token supplementary transition",
          sample.tokenSupplementary?.style?.transition ?? "",
          "height 0.4s linear",
        );
        assertApprox(
          "translated-romaji primary squeeze without secondary",
          cssPx(sample.primaryVocals?.marginBottom),
          -sample.numeric.activeFontSize * 0.5,
          0.25,
        );
      },
    });
    report.songSamples.longSupplementary = await verifySongScenario(cdp, {
      id: "long-supplementary",
      positionSec: 9.42,
      screenshotName: "apple-lyrics-song-long-supplementary.png",
      assert(sample) {
        assertEqual("long-supplementary fixture song id", sample.fixtureSongId, "long-supplementary");
        assertIncludes("long-supplementary active text", sample.active.text ?? "", "Nothing stays quiet");
        assertTruthy("long-supplementary translation visible", sample.companionRoles.includes("translation"));
        assertTruthy("long-supplementary romaji visible", sample.companionRoles.includes("romaji"));
        assertNoVisibleTextOverlap("long-supplementary visual text stack", sample.rowStack);
        assertVisibleTextGap("long-supplementary readable text gap", sample.rowStack, 24);
      },
    });
    await setViewport(cdp, 860, 820);
    report.songSamples.crowdedLines = await verifySongScenario(cdp, {
      id: "crowded-lines",
      positionSec: 9.45,
      screenshotName: "apple-lyrics-song-crowded-lines.png",
      assert(sample) {
        assertEqual("crowded-lines fixture song id", sample.fixtureSongId, "crowded-lines");
        assertIncludes("crowded-lines active text", sample.active.text ?? "", "That I just wanna get with you");
        assertTruthy("crowded-lines translation visible", sample.companionRoles.includes("translation"));
        assertTruthy("crowded-lines romaji visible", sample.companionRoles.includes("romaji"));
        const expectedGap = appleSupplementarySafetyGapPx(["translation", "romaji"], sample.numeric.activeFontSize);
        assertEqual("crowded-lines active supplementary safety gap", sample.active.supplementarySafetyGap, String(expectedGap));
        assertEqual("crowded-lines row keeps Apple shell padding", cssPx(sample.active.style.paddingBottom), 0);
        assertEqual("crowded-lines Apple line box model", sample.active.innerStyle.boxSizing, "content-box");
        assertNoVisibleTextOverlap("crowded-lines visual text stack", sample.rowStack);
        assertVisibleTextGap("crowded-lines readable text gap", sample.rowStack, 24);
      },
    });
    await setViewport(cdp, 720, 820);
    report.songSamples.crowdedLinesNarrow = await verifySongScenario(cdp, {
      id: "crowded-lines",
      positionSec: 9.45,
      screenshotName: "apple-lyrics-song-crowded-lines-720.png",
      assert(sample) {
        assertEqual("crowded-lines-720 fixture song id", sample.fixtureSongId, "crowded-lines");
        assertIncludes("crowded-lines-720 active text", sample.active.text ?? "", "That I just wanna get with you");
        assertTruthy("crowded-lines-720 translation visible", sample.companionRoles.includes("translation"));
        assertTruthy("crowded-lines-720 romaji visible", sample.companionRoles.includes("romaji"));
        const expectedGap = appleSupplementarySafetyGapPx(["translation", "romaji"], sample.numeric.activeFontSize);
        assertEqual("crowded-lines-720 active supplementary safety gap", sample.active.supplementarySafetyGap, String(expectedGap));
        assertEqual("crowded-lines-720 row keeps Apple shell padding", cssPx(sample.active.style.paddingBottom), 0);
        assertEqual("crowded-lines-720 Apple line box model", sample.active.innerStyle.boxSizing, "content-box");
        assertNoVisibleTextOverlap("crowded-lines-720 visual text stack", sample.rowStack);
        assertVisibleTextGap("crowded-lines-720 readable text gap", sample.rowStack, 24);
      },
    });
    report.supplementaryForceScrollSample = await sampleSupplementaryForceScroll(cdp);
    assertSupplementaryForceScroll(report.supplementaryForceScrollSample);
    await setViewport(cdp, 1440, 900);
    report.songSamples.companionDuet = await verifySongScenario(cdp, {
      id: "companion-duet",
      positionSec: 5.45,
      screenshotName: "apple-lyrics-song-companion-duet.png",
      assert(sample) {
        assertEqual("companion-duet fixture song id", sample.fixtureSongId, "companion-duet");
        assertIncludes("companion-duet active text", sample.active.text ?? "", "Got my guy");
        assertTruthy("companion-duet companion visible", sample.companionRoles.includes("companion"));
        assertTruthy("companion-duet translation visible", sample.companionRoles.includes("translation"));
        assertEqual("companion-duet companion font size", sample.companion.fontSize, "14px");
        assertApprox(
          "companion-duet primary line uses Apple duet width",
          transformedRawWidth(sample.active.innerStyle),
          expectedAppleDuetLineWidth(sample),
          2,
        );
      },
    });
    const duetSongSample = await sampleAtPosition(cdp, 9.55, 520);
    report.songSamples.companionDuet.duet = duetSongSample.duet;
    assertIncludes("companion-duet duet text", duetSongSample.active.text ?? "", "But I, I");
    assertEqual("companion-duet duet right align", duetSongSample.active.innerStyle.textAlign, "right");
    report.songSamples.overlapCurrent = await verifySongScenario(cdp, {
      id: "overlap-current",
      positionSec: 7.0,
      screenshotName: "apple-lyrics-song-overlap-current.png",
      assert(sample) {
        assertEqual("overlap-current fixture song id", sample.fixtureSongId, "overlap-current");
        assertIncludes("overlap-current begin/end active text", sample.active.text ?? "", "Long hold");
        assertEqual("overlap-current begin/end active index", sample.activeIndex, 0);
      },
    });
    report.songSamples.interludeActive = await verifySongScenario(cdp, {
      id: "interlude-gap",
      positionSec: 12.4,
      screenshotName: "apple-lyrics-song-interlude-active.png",
      assert(sample) {
        assertEqual("interlude-gap active fixture song id", sample.fixtureSongId, "interlude-gap");
        const interlude = findRow(sample.rowStack, "interlude");
        assertTruthy("interlude active row present", interlude);
        assertEqual("interlude active row state", interlude?.active, true);
        assertBetween("interlude active height expands", cssPx(interlude?.rowStyle?.height), 1, Number.POSITIVE_INFINITY);
        assertEqual("interlude active overflow", interlude?.rowStyle?.overflow, "visible");
        assertEqual("interlude active height animation", interlude?.rowStyle?.animationName, "appleLyricHeightExpand");
        assertTransformScale("interlude active line scale", interlude?.lineStyle?.transform, 1.05, 0.01);
      },
    });
    report.songSamples.interludeCollapsed = await verifySongScenario(cdp, {
      id: "interlude-gap",
      positionSec: 20.4,
      screenshotName: "apple-lyrics-song-interlude-collapsed.png",
      assert(sample) {
        assertEqual("interlude-gap collapsed fixture song id", sample.fixtureSongId, "interlude-gap");
        assertIncludes("interlude-gap next lyric active", sample.active.text ?? "", "After gap");
        const interlude = findRow(sample.rowStack, "interlude");
        assertTruthy("interlude collapsed row present", interlude);
        assertEqual("interlude collapsed row inactive", interlude?.active, false);
        assertApprox("interlude collapsed height", cssPx(interlude?.rowStyle?.height), 0, 0.2);
        assertEqual("interlude collapsed overflow", interlude?.rowStyle?.overflow, "hidden");
        assertEqual("interlude collapsed height animation", interlude?.rowStyle?.animationName, "appleLyricHeightCollapse");
        assertTransformScale("interlude collapsed line scale", interlude?.lineStyle?.transform, 0.1, 0.01);
      },
    });
    report.transitionSample = await verifyTransitionScenario(cdp);
    report.narrowTransitionSample = await verifyNarrowTransitionScenario(cdp);
  } finally {
    await cdp.close();
  }

  report.browserErrors = collectBrowserErrors(cdp);
  for (const error of report.browserErrors) {
    failures.push(`browser error: ${error}`);
  }
  await writeFile(join(outDir, "report.json"), `${JSON.stringify(report, null, 2)}\n`);
  if (failures.length > 0) {
    console.error(`Apple lyrics verification failed. Artifacts: ${outDir}`);
    for (const failure of failures) console.error(`- ${failure}`);
    process.exitCode = 1;
  } else {
    console.log(`Apple lyrics verification passed. Artifacts: ${outDir}`);
    if (warnings.length > 0) {
      for (const warning of warnings) console.warn(`warning: ${warning}`);
    }
  }
} finally {
  if (chromeProc) killProcess(chromeProc);
  if (nextProc) killProcess(nextProc);
  if (chromeUserDataDir) {
    await rm(chromeUserDataDir, { recursive: true, force: true }).catch(() => {});
  }
}
}

function fixtureUrlFor(songId, positionSec, playing = 0, options = {}) {
  const params = new URLSearchParams({
    playing: String(playing),
    position: String(positionSec),
    song: songId,
  });
  for (const [key, value] of Object.entries(options)) {
    if (value != null) params.set(key, String(value));
  }
  return `${baseUrl}/dev/apple-lyrics?${params.toString()}`;
}

async function startNextDev() {
  const proc = spawn(join(cwd, "node_modules/.bin/next"), ["dev", "-p", String(nextPort)], {
    cwd,
    env: { ...process.env, NEXT_TELEMETRY_DISABLED: "1" },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const logs = [];
  proc.stdout.on("data", (chunk) => logs.push(String(chunk)));
  proc.stderr.on("data", (chunk) => logs.push(String(chunk)));
  await waitForHttp(fixtureUrl, 45000, async (response) => response.status === 200);
  proc._logs = logs;
  return proc;
}

async function startChrome(userDataDir) {
  const proc = spawn(chromePath, [
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-background-networking",
    `--remote-debugging-port=${chromePort}`,
    `--user-data-dir=${userDataDir}`,
    "about:blank",
  ], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  await waitForHttp(`http://127.0.0.1:${chromePort}/json/version`, 20000, async (response) => response.status === 200);
  return proc;
}

async function createChromeTab(url) {
  const response = await fetch(`http://127.0.0.1:${chromePort}/json/new?${encodeURIComponent(url)}`, {
    method: "PUT",
  });
  if (!response.ok) throw new Error(`Unable to create Chrome tab: ${response.status}`);
  return response.json();
}

async function waitForHttp(url, timeoutMs, predicate) {
  const startedAt = Date.now();
  let lastError;
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(url, { cache: "no-store" });
      if (await predicate(response)) return response;
    } catch (error) {
      lastError = error;
    }
    await delay(250);
  }
  throw new Error(`Timed out waiting for ${url}${lastError ? ` (${lastError.message})` : ""}`);
}

function findOpenPort(preferredPort) {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.once("error", (error) => {
      if (error.code !== "EADDRINUSE") {
        reject(error);
        return;
      }
      const fallback = createServer();
      fallback.unref();
      fallback.once("error", reject);
      fallback.listen(0, "127.0.0.1", () => {
        const address = fallback.address();
        const port = typeof address === "object" && address ? address.port : preferredPort;
        fallback.close(() => resolve(port));
      });
    });
    server.listen(preferredPort, "127.0.0.1", () => {
      server.close(() => resolve(preferredPort));
    });
  });
}

async function setViewport(cdp, width, height) {
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await cdp.send("Emulation.setVisibleSize", { width, height }).catch(() => {});
}

async function navigate(cdp, url) {
  const loaded = cdp.waitForEvent("Page.loadEventFired", 15000);
  await cdp.send("Page.navigate", { url });
  await loaded.catch(() => {});
}

async function waitForFixture(cdp) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < 15000) {
    const exists = await evaluate(cdp, "Boolean(document.querySelector('[data-apple-lyrics-fixture]'))");
    if (exists) return;
    await delay(100);
  }
  throw new Error("Fixture did not render");
}

async function waitForTransitionFixture(cdp) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < 15000) {
    const exists = await evaluate(cdp, "Boolean(document.querySelector('[data-apple-lyrics-transition-fixture]'))");
    if (exists) return;
    await delay(100);
  }
  throw new Error("Transition fixture did not render");
}

async function hideNextDevOverlay(cdp) {
  await evaluate(cdp, `(() => {
    if (document.getElementById('apple-lyrics-hide-next-devtools')) return;
    const style = document.createElement('style');
    style.id = 'apple-lyrics-hide-next-devtools';
    style.textContent = 'nextjs-portal { display: none !important; }';
    document.documentElement.appendChild(style);
  })()`);
}

async function settle(cdp, ms) {
  await evaluate(cdp, `new Promise((resolve) => setTimeout(resolve, ${ms}))`, true);
}

async function sampleAtPosition(cdp, positionSec, settleMs = 120) {
  await evaluate(cdp, `window.__setAppleLyricsFixturePosition?.(${JSON.stringify(positionSec)})`);
  await settle(cdp, settleMs);
  return sampleStyles(cdp);
}

async function verifySongScenario(cdp, scenario) {
  await navigate(cdp, fixtureUrlFor(scenario.id, scenario.positionSec));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 620);
  const sample = await sampleStyles(cdp);
  scenario.assert(sample);
  assertAppleRowsUseBlock(`${scenario.id} row block display`, sample.rowStack);
  assertCollapsedRowsClipVisibleContent(`${scenario.id} collapsed row clipping`, sample.rowStack);
  assertNoVisibleTextOverlap(`${scenario.id} visual text stack`, sample.rowStack);
  const screenshot = await cdp.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  await writeFile(join(outDir, scenario.screenshotName), Buffer.from(screenshot.data, "base64"));
  return sample;
}

async function verifyTransitionScenario(cdp) {
  const url = `${fixtureUrlFor(defaultSongId, 8.2)}&mode=transition`;
  await setViewport(cdp, 1180, 820);
  await navigate(cdp, url);
  await waitForTransitionFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 300);
  const before = await sampleStyles(cdp);
  assertTruthy("transition fixture rendered", before.transitionRoot);
  assertTruthy("transition source cover", before.transitionSourceCover?.rect);
  assertTruthy("transition source lyric", before.transitionSourceLyric?.rect);

  await evaluate(cdp, "window.__openAppleLyricsTransitionFixture?.()");
  await settle(cdp, 120);
  const opening = await sampleStyles(cdp);
  const openingPng = await captureNamedScreenshot(cdp, "apple-lyrics-transition-opening-120ms.png");
  assertTruthy("transition opening backdrop", opening.backdrop);
  assertTruthy("transition opening cover", opening.cover);
  assertNotEqual("transition opening backdrop color follows artwork", opening.backdrop?.backgroundColor, "rgb(0, 0, 0)");
  assertEqual("transition opening backdrop opacity", opening.backdrop?.opacity, "1");
  assertEqual("transition opening backdrop uses opaque Apple color field", opening.backdrop?.backgroundImage, "none");
  assertIncludes("transition opening backdrop artwork image", opening.backdropArtwork?.backgroundImage ?? "", "url(");
  assertEqual("transition opening column transparent backing", opening.column?.backgroundColor, "rgba(0, 0, 0, 0)");
  assertEqual("transition opening column uses plus-lighter", opening.column?.mixBlendMode, "plus-lighter");
  assertEqual("transition opening text blend layer stays normal", opening.lyricsBlendLayer?.mixBlendMode, "normal");
  assertApprox("transition opening column left", opening.numeric.columnLeft, 590, 1.5);
  assertNoVisibleTextOverlap("transition opening visual text stack", opening.rowStack);
  assertVisibleTextGap("transition opening readable text gap", opening.rowStack, 24);
  assertScreenshotHasOpaqueLyricBackground("transition opening", openingPng, opening);

  await settle(cdp, 680);
  const opened = await sampleStyles(cdp);
  const openedPng = await captureNamedScreenshot(cdp, "apple-lyrics-transition-open-800ms.png");
  assertEqual("transition opened root", opened.transitionRoot?.dataOpen, "1");
  assertNotEqual("transition opened backdrop color follows artwork", opened.backdrop?.backgroundColor, "rgb(0, 0, 0)");
  assertEqual("transition opened backdrop uses opaque Apple color field", opened.backdrop?.backgroundImage, "none");
  assertEqual("transition opened column uses plus-lighter", opened.column?.mixBlendMode, "plus-lighter");
  assertEqual("transition opened text blend layer stays normal", opened.lyricsBlendLayer?.mixBlendMode, "normal");
  assertIncludes("transition opened active lyric", opened.active.text ?? "", "That I just wanna get with you");
  assertApprox("transition opened column left", opened.numeric.columnLeft, 590, 1.5);
  assertIncludes("transition opened cover mask", opened.cover?.maskImage, "linear-gradient");
  assertNoVisibleTextOverlap("transition opened visual text stack", opened.rowStack);
  assertScreenshotHasOpaqueLyricBackground("transition opened", openedPng, opened);

  await evaluate(cdp, "window.__closeAppleLyricsTransitionFixture?.()");
  await settle(cdp, 140);
  const closing = await sampleStyles(cdp);
  await captureNamedScreenshot(cdp, "apple-lyrics-transition-closing-140ms.png");
  assertTruthy("transition closing cover still animating", closing.cover);

  await settle(cdp, 720);
  const closed = await sampleStyles(cdp);
  await captureNamedScreenshot(cdp, "apple-lyrics-transition-closed-860ms.png");
  assertEqual("transition closed root", closed.transitionRoot?.dataOpen, "0");
  assertEqual("transition closed immersive unmounted", closed.backdrop, null);

  return {
    before: summarizeTransitionSample(before),
    opening: summarizeTransitionSample(opening),
    opened: summarizeTransitionSample(opened),
    closing: summarizeTransitionSample(closing),
    closed: summarizeTransitionSample(closed),
  };
}

async function verifyNarrowTransitionScenario(cdp) {
  const url = `${fixtureUrlFor(defaultSongId, 8.2)}&mode=transition`;
  await setViewport(cdp, 860, 820);
  await navigate(cdp, url);
  await waitForTransitionFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 260);

  await evaluate(cdp, "window.__openAppleLyricsTransitionFixture?.()");
  await settle(cdp, 820);
  const opened = await sampleStyles(cdp);
  await captureNamedScreenshot(cdp, "apple-lyrics-transition-narrow-open.png");
  assertEqual("narrow transition opened root", opened.transitionRoot?.dataOpen, "1");
  assertTruthy("narrow transition keeps desktop column", opened.column);
  assertEqual("narrow transition column uses plus-lighter", opened.column?.mixBlendMode, "plus-lighter");
  assertEqual("narrow transition text blend layer stays normal", opened.lyricsBlendLayer?.mixBlendMode, "normal");
  assertTruthy("narrow transition keeps Apple scroll viewport", opened.viewportBox);
  assertIncludes("narrow transition active lyric", opened.active.text ?? "", "That I just wanna get with you");
  assertNoVisibleTextOverlap("narrow transition visual text stack", opened.rowStack);
  assertVisibleTextGap("narrow transition readable text gap", opened.rowStack, 24);

  return {
    viewport: opened.viewport,
    active: opened.active.text,
    column: opened.column,
    viewportBox: opened.viewportBox,
    rows: opened.rowStack
      .filter((row) => row.visibleTextRect)
      .map((row) => ({
        index: row.index,
        active: row.active,
        text: row.text,
        visibleTextRect: row.visibleTextRect,
      })),
  };
}

async function captureNamedScreenshot(cdp, screenshotName) {
  const screenshot = await cdp.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  const buffer = Buffer.from(screenshot.data, "base64");
  await writeFile(join(outDir, screenshotName), buffer);
  return buffer;
}

async function sampleStyles(cdp) {
  return evaluate(cdp, `(() => {
    const number = (value) => Number.parseFloat(value) || 0;
    const rectOf = (el) => {
      if (!el) return null;
      const rect = el.getBoundingClientRect();
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height, top: rect.top, left: rect.left, right: rect.right, bottom: rect.bottom };
    };
    const styleOf = (el, pseudo = null) => {
      if (!el) return null;
      const cs = getComputedStyle(el, pseudo);
      const inlineStyle = pseudo == null && el instanceof HTMLElement ? el.style : null;
      return {
        rect: rectOf(el),
        content: cs.content,
        display: cs.display,
        color: cs.color,
        backgroundColor: cs.backgroundColor,
        backgroundImage: cs.backgroundImage,
        inlineBackgroundImage: inlineStyle?.backgroundImage ?? "",
        inlineGradientProgress: inlineStyle?.getPropertyValue('--gradient-progress').trim() ?? "",
        inlineGradientColor: inlineStyle?.getPropertyValue('--gradient-color').trim() ?? "",
        inlineGradientAlphaActive: inlineStyle?.getPropertyValue('--gradient-color-alpha-active').trim() ?? "",
        inlineGradientAlpha: inlineStyle?.getPropertyValue('--gradient-color-alpha').trim() ?? "",
        inlineTextShadowBlurRadius: inlineStyle?.getPropertyValue('--text-shadow-blur-radius').trim() ?? "",
        inlineTextShadowOpacity: inlineStyle?.getPropertyValue('--text-shadow-opacity').trim() ?? "",
        backgroundPosition: cs.backgroundPosition,
        backgroundSize: cs.backgroundSize,
        boxSizing: cs.boxSizing,
        borderRadius: cs.borderRadius,
        boxShadow: cs.boxShadow,
        filter: cs.filter,
        maskImage: cs.maskImage,
        maskComposite: cs.maskComposite,
        webkitMaskImage: cs.webkitMaskImage,
        webkitMaskComposite: cs.webkitMaskComposite,
        mixBlendMode: cs.mixBlendMode,
        isolation: cs.isolation,
        fontFamily: cs.fontFamily,
        fontSize: cs.fontSize,
        fontWeight: cs.fontWeight,
        lineHeight: cs.lineHeight,
        letterSpacing: cs.letterSpacing,
        marginTop: cs.marginTop,
        marginRight: cs.marginRight,
        marginBottom: cs.marginBottom,
        maxHeight: cs.maxHeight,
        height: cs.height,
        minHeight: cs.minHeight,
        inlineHeight: inlineStyle?.height ?? "",
        inlineMinHeight: inlineStyle?.minHeight ?? "",
        inlineAnimationName: inlineStyle?.animationName ?? "",
        inlineAnimationPlayState: inlineStyle?.animationPlayState ?? "",
        paddingTop: cs.paddingTop,
        paddingBottom: cs.paddingBottom,
        paddingRight: cs.paddingRight,
        opacity: cs.opacity,
        overflow: cs.overflow,
        overflowAnchor: cs.overflowAnchor,
        textAlign: cs.textAlign,
        textWrap: cs.textWrap,
        verticalAlign: cs.verticalAlign,
        transform: cs.transform,
        transformOrigin: cs.transformOrigin,
        transition: cs.transition,
        willChange: cs.willChange,
        animationName: cs.animationName,
        animationDuration: cs.animationDuration,
        animationDelay: cs.animationDelay,
        animationPlayState: cs.animationPlayState,
        animationTimingFunction: cs.animationTimingFunction,
        animationIterationCount: cs.animationIterationCount,
        animationFillMode: cs.animationFillMode,
        whiteSpace: cs.whiteSpace,
        wordBreak: cs.wordBreak,
        textShadow: cs.textShadow,
        webkitTextFillColor: cs.webkitTextFillColor,
        appleRowMinHeight: cs.getPropertyValue('--apple-lyric-row-min-height').trim(),
        appleWebkitMinHeight: cs.getPropertyValue('--apple-lyric-row-webkit-min-height').trim(),
        lineAnimationNameVar: cs.getPropertyValue('--line-animation-name').trim(),
      };
    };
    const normalizeRect = (rect) => rect
      ? { x: rect.x, y: rect.y, width: rect.width, height: rect.height, top: rect.top, left: rect.left, right: rect.right, bottom: rect.bottom }
      : null;
    const intersectRect = (a, b) => {
      if (!a || !b) return null;
      const left = Math.max(a.left, b.left);
      const top = Math.max(a.top, b.top);
      const right = Math.min(a.right, b.right);
      const bottom = Math.min(a.bottom, b.bottom);
      if (right <= left || bottom <= top) return null;
      return { x: left, y: top, left, top, right, bottom, width: right - left, height: bottom - top };
    };
    const unionRect = (a, b) => {
      if (!a) return b;
      if (!b) return a;
      const left = Math.min(a.left, b.left);
      const top = Math.min(a.top, b.top);
      const right = Math.max(a.right, b.right);
      const bottom = Math.max(a.bottom, b.bottom);
      return { x: left, y: top, left, top, right, bottom, width: right - left, height: bottom - top };
    };
    const clipRectToVisibleAncestors = (rect, node, boundary) => {
      let next = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
      let clipped = rect;
      while (next && clipped) {
        if (next.nodeType === Node.ELEMENT_NODE) {
          const cs = getComputedStyle(next);
          if (cs.display === 'none' || cs.visibility === 'hidden' || Number.parseFloat(cs.opacity || '1') <= 0.001) {
            return null;
          }
          if (cs.overflow !== 'visible' || cs.overflowX !== 'visible' || cs.overflowY !== 'visible') {
            clipped = intersectRect(clipped, rectOf(next));
          }
        }
        if (next === boundary) break;
        next = next.parentElement;
      }
      return clipped && clipped.width > 0.5 && clipped.height > 0.5 ? clipped : null;
    };
    const visibleTextRectOf = (root) => {
      if (!root) return null;
      let union = null;
      const range = document.createRange();
      const visit = (node) => {
        if (!node) return;
        if (node.nodeType === Node.TEXT_NODE) {
          if (!node.textContent || node.textContent.trim().length === 0) return;
          range.selectNodeContents(node);
          for (const rawRect of Array.from(range.getClientRects())) {
            const rect = clipRectToVisibleAncestors(normalizeRect(rawRect), node, root);
            union = unionRect(union, rect);
          }
          return;
        }
        if (node.nodeType !== Node.ELEMENT_NODE) return;
        const cs = getComputedStyle(node);
        if (cs.display === 'none' || cs.visibility === 'hidden' || Number.parseFloat(cs.opacity || '1') <= 0.001) {
          return;
        }
        for (const child of Array.from(node.childNodes ?? [])) visit(child);
      };
      visit(root);
      range.detach?.();
      return union;
    };
    const q = (selector, root = document) => root.querySelector(selector);
    const qa = (selector, root = document) => Array.from(root.querySelectorAll(selector));
    const classNameOf = (el) => typeof el?.className === 'string' ? el.className : '';
    const collectVisibleText = (root) => {
      let text = '';
      const visit = (node) => {
        if (!node) return;
        if (node.nodeType === Node.TEXT_NODE) {
          const parentName = node.parentElement?.tagName;
          if (parentName !== 'STYLE' && parentName !== 'SCRIPT') text += ' ' + node.textContent;
          return;
        }
        if (node.nodeType !== Node.ELEMENT_NODE && node.nodeType !== Node.DOCUMENT_NODE && node.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) {
          return;
        }
        if (node.shadowRoot) visit(node.shadowRoot);
        if (node.tagName === 'IFRAME') {
          try {
            if (node.contentDocument) visit(node.contentDocument);
          } catch {}
        }
        for (const child of Array.from(node.childNodes ?? [])) visit(child);
      };
      visit(root);
      return text;
    };
    const backdrop = q('[data-apple-lyrics-backdrop]');
    const active = q('[data-lyric-row-kind="yrc"][data-active="1"]');
    const activeInner = active?.firstElementChild ?? null;
    const translation = q('[data-companion-role="translation"]', active ?? document);
    const romaji = q('[data-companion-role="romaji"]', active ?? document);
    const backgroundTranslation = q('[data-companion-role="background-translation"]', active ?? document);
    const companion = q('[data-companion-role="companion"]', active ?? document);
    const ordinaryToken = q('[data-yrc-token-kind="ordinary"]', active ?? document);
    const ordinaryTokenGroup = ordinaryToken?.closest('[data-yrc-group]') ?? null;
    const ordinaryTokenMain = ordinaryToken?.closest('[data-yrc-main]') ?? null;
    const tokenSupplementary = q('[data-yrc-token-supplementary]', active ?? document);
    const tokenSupplementaryGroup = tokenSupplementary?.closest('[data-yrc-group]') ?? null;
    const tokenSupplementaryMain = tokenSupplementaryGroup?.querySelector('[data-yrc-main]') ?? null;
    const slowToken = q('[data-yrc-token-kind="slow"]', active ?? document);
    const slowLetter = q('[data-yrc-token-kind="slow"] [data-yrc-letter-index="0"]', active ?? document);
    const primaryVocals = q('[data-apple-vocals="primary-vocals"]', active ?? document);
    const backgroundVocals = q('[data-apple-vocals="background-vocals"]', active ?? document);
    const backgroundVocalTokens = qa('[data-apple-vocals="background-vocals"] [data-yrc-token-kind]', active ?? document);
    const yrcRows = qa('[data-lyric-row-kind="yrc"]');
    const lyricRows = qa('[data-lyric-row-kind]');
    const activeIndex = yrcRows.indexOf(active);
    const previousRow = activeIndex > 0 ? yrcRows[activeIndex - 1] : null;
    const nextRow = activeIndex >= 0 ? yrcRows[activeIndex + 1] ?? null : null;
    const duetRow = yrcRows.find((row) => row.dataset.lyricAlignment === 'end') ?? null;
    const vars = getComputedStyle(q('.appleLyricsFullscreen') ?? document.documentElement);
    const viewportVars = getComputedStyle(q('[data-apple-lyrics-viewport]') ?? document.documentElement);
    const activeStyle = active ? getComputedStyle(active) : null;
    const activeLineStyle = activeInner ? getComputedStyle(activeInner) : activeStyle;
    const supplementaryStyleOf = (node) => {
      const style = styleOf(node);
      if (!style) return null;
      return {
        ...style,
        appleKind: node.getAttribute('data-apple-supplementary-kind'),
        appleVisible: node.getAttribute('data-apple-supplementary-visible'),
      };
    };
    const lineChildrenOf = (node) => Array.from(node?.children ?? []).map((child) => ({
      tagName: child.tagName,
      className: classNameOf(child),
      companionRole: child.getAttribute('data-companion-role'),
      appleVocals: child.getAttribute('data-apple-vocals'),
      appleKind: child.getAttribute('data-apple-supplementary-kind'),
      text: child.textContent ?? '',
      style: styleOf(child),
    }));
    return {
      viewport: { width: window.innerWidth, height: window.innerHeight, devicePixelRatio: window.devicePixelRatio },
      browserNow: performance.now(),
      fixtureSongId: q('[data-apple-lyrics-fixture]')?.getAttribute('data-song-id') ?? null,
      fixturePosition: q('[data-apple-lyrics-fixture]')?.getAttribute('data-position-sec') ?? null,
      transitionRoot: (() => {
        const root = q('[data-apple-lyrics-transition-fixture]');
        return root ? { rect: rectOf(root), dataOpen: root.getAttribute('data-open') } : null;
      })(),
      transitionSourceCover: styleOf(q('[data-transition-source-cover]')),
      transitionSourceLyric: styleOf(q('[data-transition-source-lyric]')),
      cssVars: {
        platform: document.documentElement.dataset.platform ?? "",
        pagePadding: vars.getPropertyValue('--apple-lyrics-page-padding').trim(),
        coverColumnWidth: vars.getPropertyValue('--apple-lyrics-cover-column-width').trim(),
        lyricsColumnWidth: vars.getPropertyValue('--apple-lyrics-column-width').trim(),
        inactiveGaussianBlur: viewportVars.getPropertyValue('--inactive-gaussian-blur').trim(),
        displaySyncedLineOpacity: viewportVars.getPropertyValue('--lyrics-display-synced-line-opacity').trim(),
        lineAnimationPlayState: viewportVars.getPropertyValue('--line-animation-play-state').trim(),
      },
      backdrop: styleOf(backdrop),
      backdropArtwork: styleOf(q('[data-apple-lyrics-backdrop-artwork]') ?? backdrop?.firstElementChild),
      backdropVeil: styleOf(q('[data-apple-lyrics-backdrop-veil]')),
      cover: styleOf(q('[data-apple-lyrics-cover]')),
      coverHalo: styleOf(q('[data-apple-lyrics-cover-halo]')),
	      columnVeil: styleOf(q('[data-apple-lyrics-column-veil]')),
	      column: styleOf(q('[data-apple-lyrics-column]')),
	      lyricsBlendLayer: styleOf(q('[data-apple-lyrics-text-blend]')),
	      viewportBox: styleOf(q('[data-apple-lyrics-viewport]')),
      scrollHistory: (() => {
        const raw = q('[data-apple-lyrics-viewport]')?.getAttribute('data-apple-lyrics-scroll-history') ?? '[]';
        try {
          const parsed = JSON.parse(raw);
          return Array.isArray(parsed) ? parsed : [];
        } catch {
          return [];
        }
      })(),
      scrollAnimation: (() => {
        const viewport = q('[data-apple-lyrics-viewport]');
        return viewport
          ? {
            serial: viewport.getAttribute('data-apple-lyrics-scroll-serial'),
            startedAt: number(viewport.getAttribute('data-apple-lyrics-scroll-started-at') ?? ''),
            from: number(viewport.getAttribute('data-apple-lyrics-scroll-from') ?? ''),
            target: number(viewport.getAttribute('data-apple-lyrics-scroll-target') ?? ''),
            duration: number(viewport.getAttribute('data-apple-lyrics-scroll-duration') ?? ''),
            motion: viewport.getAttribute('data-apple-lyrics-scroll-motion'),
            springStiffness: number(viewport.getAttribute('data-apple-lyrics-scroll-spring-stiffness') ?? ''),
            springDamping: number(viewport.getAttribute('data-apple-lyrics-scroll-spring-damping') ?? ''),
            velocity: number(viewport.getAttribute('data-apple-lyrics-scroll-velocity') ?? ''),
            activeCountAtStart: number(viewport.getAttribute('data-apple-lyrics-scroll-active-count-at-start') ?? ''),
            activeCount: number(viewport.getAttribute('data-apple-lyrics-scroll-active-count') ?? ''),
            scrolling: viewport.getAttribute('data-apple-lyrics-scrolling'),
            source: viewport.getAttribute('data-apple-lyrics-scroll-source'),
            force: viewport.getAttribute('data-apple-lyrics-scroll-force') === 'true',
            targetIndex: number(viewport.getAttribute('data-apple-lyrics-scroll-target-index') ?? ''),
            targetRowTop: number(viewport.getAttribute('data-apple-lyrics-scroll-target-row-top') ?? ''),
            targetRowHeight: number(viewport.getAttribute('data-apple-lyrics-scroll-target-row-height') ?? ''),
            anchorPx: number(viewport.getAttribute('data-apple-lyrics-scroll-anchor-px') ?? ''),
            offsetRatio: number(viewport.getAttribute('data-apple-lyrics-scroll-offset-ratio') ?? ''),
            topSpacerHeight: number(viewport.getAttribute('data-apple-lyrics-scroll-top-spacer-height') ?? ''),
            baseScrollTop: number(viewport.getAttribute('data-apple-lyrics-scroll-base-scroll-top') ?? ''),
            topMargin: number(viewport.getAttribute('data-apple-lyrics-scroll-top-margin') ?? ''),
            geometryActiveIdx: number(viewport.getAttribute('data-apple-lyrics-geometry-active-idx') ?? ''),
            geometryScrollTop: number(viewport.getAttribute('data-apple-lyrics-geometry-scroll-top') ?? ''),
          }
          : null;
      })(),
      lineTransition: (() => {
        const viewport = q('[data-apple-lyrics-viewport]');
        return viewport
          ? {
            serial: viewport.getAttribute('data-apple-lyrics-line-transition-serial'),
            startedAt: number(viewport.getAttribute('data-apple-lyrics-line-transition-started-at') ?? ''),
            from: number(viewport.getAttribute('data-apple-lyrics-line-transition-from') ?? ''),
            to: number(viewport.getAttribute('data-apple-lyrics-line-transition-to') ?? ''),
            cssSerial: viewport.getAttribute('data-apple-lyrics-css-line-transition-serial'),
            cssStartedAt: number(viewport.getAttribute('data-apple-lyrics-css-line-transition-started-at') ?? ''),
            cssPropertyName: viewport.getAttribute('data-apple-lyrics-css-line-transition-property'),
            cssRowText: viewport.getAttribute('data-apple-lyrics-css-line-transition-row-text'),
            cssHistory: (() => {
              try {
                const parsed = JSON.parse(viewport.getAttribute('data-apple-lyrics-css-line-transition-history') ?? '[]');
                return Array.isArray(parsed) ? parsed : [];
              } catch {
                return [];
              }
            })(),
          }
          : null;
      })(),
      inner: styleOf(q('[data-apple-lyrics-inner]')),
      active: {
        text: active?.dataset.lyricText ?? null,
        tagName: active?.tagName ?? null,
        className: classNameOf(active),
        innerTagName: activeInner?.tagName ?? null,
        innerClassName: classNameOf(activeInner),
        lineChildren: lineChildrenOf(activeInner),
        supplementarySafetyGap: active?.getAttribute('data-apple-lyric-supplementary-safety-gap') ?? null,
        style: styleOf(active),
        innerStyle: styleOf(activeInner),
      },
      previous: styleOf(previousRow),
      previousInner: styleOf(previousRow?.firstElementChild ?? null),
      next: styleOf(nextRow),
      translation: supplementaryStyleOf(translation),
      romaji: supplementaryStyleOf(romaji),
      backgroundTranslation: supplementaryStyleOf(backgroundTranslation),
      companion: supplementaryStyleOf(companion),
      primaryVocals: styleOf(primaryVocals),
      backgroundVocals: styleOf(backgroundVocals),
      backgroundVocalTokens: backgroundVocalTokens.map((token) => ({
        text: token.getAttribute('data-yrc-token') ?? null,
        renderedText: token.textContent ?? null,
        kind: token.getAttribute('data-yrc-token-kind') ?? null,
        style: styleOf(token),
      })),
      ordinaryToken: {
        text: ordinaryToken?.getAttribute('data-yrc-token') ?? null,
        renderedText: ordinaryToken?.textContent ?? null,
        className: ordinaryToken?.className ?? null,
        appleSyllable: ordinaryToken?.getAttribute('data-apple-syllable') ?? null,
        style: styleOf(ordinaryToken),
      },
      ordinaryTokenGroup: {
        tagName: ordinaryTokenGroup?.tagName ?? null,
        className: ordinaryTokenGroup?.className ?? null,
        trailingWhitespace: ordinaryTokenGroup?.getAttribute('data-yrc-group-trailing-whitespace') ?? null,
        style: styleOf(ordinaryTokenGroup),
      },
      ordinaryTokenMain: {
        tagName: ordinaryTokenMain?.tagName ?? null,
        className: ordinaryTokenMain?.className ?? null,
        style: styleOf(ordinaryTokenMain),
        afterStyle: styleOf(ordinaryTokenMain, '::after'),
      },
      tokenSupplementary: {
        tagName: tokenSupplementary?.tagName ?? null,
        className: tokenSupplementary?.className ?? null,
        text: tokenSupplementary?.textContent ?? null,
        style: styleOf(tokenSupplementary),
      },
      tokenSupplementaryGroup: {
        tagName: tokenSupplementaryGroup?.tagName ?? null,
        className: tokenSupplementaryGroup?.className ?? null,
        showSupplementary: tokenSupplementaryGroup?.getAttribute('data-yrc-group-show-supplementary') ?? null,
        style: styleOf(tokenSupplementaryGroup),
      },
      tokenSupplementaryMain: {
        tagName: tokenSupplementaryMain?.tagName ?? null,
        className: tokenSupplementaryMain?.className ?? null,
        style: styleOf(tokenSupplementaryMain),
      },
      previousOrdinaryToken: (() => {
        const token = q('[data-yrc-token-kind="ordinary"],[data-yrc-token-kind="ordinary-static"]', previousRow ?? document);
        return { text: token?.getAttribute('data-yrc-token') ?? null, kind: token?.getAttribute('data-yrc-token-kind') ?? null, style: styleOf(token) };
      })(),
      nextOrdinaryToken: (() => {
        const token = q('[data-yrc-token-kind="ordinary"],[data-yrc-token-kind="ordinary-static"]', nextRow ?? document);
        return { text: token?.getAttribute('data-yrc-token') ?? null, kind: token?.getAttribute('data-yrc-token-kind') ?? null, style: styleOf(token) };
      })(),
      slowToken: {
        text: slowToken?.getAttribute('data-yrc-token') ?? null,
        style: styleOf(slowToken),
        firstLetterStyle: styleOf(slowLetter),
        letterStyles: qa('[data-yrc-letter-index]', slowToken ?? document).map((node) => ({
          index: Number(node.getAttribute('data-yrc-letter-index')),
          text: node.getAttribute('data-yrc-letter') ?? '',
          style: styleOf(node),
          foregroundStyle: styleOf(q('[data-yrc-letter-foreground]', node) ?? node),
          glowStyle: styleOf(q('[data-yrc-letter-glow]', node)),
        })),
      },
      duet: { text: duetRow?.dataset.lyricText ?? null, style: styleOf(duetRow), innerStyle: styleOf(duetRow?.firstElementChild ?? null) },
      rowStack: lyricRows.map((row) => ({
        tagName: row.tagName,
        className: classNameOf(row),
        kind: row.getAttribute('data-lyric-row-kind'),
        index: Number(row.getAttribute('data-apple-lyric-row-index')),
        active: row.getAttribute('data-active') === '1',
        scrollTarget: row.getAttribute('data-scroll-target') === '1',
        willAnimate: row.getAttribute('data-apple-lyric-will-animate') === '1',
        wasActiveBeforeSwitch: row.getAttribute('data-apple-lyric-was-active-before-switch') === '1',
        timedRelease: row.getAttribute('data-apple-lyric-timed-release') === '1',
        supplementarySafetyGap: number(row.getAttribute('data-apple-lyric-supplementary-safety-gap') ?? ''),
        text: row.dataset.lyricText ?? '',
        rowStyle: styleOf(row),
        lineStyle: styleOf(row.firstElementChild ?? null),
        lineTagName: row.firstElementChild?.tagName ?? null,
        lineClassName: classNameOf(row.firstElementChild),
        visibleTextRect: visibleTextRectOf(row),
      })),
      companionRoles: qa('[data-companion-role]').map((node) => node.getAttribute('data-companion-role')).filter(Boolean),
      rowCount: yrcRows.length,
      activeIndex,
      devOverlayText: (() => {
        const text = qa('nextjs-portal')
          .map((portal) => collectVisibleText(portal.shadowRoot ?? portal))
          .join(' ')
          .replace(/\\s+/g, ' ')
          .trim();
        return /\\b\\d+ error\\b|Unhandled Runtime Error|Hydration|Error:/i.test(text)
          ? text.slice(0, 1200)
          : '';
      })(),
      numeric: {
        activeFontSize: activeLineStyle ? number(activeLineStyle.fontSize) : 0,
        activeLineHeight: activeLineStyle ? number(activeLineStyle.lineHeight) : 0,
        activeHeight: activeStyle ? number(activeStyle.height) : 0,
        activeMarginBottom: activeStyle ? number(activeStyle.marginBottom) : 0,
        activeLineMarginBottom: activeLineStyle ? number(activeLineStyle.marginBottom) : 0,
        activePaddingTop: activeLineStyle ? number(activeLineStyle.paddingTop) : 0,
        activePaddingBottom: activeLineStyle ? number(activeLineStyle.paddingBottom) : 0,
        columnLeft: rectOf(q('[data-apple-lyrics-column]'))?.left ?? 0,
        columnWidth: rectOf(q('[data-apple-lyrics-column]'))?.width ?? 0,
        viewportTop: rectOf(q('[data-apple-lyrics-viewport]'))?.top ?? 0,
        viewportHeight: rectOf(q('[data-apple-lyrics-viewport]'))?.height ?? 0,
        activeTop: rectOf(active)?.top ?? 0,
        innerY: rectOf(q('[data-apple-lyrics-inner]'))?.top ?? 0,
        viewportScrollTop: q('[data-apple-lyrics-viewport]')?.scrollTop ?? 0,
        topSpacerHeight: rectOf(q('[data-apple-lyrics-top-spacer]'))?.height ?? 0,
      },
    };
  })()`);
}

async function sampleScrollTransition(cdp, options = {}) {
  const timings = [0, 50, 100, 125, 175, 250, 350, 400, 425];
  const lineTransition = await sampleLineTransitionFrames(cdp, timings);
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(7.70)");
  await settle(cdp, 520);
  const before = await sampleStyles(cdp);
  const previousScrollSerial = before.scrollAnimation?.serial ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(7.76)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('That I just wanna get with you')`, 1000);
  const scrollAnimation = await waitForScrollAnimationStart(cdp, previousScrollSerial);
  const samples = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, scrollAnimation.startedAt + timing);
    await nextAnimationFrame(cdp);
    const sample = await sampleStyles(cdp);
    samples.push(summarizeLineTransitionFrame(sample, timing));
  }
  await settle(cdp, 160);
  const after = await sampleStyles(cdp);
  const screenshots = options.captureScreenshots === false
    ? []
    : await captureScrollTransitionScreenshots(cdp, timings, options.screenshotPrefix);
  return {
    before: summarizeScrollStyle(before),
    frames: withCombinedMotionMetrics(samples, before.numeric.activeHeight),
    after: summarizeScrollStyle(after),
    lineBefore: lineTransition.before,
    lineFrames: withCombinedMotionMetrics(lineTransition.frames, lineTransition.before?.activeHeight),
    lineAfter: lineTransition.after,
    lineTransition: lineTransition.lineTransition,
    scrollAnimation,
    screenshots,
  };
}

async function sampleNaturalPlaybackLineSwitch(cdp, options = {}) {
  const clock = options.clock ?? "raf";
  const timings = [0, 50, 100, 125, 175, 250, 350, 425];
  await navigate(cdp, fixtureUrlFor(defaultSongId, 7.50, 1, { clock }));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 40);
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('You see through')`, 1000);
  await resetCssLineTransitionProbe(cdp);
  const before = await sampleStyles(cdp);
  const previousScrollSerial = before.scrollAnimation?.serial ?? null;
  const previousCssLineTransitionSerial = before.lineTransition?.cssSerial ?? null;
  const cssLineTransitionPromise = waitForCssLineTransitionStart(
    cdp,
    previousCssLineTransitionSerial,
    1400,
  );
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('That I just wanna get with you')`, 1200);
  const scrollAnimation = await waitForScrollAnimationStart(cdp, previousScrollSerial);
  const frames = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, scrollAnimation.startedAt + timing);
    await nextAnimationFrame(cdp);
    const sample = await sampleStyles(cdp);
    frames.push({
      ...summarizeLineTransitionFrame(sample, timing),
      ordinary: summarizeTokenStyle(sample.ordinaryToken),
      fixturePosition: Number.parseFloat(sample.fixturePosition ?? "0") || 0,
    });
  }
  const cssLineTransition = await cssLineTransitionPromise;
  await settle(cdp, 140);
  const after = await sampleStyles(cdp);
  return {
    before: summarizeScrollStyle(before),
    scrollAnimation,
    lineTransition: { cssLineTransition },
    frames: withCombinedMotionMetrics(frames, before.numeric.activeHeight),
    after: {
      ...summarizeScrollStyle(after),
      ordinary: summarizeTokenStyle(after.ordinaryToken),
      fixturePosition: Number.parseFloat(after.fixturePosition ?? "0") || 0,
    },
  };
}

async function sampleBackgroundVocalLineSwitch(cdp) {
  const timings = [0, 50, 100, 125, 175, 250, 350, 400, 425];
  await setViewport(cdp, 1440, 900);
  await navigate(cdp, fixtureUrlFor(defaultSongId, 16.70, 0, { clock: "held" }));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 520);
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('You right I')`, 1000);
  await resetCssLineTransitionProbe(cdp);
  const before = await sampleStyles(cdp);
  const previousScrollSerial = before.scrollAnimation?.serial ?? null;
  const previousCssLineTransitionSerial = before.lineTransition?.cssSerial ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(16.76)");
  const cssLineTransitionPromise = waitForCssLineTransitionStart(
    cdp,
    previousCssLineTransitionSerial,
    1400,
  );
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('Got my guy')`, 1000);
  const scrollAnimation = await waitForScrollAnimationStart(cdp, previousScrollSerial);
  const frames = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, scrollAnimation.startedAt + timing);
    await nextAnimationFrame(cdp);
    const sample = await sampleStyles(cdp);
    frames.push(summarizeLineTransitionFrame(sample, timing));
  }
  const cssLineTransition = await cssLineTransitionPromise;
  await settle(cdp, 160);
  const after = await sampleStyles(cdp);
  const screenshot = await cdp.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  await writeFile(
    join(outDir, "apple-lyrics-background-vocal-switch.png"),
    Buffer.from(screenshot.data, "base64"),
  );
  return {
    before: summarizeScrollStyle(before),
    scrollAnimation,
    lineTransition: { cssLineTransition },
    frames: withCombinedMotionMetrics(frames, before.numeric.activeHeight),
    after: summarizeScrollStyle(after),
    rowStackAfter: after.rowStack,
    screenshotName: "apple-lyrics-background-vocal-switch.png",
  };
}

async function sampleContinuousPlaybackWindow(cdp) {
  const durationMs = 10500;
  const timings = [];
  for (let timing = 0; timing <= durationMs; timing += 125) timings.push(timing);
  await setViewport(cdp, 1440, 900);
  await navigate(cdp, fixtureUrlFor(defaultSongId, 7.25, 1, { clock: "raf" }));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 60);
  const startNow = await evaluate(cdp, "performance.now()");
  const frames = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, startNow + timing);
    if (timing > 0) await nextAnimationFrame(cdp);
    frames.push(summarizeContinuousPlaybackFrame(await sampleStyles(cdp), timing));
  }
  const after = await sampleStyles(cdp);
  const screenshot = await cdp.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  const screenshotBuffer = Buffer.from(screenshot.data, "base64");
  const screenshotName = "apple-lyrics-continuous-playback.png";
  await writeFile(join(outDir, screenshotName), screenshotBuffer);
  assertScreenshotHasOpaqueLyricBackground("continuous playback", screenshotBuffer, after);
  return {
    startPositionSec: 7.25,
    durationMs,
    sampleIntervalMs: 125,
    frames,
    after: summarizeContinuousPlaybackFrame(after, durationMs),
    screenshotName,
  };
}

async function sampleRapidScrollOverlap(cdp) {
  await navigate(cdp, fixtureUrlFor("rapid-switch", 4.86));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 520);
  const before = await sampleStyles(cdp);
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(5.08)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('Quick two')`, 1000);
  const firstScroll = await waitForScrollAnimationStart(cdp, before.scrollAnimation?.serial ?? null);
  await waitUntilBrowserTime(cdp, firstScroll.startedAt + 80);
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(5.40)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('Quick three')`, 1000);
  const secondScroll = await waitForScrollAnimationStart(cdp, firstScroll.serial);
  await nextAnimationFrame(cdp);
  const overlapFrame = await sampleStyles(cdp);
  await waitUntilBrowserTime(cdp, secondScroll.startedAt + 80);
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(5.96)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('Settle down')`, 1000);
  const thirdScroll = await waitForScrollAnimationStart(cdp, secondScroll.serial);
  await waitUntilBrowserTime(cdp, firstScroll.startedAt + 370);
  const afterFirstCompletion = await sampleStyles(cdp);
  await waitForAppleScrollIdle(cdp);
  const after = await sampleStyles(cdp);
  return {
    before: summarizeScrollStyle(before),
    firstScroll,
    secondScroll,
    thirdScroll,
    overlapFrame: summarizeScrollStyle(overlapFrame),
    afterFirstCompletion: summarizeScrollStyle(afterFirstCompletion),
    after: summarizeScrollStyle(after),
  };
}

async function sampleCollapsibleInterludeSwitch(cdp) {
  const timings = [0, 100, 300, 350];
  await setViewport(cdp, 1440, 900);
  await navigate(cdp, fixtureUrlFor("interlude-gap", 5.40));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 520);
  const before = await sampleStyles(cdp);

  const previousExpandScrollSerial = before.scrollAnimation?.serial ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(12.40)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="interlude"][data-active="1"]')`, 1000);
  const expandScroll = await waitForScrollAnimationStart(cdp, previousExpandScrollSerial);
  const expandFrames = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, expandScroll.startedAt + timing);
    await nextAnimationFrame(cdp);
    expandFrames.push(summarizeCollapsibleInterludeFrame(await sampleStyles(cdp), timing));
  }
  await settle(cdp, 420);
  const expanded = await sampleStyles(cdp);

  const previousCollapseScrollSerial = expanded.scrollAnimation?.serial ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(20.40)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('After gap')`, 1000);
  const collapseScroll = await waitForScrollAnimationStart(cdp, previousCollapseScrollSerial);
  const collapseFrames = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, collapseScroll.startedAt + timing);
    await nextAnimationFrame(cdp);
    collapseFrames.push(summarizeCollapsibleInterludeFrame(await sampleStyles(cdp), timing));
  }
  await settle(cdp, 120);
  const collapsed = await sampleStyles(cdp);

  return {
    before: summarizeCollapsibleInterludeFrame(before, -1),
    expandScroll,
    expandFrames,
    expanded: summarizeCollapsibleInterludeFrame(expanded, 420),
    collapseScroll,
    collapseFrames,
    collapsed: summarizeCollapsibleInterludeFrame(collapsed, 470),
  };
}

function summarizeCollapsibleInterludeFrame(sample, timingMs) {
  const interlude = findRow(sample.rowStack, "interlude");
  return {
    timingMs,
    activeText: sample.active?.text ?? null,
    activeKind: sample.active?.kind ?? null,
    interlude: interlude
      ? {
          active: interlude.active,
          className: interlude.className,
          height: cssPx(interlude.rowStyle?.height),
          rectHeight: interlude.rowStyle?.rect?.height ?? null,
          overflow: interlude.rowStyle?.overflow,
          animationName: interlude.rowStyle?.animationName,
          animationDuration: interlude.rowStyle?.animationDuration,
          animationTimingFunction: interlude.rowStyle?.animationTimingFunction,
          lineScale: transformScale(interlude.lineStyle?.transform),
          lineTransform: interlude.lineStyle?.transform,
          lineAnimationName: interlude.lineStyle?.animationName,
        }
      : null,
    rowStack: sample.rowStack,
  };
}

async function waitForScrollAnimationStart(cdp, previousSerial) {
  const deadline = Date.now() + 1000;
  while (Date.now() < deadline) {
    const info = await evaluate(cdp, `(() => {
      const viewport = document.querySelector('[data-apple-lyrics-viewport]');
      if (!viewport) return null;
      const number = (value) => Number.parseFloat(value) || 0;
      return {
        serial: viewport.getAttribute('data-apple-lyrics-scroll-serial'),
        startedAt: number(viewport.getAttribute('data-apple-lyrics-scroll-started-at') ?? ''),
        from: number(viewport.getAttribute('data-apple-lyrics-scroll-from') ?? ''),
        target: number(viewport.getAttribute('data-apple-lyrics-scroll-target') ?? ''),
        duration: number(viewport.getAttribute('data-apple-lyrics-scroll-duration') ?? ''),
        motion: viewport.getAttribute('data-apple-lyrics-scroll-motion'),
        springStiffness: number(viewport.getAttribute('data-apple-lyrics-scroll-spring-stiffness') ?? ''),
        springDamping: number(viewport.getAttribute('data-apple-lyrics-scroll-spring-damping') ?? ''),
        activeCountAtStart: number(viewport.getAttribute('data-apple-lyrics-scroll-active-count-at-start') ?? ''),
        activeCount: number(viewport.getAttribute('data-apple-lyrics-scroll-active-count') ?? ''),
        source: viewport.getAttribute('data-apple-lyrics-scroll-source'),
        force: viewport.getAttribute('data-apple-lyrics-scroll-force') === 'true',
        targetIndex: number(viewport.getAttribute('data-apple-lyrics-scroll-target-index') ?? ''),
        targetRowTop: number(viewport.getAttribute('data-apple-lyrics-scroll-target-row-top') ?? ''),
        targetRowHeight: number(viewport.getAttribute('data-apple-lyrics-scroll-target-row-height') ?? ''),
        anchorPx: number(viewport.getAttribute('data-apple-lyrics-scroll-anchor-px') ?? ''),
        offsetRatio: number(viewport.getAttribute('data-apple-lyrics-scroll-offset-ratio') ?? ''),
        topSpacerHeight: number(viewport.getAttribute('data-apple-lyrics-scroll-top-spacer-height') ?? ''),
        baseScrollTop: number(viewport.getAttribute('data-apple-lyrics-scroll-base-scroll-top') ?? ''),
        topMargin: number(viewport.getAttribute('data-apple-lyrics-scroll-top-margin') ?? ''),
        geometryActiveIdx: number(viewport.getAttribute('data-apple-lyrics-geometry-active-idx') ?? ''),
        geometryScrollTop: number(viewport.getAttribute('data-apple-lyrics-geometry-scroll-top') ?? ''),
      };
    })()`);
    if (info?.serial && info.serial !== previousSerial && info.duration > 0) return info;
    await delay(8);
  }
  throw new Error("Timed out waiting for Apple lyric scroll animation start");
}

async function waitForScrollUpdate(cdp, previousSerial, timeoutMs = 1400) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const info = await evaluate(cdp, `(() => {
      const viewport = document.querySelector('[data-apple-lyrics-viewport]');
      if (!viewport) return null;
      const number = (value) => Number.parseFloat(value) || 0;
      return {
        serial: viewport.getAttribute('data-apple-lyrics-scroll-serial'),
        startedAt: number(viewport.getAttribute('data-apple-lyrics-scroll-started-at') ?? ''),
        from: number(viewport.getAttribute('data-apple-lyrics-scroll-from') ?? ''),
        target: number(viewport.getAttribute('data-apple-lyrics-scroll-target') ?? ''),
        duration: number(viewport.getAttribute('data-apple-lyrics-scroll-duration') ?? ''),
        motion: viewport.getAttribute('data-apple-lyrics-scroll-motion'),
        springStiffness: number(viewport.getAttribute('data-apple-lyrics-scroll-spring-stiffness') ?? ''),
        springDamping: number(viewport.getAttribute('data-apple-lyrics-scroll-spring-damping') ?? ''),
        activeCountAtStart: number(viewport.getAttribute('data-apple-lyrics-scroll-active-count-at-start') ?? ''),
        activeCount: number(viewport.getAttribute('data-apple-lyrics-scroll-active-count') ?? ''),
        source: viewport.getAttribute('data-apple-lyrics-scroll-source'),
        force: viewport.getAttribute('data-apple-lyrics-scroll-force') === 'true',
        targetIndex: number(viewport.getAttribute('data-apple-lyrics-scroll-target-index') ?? ''),
        targetRowTop: number(viewport.getAttribute('data-apple-lyrics-scroll-target-row-top') ?? ''),
        targetRowHeight: number(viewport.getAttribute('data-apple-lyrics-scroll-target-row-height') ?? ''),
        anchorPx: number(viewport.getAttribute('data-apple-lyrics-scroll-anchor-px') ?? ''),
        offsetRatio: number(viewport.getAttribute('data-apple-lyrics-scroll-offset-ratio') ?? ''),
        topSpacerHeight: number(viewport.getAttribute('data-apple-lyrics-scroll-top-spacer-height') ?? ''),
        baseScrollTop: number(viewport.getAttribute('data-apple-lyrics-scroll-base-scroll-top') ?? ''),
        topMargin: number(viewport.getAttribute('data-apple-lyrics-scroll-top-margin') ?? ''),
        geometryActiveIdx: number(viewport.getAttribute('data-apple-lyrics-geometry-active-idx') ?? ''),
        geometryScrollTop: number(viewport.getAttribute('data-apple-lyrics-geometry-scroll-top') ?? ''),
      };
    })()`);
    if (info?.serial && info.serial !== previousSerial) return info;
    await delay(8);
  }
  throw new Error("Timed out waiting for Apple lyric scroll update");
}

async function waitForAppleScrollIdle(cdp, timeoutMs = 1400) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const activeCount = await evaluate(cdp, `(() => {
      const viewport = document.querySelector('[data-apple-lyrics-viewport]');
      return Number.parseFloat(viewport?.getAttribute('data-apple-lyrics-scroll-active-count') ?? '0') || 0;
    })()`);
    if (activeCount === 0) return;
    await delay(8);
  }
  throw new Error("Timed out waiting for Apple lyric scroll writers to finish");
}

async function waitForLineTransitionUpdate(cdp, previousSerial, timeoutMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const info = await evaluate(cdp, `(() => {
      const viewport = document.querySelector('[data-apple-lyrics-viewport]');
      if (!viewport) return null;
      const number = (value) => Number.parseFloat(value) || 0;
      return {
        serial: viewport.getAttribute('data-apple-lyrics-line-transition-serial'),
        startedAt: number(viewport.getAttribute('data-apple-lyrics-line-transition-started-at') ?? ''),
        from: number(viewport.getAttribute('data-apple-lyrics-line-transition-from') ?? ''),
        to: number(viewport.getAttribute('data-apple-lyrics-line-transition-to') ?? ''),
      };
    })()`);
    if (info?.serial && info.serial !== previousSerial && info.startedAt > 0) return info;
    await delay(8);
  }
  throw new Error("Timed out waiting for Apple lyric line transition update");
}

async function resetCssLineTransitionProbe(cdp) {
  await evaluate(cdp, `(() => {
    const viewport = document.querySelector('[data-apple-lyrics-viewport]');
    if (!viewport) return;
    viewport.dataset.appleLyricsCssLineTransitionSerial = String(
      Number(viewport.dataset.appleLyricsCssLineTransitionSerial || '0'),
    );
    delete viewport.dataset.appleLyricsCssLineTransitionStartedAt;
    delete viewport.dataset.appleLyricsCssLineTransitionProperty;
    delete viewport.dataset.appleLyricsCssLineTransitionRowText;
    viewport.dataset.appleLyricsCssLineTransitionHistory = '[]';
    if (window.__appleLyricsCssLineTransitionProbeInstalled) return;
    window.__appleLyricsCssLineTransitionProbeInstalled = true;
    document.addEventListener('transitionrun', (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;
      if (!target.classList.contains('line')) return;
      if (!['transform', 'padding-top', 'padding-bottom'].includes(event.propertyName)) return;
      const viewport = document.querySelector('[data-apple-lyrics-viewport]');
      if (!viewport) return;
      const row = target.closest('[data-lyric-row-kind]');
      let history = [];
      try {
        const parsed = JSON.parse(viewport.dataset.appleLyricsCssLineTransitionHistory || '[]');
        history = Array.isArray(parsed) ? parsed : [];
      } catch {}
      const eventRecord = {
        startedAt: Number(performance.now().toFixed(3)),
        propertyName: event.propertyName,
        rowText: row?.getAttribute('data-lyric-text') ?? '',
        active: row?.getAttribute('data-active') === '1',
        scrollTarget: row?.getAttribute('data-scroll-target') === '1',
        willAnimate: row?.getAttribute('data-apple-lyric-will-animate') === '1',
        wasActiveBeforeSwitch: row?.getAttribute('data-apple-lyric-was-active-before-switch') === '1',
      };
      history.push(eventRecord);
      viewport.dataset.appleLyricsCssLineTransitionHistory = JSON.stringify(history.slice(-16));
      if (viewport.dataset.appleLyricsCssLineTransitionStartedAt) return;
      viewport.dataset.appleLyricsCssLineTransitionSerial = String(
        Number(viewport.dataset.appleLyricsCssLineTransitionSerial || '0') + 1,
      );
      viewport.dataset.appleLyricsCssLineTransitionStartedAt = eventRecord.startedAt.toFixed(3);
      viewport.dataset.appleLyricsCssLineTransitionProperty = event.propertyName;
      viewport.dataset.appleLyricsCssLineTransitionRowText = eventRecord.rowText;
    }, true);
  })()`);
}

async function waitForCssLineTransitionStart(cdp, previousSerial, timeoutMs = 1000, options = {}) {
  const required = options.required !== false;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const info = await evaluate(cdp, `(() => {
      const viewport = document.querySelector('[data-apple-lyrics-viewport]');
      if (!viewport) return null;
      const number = (value) => Number.parseFloat(value) || 0;
      return {
        serial: viewport.getAttribute('data-apple-lyrics-css-line-transition-serial'),
        startedAt: number(viewport.getAttribute('data-apple-lyrics-css-line-transition-started-at') ?? ''),
        propertyName: viewport.getAttribute('data-apple-lyrics-css-line-transition-property'),
        rowText: viewport.getAttribute('data-apple-lyrics-css-line-transition-row-text'),
        history: (() => {
          try {
            const parsed = JSON.parse(viewport.getAttribute('data-apple-lyrics-css-line-transition-history') ?? '[]');
            return Array.isArray(parsed) ? parsed : [];
          } catch {
            return [];
          }
        })(),
      };
    })()`);
    if (info?.serial && info.serial !== previousSerial && info.startedAt > 0) return info;
    await delay(8);
  }
  if (!required) return null;
  throw new Error("Timed out waiting for Apple lyric CSS line transition start");
}

async function waitUntilBrowserTime(cdp, targetTimeMs) {
  while (true) {
    const now = await evaluate(cdp, "performance.now()");
    const waitMs = targetTimeMs - now;
    if (waitMs <= 0) return;
    await delay(Math.min(Math.max(waitMs, 1), 16));
  }
}

async function nextAnimationFrame(cdp) {
  await evaluate(cdp, "new Promise((resolve) => requestAnimationFrame(() => resolve()))", true);
}

async function sampleLineTransitionFrames(cdp, timings) {
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(7.70)");
  await settle(cdp, 520);
  await resetCssLineTransitionProbe(cdp);
  const before = await sampleStyles(cdp);
  const previousLineTransitionSerial = before.lineTransition?.serial ?? null;
  const previousCssLineTransitionSerial = before.lineTransition?.cssSerial ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(7.76)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('That I just wanna get with you')`, 1000);
  const lineTransition = await waitForLineTransitionUpdate(cdp, previousLineTransitionSerial);
  const cssLineTransition = await waitForCssLineTransitionStart(
    cdp,
    previousCssLineTransitionSerial,
    160,
    { required: false },
  ) ?? {
    serial: null,
    startedAt: lineTransition.startedAt + 40,
    propertyName: "fallback",
    rowText: "That I just wanna get with you",
    history: [],
  };
  const frames = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, cssLineTransition.startedAt + timing);
    if (timing > 0) await nextAnimationFrame(cdp);
    const sample = await sampleStyles(cdp);
    frames.push(summarizeLineTransitionFrame(sample, timing));
  }
  await settle(cdp, 160);
  const after = await sampleStyles(cdp);
  return {
    before: summarizeScrollStyle(before),
    frames,
    after: summarizeScrollStyle(after),
    lineTransition: { ...lineTransition, cssLineTransition },
  };
}

async function sampleNonAdjacentSeek(cdp) {
  const timings = [0, 50, 100, 125, 175, 250, 350, 400, 425];
  await navigate(cdp, fixtureUrlFor(defaultSongId, 8.20));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 520);
  await resetCssLineTransitionProbe(cdp);
  const before = await sampleStyles(cdp);
  const previousScrollSerial = before.scrollAnimation?.serial ?? null;
  const previousCssLineTransitionSerial = before.lineTransition?.cssSerial ?? null;
  const oldActiveIndex = before.activeIndex;
  const beforeOldActiveHeight = (before.rowStack ?? [])
    .find((row) => row.index === oldActiveIndex)
    ?.rowStyle?.rect?.height ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(25.20)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes("Can't help it I want you")`, 1000);
  const scrollAnimation = await waitForScrollAnimationStart(cdp, previousScrollSerial);
  const frames = [];
  for (const timing of timings) {
    await waitUntilBrowserTime(cdp, scrollAnimation.startedAt + timing);
    await nextAnimationFrame(cdp);
    const sample = await sampleStyles(cdp);
    const oldActiveRow = (sample.rowStack ?? []).find((row) => row.index === oldActiveIndex) ?? null;
    frames.push({
      ...summarizeLineTransitionFrame(sample, timing),
      oldActiveRow,
      oldActiveHeight: oldActiveRow?.rowStyle?.rect?.height ?? null,
    });
  }
  const cssLineTransition = await waitForCssLineTransitionStart(cdp, previousCssLineTransitionSerial);
  await settle(cdp, 120);
  const after = await sampleStyles(cdp);
  const metricFrames = withOldActiveReleaseMetrics(
    withCombinedMotionMetrics(frames),
    beforeOldActiveHeight,
  );
  return {
    before: summarizeScrollStyle(before),
    scrollAnimation,
    lineTransition: { cssLineTransition },
    frames: metricFrames,
    after: summarizeScrollStyle(after),
    afterJump: metricFrames[0] ?? null,
    scrollHistory: after.scrollHistory ?? metricFrames[0]?.scrollHistory ?? [],
    releaseRows: (metricFrames[0]?.rowStack ?? [])
      .filter((row) => row.rowStyle?.animationName === "appleLyricRowRelease")
      .map((row) => ({
        index: row.index,
        kind: row.kind,
        text: row.text,
        active: row.active,
        animationName: row.rowStyle?.animationName,
        height: row.rowStyle?.height,
      })),
    rowStack: metricFrames[0]?.rowStack ?? [],
    previousRow: (metricFrames[0]?.rowStack ?? []).find((row) => row.index === (metricFrames[0]?.scrollTargetIndex ?? 0) - 1) ?? null,
    oldActiveRow: (metricFrames[0]?.rowStack ?? []).find((row) => row.wasActiveBeforeSwitch) ?? null,
  };
}

async function sampleAdjacentSeekJump(cdp) {
  await navigate(cdp, fixtureUrlFor(defaultSongId, 6.70));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 520);
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('You see through')`, 1000);
  const before = await sampleStyles(cdp);
  const previousScrollSerial = before.scrollAnimation?.serial ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(7.76)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('That I just wanna get with you')`, 1000);
  await waitForScrollUpdate(cdp, previousScrollSerial);
  await settle(cdp, 40);
  const afterJump = await sampleStyles(cdp);
  return {
    before: summarizeScrollStyle(before),
    afterJump: summarizeScrollStyle(afterJump),
    scrollAnimation: afterJump.scrollAnimation,
    scrollHistory: afterJump.scrollHistory ?? [],
    indexDelta: (afterJump.activeIndex ?? 0) - (before.activeIndex ?? 0),
    positionDelta: (Number.parseFloat(afterJump.fixturePosition ?? "0") || 0) -
      (Number.parseFloat(before.fixturePosition ?? "0") || 0),
  };
}

async function sampleSupplementaryForceScroll(cdp) {
  await setViewport(cdp, 860, 820);
  await navigate(cdp, fixtureUrlFor("crowded-lines", 9.45, 0, { supplementary: 0 }));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 620);
  const before = await sampleStyles(cdp);
  const previousScrollSerial = before.scrollAnimation?.serial ?? null;
  await evaluate(cdp, "window.__setAppleLyricsFixtureSupplementaryVisible?.(true)");
  await waitForExpression(cdp, `document.querySelector('[data-companion-role="translation"][data-apple-supplementary-visible="false"]')`, 1000);
  const revealHiddenStart = await sampleStyles(cdp);
  await waitForExpression(cdp, `document.querySelector('[data-companion-role="translation"][data-apple-supplementary-visible="true"]')`, 1000);
  const revealStart = await sampleStyles(cdp);
  const revealFrames = [0, 300, 620, 750];
  const revealFrameSamples = [];
  const revealStartedAt = revealStart.browserNow;
  for (const timing of revealFrames) {
    await waitUntilBrowserTime(cdp, revealStartedAt + timing);
    await nextAnimationFrame(cdp);
    revealFrameSamples.push(summarizeSupplementaryRevealFrame(await sampleStyles(cdp), timing));
  }
  const afterRevealRender = await sampleStyles(cdp);
  const forceScroll = await waitForScrollUpdate(cdp, previousScrollSerial, 1600);
  if (forceScroll.duration > 0) {
    await waitUntilBrowserTime(cdp, forceScroll.startedAt + forceScroll.duration + 40);
  } else {
    await settle(cdp, 80);
  }
  await nextAnimationFrame(cdp);
  const after = await sampleStyles(cdp);
  const screenshot = await cdp.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  await writeFile(
    join(outDir, "apple-lyrics-supplementary-force-scroll.png"),
    Buffer.from(screenshot.data, "base64"),
  );
  return {
    before: summarizeScrollStyle(before),
    revealHiddenStart: summarizeSupplementaryRevealFrame(revealHiddenStart, -1),
    revealStart: summarizeSupplementaryRevealFrame(revealStart, 0),
    revealFrames: revealFrameSamples,
    afterRevealRender: summarizeScrollStyle(afterRevealRender),
    forceScroll,
    after: summarizeScrollStyle(after),
    companionRolesBefore: before.companionRoles,
    companionRolesAfter: after.companionRoles,
    rowStackAfter: after.rowStack,
  };
}

function summarizeSupplementaryRevealFrame(sample, timingMs) {
  return {
    timingMs,
    fixturePosition: Number.parseFloat(sample.fixturePosition ?? "0") || 0,
    activeText: sample.active?.text ?? null,
    translation: summarizeSupplementaryStyle(sample.translation),
    romaji: summarizeSupplementaryStyle(sample.romaji),
    activeRow: (sample.rowStack ?? []).find((row) => row.active) ?? null,
    rowStack: sample.rowStack,
  };
}

function summarizeLineTransitionFrame(sample, timing) {
  const activeRect = sample.active?.style?.rect ?? null;
  const previousRect = sample.previous?.rect ?? null;
  const nextRect = sample.next?.rect ?? null;
  const activeRow = (sample.rowStack ?? []).find((row) => row.active) ?? null;
  const previousRow = activeRow
    ? (sample.rowStack ?? []).find((row) => row.index === activeRow.index - 1) ?? null
    : null;
  const nextRow = activeRow
    ? (sample.rowStack ?? []).find((row) => row.index === activeRow.index + 1) ?? null
    : null;
  const activeTextRect = activeRow?.visibleTextRect ?? null;
  const previousTextRect = previousRow?.visibleTextRect ?? null;
  const nextTextRect = nextRow?.visibleTextRect ?? null;
  const scrollElapsed = sample.scrollAnimation?.startedAt
    ? Math.max(0, sample.browserNow - sample.scrollAnimation.startedAt)
    : null;
  const lineTransitionStartedAt = sample.lineTransition?.cssStartedAt || sample.lineTransition?.startedAt;
  const lineElapsed = lineTransitionStartedAt
    ? Math.max(0, sample.browserNow - lineTransitionStartedAt)
    : null;
  return {
    timingMs: timing,
    scrollElapsedMs: scrollElapsed,
    lineElapsedMs: lineElapsed,
    text: sample.active.text,
    backdropOpacity: sample.backdrop?.opacity ?? null,
    innerY: sample.numeric.innerY,
    scrollTop: sample.numeric.viewportScrollTop,
    scrollSource: sample.scrollAnimation?.source ?? null,
    cssTransitionHistory: sample.lineTransition?.cssHistory ?? [],
    scrollTargetIndex: sample.scrollAnimation?.targetIndex ?? null,
    scrollTargetRowTop: sample.scrollAnimation?.targetRowTop ?? null,
    scrollTargetRowHeight: sample.scrollAnimation?.targetRowHeight ?? null,
    scrollAnchorPx: sample.scrollAnimation?.anchorPx ?? null,
    scrollOffsetRatio: sample.scrollAnimation?.offsetRatio ?? null,
    scrollBaseScrollTop: sample.scrollAnimation?.baseScrollTop ?? null,
    scrollActiveCount: sample.scrollAnimation?.activeCount ?? null,
    scrollingMaskState: sample.scrollAnimation?.scrolling ?? null,
    viewportMaskImage: sample.viewportBox?.maskImage ?? null,
    viewportWebkitMaskImage: sample.viewportBox?.webkitMaskImage ?? null,
    activeTop: sample.numeric.activeTop,
    activeBottom: activeRect?.bottom ?? null,
    activeTextTop: activeTextRect?.top ?? null,
    activeTextBottom: activeTextRect?.bottom ?? null,
    activeTextCenterY: rectCenterY(activeTextRect),
    activeTextOffsetY: activeTextRect && activeRect
      ? rectCenterY(activeTextRect) - activeRect.top
      : null,
    activeHeight: sample.numeric.activeHeight,
    activeScale: transformScale(sample.active.innerStyle?.transform),
    activePaddingTop: sample.numeric.activePaddingTop,
    activePaddingBottom: sample.numeric.activePaddingBottom,
    activeMarginBottom: sample.numeric.activeMarginBottom,
    activeLineMarginBottom: sample.numeric.activeLineMarginBottom,
    activeColor: sample.active.innerStyle?.color ?? sample.active.style?.color ?? null,
    activeFilter: sample.active.style?.filter ?? null,
    activeBlurPx: cssBlurPx(sample.active.style?.filter),
    activeLineChildren: sample.active.lineChildren,
    companion: summarizeSupplementaryStyle(sample.companion),
    backgroundVocals: summarizeBoxStyle(sample.backgroundVocals),
    backgroundTranslation: summarizeSupplementaryStyle(sample.backgroundTranslation),
    scrollHistory: sample.scrollHistory ?? [],
    previousTop: previousRect?.top ?? null,
    previousBottom: previousRect?.bottom ?? null,
    previousTextTop: previousTextRect?.top ?? null,
    previousTextBottom: previousTextRect?.bottom ?? null,
    previousTextCenterY: rectCenterY(previousTextRect),
    previousHeight: sample.previous?.rect?.height ?? null,
    previousFilter: sample.previous?.filter ?? null,
    previousBlurPx: cssBlurPx(sample.previous?.filter),
    previousScale: transformScale(sample.previousInner?.transform),
    previousPaddingTop: cssPx(sample.previousInner?.paddingTop),
    previousMarginBottom: cssPx(sample.previousInner?.marginBottom),
    previousColor: sample.previousInner?.color ?? sample.previous?.color ?? null,
    previousOpacity: cssPx(sample.previousInner?.opacity),
    previousOrdinaryToken: summarizeLineTokenStyle(sample.previousOrdinaryToken),
    nextTop: nextRect?.top ?? null,
    nextBottom: nextRect?.bottom ?? null,
    nextTextTop: nextTextRect?.top ?? null,
    nextTextBottom: nextTextRect?.bottom ?? null,
    nextTextCenterY: rectCenterY(nextTextRect),
    nextHeight: sample.next?.rect?.height ?? null,
    nextFilter: sample.next?.filter ?? null,
    nextBlurPx: cssBlurPx(sample.next?.filter),
    nextOrdinaryToken: summarizeLineTokenStyle(sample.nextOrdinaryToken),
    rowStack: sample.rowStack,
  };
}

function summarizeContinuousPlaybackFrame(sample, timing) {
  return {
    ...summarizeLineTransitionFrame(sample, timing),
    fixturePosition: Number.parseFloat(sample.fixturePosition ?? "0") || 0,
    activeIndex: sample.activeIndex,
    scrollSerial: sample.scrollAnimation?.serial ?? null,
    scrollActiveCount: sample.scrollAnimation?.activeCount ?? null,
    scrollActiveCountAtStart: sample.scrollAnimation?.activeCountAtStart ?? null,
    backdropBackgroundColor: sample.backdrop?.backgroundColor ?? null,
    backdropBackgroundImage: sample.backdrop?.backgroundImage ?? null,
    columnBackgroundColor: sample.column?.backgroundColor ?? null,
  };
}

function summarizeSupplementaryStyle(style) {
  return style
    ? {
        text: style.text ?? null,
        display: style.display ?? null,
        fontSize: style.fontSize ?? null,
        lineHeight: style.lineHeight ?? null,
        marginTop: style.marginTop ?? null,
        maxHeight: style.maxHeight ?? null,
        opacity: style.opacity ?? null,
        overflow: style.overflow ?? null,
        transform: style.transform ?? null,
        appleKind: style.appleKind ?? null,
        appleVisible: style.appleVisible ?? null,
        rect: style.rect ?? null,
      }
    : null;
}

function summarizeBoxStyle(style) {
  return style
    ? {
        display: style.display ?? null,
        fontSize: style.fontSize ?? null,
        lineHeight: style.lineHeight ?? null,
        marginTop: style.marginTop ?? null,
        maxHeight: style.maxHeight ?? null,
        opacity: style.opacity ?? null,
        overflow: style.overflow ?? null,
        color: style.color ?? null,
        rect: style.rect ?? null,
      }
    : null;
}

function summarizeLineTokenStyle(token) {
  return {
    kind: token?.kind ?? null,
    ...summarizeTokenStyle(token),
  };
}

function withCombinedMotionMetrics(frames, baselinePreviousHeight) {
  if (!Array.isArray(frames) || frames.length === 0) return frames;
  const first = frames[0];
  const firstScrollTop = finiteNumber(first.scrollTop);
  const firstActiveTop = finiteNumber(first.activeTop);
  const firstPreviousHeight = finiteNumber(baselinePreviousHeight) ?? finiteNumber(first.previousHeight);
  if (firstScrollTop === null || firstActiveTop === null || firstPreviousHeight === null) {
    return frames;
  }
  const firstSamplePreviousHeight = finiteNumber(first.previousHeight) ?? firstPreviousHeight;
  const firstSamplePreviousReleaseDeltaPx = firstPreviousHeight - firstSamplePreviousHeight;
  return frames.map((frame) => {
    const scrollTop = finiteNumber(frame.scrollTop) ?? firstScrollTop;
    const activeTop = finiteNumber(frame.activeTop) ?? firstActiveTop;
    const previousHeight = finiteNumber(frame.previousHeight) ?? firstPreviousHeight;
    const scrollOnlyActiveTop = firstActiveTop - (scrollTop - firstScrollTop);
    const layoutResidualPx = activeTop - scrollOnlyActiveTop;
    const previousReleaseDeltaPx = firstPreviousHeight - previousHeight;
    const previousReleaseDeltaSinceFirstPx =
      previousReleaseDeltaPx - firstSamplePreviousReleaseDeltaPx;
    return {
      ...frame,
      scrollOnlyActiveTop,
      layoutResidualPx,
      previousReleaseDeltaPx,
      previousReleaseDeltaSinceFirstPx,
    };
  });
}

function residualReleaseDelta(frame) {
  return finiteNumber(frame?.previousReleaseDeltaSinceFirstPx) ??
    finiteNumber(frame?.previousReleaseDeltaPx) ??
    0;
}

function assertCombinedActiveTopMotion(label, frames, options = {}) {
  const samples = (frames ?? [])
    .filter((frame) =>
      finiteNumber(frame?.activeTop) !== null &&
      finiteNumber(frame?.scrollTop) !== null);
  if (samples.length < 2) {
    failures.push(`${label}: missing active top/scroll samples`);
    return;
  }
  const releaseDelta = options.releaseDelta ?? residualReleaseDelta;
  const tolerancePx = options.tolerancePx ?? 2.5;
  const first = samples[0];
  const firstTop = finiteNumber(first.activeTop);
  const firstScrollTop = finiteNumber(first.scrollTop);
  const firstRelease = finiteNumber(releaseDelta(first)) ?? 0;
  if (firstTop === null || firstScrollTop === null) {
    failures.push(`${label}: invalid first active top/scroll sample`);
    return;
  }

  for (const frame of samples) {
    const scrollTop = finiteNumber(frame.scrollTop);
    const activeTop = finiteNumber(frame.activeTop);
    const release = finiteNumber(releaseDelta(frame)) ?? firstRelease;
    if (scrollTop === null || activeTop === null) continue;
    const expectedTop = firstTop - (scrollTop - firstScrollTop) - (release - firstRelease);
    assertApprox(
      `${label} ${frame.timingMs}ms`,
      activeTop,
      expectedTop,
      tolerancePx,
    );
  }
}

function assertCombinedActiveTextCenterMotion(label, frames, options = {}) {
  const samples = (frames ?? [])
    .filter((frame) =>
      finiteNumber(frame?.activeTextCenterY) !== null &&
      finiteNumber(frame?.activeTextOffsetY) !== null &&
      finiteNumber(frame?.scrollTop) !== null);
  if (samples.length < 2) {
    failures.push(`${label}: missing active visible-text center samples`);
    return;
  }
  const releaseDelta = options.releaseDelta ?? residualReleaseDelta;
  const tolerancePx = options.tolerancePx ?? 4;
  const first = samples[0];
  const last = samples[samples.length - 1];
  const firstCenter = finiteNumber(first.activeTextCenterY);
  const firstOffset = finiteNumber(first.activeTextOffsetY);
  const firstScrollTop = finiteNumber(first.scrollTop);
  const firstRelease = finiteNumber(releaseDelta(first)) ?? 0;
  if (firstCenter === null || firstOffset === null || firstScrollTop === null) {
    failures.push(`${label}: invalid first text-center sample`);
    return;
  }

  for (const frame of samples) {
    const center = finiteNumber(frame.activeTextCenterY);
    const offset = finiteNumber(frame.activeTextOffsetY) ?? firstOffset;
    const scrollTop = finiteNumber(frame.scrollTop);
    const release = finiteNumber(releaseDelta(frame)) ?? firstRelease;
    if (center === null || scrollTop === null) continue;
    const expectedCenter =
      firstCenter -
      (scrollTop - firstScrollTop) -
      (release - firstRelease) +
      (offset - firstOffset);
    assertApprox(
      `${label} ${frame.timingMs}ms`,
      center,
      expectedCenter,
      tolerancePx,
    );
  }

  const totalDelta = (finiteNumber(last?.activeTextCenterY) ?? firstCenter) - firstCenter;
  const direction = Math.sign(totalDelta);
  if (direction !== 0) {
    for (let i = 1; i < samples.length; i++) {
      const previous = finiteNumber(samples[i - 1]?.activeTextCenterY);
      const current = finiteNumber(samples[i]?.activeTextCenterY);
      if (previous === null || current === null) continue;
      const step = current - previous;
      if (step * direction < -8) {
        failures.push(
          `${label}: visible text center reverses by ${Math.abs(step).toFixed(2)}px at ${samples[i]?.timingMs}ms`,
        );
      }
    }
  }
}

function assertAdjacentSiblingTextMotion(label, frames, options = {}) {
  const samples = (frames ?? [])
    .filter((frame) =>
      finiteNumber(frame?.previousTextCenterY) !== null &&
      finiteNumber(frame?.previousTop) !== null &&
      finiteNumber(frame?.previousHeight) !== null &&
      finiteNumber(frame?.activeHeight) !== null &&
      finiteNumber(frame?.nextTextCenterY) !== null &&
      finiteNumber(frame?.nextTop) !== null &&
      finiteNumber(frame?.scrollTop) !== null);
  if (samples.length < 2) {
    failures.push(`${label}: missing adjacent sibling text samples`);
    return;
  }

  const tolerancePx = options.tolerancePx ?? 3.5;
  const first = samples[0];
  const firstScrollTop = finiteNumber(first.scrollTop);
  const firstPreviousCenter = finiteNumber(first.previousTextCenterY);
  const firstPreviousTop = finiteNumber(first.previousTop);
  const firstPreviousHeight = finiteNumber(first.previousHeight);
  const firstActiveHeight = finiteNumber(first.activeHeight);
  const firstNextCenter = finiteNumber(first.nextTextCenterY);
  const firstNextTop = finiteNumber(first.nextTop);
  if (
    firstScrollTop === null ||
    firstPreviousCenter === null ||
    firstPreviousTop === null ||
    firstPreviousHeight === null ||
    firstActiveHeight === null ||
    firstNextCenter === null ||
    firstNextTop === null
  ) {
    failures.push(`${label}: invalid first adjacent sibling sample`);
    return;
  }

  const firstPreviousOffset = firstPreviousCenter - firstPreviousTop;
  const firstNextOffset = firstNextCenter - firstNextTop;
  for (const frame of samples) {
    const scrollTop = finiteNumber(frame.scrollTop);
    const previousCenter = finiteNumber(frame.previousTextCenterY);
    const previousTop = finiteNumber(frame.previousTop);
    const previousHeight = finiteNumber(frame.previousHeight);
    const activeHeight = finiteNumber(frame.activeHeight);
    const nextCenter = finiteNumber(frame.nextTextCenterY);
    const nextTop = finiteNumber(frame.nextTop);
    if (
      scrollTop === null ||
      previousCenter === null ||
      previousTop === null ||
      previousHeight === null ||
      activeHeight === null ||
      nextCenter === null ||
      nextTop === null
    ) {
      continue;
    }
    const scrollDelta = scrollTop - firstScrollTop;
    const previousOffsetDelta = (previousCenter - previousTop) - firstPreviousOffset;
    const oldCurrentReleaseDelta = firstPreviousHeight - previousHeight;
    const activeExpandDelta = activeHeight - firstActiveHeight;
    const nextOffsetDelta = (nextCenter - nextTop) - firstNextOffset;

    assertApprox(
      `${label} previous ${frame.timingMs}ms`,
      previousCenter,
      firstPreviousCenter - scrollDelta + previousOffsetDelta,
      tolerancePx,
    );
    assertApprox(
      `${label} next ${frame.timingMs}ms`,
      nextCenter,
      firstNextCenter - scrollDelta - oldCurrentReleaseDelta + activeExpandDelta + nextOffsetDelta,
      tolerancePx,
    );
  }
}

function expectedAppleVisibleTop(scrollAnimation) {
  const topSpacerHeight = finiteNumber(scrollAnimation?.topSpacerHeight);
  const topMargin = finiteNumber(scrollAnimation?.topMargin);
  if (topSpacerHeight === null || topMargin === null) return null;
  return topSpacerHeight + topMargin;
}

function expectedAppleScrollTarget(scrollAnimation) {
  const targetRowTop = finiteNumber(scrollAnimation?.targetRowTop);
  const topSpacerHeight = finiteNumber(scrollAnimation?.topSpacerHeight);
  const topMargin = finiteNumber(scrollAnimation?.topMargin);
  const baseScrollTop = finiteNumber(scrollAnimation?.baseScrollTop);
  if (
    targetRowTop === null ||
    topSpacerHeight === null ||
    topMargin === null ||
    baseScrollTop === null
  ) {
    return null;
  }
  return targetRowTop - topSpacerHeight - topMargin + baseScrollTop;
}

function withOldActiveReleaseMetrics(frames, baselineHeight) {
  if (!Array.isArray(frames) || frames.length === 0) return frames;
  const firstOldActiveHeight = finiteNumber(baselineHeight) ?? finiteNumber(frames[0].oldActiveHeight);
  if (firstOldActiveHeight === null) return frames;
  const firstSampleOldActiveHeight = finiteNumber(frames[0].oldActiveHeight) ?? firstOldActiveHeight;
  const firstSampleOldActiveReleaseDeltaPx = firstOldActiveHeight - firstSampleOldActiveHeight;
  return frames.map((frame) => {
    const oldActiveHeight = finiteNumber(frame.oldActiveHeight) ?? firstOldActiveHeight;
    const oldActiveReleaseDeltaPx = firstOldActiveHeight - oldActiveHeight;
    return {
      ...frame,
      oldActiveReleaseDeltaPx,
      oldActiveReleaseDeltaSinceFirstPx:
        oldActiveReleaseDeltaPx - firstSampleOldActiveReleaseDeltaPx,
    };
  });
}

function finiteNumber(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function assertScrollMovesTowardTargetBeforeLayout(label, firstFrame, movingFrame, scrollAnimation) {
  const firstScrollTop = finiteNumber(firstFrame?.scrollTop);
  const movingScrollTop = finiteNumber(movingFrame?.scrollTop);
  const targetScrollTop = finiteNumber(scrollAnimation?.target);
  if (firstScrollTop == null || movingScrollTop == null || targetScrollTop == null) {
    assertTruthy(label, false);
    return;
  }
  const expectedDirection = Math.sign(targetScrollTop - firstScrollTop);
  const actualDelta = movingScrollTop - firstScrollTop;
  assertTruthy(
    label,
    expectedDirection === 0
      ? Math.abs(actualDelta) <= 0.5
      : Math.sign(actualDelta) === expectedDirection && Math.abs(actualDelta) >= 0.5,
  );
}

async function captureScrollTransitionScreenshots(cdp, timings, screenshotPrefix = "apple-lyrics-scroll") {
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(7.70)");
  await settle(cdp, 520);
  await evaluate(cdp, "window.__setAppleLyricsFixturePosition?.(7.76)");
  await waitForExpression(cdp, `document.querySelector('[data-lyric-row-kind="yrc"][data-active="1"]')?.dataset.lyricText?.includes('That I just wanna get with you')`, 1000);
  const startedAt = Date.now();
  const screenshots = [];
  for (const timing of timings) {
    const waitMs = Math.max(0, startedAt + timing - Date.now());
    if (waitMs > 0) await delay(waitMs);
    const screenshot = await cdp.send("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: false,
    });
    const screenshotName = `${screenshotPrefix}-${String(timing).padStart(3, "0")}ms.png`;
    await writeFile(join(outDir, screenshotName), Buffer.from(screenshot.data, "base64"));
    screenshots.push({ timingMs: timing, screenshotName });
  }
  await settle(cdp, 120);
  return screenshots;
}

async function sampleTokenAnimationFrames(cdp) {
  const appleTimelinePosition = (audioPositionSec) =>
    Number((audioPositionSec - APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC).toFixed(3));
  const frameSpecs = [
    { label: "ordinary-gradient-before-lift", positionSec: appleTimelinePosition(8.06) },
    { label: "ordinary-mid-lift", positionSec: appleTimelinePosition(8.27) },
    { label: "ordinary-finished", positionSec: appleTimelinePosition(8.45) },
    { label: "slow-before", positionSec: appleTimelinePosition(9.17) },
    { label: "slow-first-rise-mid", positionSec: appleTimelinePosition(9.43) },
    { label: "slow-first-peak", positionSec: appleTimelinePosition(9.68), screenshotName: "apple-lyrics-token-slow-peak.png" },
    { label: "slow-first-release-mid", positionSec: appleTimelinePosition(9.93) },
    { label: "slow-first-complete-tail-start", positionSec: appleTimelinePosition(10.19) },
  ];
  const frames = [];
  for (const spec of frameSpecs) {
    const sample = await sampleAtPosition(cdp, spec.positionSec, 180);
    if (spec.screenshotName) {
      const screenshot = await captureNamedScreenshot(cdp, spec.screenshotName);
      assertScreenshotHasNoRectangularGlowBlock(
        `slow screenshot ${spec.label}`,
        screenshot,
        sample.slowToken.firstLetterStyle?.rect,
      );
    }
    frames.push({
      label: spec.label,
      positionSec: spec.positionSec,
      screenshotName: spec.screenshotName ?? null,
      activeText: sample.active.text,
      ordinary: summarizeTokenStyle(sample.ordinaryToken),
      slow: summarizeSlowTokenStyle(sample.slowToken),
    });
  }
  return frames;
}

async function sampleCjkTokenAnimationFrames(cdp) {
  const appleTimelinePosition = (audioPositionSec) =>
    Number((audioPositionSec - APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC).toFixed(3));
  const frameSpecs = [
    { label: "cjk-slow-before", positionSec: appleTimelinePosition(31.0) },
    { label: "cjk-slow-rise-mid", positionSec: appleTimelinePosition(31.25) },
    { label: "cjk-slow-peak", positionSec: appleTimelinePosition(31.5), screenshotName: "apple-lyrics-token-cjk-peak.png" },
    { label: "cjk-slow-release-mid", positionSec: appleTimelinePosition(31.75) },
    { label: "cjk-slow-complete", positionSec: appleTimelinePosition(32.17) },
  ];
  const frames = [];
  for (const spec of frameSpecs) {
    const sample = await sampleAtPosition(cdp, spec.positionSec, 180);
    if (spec.screenshotName) {
      const screenshot = await captureNamedScreenshot(cdp, spec.screenshotName);
      assertScreenshotHasNoRectangularGlowBlock(
        `cjk screenshot ${spec.label}`,
        screenshot,
        sample.slowToken.firstLetterStyle?.rect,
      );
    }
    frames.push({
      label: spec.label,
      positionSec: spec.positionSec,
      screenshotName: spec.screenshotName ?? null,
      activeText: sample.active.text,
      activeFontWeight: sample.active.innerStyle?.fontWeight ?? null,
      activeFontFamily: sample.active.innerStyle?.fontFamily ?? null,
      activeScale: transformScale(sample.active.innerStyle?.transform),
      slow: summarizeSlowTokenStyle(sample.slowToken),
      translation: summarizeTokenStyle({ text: "translation", style: sample.translation }),
    });
  }
  return frames;
}

async function sampleHeldClockSmoothing(cdp) {
  await navigate(cdp, fixtureUrlFor(defaultSongId, 7.82, 1, { clock: "held" }));
  await waitForFixture(cdp);
  await hideNextDevOverlay(cdp);
  await settle(cdp, 80);
  const first = await sampleStyles(cdp);
  await settle(cdp, 120);
  const second = await sampleStyles(cdp);
  return {
    first: {
      fixturePosition: first.fixturePosition,
      activeText: first.active.text,
      ordinary: summarizeTokenStyle(first.ordinaryToken),
    },
    second: {
      fixturePosition: second.fixturePosition,
      activeText: second.active.text,
      ordinary: summarizeTokenStyle(second.ordinaryToken),
    },
  };
}

function summarizeTokenStyle(token) {
  const paintStyle = token?.foregroundStyle ?? token?.style;
  const glowStyle = token?.glowStyle ?? null;
  return {
    text: token?.text ?? null,
    scale: transformScale(token?.style?.transform),
    y: transformTranslateY(token?.style?.transform),
    gradientProgress: gradientFirstStopPercent(paintStyle?.backgroundImage),
    backgroundImage: paintStyle?.backgroundImage ?? null,
    inlineBackgroundImage: paintStyle?.inlineBackgroundImage ?? null,
    inlineGradientProgress: paintStyle?.inlineGradientProgress ?? null,
    inlineGradientColor: paintStyle?.inlineGradientColor ?? null,
    inlineGradientAlphaActive: paintStyle?.inlineGradientAlphaActive ?? null,
    inlineGradientAlpha: paintStyle?.inlineGradientAlpha ?? null,
    inlineTextShadowBlurRadius: paintStyle?.inlineTextShadowBlurRadius ?? null,
    inlineTextShadowOpacity: paintStyle?.inlineTextShadowOpacity ?? null,
    textShadow: glowStyle?.textShadow ?? token?.style?.textShadow ?? null,
    shadow: textShadowComponents(glowStyle?.textShadow ?? token?.style?.textShadow),
    textFillColor: paintStyle?.webkitTextFillColor ?? null,
    color: paintStyle?.color ?? token?.style?.color ?? null,
    shellBackgroundImage: token?.style?.backgroundImage ?? null,
    shellTextShadow: token?.style?.textShadow ?? null,
    foregroundTextShadow: token?.foregroundStyle?.textShadow ?? null,
    glowBackgroundImage: glowStyle?.backgroundImage ?? null,
    glowTextFillColor: glowStyle?.webkitTextFillColor ?? null,
  };
}

function summarizeSlowTokenStyle(token) {
  const letters = Array.isArray(token?.letterStyles)
    ? token.letterStyles.map((letter) => ({
      index: letter.index,
      text: letter.text,
      ...summarizeTokenStyle({
        text: letter.text,
        style: letter.style,
        foregroundStyle: letter.foregroundStyle,
        glowStyle: letter.glowStyle,
      }),
    }))
    : [];
  return {
    ...summarizeTokenStyle(token),
    letters,
    first: letters[0] ?? null,
    second: letters[1] ?? null,
    tail: letters[letters.length - 1] ?? null,
  };
}

async function waitForExpression(cdp, expression, timeoutMs) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const result = await evaluate(cdp, `Boolean(${expression})`);
    if (result) return;
    await delay(8);
  }
  throw new Error(`Timed out waiting for expression: ${expression}`);
}

function summarizeScrollStyle(sample) {
  return {
    text: sample.active.text,
    innerY: sample.numeric.innerY,
    scrollTop: sample.numeric.viewportScrollTop,
    scrollSource: sample.scrollAnimation?.source ?? null,
    scrollForce: sample.scrollAnimation?.force ?? null,
    scrollActiveCountAtStart: sample.scrollAnimation?.activeCountAtStart ?? null,
    scrollActiveCount: sample.scrollAnimation?.activeCount ?? null,
    scrollingMaskState: sample.scrollAnimation?.scrolling ?? null,
    viewportMaskImage: sample.viewportBox?.maskImage ?? null,
    viewportWebkitMaskImage: sample.viewportBox?.webkitMaskImage ?? null,
    scrollHistory: sample.scrollHistory ?? [],
    geometryActiveIdx: sample.scrollAnimation?.geometryActiveIdx ?? null,
    geometryScrollTop: sample.scrollAnimation?.geometryScrollTop ?? null,
    viewportTop: sample.numeric.viewportTop,
    viewportHeight: sample.numeric.viewportHeight,
    topSpacerHeight: sample.numeric.topSpacerHeight,
    activeTop: sample.numeric.activeTop,
    activeHeight: sample.numeric.activeHeight,
    activeMarginBottom: sample.numeric.activeMarginBottom,
    previousHeight: sample.previous?.rect?.height ?? null,
    previousMarginBottom: cssPx(sample.previous?.marginBottom),
    previousLineMarginBottom: cssPx(sample.previousInner?.marginBottom),
    previousOrdinaryToken: summarizeLineTokenStyle(sample.previousOrdinaryToken),
    nextOrdinaryToken: summarizeLineTokenStyle(sample.nextOrdinaryToken),
    activeIndex: sample.activeIndex,
  };
}

function summarizeTransitionSample(sample) {
  return {
    dataOpen: sample.transitionRoot?.dataOpen ?? null,
    sourceCover: sample.transitionSourceCover?.rect ?? null,
    cover: sample.cover?.rect ?? null,
    backdrop: Boolean(sample.backdrop),
    column: sample.column?.rect ?? null,
    activeText: sample.active?.text ?? null,
  };
}

async function evaluate(cdp, expression, awaitPromise = false) {
  const response = await cdp.send("Runtime.evaluate", {
    expression,
    awaitPromise,
    returnByValue: true,
  });
  if (response.exceptionDetails) {
    throw new Error(response.exceptionDetails.text ?? "Runtime.evaluate failed");
  }
  return response.result?.value;
}

function assertViewportSample(spec, sample) {
  assertEqual(`css var cover column ${spec.width}`, sample.cssVars.coverColumnWidth, spec.coverColumnCss);
  assertEqual(`css var lyrics column ${spec.width}`, sample.cssVars.lyricsColumnWidth, spec.columnCss);
  assertEqual(`css var page padding ${spec.width}`, sample.cssVars.pagePadding, spec.pagePaddingCss);
  assertEqual(`auto scroll inactive gaussian blur ${spec.width}`, sample.cssVars.inactiveGaussianBlur, "2px");
  assertEqual(`auto scroll old-line fade target ${spec.width}`, sample.cssVars.displaySyncedLineOpacity, "0");
  assertEqual(`fullscreen line animation play-state var ${spec.width}`, sample.cssVars.lineAnimationPlayState, "running");
  assertEqual(`Apple scroll viewport disables browser anchoring ${spec.width}`, sample.viewportBox.overflowAnchor, "none");
  assertEqual(`active synced-line tag ${spec.width}`, sample.active.tagName, "RUBY");
  assertIncludes(`active synced-line class ${spec.width}`, sample.active.className, "display-synced-line");
  assertIncludes(`active synced-line current class ${spec.width}`, sample.active.className, "is-current");
  assertEqual(`active line button tag ${spec.width}`, sample.active.innerTagName, "BUTTON");
  assertIncludes(`active line class ${spec.width}`, sample.active.innerClassName, "line");
  assertActiveIsOnlyScrollTarget(`viewport active scroll target ${spec.width}`, sample.rowStack);
  assertAppleFirstRowClass(`viewport first-row class ${spec.width}`, sample.rowStack);
  assertAppleWillChangePlacement(`viewport will-change placement ${spec.width}`, sample.rowStack);
  assertNotEqual(`backdrop color follows artwork ${spec.width}`, sample.backdrop.backgroundColor, "rgb(0, 0, 0)");
  assertIncludes(`backdrop position ${spec.width}`, sample.backdrop.backgroundPosition, "50% 50%");
  assertIncludes(`backdrop size ${spec.width}`, sample.backdrop.backgroundSize, "cover");
  assertEqual(`backdrop opacity ${spec.width}`, sample.backdrop.opacity, "1");
  assertEqual(`backdrop uses opaque Apple color field ${spec.width}`, sample.backdrop.backgroundImage, "none");
  assertTruthy(`backdrop blur artwork present ${spec.width}`, sample.backdropArtwork);
  assertIncludes(`desktop backdrop blur artwork image ${spec.width}`, sample.backdropArtwork?.backgroundImage ?? "", "url(");
  assertIncludes(`backdrop blur filter ${spec.width}`, sample.backdropArtwork?.filter, "blur(");
  assertIncludes(`backdrop saturate filter ${spec.width}`, sample.backdropArtwork?.filter, "saturate(");
  assertIncludes(`backdrop brightness filter ${spec.width}`, sample.backdropArtwork?.filter, "brightness(");
  assertEqual(`backdrop artwork opacity ${spec.width}`, sample.backdropArtwork?.opacity, "0");
  assertTruthy(`backdrop veil present ${spec.width}`, sample.backdropVeil);
  assertEqual(`backdrop veil is transparent ${spec.width}`, sample.backdropVeil?.backgroundColor, "rgba(0, 0, 0, 0)");
  assertTruthy(`lyric column veil present ${spec.width}`, sample.columnVeil);
  assertEqual(`lyric column veil is transparent ${spec.width}`, sample.columnVeil?.backgroundColor, "rgba(0, 0, 0, 0)");
  assertEqual(`lyric column veil opacity ${spec.width}`, sample.columnVeil?.opacity, "0");
  assertEqual(`lyric column itself transparent ${spec.width}`, sample.column.backgroundColor, "rgba(0, 0, 0, 0)");
  assertEqual(`lyric column uses plus-lighter ${spec.width}`, sample.column.mixBlendMode, "plus-lighter");
  assertEqual(`lyric text layer stays normal ${spec.width}`, sample.lyricsBlendLayer?.mixBlendMode, "normal");
  assertBetween(
    `lyric column veil covers left edge ${spec.width}`,
    sample.column?.rect?.left - sample.columnVeil?.rect?.left,
    0,
    Number.POSITIVE_INFINITY,
  );
  assertBetween(
    `lyric column veil covers right edge ${spec.width}`,
    sample.columnVeil?.rect?.right - sample.column?.rect?.right,
    0,
    Number.POSITIVE_INFINITY,
  );
  assertApprox(
    `lyric column veil reaches viewport right ${spec.width}`,
    sample.columnVeil?.rect?.right,
    sample.viewport.width,
    1.5,
  );
  assertIncludes(`cover dissolve mask ${spec.width}`, sample.cover?.maskImage, "linear-gradient");
  assertIncludes(`cover artwork shadow ${spec.width}`, sample.cover?.boxShadow, "0px 4px 10px");
  assertTruthy(`cover halo present ${spec.width}`, sample.coverHalo);
  assertIncludes(`cover halo mask ${spec.width}`, sample.coverHalo?.maskImage, "radial-gradient");
  assertIncludes(`cover halo blur ${spec.width}`, sample.coverHalo?.filter, "blur(");
  assertIncludes(`cover halo radiosity drop shadow ${spec.width}`, sample.coverHalo?.filter, "drop-shadow(");
  assertEqual(`cover halo opacity ${spec.width}`, sample.coverHalo?.opacity, "0.4");
  assertEqual(`cover halo avoids square box shadow ${spec.width}`, sample.coverHalo?.boxShadow, "none");
  assertTruthy(`lyrics text blend layer present ${spec.width}`, sample.lyricsBlendLayer);
  assertEqual(`lyrics text blend mode ${spec.width}`, sample.lyricsBlendLayer?.mixBlendMode, "normal");
  assertApprox(`column left ${spec.width}`, sample.numeric.columnLeft, spec.columnLeftPx, 1.5);
  assertApprox(`column width ${spec.width}`, sample.numeric.columnWidth, Math.min(spec.columnTrackPx, 972.8), 1.5);
  assertApprox(`Apple amp lyrics viewport top ${spec.width}`, sample.numeric.viewportTop, spec.height * 0.1, 1.5);
  assertApprox(`Apple amp lyrics viewport height ${spec.width}`, sample.numeric.viewportHeight, spec.height * 0.8, 1.5);
  assertApprox(`font size ${spec.width}`, sample.numeric.activeFontSize, spec.font, 0.2);
  assertApprox(`line height ${spec.width}`, sample.numeric.activeLineHeight, spec.font * spec.lineHeight, 1.2);
  assertApprox(`active padding top ${spec.width}`, sample.numeric.activePaddingTop, 12, 0.2);
  assertApprox(`active padding bottom ${spec.width}`, sample.numeric.activePaddingBottom, 12, 0.2);
  assertApprox(`outer row margin bottom ${spec.width}`, sample.numeric.activeMarginBottom, 0, 0.2);
  assertApprox(`line margin bottom ${spec.width}`, sample.numeric.activeLineMarginBottom, spec.margin, 0.75);
  assertEqual(`outer row display ${spec.width}`, sample.active.style.display, "block");
  assertEqual(`outer row line-height ${spec.width}`, sample.active.style.lineHeight, "0px");
  assertEqual(`outer row keeps Apple auto height ${spec.width}`, sample.active.style.minHeight, "0px");
  assertEqual(`line display ${spec.width}`, sample.active.innerStyle.display, "inline-block");
  assertEqual(`line uses Apple content-box sizing ${spec.width}`, sample.active.innerStyle.boxSizing, "content-box");
  assertEqual(`line vertical align ${spec.width}`, sample.active.innerStyle.verticalAlign, "baseline");
  const lineChildren = sample.active.lineChildren ?? [];
  assertIncludes(
    `line direct child primary vocals ${spec.width}`,
    lineChildren[0]?.className ?? "",
    "primary-vocals",
  );
  assertEqual(
    `line direct child primary vocals marker ${spec.width}`,
    lineChildren[0]?.appleVocals,
    "primary-vocals",
  );
  assertTruthy(
    `line has no anonymous direct wrapper ${spec.width}`,
    lineChildren.every((child) => Boolean(child.className || child.companionRole || child.appleKind)),
  );
  assertAppleRowsUseBlock(`viewport row block display ${spec.width}`, sample.rowStack);
  const currentScale = transformScale(sample.active.innerStyle.transform);
  const activeSupplementarySafetyGap = appleSupplementarySafetyGapPx([
    sample.translation ? "translation" : null,
    sample.romaji ? "romaji" : null,
    sample.backgroundTranslation ? "background-translation" : null,
  ].filter(Boolean), spec.font);
  if (sample.translation) {
    assertTruthy(
      `translation is direct secondary child ${spec.width}`,
      lineChildren.some((child) => child.companionRole === "translation" && child.className.includes("secondary")),
    );
    assertApprox(
      `translation secondary font ratio ${spec.width}`,
      cssPx(sample.translation.fontSize),
      appleSecondaryFontPx(spec.font),
      0.05,
    );
  }
  if (sample.romaji) {
    assertTruthy(
      `romaji is direct static supplementary child ${spec.width}`,
      lineChildren.some((child) => child.companionRole === "romaji" && child.className.includes("static-supplementary")),
    );
    assertApprox(
      `romaji static supplementary font ratio ${spec.width}`,
      cssPx(sample.romaji.fontSize),
      appleStaticSupplementaryFontPx(spec.font),
      0.05,
    );
  }
  assertEqual(
    `active supplementary safety data ${spec.width}`,
    sample.active.supplementarySafetyGap,
    String(activeSupplementarySafetyGap),
  );
  const expectedActiveLineLayoutHeight =
    (sample.active.innerStyle.rect.height / Math.max(currentScale, 0.001)) +
    sample.numeric.activeLineMarginBottom;
  assertBetween(
    `active height lower bound ${spec.width}`,
    sample.numeric.activeHeight,
    spec.lineBox + 24 + spec.margin - 2,
    Number.POSITIVE_INFINITY,
  );
  assertApprox(
    `active row height follows .line layout ${spec.width}`,
    sample.numeric.activeHeight,
    expectedActiveLineLayoutHeight,
    1.5,
  );
  assertTransformScale(`current scale ${spec.width}`, sample.active.innerStyle.transform, 1.05, 0.01);
  assertIncludes(`font family ${spec.width}`, sample.active.innerStyle.fontFamily, "PingFang");
  assertIncludes(`line transition ${spec.width}`, sample.active.innerStyle.transition, "height 0.4s linear");
  assertNotIncludes(`line scale has no independent transition ${spec.width}`, sample.active.innerStyle.transition, "transform");
  assertNotIncludes(`line alpha has no independent transition ${spec.width}`, sample.active.innerStyle.transition, "color");
  assertIncludes(`line padding transition ${spec.width}`, sample.active.innerStyle.transition, "padding 0.1s ease-in-out");
  assertNotIncludes(`line transition has no custom opacity ${spec.width}`, sample.active.innerStyle.transition, "opacity");
  assertNotIncludes(`line transition has no custom filter ${spec.width}`, sample.active.innerStyle.transition, "filter");
  assertEqual(`current line fullscreen animation play state ${spec.width}`, sample.active.innerStyle.animationPlayState, "running");
  assertEqual(
    `current line play state uses Apple var ${spec.width}`,
    sample.active.innerStyle.inlineAnimationPlayState,
    "var(--line-animation-play-state, paused)",
  );
  assertIncludes(`outer row filter transition ${spec.width}`, sample.active.style.transition, "filter 0.25s linear");
  assertEqual(`current row has no inactive blur ${spec.width}`, sample.active.style.filter, "none");
  assertEqual(`previous row auto-scroll inactive blur ${spec.width}`, sample.previous?.filter, "blur(2px)");
  assertEqual(`next row auto-scroll inactive blur ${spec.width}`, sample.next?.filter, "blur(2px)");
  assertEqual(`current line has no fade-out animation ${spec.width}`, sample.active.innerStyle.animationName, "none");
  assertEqual(
    `current line animation-name uses Apple var ${spec.width}`,
    sample.active.innerStyle.inlineAnimationName,
    "var(--line-animation-name, none)",
  );
  assertEqual(`current row line-animation var empty ${spec.width}`, sample.active.style.lineAnimationNameVar, "");
  assertEqual(`next row line-animation var empty ${spec.width}`, sample.next?.lineAnimationNameVar, "");
  assertNotIncludes(
    `line color transition has no custom easing ${spec.width}`,
    sample.active.innerStyle.transition ?? "",
    "color 0.1s cubic-bezier",
  );
  assertIncludes(`active token color transition ${spec.width}`, sample.ordinaryToken?.style?.transition ?? "", "color 0.1s");
  assertNotIncludes(
    `active token color transition has no custom easing ${spec.width}`,
    sample.ordinaryToken?.style?.transition ?? "",
    "cubic-bezier",
  );
  assertEqual(`primary vocals class ${spec.width}`, sample.primaryVocals?.rect ? true : false, true);
  assertNotIncludes(
    `primary vocals color transition has no custom easing ${spec.width}`,
    sample.primaryVocals?.transition ?? "",
    "cubic-bezier",
  );
  assertEqual(`ordinary token syllable class ${spec.width}`, sample.ordinaryToken?.className, "syllable");
  assertEqual(`ordinary token Apple syllable marker ${spec.width}`, sample.ordinaryToken?.appleSyllable, "true");
  assertEqual(`ordinary token group tag ${spec.width}`, sample.ordinaryTokenGroup?.tagName, "SPAN");
  assertIncludes(`ordinary token group class ${spec.width}`, sample.ordinaryTokenGroup?.className ?? "", "group");
  assertIncludes(`ordinary token group trailing class ${spec.width}`, sample.ordinaryTokenGroup?.className ?? "", "trailing-whitespace");
  assertEqual(`ordinary token group trailing marker ${spec.width}`, sample.ordinaryTokenGroup?.trailingWhitespace, "true");
  assertApprox(`ordinary token group margin removed ${spec.width}`, cssPx(sample.ordinaryTokenGroup?.style?.marginRight), 0, 0.1);
  assertEqual(`ordinary token main tag ${spec.width}`, sample.ordinaryTokenMain?.tagName, "DIV");
  assertEqual(`ordinary token main class ${spec.width}`, sample.ordinaryTokenMain?.className, "main");
  assertEqual(`ordinary token main after content ${spec.width}`, sample.ordinaryTokenMain?.afterStyle?.content, '""');
  assertBetween(`ordinary token trailing after margin ${spec.width}`, cssPx(sample.ordinaryTokenMain?.afterStyle?.marginRight), 1, Number.POSITIVE_INFINITY);
  assertEqual(`ordinary token rendered text strips trailing whitespace ${spec.width}`, sample.ordinaryToken?.renderedText, "That");
  assertEqual(`next inactive row keeps timed syllable DOM ${spec.width}`, sample.nextOrdinaryToken?.kind, "ordinary");
  assertNotIncludes(`next inactive row is not static token DOM ${spec.width}`, sample.nextOrdinaryToken?.kind ?? "", "static");
  assertTruthy(`previous line exists for fade-out ${spec.width}`, sample.previousInner);
  assertEqual(`previous row line-animation var ${spec.width}`, sample.previous?.lineAnimationNameVar, "appleLyricFadeOut");
  assertEqual(
    `previous line animation-name uses Apple var ${spec.width}`,
    sample.previousInner?.inlineAnimationName,
    "var(--line-animation-name, none)",
  );
  assertEqual(`previous line fade-out animation ${spec.width}`, sample.previousInner?.animationName, "appleLyricFadeOut");
  assertEqual(`previous line fade-out duration ${spec.width}`, sample.previousInner?.animationDuration, "1s");
  assertEqual(`previous line fade-out delay ${spec.width}`, sample.previousInner?.animationDelay, "0s");
  assertEqual(`previous line fade-out play state ${spec.width}`, sample.previousInner?.animationPlayState, "running");
  assertEqual(
    `previous line play state uses Apple var ${spec.width}`,
    sample.previousInner?.inlineAnimationPlayState,
    "var(--line-animation-play-state, paused)",
  );
	  assertEqual(`previous line fade-out timing ${spec.width}`, sample.previousInner?.animationTimingFunction, "linear");
	  assertEqual(`previous line fade-out fill mode ${spec.width}`, sample.previousInner?.animationFillMode, "forwards");
	  assertNoVerticalOverlap(`viewport row stack ${spec.width}`, sample.previous?.rect, sample.active?.style?.rect, sample.next?.rect);
	  assertNoVisibleTextOverlap(`viewport visual text stack ${spec.width}`, sample.rowStack);
	  assertVisibleTextGap(
	    `viewport readable text gap ${spec.width}`,
	    sample.rowStack,
	    Math.max(24, spec.margin - 9),
	  );
	}

function assertTranslationAndTokenSample(sample) {
  assertIncludes("active text at translation sample", sample.active.text ?? "", "That I just wanna get with you");
  assertTruthy("translation visible on every line", sample.translation);
  assertEqual("translation Apple kind", sample.translation.appleKind, "secondary");
  assertEqual("translation Apple visible state", sample.translation.appleVisible, "true");
  assertApprox(
    "translation fullscreen secondary font ratio",
    cssPx(sample.translation.fontSize),
    appleSecondaryFontPx(sample.numeric.activeFontSize),
    0.05,
  );
  assertEqual("translation opacity", sample.translation.opacity, "0.45");
  assertApprox(
    "translation whole-line capped reveal height",
    cssPx(sample.translation.maxHeight),
    appleSupplementaryVisibleBoxPx("translation", sample.numeric.activeFontSize),
    0.05,
  );
  assertEqual("translation stays clipped inside Apple reveal box", sample.translation.overflow, "hidden");
  assertIncludes("translation reveal transition max-height", sample.translation.transition, "max-height 0.6s");
  assertIncludes("translation reveal transition transform", sample.translation.transition, "transform 0.6s");
  assertApprox("translation reveal final y", transformTranslateY(sample.translation.transform), 0, 0.1);
  assertTruthy("ordinary token present", sample.ordinaryToken.text);
  assertTruthy("slow token present", sample.slowToken.text);
  assertIncludes("slow token text", sample.slowToken.text ?? "", "wanna");
  assertNotEqual("slow letter is moving/scaling", sample.slowToken.firstLetterStyle.transform, "none");
}

function appleSecondaryFontPx(activeFontPx) {
  return activeFontPx * (activeFontPx >= 38 ? 0.42 : 0.54);
}

function appleStaticSupplementaryFontPx(activeFontPx) {
  return activeFontPx * (activeFontPx >= 38 ? 0.5 : 0.64);
}

function appleSupplementaryVisibleBoxPx(role, activeFontPx) {
  const fontPx = role === "background-translation"
    ? 12
    : role === "romaji"
      ? appleStaticSupplementaryFontPx(activeFontPx)
      : appleSecondaryFontPx(activeFontPx);
  const lineHeightPx = fontPx * 1.2;
  if (!Number.isFinite(lineHeightPx) || lineHeightPx <= 0) {
    return APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX;
  }
  const wholeLines = Math.max(
    1,
    Math.floor(APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX / lineHeightPx),
  );
  return Math.min(APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX, wholeLines * lineHeightPx);
}

function appleSupplementarySafetyGapPx(roles, activeFontPx) {
  return Math.ceil((roles ?? []).reduce((sum, role) => {
    return sum + APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX + appleSupplementaryMarginTopPx(role, activeFontPx);
  }, 0));
}

function appleSupplementaryMarginTopPx(role, activeFontPx) {
  if (role === "background-translation") return 0;
  if (role === "companion") return 20;
  const fontPx = role === "romaji"
    ? appleStaticSupplementaryFontPx(activeFontPx)
    : appleSecondaryFontPx(activeFontPx);
  return fontPx * APPLE_LYRIC_SUPPLEMENTARY_MARGIN_TOP_EM;
}

function appleTokenSupplementarySafetyGapPx() {
  return Math.ceil(
    APPLE_LYRIC_TOKEN_SUPPLEMENTARY_BOX_PX +
      APPLE_LYRIC_TOKEN_SUPPLEMENTARY_FONT_PX * APPLE_LYRIC_SUPPLEMENTARY_MARGIN_TOP_EM,
  );
}

function assertCompanionSample(sample) {
  assertIncludes("active text at companion sample", sample.active.text ?? "", "Got my guy");
  assertTruthy("companion vocal visible", sample.companion);
  assertEqual("companion font size", sample.companion.fontSize, "14px");
  assertEqual("companion margin top", sample.companion.marginTop, "20px");
  assertTruthy("background vocals direct Apple element visible", sample.backgroundVocals);
  assertEqual("background vocals direct font size", sample.backgroundVocals.fontSize, "14px");
  assertEqual("background vocals direct margin top", sample.backgroundVocals.marginTop, "20px");
  assertApprox("companion role shares background-vocals top", sample.companion.rect.top, sample.backgroundVocals.rect.top, 0.1);
  assertApprox("companion role shares background-vocals height", sample.companion.rect.height, sample.backgroundVocals.rect.height, 0.1);
  assertTruthy("background vocal token paint sampled", (sample.backgroundVocalTokens ?? []).length > 0);
  const parenthesizedBackgroundTokens = (sample.backgroundVocalTokens ?? [])
    .filter((token) => /[()]/.test(token.text ?? ""));
  assertTruthy("background vocal fixture has parenthesized raw tokens", parenthesizedBackgroundTokens.length > 0);
  for (const token of parenthesizedBackgroundTokens) {
    assertNotIncludes("background vocal rendered syllable strips opening parenthesis", token.renderedText ?? "", "(");
    assertNotIncludes("background vocal rendered syllable strips closing parenthesis", token.renderedText ?? "", ")");
  }
  const backgroundVocalPaint = (sample.backgroundVocalTokens ?? [])
    .map((token) => [
      token.text,
      token.renderedText,
      token.style?.color,
      token.style?.webkitTextFillColor,
      token.style?.backgroundImage,
    ].join(" "))
    .join(" | ");
  assertIncludes(
    "background vocal token active gradient alpha",
    backgroundVocalPaint,
    APPLE_BG_VOCAL_GRADIENT_ACTIVE_ALPHA,
  );
  assertIncludes(
    "background vocal token unsung gradient alpha",
    backgroundVocalPaint,
    APPLE_BG_VOCAL_GRADIENT_UNSUNG_ALPHA,
    APPLE_BG_VOCAL_GRADIENT_UNSUNG_ALPHA_RENDERED,
  );
  assertNotIncludes("background vocal token avoids primary active alpha", backgroundVocalPaint, "0.85");
  assertNotIncludes("background vocal token avoids primary unsung alpha", backgroundVocalPaint, "0.5");
  assertTruthy("background translation visible", sample.backgroundTranslation);
  assertEqual("background translation Apple kind", sample.backgroundTranslation.appleKind, "secondary secondary--background");
  assertEqual("background translation Apple visible state", sample.backgroundTranslation.appleVisible, "true");
  assertEqual("background translation font size", sample.backgroundTranslation.fontSize, "12px");
  assertEqual("background translation margin top", sample.backgroundTranslation.marginTop, "0px");
  assertEqual("background translation opacity", sample.backgroundTranslation.opacity, "0.45");
  assertApprox(
    "background translation whole-line capped reveal height",
    cssPx(sample.backgroundTranslation.maxHeight),
    appleSupplementaryVisibleBoxPx("background-translation", sample.numeric.activeFontSize),
    0.05,
  );
  assertEqual("background translation stays clipped inside Apple reveal box", sample.backgroundTranslation.overflow, "hidden");
}

function assertDuetSample(sample) {
  assertIncludes("active text at duet sample", sample.active.text ?? "", "But I, I");
  assertEqual("duet inner text align", sample.active.innerStyle.textAlign, "right");
  assertEqual("duet row text align", sample.active.style.textAlign, "right");
  assertApprox(
    "duet line transform origin right",
    transformOriginX(sample.active.innerStyle.transformOrigin),
    sample.active.innerStyle.rect.width / transformScale(sample.active.innerStyle.transform),
    2,
  );
  assertApprox(
    "duet line uses Apple global duet width",
    transformedRawWidth(sample.active.innerStyle),
    expectedAppleDuetLineWidth(sample),
    2,
  );
}

function assertScrollSamples(scroll, options = {}) {
  const labelPrefix = options.labelPrefix ?? "";
  const frames = scroll.frames;
  const first = frames[0];
  const scrollAt50 = frames.find((frame) => frame.timingMs === 50);
  const scrollAt100 = frames.find((frame) => frame.timingMs === 100);
  const scrollAt125 = frames.find((frame) => frame.timingMs === 125);
  const scrollAt250 = frames.find((frame) => frame.timingMs === 250);
  const mid = frames.find((frame) => frame.timingMs === 175);
  const end = frames.find((frame) => frame.timingMs === 350);
  const after = scroll.after;
  const lineFrames = scroll.lineFrames ?? frames;
  const firstLine = lineFrames.find((frame) => frame.timingMs === 0) ?? first;
  const at100 = lineFrames.find((frame) => frame.timingMs === 100);
  const at125 = lineFrames.find((frame) => frame.timingMs === 125);
  const at175 = lineFrames.find((frame) => frame.timingMs === 175);
  const at250 = lineFrames.find((frame) => frame.timingMs === 250);
  const lineEnd = lineFrames.find((frame) => frame.timingMs === 350);
  const at400 = lineFrames.find((frame) => frame.timingMs === 400);
  const at425 = lineFrames.find((frame) => frame.timingMs === 425);
  const lineAfter = scroll.lineAfter ?? after;
  const finalRowHeightTolerance = 3.5;
  const hasPreciseCssLineTransition =
    scroll.lineTransition?.cssLineTransition?.propertyName !== "fallback" &&
      (finiteNumber(firstLine?.activePaddingTop) ?? 12) < 4 &&
      (finiteNumber(firstLine?.previousBlurPx) ?? 2) < 1.25;
  assertIncludes(`${labelPrefix}scroll target active text`, end?.text ?? after.text ?? "", "That I just wanna get with you");
  assertIncludes(
    `${labelPrefix}scroll idle mask before switch uses Apple fullscreen default`,
    scroll.before?.viewportMaskImage ?? scroll.before?.viewportWebkitMaskImage ?? "",
    "50%",
  );
  for (const frame of [first, scrollAt50, scrollAt100, mid]) {
    if (!frame) continue;
    assertEqual(`${labelPrefix}scroll ${frame.timingMs}ms mask state`, frame.scrollingMaskState, "true");
    assertIncludes(
      `${labelPrefix}scroll ${frame.timingMs}ms mask uses Apple is-scrolling gradient`,
      frame.viewportMaskImage ?? frame.viewportWebkitMaskImage ?? "",
      "calc(100% - 80px)",
    );
  }
  assertEqual(`${labelPrefix}scroll after mask state`, after?.scrollingMaskState, "false");
  assertIncludes(
    `${labelPrefix}scroll after mask returns to Apple fullscreen default`,
    after?.viewportMaskImage ?? after?.viewportWebkitMaskImage ?? "",
    "50%",
  );
  const expandedPreviousHeight = scroll.lineBefore?.activeHeight ?? (lineAfter.previousHeight ?? 0) + 24;
  const previousLinePaddingDelta = Math.max(
    0,
    expandedPreviousHeight - (lineAfter.previousHeight ?? expandedPreviousHeight),
  );
  const appleVisibleTop = expectedAppleVisibleTop(scroll.scrollAnimation);
  const expectedFinalActiveTop = appleVisibleTop == null
    ? null
    : appleVisibleTop - (finiteNumber(end?.previousReleaseDeltaPx) ?? previousLinePaddingDelta);
  assertApprox(`${labelPrefix}scroll final active top after Apple line padding reflow`, after.activeTop, expectedFinalActiveTop, 2.5);
  if (first && mid && end && scroll.scrollAnimation) {
    const {
      from,
      target,
      duration,
      source,
      targetIndex,
      targetRowTop,
      anchorPx,
      offsetRatio,
      topSpacerHeight,
      topMargin,
      baseScrollTop,
      motion,
      springStiffness,
      springDamping,
    } = scroll.scrollAnimation;
    assertEqual(`${labelPrefix}scroll motion uses retained-velocity spring`, motion, "spring");
    assertApprox(`${labelPrefix}scroll spring stiffness`, springStiffness, 140, 0.01);
    assertApprox(`${labelPrefix}scroll spring damping`, springDamping, 24, 0.01);
    assertEqual(`${labelPrefix}scroll target source follows Apple pre-current layout`, source, "previous-layout");
    assertEqual(`${labelPrefix}scroll target row index`, targetIndex, 1);
    assertApprox(`${labelPrefix}scroll fullscreen offset-ratio follows Apple`, offsetRatio, APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO, 0.001);
    assertApprox(`${labelPrefix}scroll top spacer follows fullscreen offset-ratio`, topSpacerHeight, anchorPx, 1.25);
    assertApprox(`${labelPrefix}scroll top margin follows Apple`, topMargin, 55, 0.1);
    assertApprox(
      `${labelPrefix}scroll target formula uses Apple top spacer plus margin`,
      target,
      expectedAppleScrollTarget(scroll.scrollAnimation),
      0.75,
    );
    const delta = target - from;
    const tolerance = Math.max(3.5, Math.abs(delta) * 0.045);
    for (const frame of frames) {
      const elapsed = Number.isFinite(frame.scrollElapsedMs)
        ? frame.scrollElapsedMs
        : frame.timingMs;
      const expected = from + delta * springStepProgress(elapsed / 1000, 140, 24);
      assertApprox(`${labelPrefix}scrollTop ${frame.timingMs}ms shared spring`, frame.scrollTop, expected, tolerance);
    }
    assertBetween(
      `${labelPrefix}scrollTop 350ms enters spring settle tail`,
      Math.abs((target ?? 0) - (end.scrollTop ?? 0)),
      0,
      Math.max(4, Math.abs(delta) * 0.13),
    );
  } else {
    failures.push("scroll frame samples missing");
  }
  const scrollLeadPhaseMs =
    finiteNumber(firstLine?.scrollElapsedMs) != null && finiteNumber(firstLine?.lineElapsedMs) != null
      ? finiteNumber(firstLine.scrollElapsedMs) - finiteNumber(firstLine.lineElapsedMs)
      : null;
  assertBetween(
    "line CSS transition starts one frame after scroll",
    scrollLeadPhaseMs,
    0,
    88,
  );
  assertBetween("line 0ms current scale starts near inactive", firstLine?.activeScale, 0.995, 1.055);
  const scrollClockInFlightFrame = [scrollAt50, scrollAt100, at125].find(isLineSwitchInFlight);
  assertTruthy(
    "scroll-clock line transition has an in-flight 50ms/100ms/125ms sample",
    scrollClockInFlightFrame,
  );
  assertBetween("line 100ms current scale follows spring into focus", at100?.activeScale, 1.005, 1.035);
  assertBetween("line 100ms current padding top reaches active", at100?.activePaddingTop, 9, 12.5);
  assertBetween("line 100ms current padding bottom reaches active", at100?.activePaddingBottom, 9, 12.5);
  assertBetween("line 125ms current scale continues shared spring", at125?.activeScale, 1.012, 1.04);
  assertBetween("line 125ms current padding top is settled", at125?.activePaddingTop, 10.8, 12.5);
  assertBetween("line 125ms current padding bottom is settled", at125?.activePaddingBottom, 10.8, 12.5);
  assertLineTransitionHistory("ordinary line switch CSS transition history", lineFrames, {
    currentText: "That I just wanna get with you",
    previousText: "You see through",
  });
  assertBetween("line 0ms previous scale starts in legal range", firstLine?.previousScale, 1, 1.055);
  assertBetween("line 0ms previous fade starts opaque", firstLine?.previousOpacity, 0.82, 1.01);
  assertBetween("line 100ms previous scale returns inactive", at100?.previousScale, 0.995, 1.015);
  assertBetween("line 125ms previous scale is settled", at125?.previousScale, 0.995, 1.01);
  assertBetween("line 400ms previous fade progresses toward auto-scroll target", at400?.previousOpacity, 0.42, 0.78);
  assertAppleFadeOutCurve("line switch Apple fade-out curve", lineFrames);
  assertTruthy(
    "previous fade opacity decreases during line switch",
    (at400?.previousOpacity ?? 1) < (firstLine?.previousOpacity ?? 0) - 0.18,
  );
  assertBetween("line 0ms previous padding starts in legal range", firstLine?.previousPaddingTop, 0, 12.5);
	  assertApprox("line 100ms previous padding returns inactive", at100?.previousPaddingTop, 0, 1.2);
	  assertAppleLinePaddingCurve("line switch Apple padding curve", lineFrames, {
	    includePrevious: true,
	  });
	  assertAppleScaleCurve("line switch Apple scale curve", lineFrames, {
	    includePrevious: true,
	  });
	  assertAppleBlurCurve("line switch Apple blur curve", lineFrames);
  if (hasPreciseCssLineTransition) {
    assertBetween("line 0ms current row starts from inactive blur", firstLine?.activeBlurPx, 0.25, 2.05);
    assertBetween("line 0ms previous row starts from current blur", firstLine?.previousBlurPx, 0, 1.7);
    assertApprox(
      "line 0ms current/previous blur stays complementary",
      (firstLine?.activeBlurPx ?? 0) + (firstLine?.previousBlurPx ?? 0),
      2,
      0.4,
    );
  }
  const earlyBlurFrame = [scrollAt50, scrollAt100, scrollAt125].find((frame) =>
    (finiteNumber(frame?.activeBlurPx) ?? 2) > 0.1 &&
      (finiteNumber(frame?.activeBlurPx) ?? 2) < 1.95 &&
      (finiteNumber(frame?.previousBlurPx) ?? 0) > 0.05 &&
      (finiteNumber(frame?.previousBlurPx) ?? 0) < 1.95);
  assertTruthy("line switch has early blur release by 125ms", earlyBlurFrame);
  if (earlyBlurFrame) {
    assertApprox(
      `line switch ${earlyBlurFrame.timingMs}ms current/previous blur stays complementary`,
      (earlyBlurFrame.activeBlurPx ?? 0) + (earlyBlurFrame.previousBlurPx ?? 0),
      2,
      0.4,
    );
  }
  assertApprox("line 350ms current row blur reaches none", lineEnd?.activeBlurPx, 0, 0.15);
  assertApprox("line 350ms previous row reaches inactive blur", lineEnd?.previousBlurPx, 2, 0.15);
  assertApprox("line 350ms next row stays inactive blur", lineEnd?.nextBlurPx, 2, 0.15);
  assertApprox("line 250ms current row blur reaches none", at250?.activeBlurPx, 0, 0.25);
  assertApprox("line 250ms previous row reaches inactive blur", at250?.previousBlurPx, 2, 0.25);
  assertApprox("scroll-clock 250ms current row blur reaches none", scrollAt250?.activeBlurPx, 0, 0.4);
  const adjacentTimedReleaseRows = (firstLine?.rowStack ?? []).filter((row) => row.timedRelease);
  assertEqual("adjacent line switch has one timed release row", adjacentTimedReleaseRows.length, 1);
  assertIncludes(
    "adjacent timed release row is old current",
    adjacentTimedReleaseRows[0]?.text ?? "",
    "You see through",
  );
  const firstLineAnimatingRows = (firstLine?.rowStack ?? []).filter((row) => row.willAnimate);
  assertEqual("Apple is-animating row count", firstLineAnimatingRows.length, 2);
  assertIncludes("Apple is-animating includes current", firstLineAnimatingRows[0]?.text ?? "", "That I just wanna get with you");
  assertIncludes("Apple is-animating includes next", firstLineAnimatingRows[1]?.text ?? "", "You right I");
  assertEqual(
    "Apple is-animating excludes previous release row",
    adjacentTimedReleaseRows[0]?.willAnimate,
    false,
  );
  assertBetween(
    "line 0ms previous row starts within the Apple padding-release transition",
    firstLine?.previousHeight,
    (lineAfter.previousHeight ?? expandedPreviousHeight - 24) + 2,
    expandedPreviousHeight + 10,
  );
  const earlyReleaseFrame = [scrollAt50, scrollAt100].find((frame) =>
    Number.isFinite(residualReleaseDelta(frame)) &&
    residualReleaseDelta(frame) > 2 &&
    residualReleaseDelta(frame) < 23.95);
  assertTruthy("scroll-clock previous row starts releasing by 100ms", earlyReleaseFrame);
  assertBetween("scroll-clock active top has early non-scroll residual", Math.abs(earlyReleaseFrame?.layoutResidualPx ?? 0), 2, 23.95);
  assertApprox(
    "scroll-clock active top residual matches in-flight previous release",
    earlyReleaseFrame?.layoutResidualPx,
    -residualReleaseDelta(earlyReleaseFrame),
    3,
  );
  assertBetween(
    "scroll-clock 100ms previous row is substantially released",
    scrollAt100?.previousReleaseDeltaPx,
    12,
    24.5,
  );
  assertApprox(
    "scroll-clock 100ms residual matches previous row release",
    scrollAt100?.layoutResidualPx,
    -residualReleaseDelta(scrollAt100),
    2.5,
  );
  assertApprox("line 100ms previous row reaches padding final", at100?.previousHeight, lineAfter.previousHeight, finalRowHeightTolerance);
  assertApprox("line 350ms previous row stays padding final", lineEnd?.previousHeight, lineAfter.previousHeight, finalRowHeightTolerance);
  if (hasPreciseCssLineTransition) {
    assertBetween(
      "line 100ms active top has non-scroll release residual",
      Math.abs(at100?.layoutResidualPx ?? 0),
      8,
      30,
    );
    assertApprox(
      "line 100ms active top residual matches previous row release",
      at100?.layoutResidualPx,
      -residualReleaseDelta(at100),
      2.5,
    );
    assertApprox(
      "line 175ms active top residual persists after padding release",
      at175?.layoutResidualPx,
      -residualReleaseDelta(at175),
      2.5,
    );
	  assertApprox(
	    "line 350ms active top residual persists to scroll finish",
	    lineEnd?.layoutResidualPx,
	    -residualReleaseDelta(lineEnd),
	    2.5,
	  );
	  assertCombinedActiveTopMotion("ordinary line switch combined top motion", lineFrames);
	  assertCombinedActiveTextCenterMotion("ordinary line switch text-center motion", lineFrames);
	  assertAdjacentSiblingTextMotion("ordinary line switch sibling text motion", lineFrames);
  }
  assertApprox("line 400ms current height final", at400?.activeHeight, lineAfter.activeHeight, finalRowHeightTolerance);
  assertApprox("line 400ms previous height final", at400?.previousHeight, lineAfter.previousHeight, finalRowHeightTolerance);
  assertApprox("line 425ms current height remains final", at425?.activeHeight, lineAfter.activeHeight, finalRowHeightTolerance);
  assertApprox("line 425ms previous height remains final", at425?.previousHeight, lineAfter.previousHeight, finalRowHeightTolerance);
  assertApprox("line 400ms active margin final", at400?.activeMarginBottom, lineAfter.activeMarginBottom, 1.2);
  assertApprox("line 400ms previous margin final", at400?.previousMarginBottom, lineAfter.previousLineMarginBottom, 1.2);
  assertApprox("line 425ms active margin remains final", at425?.activeMarginBottom, lineAfter.activeMarginBottom, 1.2);
  assertApprox("line 425ms previous margin remains final", at425?.previousMarginBottom, lineAfter.previousLineMarginBottom, 1.2);
  const releaseRows = lineFrames.flatMap((frame) => (frame.rowStack ?? [])
    .filter((row) => row.rowStyle?.animationName === "appleLyricRowRelease"));
  assertEqual("ordinary adjacent line switch has no custom outer release rows", releaseRows.length, 0);
  for (const frame of lineFrames) {
    const previousToken = frame.previousOrdinaryToken;
    assertEqual(
      `line switch ${frame.timingMs}ms previous release row keeps timed syllable DOM`,
      previousToken?.kind,
      "ordinary",
    );
    assertIncludes(
      `line switch ${frame.timingMs}ms previous release token text`,
      previousToken?.text ?? "",
      "You ",
    );
    assertApprox(
      `line switch ${frame.timingMs}ms previous release token keeps sung lift`,
      previousToken?.y,
      -2,
      0.22,
    );
    assertStableGradientTextPaint(
      `line switch ${frame.timingMs}ms previous release token keeps stable text paint`,
      previousToken,
    );
  }
  if (hasPreciseCssLineTransition) {
    assertPreviousTokenStableGradientColorRelease("ordinary line switch previous token color release", lineFrames);
  }
  const postReleasePreviousToken = after.previousOrdinaryToken;
  assertEqual(
    "post-release previous row keeps timed syllable DOM",
    postReleasePreviousToken?.kind,
    "ordinary",
  );
  assertIncludes(
    "post-release previous token text",
    postReleasePreviousToken?.text ?? "",
    "You ",
  );
  assertApprox(
    "post-release previous token keeps completed lift",
    postReleasePreviousToken?.y,
    -2,
    0.22,
  );
  assertStableGradientTextPaint(
    "post-release previous token keeps stable text paint",
    postReleasePreviousToken,
  );
  for (const frame of lineFrames) {
    assertActiveIsOnlyScrollTarget(`line switch active scroll target ${frame.timingMs}ms`, frame.rowStack);
    assertAppleRowsUseBlock(`line switch row block display ${frame.timingMs}ms`, frame.rowStack);
    assertAppleWillChangePlacement(`line switch will-change placement ${frame.timingMs}ms`, frame.rowStack);
    assertNoVerticalOverlap(
      `line switch row stack ${frame.timingMs}ms`,
      rectFromTopBottom(frame.previousTop, frame.previousBottom),
      rectFromTopBottom(frame.activeTop, frame.activeBottom),
      rectFromTopBottom(frame.nextTop, frame.nextBottom),
    );
    assertNoVisibleTextOverlap(`line switch visual text stack ${frame.timingMs}ms`, frame.rowStack);
  }
}

function assertNaturalPlaybackLineSwitch(sample, options = {}) {
  const labelPrefix = options.labelPrefix ?? "natural switch";
  const fixturePositionMin = options.fixturePositionMin ?? 7.74;
  const fixturePositionMax = options.fixturePositionMax ?? 7.9;
  const frames = sample.frames ?? [];
  const first = frames.find((frame) => frame.timingMs === 0);
  const at50 = frames.find((frame) => frame.timingMs === 50);
  const at100 = frames.find((frame) => frame.timingMs === 100);
  const at125 = frames.find((frame) => frame.timingMs === 125);
  const mid = frames.find((frame) => frame.timingMs === 175);
  const at250 = frames.find((frame) => frame.timingMs === 250);
  const end = frames.find((frame) => frame.timingMs === 350);
  const at425 = frames.find((frame) => frame.timingMs === 425);
  assertIncludes(`${labelPrefix} starts from previous lyric`, sample.before?.text ?? "", "You see through");
  assertIncludes(`${labelPrefix} active text`, first?.text ?? "", "That I just wanna get with you");
  assertEqual(`${labelPrefix} scroll source`, sample.scrollAnimation?.source, "previous-layout");
  assertLineCssTransitionFollowsScroll(`${labelPrefix} CSS transition phase`, sample);
  assertBetween(`${labelPrefix} first fixture position near Apple lookahead cut`, first?.fixturePosition, fixturePositionMin, fixturePositionMax);
  assertBetween(`${labelPrefix} first token gradient near timeline start`, first?.ordinary?.gradientProgress, -24, 35);
  assertBetween(`${labelPrefix} first token lift starts low`, first?.ordinary?.y, -0.45, 0.05);
  assertTruthy(
    `${labelPrefix} scroll moves before line transition fully catches up`,
    (at50?.scrollTop ?? 0) > (first?.scrollTop ?? 0) + 1,
  );
  const lineInFlightAt50 = isLineSwitchInFlight(at50);
  const lineInFlightAt100 = isLineSwitchInFlight(at100);
  const lineInFlightAt125 = isLineSwitchInFlight(at125);
  assertTruthy(
    `${labelPrefix} line transition has an in-flight 50ms/100ms/125ms sample`,
    lineInFlightAt50 || lineInFlightAt100 || lineInFlightAt125,
  );
  assertLineTransitionHistory(`${labelPrefix} CSS transition history`, frames, {
    currentText: "That I just wanna get with you",
    previousText: "You see through",
  });
  assertAppleFadeOutCurve(`${labelPrefix} Apple fade-out curve`, frames);
  assertTruthy(
    `${labelPrefix} active line expands between scroll start and 100ms`,
    (at100?.activePaddingTop ?? 0) > (first?.activePaddingTop ?? 99) + 0.2,
  );
  assertBetween(`${labelPrefix} 125ms active scale follows spring`, at125?.activeScale, 1.012, 1.04);
  assertBetween(`${labelPrefix} 125ms active padding is settled`, at125?.activePaddingTop, 9, 12.5);
  assertBetween(`${labelPrefix} 125ms previous scale is settled`, at125?.previousScale, 0.995, 1.01);
  assertBetween(`${labelPrefix} 125ms previous padding is settled`, at125?.previousPaddingTop, 0, 3);
  assertTruthy(
    `${labelPrefix} previous line releases between scroll start and 100ms`,
    (at100?.previousPaddingTop ?? 99) < (first?.previousPaddingTop ?? 0) - 0.2,
  );
	  assertAppleLinePaddingCurve(`${labelPrefix} Apple padding curve`, frames, {
	    includePrevious: true,
	  });
	  assertAppleScaleCurve(`${labelPrefix} Apple scale curve`, frames, {
	    includePrevious: true,
	  });
	  assertAppleBlurCurve(`${labelPrefix} Apple blur curve`, frames);
  assertTruthy(
    `${labelPrefix} active top accumulates non-scroll release residual by 100ms`,
    Math.abs(at100?.layoutResidualPx ?? 0) > 5 || Math.abs(at125?.layoutResidualPx ?? 0) > 10,
  );
  assertBetween(`${labelPrefix} 175ms active scale approaches current`, mid?.activeScale, 1.025, 1.052);
  assertBetween(`${labelPrefix} 175ms active padding reaches current`, mid?.activePaddingTop, 10.5, 12.5);
  assertApprox(`${labelPrefix} 250ms current row blur reaches none`, at250?.activeBlurPx, 0, 0.55);
  assertApprox(`${labelPrefix} 250ms previous row reaches inactive blur`, at250?.previousBlurPx, 2, 0.55);
  assertApprox(
    `${labelPrefix} 175ms residual matches previous row release`,
    mid?.layoutResidualPx,
    -residualReleaseDelta(mid),
    3,
  );
  assertTruthy(
    `${labelPrefix} token gradient advances during scroll`,
    (mid?.ordinary?.gradientProgress ?? -999) > (first?.ordinary?.gradientProgress ?? 999) + 25 ||
      ((mid?.ordinary?.gradientProgress == null) && (first?.ordinary?.gradientProgress ?? 999) > 45),
  );
  assertTruthy(
    `${labelPrefix} token lift advances during scroll`,
    (mid?.ordinary?.y ?? 0) < (first?.ordinary?.y ?? 0) - 0.3,
  );
  assertBetween(
    `${labelPrefix} 350ms scroll is in spring settle tail`,
    Math.abs((sample.scrollAnimation?.target ?? 0) - (end?.scrollTop ?? 0)),
    0,
    Math.max(
      4,
      Math.abs((sample.scrollAnimation?.target ?? 0) - (sample.scrollAnimation?.from ?? 0)) * 0.13,
    ),
  );
  assertApprox(`${labelPrefix} 425ms active height remains final`, at425?.activeHeight, sample.after?.activeHeight, 3.5);
  assertApprox(`${labelPrefix} 425ms previous height remains final`, at425?.previousHeight, sample.after?.previousHeight, 3.5);
  const naturalVisibleTop = expectedAppleVisibleTop(sample.scrollAnimation);
  assertApprox(`${labelPrefix} fullscreen offset-ratio`, sample.scrollAnimation?.offsetRatio, APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO, 0.001);
  assertApprox(
    `${labelPrefix} final active top keeps Apple previous-layout residual`,
    sample.after?.activeTop,
    (naturalVisibleTop ?? 0) - (sample.after?.previousReleaseDeltaPx ?? 0),
    3,
  );
	  assertApprox(
	    `${labelPrefix} final residual matches previous row release`,
	    end?.layoutResidualPx,
	    -residualReleaseDelta(end),
	    3,
	  );
	  assertCombinedActiveTopMotion(`${labelPrefix} combined top motion`, frames, {
	    tolerancePx: 3,
	  });
	  assertCombinedActiveTextCenterMotion(`${labelPrefix} text-center motion`, frames, {
	    tolerancePx: 5,
	  });
	  assertAdjacentSiblingTextMotion(`${labelPrefix} sibling text motion`, frames, {
	    tolerancePx: 5,
	  });
	  for (const frame of frames) {
    assertEqual(`${labelPrefix} backdrop opaque ${frame.timingMs}ms`, frame.backdropOpacity, "1");
    assertActiveIsOnlyScrollTarget(`${labelPrefix} active scroll target ${frame.timingMs}ms`, frame.rowStack);
    assertAppleRowsUseBlock(`${labelPrefix} row block display ${frame.timingMs}ms`, frame.rowStack);
    assertAppleWillChangePlacement(`${labelPrefix} will-change placement ${frame.timingMs}ms`, frame.rowStack);
    assertNoVisibleTextOverlap(`${labelPrefix} visual text stack ${frame.timingMs}ms`, frame.rowStack);
  }
}

function assertBackgroundVocalLineSwitch(sample) {
  const frames = sample.frames ?? [];
  const first = frames.find((frame) => frame.timingMs === 0);
  const at50 = frames.find((frame) => frame.timingMs === 50);
  const at100 = frames.find((frame) => frame.timingMs === 100);
  const at125 = frames.find((frame) => frame.timingMs === 125);
  const mid = frames.find((frame) => frame.timingMs === 175);
  const end = frames.find((frame) => frame.timingMs === 350);
  const final = frames.find((frame) => frame.timingMs === 400) ?? end;
  assertIncludes("background-vocal switch starts from romaji line", sample.before?.text ?? "", "You right I");
  assertIncludes("background-vocal switch active text", first?.text ?? "", "Got my guy");
  assertEqual("background-vocal switch scroll source", sample.scrollAnimation?.source, "previous-layout");
  assertEqual("background-vocal switch target row index", sample.scrollAnimation?.targetIndex, 3);
  assertLineCssTransitionFollowsScroll("background-vocal switch CSS transition phase", sample);
	  assertAppleLinePaddingCurve("background-vocal switch Apple padding curve", frames, {
	    includePrevious: true,
	  });
	  assertAppleScaleCurve("background-vocal switch Apple scale curve", frames, {
	    includePrevious: true,
	  });
	  assertAppleBlurCurve("background-vocal switch Apple blur curve", frames);
	  assertAppleFadeOutCurve("background-vocal switch Apple fade-out curve", frames);
	  assertCombinedActiveTopMotion("background-vocal switch combined top motion", frames, {
	    tolerancePx: 3,
	  });
	  assertCombinedActiveTextCenterMotion("background-vocal switch text-center motion", frames, {
	    tolerancePx: 5,
	  });
	  assertAdjacentSiblingTextMotion("background-vocal switch sibling text motion", frames, {
	    tolerancePx: 5,
	  });
	  assertScrollMovesTowardTargetBeforeLayout(
    "background-vocal switch scroll moves before layout finishes",
    first,
    at50,
    sample.scrollAnimation,
  );
  assertTruthy(
    "background-vocal switch active row expands by 100ms",
    (at100?.activePaddingTop ?? 0) > (first?.activePaddingTop ?? 99) + 0.2,
  );
  assertBetween("background-vocal switch 175ms current scale reaches current", mid?.activeScale, 1.045, 1.055);
  assertApprox("background-vocal switch scroll finishes at target", end?.scrollTop, sample.after?.scrollTop, 4);

  for (const frame of frames) {
    assertEqual(`background-vocal backdrop opaque ${frame.timingMs}ms`, frame.backdropOpacity, "1");
    assertActiveIsOnlyScrollTarget(`background-vocal active scroll target ${frame.timingMs}ms`, frame.rowStack);
    assertAppleRowsUseBlock(`background-vocal row block display ${frame.timingMs}ms`, frame.rowStack);
    assertAppleWillChangePlacement(`background-vocal will-change placement ${frame.timingMs}ms`, frame.rowStack);
    assertNoVisibleTextOverlap(`background-vocal visual text stack ${frame.timingMs}ms`, frame.rowStack);
    assertVisibleTextGap(`background-vocal readable text gap ${frame.timingMs}ms`, frame.rowStack, 24);
    assertIncludes(
      `background-vocal line has primary child ${frame.timingMs}ms`,
      frame.activeLineChildren?.map((child) => child.appleVocals || child.appleKind || child.companionRole).join("|") ?? "",
      "primary-vocals",
    );
    assertIncludes(
      `background-vocal line has background vocals ${frame.timingMs}ms`,
      frame.activeLineChildren?.map((child) => child.appleVocals || child.appleKind || child.companionRole).join("|") ?? "",
      "background-vocals",
    );
    assertIncludes(
      `background-vocal line has background translation ${frame.timingMs}ms`,
      frame.activeLineChildren?.map((child) => child.appleKind || child.appleVocals || child.companionRole).join("|") ?? "",
      "secondary secondary--background",
    );
    assertEqual(`background-vocal display ${frame.timingMs}ms`, frame.backgroundVocals?.display, "block");
    assertEqual(`background-vocal font-size ${frame.timingMs}ms`, frame.backgroundVocals?.fontSize, "14px");
    assertEqual(`background-vocal margin-top ${frame.timingMs}ms`, frame.backgroundVocals?.marginTop, "20px");
    assertEqual(`background translation display ${frame.timingMs}ms`, frame.backgroundTranslation?.display, "block");
    assertEqual(`background translation kind ${frame.timingMs}ms`, frame.backgroundTranslation?.appleKind, "secondary secondary--background");
    assertEqual(`background translation font-size ${frame.timingMs}ms`, frame.backgroundTranslation?.fontSize, "12px");
    assertEqual(`background translation margin-top ${frame.timingMs}ms`, frame.backgroundTranslation?.marginTop, "0px");
    assertEqual(`background translation opacity ${frame.timingMs}ms`, frame.backgroundTranslation?.opacity, "0.45");
  }

  const finalActiveRow = (final?.rowStack ?? []).find((row) => row.active);
  assertBetween(
    "background-vocal final row reserves companion vertical space",
    finalActiveRow?.supplementarySafetyGap,
    50,
    70,
  );
  assertNoVisibleTextOverlap("background-vocal final visual text stack", sample.rowStackAfter);
  assertVisibleTextGap("background-vocal final readable text gap", sample.rowStackAfter, 24);
}

function assertContinuousPlaybackWindow(sample) {
  const frames = sample.frames ?? [];
  assertBetween("continuous playback frame count", frames.length, 70, 100);
  const activeTexts = frames.map((frame) => frame.text ?? "");
  assertTruthy(
    "continuous playback sees first source line",
    activeTexts.some((text) => text.includes("You see through")),
  );
  assertTruthy(
    "continuous playback sees second source line",
    activeTexts.some((text) => text.includes("That I just wanna get with you")),
  );
  assertTruthy(
    "continuous playback sees romaji source line",
    activeTexts.some((text) => text.includes("You right I")),
  );
  assertTruthy(
    "continuous playback sees background-vocal source line",
    activeTexts.some((text) => text.includes("Got my guy")),
  );

  const switches = [];
  for (let i = 1; i < frames.length; i++) {
    const previous = frames[i - 1];
    const current = frames[i];
    if (previous?.activeIndex !== current?.activeIndex) {
      switches.push({ previous, current });
    }
  }
  assertBetween("continuous playback natural switch count", switches.length, 3, 5);

  for (const frame of frames) {
    assertEqual(`continuous backdrop opaque ${frame.timingMs}ms`, frame.backdropOpacity, "1");
    assertNotEqual(`continuous backdrop color follows artwork ${frame.timingMs}ms`, frame.backdropBackgroundColor, "rgb(0, 0, 0)");
    assertEqual(`continuous backdrop uses opaque Apple color field ${frame.timingMs}ms`, frame.backdropBackgroundImage, "none");
    assertEqual(`continuous column transparent ${frame.timingMs}ms`, frame.columnBackgroundColor, "rgba(0, 0, 0, 0)");
    assertContinuousActiveScrollPhase(`continuous active scroll target ${frame.timingMs}ms`, frame);
    assertAppleRowsUseBlock(`continuous row block display ${frame.timingMs}ms`, frame.rowStack);
    assertNoVisibleTextOverlap(`continuous visual text stack ${frame.timingMs}ms`, frame.rowStack);
  }

  switches.forEach((entry, index) => {
    assertEqual(
      `continuous switch ${index + 1} source`,
      entry.current?.scrollSource,
      "previous-layout",
    );
    assertBetween(
      `continuous switch ${index + 1} has active scroll writer`,
      entry.current?.scrollActiveCount,
      1,
      4,
    );
    assertTruthy(
      `continuous switch ${index + 1} active row moves`,
      Math.abs((entry.current?.activeTop ?? 0) - (entry.previous?.activeTop ?? 0)) > 1 ||
        Math.abs((entry.current?.scrollTop ?? 0) - (entry.previous?.scrollTop ?? 0)) > 1,
    );
  });

  for (const frame of frames.filter((item) => (item.text ?? "").includes("Got my guy"))) {
    assertEqual(`continuous background-vocal display ${frame.timingMs}ms`, frame.backgroundVocals?.display, "block");
    assertEqual(`continuous background-vocal font-size ${frame.timingMs}ms`, frame.backgroundVocals?.fontSize, "14px");
    assertEqual(`continuous background-vocal margin-top ${frame.timingMs}ms`, frame.backgroundVocals?.marginTop, "20px");
    assertEqual(`continuous background translation display ${frame.timingMs}ms`, frame.backgroundTranslation?.display, "block");
    assertEqual(`continuous background translation font-size ${frame.timingMs}ms`, frame.backgroundTranslation?.fontSize, "12px");
    assertEqual(`continuous background translation opacity ${frame.timingMs}ms`, frame.backgroundTranslation?.opacity, "0.45");
    const activeRow = (frame.rowStack ?? []).find((row) => row.active);
    assertBetween(`continuous background row safety gap ${frame.timingMs}ms`, activeRow?.supplementarySafetyGap, 50, 70);
  }
}

function assertContinuousActiveScrollPhase(label, frame) {
  const rows = frame?.rowStack ?? [];
  const activeRows = rows.filter((row) => row?.active);
  const scrollTargetRows = rows.filter((row) => row?.scrollTarget);
  assertEqual(`${label}: one active row`, activeRows.length, 1);
  assertEqual(`${label}: one scroll target row`, scrollTargetRows.length, 1);
  if (activeRows.length !== 1 || scrollTargetRows.length !== 1) return;
  const activeIndex = activeRows[0]?.index;
  const targetIndex = scrollTargetRows[0]?.index;
  if (targetIndex === activeIndex) return;
  assertEqual(`${label}: pre-current scroll target advances one row`, targetIndex, activeIndex + 1);
  assertEqual(`${label}: pre-current scroll source`, frame.scrollSource, "previous-layout");
  assertBetween(`${label}: pre-current scroll writer active`, frame.scrollActiveCount, 1, 4);
}

function isLineSwitchInFlight(frame) {
  return (
    frame &&
    frame.activeScale > 1.0005 &&
    frame.activeScale < 1.0495 &&
    frame.activePaddingTop > 0.1 &&
    frame.activePaddingTop < 11.95 &&
    frame.previousScale > 1.0005 &&
    frame.previousScale < 1.0495 &&
    frame.previousPaddingTop > 0.1 &&
    frame.previousPaddingTop < 11.95
  );
}

function assertLineCssTransitionFollowsScroll(label, sample) {
  const scrollStartedAt = finiteNumber(sample?.scrollAnimation?.startedAt);
  const cssStartedAt = finiteNumber(sample?.lineTransition?.cssLineTransition?.startedAt);
  if (scrollStartedAt === null || cssStartedAt === null) {
    failures.push(`${label}: missing scroll/CSS transition timestamps`);
    return;
  }
  assertBetween(label, cssStartedAt - scrollStartedAt, 0, 88);
}

function assertLineTransitionHistory(label, frames, options) {
  const history = [];
  for (const frame of frames ?? []) {
    for (const event of frame?.cssTransitionHistory ?? []) {
      history.push(event);
    }
  }
  const propertyNames = new Set(["transform", "padding-top", "padding-bottom"]);
  const currentEvents = history.filter((event) =>
    String(event?.rowText ?? "").includes(options.currentText) &&
    propertyNames.has(event?.propertyName));
  const previousEvents = history.filter((event) =>
    String(event?.rowText ?? "").includes(options.previousText) &&
    propertyNames.has(event?.propertyName));
  if (currentEvents.length === 0 || previousEvents.length === 0) {
    warnings.push(`${label}: transitionrun history incomplete; using sampled CSS frames`);
    return;
  }
  assertTruthy(
    `${label}: current row is active scroll target`,
    currentEvents.some((event) => event.active === true && event.scrollTarget === true && event.willAnimate === true),
  );
  assertTruthy(
    `${label}: previous row is old current release, not Apple is-animating`,
    previousEvents.some((event) =>
      event.active === false &&
      event.scrollTarget === false &&
      event.wasActiveBeforeSwitch === true &&
      event.willAnimate === false),
  );
  const firstCurrent = Math.min(...currentEvents.map((event) => finiteNumber(event.startedAt) ?? Number.POSITIVE_INFINITY));
  const firstPrevious = Math.min(...previousEvents.map((event) => finiteNumber(event.startedAt) ?? Number.POSITIVE_INFINITY));
  assertApprox(`${label}: current/previous CSS starts together`, firstCurrent, firstPrevious, 24);
}

function findAppleLineTransitionAnchor(frames, getProgress) {
  const candidates = (frames ?? [])
    .map((frame) => {
      const elapsed = finiteNumber(frame?.lineElapsedMs);
      const progress = getProgress(frame);
      return { frame, elapsed, progress };
    })
    .filter(({ elapsed, progress }) =>
      elapsed !== null &&
      elapsed >= 0 &&
      elapsed <= 125 &&
      progress !== null &&
      progress > 0.05 &&
      progress < 0.95);
  return (
    candidates.find(({ progress }) => progress >= 0.25 && progress <= 0.9)?.frame ??
    candidates.find(({ progress }) => progress >= 0.12 && progress <= 0.92)?.frame ??
    candidates[0]?.frame ??
    null
  );
}

function assertAppleLinePaddingCurve(label, frames, options = {}) {
  const includePrevious = options.includePrevious !== false;
  const sortedFrames = (frames ?? [])
    .filter((frame) => finiteNumber(frame?.activePaddingTop) !== null)
    .sort((a, b) => (a.timingMs ?? 0) - (b.timingMs ?? 0));
  const firstFrame = sortedFrames[0];
  const startsMidTransition = Boolean(
    firstFrame &&
      (finiteNumber(firstFrame.activePaddingTop) ?? 0) > 4 &&
      (finiteNumber(firstFrame.activePaddingTop) ?? 0) < 11.8,
  );
  if (startsMidTransition) {
    let previousActivePadding = finiteNumber(firstFrame.activePaddingTop) ?? 0;
    for (const frame of sortedFrames) {
      const activePadding = finiteNumber(frame.activePaddingTop);
      const previousPadding = finiteNumber(frame.previousPaddingTop);
      if (activePadding === null) continue;
      assertBetween(
        `${label} active ${frame.timingMs}ms remains in Apple padding range`,
        activePadding,
        Math.max(0, previousActivePadding - 0.35),
        12.35,
      );
      if (includePrevious && previousPadding !== null) {
        assertApprox(
          `${label} complementary ${frame.timingMs}ms`,
          activePadding + previousPadding,
          12,
          0.75,
        );
      }
      previousActivePadding = activePadding;
    }
    const settlingFrame = sortedFrames.find((frame) => (frame.timingMs ?? 0) >= 100);
    assertBetween(
      `${label} settles by 100ms when sample starts mid-transition`,
      settlingFrame?.activePaddingTop,
      11,
      12.5,
    );
    return;
  }
  const anchor = findAppleLineTransitionAnchor(frames, (frame) => {
    const padding = finiteNumber(frame?.activePaddingTop);
    return padding === null ? null : Math.max(0, Math.min(1, padding / 12));
  });
  if (!anchor) return;
  const anchorProgress = Math.max(0, Math.min(1, (finiteNumber(anchor.activePaddingTop) ?? 0) / 12));
  const inferredTransitionStartOffsetMs =
    (finiteNumber(anchor.lineElapsedMs) ?? 0) - inverseCssEaseInOut(anchorProgress) * 100;
  for (const frame of frames ?? []) {
    if (frame.timingMs < anchor.timingMs) continue;
    const elapsed = finiteNumber(frame.lineElapsedMs);
    if (elapsed === null || elapsed < 0 || elapsed > 125) continue;
    const adjustedElapsed = elapsed - inferredTransitionStartOffsetMs;
    if (adjustedElapsed < -8 || adjustedElapsed > 125) continue;
    const progress = cssEaseInOut(Math.max(0, Math.min(1, adjustedElapsed / 100)));
    const expectedActivePadding = 12 * progress;
    assertApprox(
      `${label} active ${frame.timingMs}ms`,
      frame.activePaddingTop,
      expectedActivePadding,
      3.4,
    );
    if (includePrevious) {
      assertApprox(
        `${label} previous ${frame.timingMs}ms`,
        frame.previousPaddingTop,
        12 * (1 - progress),
        3.4,
      );
    }
  }
}

function assertAppleScaleCurve(label, frames, options = {}) {
  const includePrevious = options.includePrevious !== false;
  const anchor = findAppleLineTransitionAnchor(frames, (frame) => {
    const scale = finiteNumber(frame?.activeScale);
    return scale === null ? null : Math.max(0, Math.min(1, (scale - 1) / 0.05));
  });
  if (!anchor) {
    assertTruthy(
      `${label}: active scale reaches current value`,
      (frames ?? []).some((frame) => (finiteNumber(frame?.activeScale) ?? 0) >= 1.045),
    );
    return;
  }
  const anchorProgress = Math.max(0, Math.min(1, ((finiteNumber(anchor.activeScale) ?? 1) - 1) / 0.05));
  const inferredTransitionStartOffsetMs =
    (finiteNumber(anchor.lineElapsedMs) ?? 0) - inverseCssEaseInOut(anchorProgress) * 100;
  for (const frame of frames ?? []) {
    if (frame.timingMs < anchor.timingMs) continue;
    const elapsed = finiteNumber(frame.lineElapsedMs);
    if (elapsed === null || elapsed < 0 || elapsed > 125) continue;
    const adjustedElapsed = elapsed - inferredTransitionStartOffsetMs;
    if (adjustedElapsed < -8 || adjustedElapsed > 125) continue;
    const progress = cssEaseInOut(Math.max(0, Math.min(1, adjustedElapsed / 100)));
    assertApprox(
      `${label} active ${frame.timingMs}ms`,
      frame.activeScale,
      1 + 0.05 * progress,
      0.006,
    );
    if (includePrevious) {
      assertApprox(
        `${label} previous ${frame.timingMs}ms`,
        frame.previousScale,
        1.05 - 0.05 * progress,
        0.006,
      );
    }
  }
}

function assertAppleBlurCurve(label, frames, options = {}) {
  const includePrevious = options.includePrevious !== false;
  const anchor = (frames ?? []).find((frame) => {
    const elapsed = finiteNumber(frame?.lineElapsedMs) ?? finiteNumber(frame?.timingMs);
    const blur = finiteNumber(frame?.activeBlurPx);
    return elapsed !== null && blur !== null && blur > 0.15 && blur < 1.85;
  });
  assertTruthy(`${label}: active blur in-flight sample`, anchor);
  if (!anchor) return;
  const anchorProgress = Math.max(0, Math.min(1, (2 - (finiteNumber(anchor.activeBlurPx) ?? 2)) / 2));
  const inferredTransitionStartOffsetMs =
    (finiteNumber(anchor.lineElapsedMs) ?? finiteNumber(anchor.timingMs) ?? 0) -
      anchorProgress * 250;
	  for (const frame of frames ?? []) {
	    if (frame.timingMs < anchor.timingMs) continue;
	    const elapsed = finiteNumber(frame?.lineElapsedMs) ?? finiteNumber(frame?.timingMs);
	    if (elapsed === null || elapsed < 0 || elapsed > 320) continue;
	    const adjustedElapsed = elapsed - inferredTransitionStartOffsetMs;
	    if (adjustedElapsed < 0 || adjustedElapsed > 320) continue;
    const progress = Math.max(0, Math.min(1, adjustedElapsed / 250));
    assertApprox(
      `${label} active ${frame.timingMs}ms`,
      frame.activeBlurPx,
      2 * (1 - progress),
      0.28,
    );
    if (includePrevious) {
      assertApprox(
        `${label} previous ${frame.timingMs}ms`,
        frame.previousBlurPx,
        2 * progress,
        0.28,
      );
    }
  }
}

function assertAppleFadeOutCurve(label, frames) {
  const samples = (frames ?? [])
    .map((frame) => ({
      timingMs: frame?.timingMs,
      elapsed: finiteNumber(frame?.lineElapsedMs),
      opacity: finiteNumber(frame?.previousOpacity),
    }))
    .filter((frame) =>
      frame.elapsed !== null &&
      frame.opacity !== null &&
      frame.elapsed >= 0 &&
      frame.elapsed <= 1000);
  assertTruthy(`${label}: opacity samples`, samples.length > 0);
  const firstDropIndex = samples.findIndex((frame) => frame.opacity < 0.995);
  assertTruthy(`${label}: opacity begins dropping`, firstDropIndex >= 0);
  if (firstDropIndex < 0) return;
  const checkedSamples = samples.slice(firstDropIndex);
  const anchor = checkedSamples.find((frame) => frame.opacity < 0.98) ?? checkedSamples[0];
  const inferredStartOffsetMs =
    anchor && anchor.opacity !== null
      ? anchor.elapsed - (1 - anchor.opacity) * 1000
      : 0;
  for (const frame of checkedSamples) {
    const adjustedElapsed = Math.max(0, frame.elapsed - inferredStartOffsetMs);
    const expectedOpacity = Math.max(0, Math.min(1, 1 - adjustedElapsed / 1000));
    assertApprox(
      `${label} ${frame.timingMs}ms`,
      frame.opacity,
      expectedOpacity,
      0.08,
    );
  }
}

function assertRapidScrollOverlapSample(sample) {
  assertEqual("rapid takeover first target source", sample.firstScroll?.source, "previous-layout");
  assertEqual("rapid takeover second target source", sample.secondScroll?.source, "previous-layout");
  assertEqual("rapid takeover third target source", sample.thirdScroll?.source, "previous-layout");
  assertEqual("rapid takeover second retargets the active spring", sample.secondScroll?.activeCountAtStart, 1);
  assertEqual("rapid takeover third still retargets one shared spring", sample.thirdScroll?.activeCountAtStart, 1);
  assertBetween(
    "rapid takeover second starts from in-flight scrollTop",
    sample.secondScroll?.from,
    (sample.firstScroll?.from ?? 0) + 1,
    (sample.firstScroll?.target ?? 0) - 1,
  );
  assertBetween(
    "rapid takeover third starts from in-flight scrollTop",
    sample.thirdScroll?.from,
    (sample.secondScroll?.from ?? 0) + 1,
    (sample.secondScroll?.target ?? 0) - 1,
  );
  assertIncludes("rapid takeover active text", sample.overlapFrame?.text ?? "", "Quick three");
  assertEqual("rapid takeover keeps one retained-velocity spring writer", sample.overlapFrame?.scrollActiveCount, 1);
  assertIncludes("rapid takeover cache sample active text", sample.afterFirstCompletion?.text ?? "", "Settle down");
  assertBetween("rapid takeover latest spring remains active during settle", sample.afterFirstCompletion?.scrollActiveCount, 0, 1);
  assertEqual(
    "rapid takeover cache active index survives stale completion",
    sample.afterFirstCompletion?.geometryActiveIdx,
    sample.afterFirstCompletion?.activeIndex,
  );
  assertApprox(
    "rapid takeover geometry cache tracks in-flight scrollTop",
    sample.afterFirstCompletion?.geometryScrollTop,
    sample.afterFirstCompletion?.scrollTop,
    8,
  );
  assertIncludes("rapid takeover final text", sample.after?.text ?? "", "Settle down");
  assertEqual("rapid takeover final scroll writers finish", sample.after?.scrollActiveCount, 0);
  assertEqual("rapid takeover final geometry active index", sample.after?.geometryActiveIdx, sample.after?.activeIndex);
}

function assertCollapsibleInterludeSwitch(sample) {
  assertIncludes("collapsible before starts on lyric", sample.before?.activeText ?? "", "Before gap");
  assertEqual("collapsible expand target source", sample.expandScroll?.source, "previous-layout");
  assertEqual("collapsible expand target row index", sample.expandScroll?.targetIndex, 1);
  assertEqual("collapsible collapse target source", sample.collapseScroll?.source, "previous-layout");
  assertEqual("collapsible collapse target row index", sample.collapseScroll?.targetIndex, 2);

  const expand0 = sample.expandFrames?.find((frame) => frame.timingMs === 0);
  const expand100 = sample.expandFrames?.find((frame) => frame.timingMs === 100);
  const expand300 = sample.expandFrames?.find((frame) => frame.timingMs === 300);
  const expand350 = sample.expandFrames?.find((frame) => frame.timingMs === 350);
  const collapse0 = sample.collapseFrames?.find((frame) => frame.timingMs === 0);
  const collapse100 = sample.collapseFrames?.find((frame) => frame.timingMs === 100);
  const collapse300 = sample.collapseFrames?.find((frame) => frame.timingMs === 300);
  const collapse350 = sample.collapseFrames?.find((frame) => frame.timingMs === 350);

  for (const frame of sample.expandFrames ?? []) {
    assertTruthy(`collapsible expand interlude frame ${frame.timingMs}ms`, frame.interlude);
    assertIncludes(`collapsible expand class ${frame.timingMs}ms`, frame.interlude?.className ?? "", "display-synced-line");
    assertIncludes(`collapsible expand carries collapsible class ${frame.timingMs}ms`, frame.interlude?.className ?? "", "collapsible");
    assertEqual(`collapsible expand active ${frame.timingMs}ms`, frame.interlude?.active, true);
    assertEqual(`collapsible expand animation ${frame.timingMs}ms`, frame.interlude?.animationName, "appleLyricHeightExpand");
    assertEqual(`collapsible expand duration ${frame.timingMs}ms`, frame.interlude?.animationDuration, "0.3s");
    assertEqual(`collapsible expand overflow ${frame.timingMs}ms`, frame.interlude?.overflow, "visible");
    if (frame.timingMs === 0) {
      assertBetween("collapsible expand line scale starts from collapsed state", frame.interlude?.lineScale, 0.1, 0.45);
    } else if (frame.timingMs === 100) {
      assertBetween("collapsible expand line scale is in-flight by 100ms", frame.interlude?.lineScale, 0.45, 1.051);
    } else {
      assertTransformScale(`collapsible expand line scale ${frame.timingMs}ms`, frame.interlude?.lineTransform, 1.05, 0.015);
    }
    assertActiveIsOnlyScrollTarget(`collapsible expand active scroll target ${frame.timingMs}ms`, frame.rowStack);
    assertAppleRowsUseBlock(`collapsible expand row block display ${frame.timingMs}ms`, frame.rowStack);
    assertNoVisibleTextOverlap(
      `collapsible expand visual stack ${frame.timingMs}ms`,
      (frame.rowStack ?? []).filter((row) => row.kind !== "interlude"),
    );
  }
  assertBetween("collapsible expand 100ms height in-flight", expand100?.interlude?.height, 1, 88);
  assertBetween("collapsible expand 300ms height reaches Apple keyframe", expand300?.interlude?.height, 60, 120);
  assertBetween("collapsible expand final height visible", expand350?.interlude?.height, 1, Number.POSITIVE_INFINITY);
  assertBetween(
    "collapsible expand height increases",
    (expand300?.interlude?.height ?? 0) - (expand0?.interlude?.height ?? 0),
    1,
    Number.POSITIVE_INFINITY,
  );

  for (const frame of sample.collapseFrames ?? []) {
    assertTruthy(`collapsible collapse interlude frame ${frame.timingMs}ms`, frame.interlude);
    assertIncludes(`collapsible collapse class ${frame.timingMs}ms`, frame.interlude?.className ?? "", "display-synced-line");
    assertIncludes(`collapsible collapse carries collapsible class ${frame.timingMs}ms`, frame.interlude?.className ?? "", "collapsible");
    assertEqual(`collapsible collapse inactive ${frame.timingMs}ms`, frame.interlude?.active, false);
    assertEqual(`collapsible collapse animation ${frame.timingMs}ms`, frame.interlude?.animationName, "appleLyricHeightCollapse");
    assertEqual(`collapsible collapse duration ${frame.timingMs}ms`, frame.interlude?.animationDuration, "0.3s");
    assertEqual(`collapsible collapse overflow ${frame.timingMs}ms`, frame.interlude?.overflow, "hidden");
    if (frame.timingMs === 0) {
      assertBetween("collapsible collapse line scale starts from current state", frame.interlude?.lineScale, 0.45, 1.06);
    } else if (frame.timingMs === 100) {
      assertBetween("collapsible collapse line scale is near collapsed by 100ms", frame.interlude?.lineScale, 0.1, 0.55);
    } else {
      assertTransformScale(`collapsible collapse line scale ${frame.timingMs}ms`, frame.interlude?.lineTransform, 0.1, 0.02);
    }
    assertActiveIsOnlyScrollTarget(`collapsible collapse active scroll target ${frame.timingMs}ms`, frame.rowStack);
    assertAppleRowsUseBlock(`collapsible collapse row block display ${frame.timingMs}ms`, frame.rowStack);
    assertNoVisibleTextOverlap(
      `collapsible collapse visual stack ${frame.timingMs}ms`,
      (frame.rowStack ?? []).filter((row) => row.kind !== "interlude"),
    );
  }
  assertBetween("collapsible collapse 100ms height in-flight", collapse100?.interlude?.height, 1, 88);
  assertApprox("collapsible collapse 300ms height reaches zero", collapse300?.interlude?.height, 0, 5);
  assertApprox("collapsible collapse final height zero", collapse350?.interlude?.height, 0, 0.5);
  assertBetween(
    "collapsible collapse height decreases",
    (collapse0?.interlude?.height ?? 0) - (collapse300?.interlude?.height ?? 0),
    1,
    Number.POSITIVE_INFINITY,
  );
  assertEqual("collapsible expanded active", sample.expanded?.interlude?.active, true);
  assertEqual("collapsible collapsed inactive", sample.collapsed?.interlude?.active, false);
  assertApprox("collapsible collapsed height stays zero", sample.collapsed?.interlude?.height, 0, 0.5);
}

function assertNonAdjacentSeekSample(sample) {
  const frames = sample.frames ?? [];
  const first = frames.find((frame) => frame.timingMs === 0);
  const at50 = frames.find((frame) => frame.timingMs === 50);
  const at100 = frames.find((frame) => frame.timingMs === 100);
  const at125 = frames.find((frame) => frame.timingMs === 125);
  const mid = frames.find((frame) => frame.timingMs === 175);
  const end = frames.find((frame) => frame.timingMs === 350);
  assertIncludes("non-adjacent seek starts from previous lyric", sample.before.text ?? "", "That I just wanna get with you");
  assertIncludes("non-adjacent seek active text", sample.afterJump?.text ?? "", "Can't help it I want you");
  assertEqual("non-adjacent seek scroll source", sample.scrollAnimation?.source, "previous-layout");
  assertLineCssTransitionFollowsScroll("non-adjacent seek CSS transition phase", sample);
  assertEqual("non-adjacent seek target row index", sample.scrollAnimation?.targetIndex, 5);
  const seekScrolls = (sample.scrollHistory ?? [])
    .filter((entry) =>
      Number(entry?.targetIndex) === 5 &&
      entry?.source === "previous-layout" &&
      entry?.force === false &&
      Number(entry?.duration) === 350)
    .slice(-2);
  assertEqual("non-adjacent seek records Apple double updateScroll", seekScrolls.length, 2);
  assertEqual("non-adjacent first seek scroll starts with no active writer", seekScrolls[0]?.activeCountAtStart, 0);
  assertEqual("non-adjacent second seek scroll starts while first writer is active", seekScrolls[1]?.activeCountAtStart, 1);
  assertTruthy(
    "non-adjacent second seek scroll retargets immediately after first",
    Number.isFinite(seekScrolls[0]?.startedAt) &&
      Number.isFinite(seekScrolls[1]?.startedAt) &&
      seekScrolls[1].startedAt >= seekScrolls[0].startedAt &&
      seekScrolls[1].startedAt - seekScrolls[0].startedAt <= 24,
  );
  assertApprox("non-adjacent double updateScroll target is stable", seekScrolls[1]?.target, seekScrolls[0]?.target, 1);
  assertApprox("non-adjacent latest scroll is second Apple updateScroll", sample.scrollAnimation?.startedAt, seekScrolls[1]?.startedAt, 1);
  if (sample.scrollAnimation) {
    const {
      from,
      target,
      duration,
      targetRowTop,
      anchorPx,
      offsetRatio,
      topSpacerHeight,
      topMargin,
      baseScrollTop,
    } = sample.scrollAnimation;
    assertApprox("non-adjacent seek fullscreen offset-ratio", offsetRatio, APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO, 0.001);
    assertApprox("non-adjacent seek top spacer follows fullscreen offset-ratio", topSpacerHeight, anchorPx, 1.25);
    assertApprox("non-adjacent seek top margin follows Apple", topMargin, 55, 0.1);
    assertApprox(
      "non-adjacent seek target formula uses Apple top spacer plus margin",
      target,
      expectedAppleScrollTarget(sample.scrollAnimation),
      0.75,
    );
    const delta = target - from;
    assertEqual("non-adjacent seek uses shared spring", sample.scrollAnimation.motion, "spring");
    const tolerance = Math.max(4, Math.abs(delta) * 0.05);
    for (const frame of frames) {
      const elapsed = Number.isFinite(frame.scrollElapsedMs)
        ? frame.scrollElapsedMs
        : frame.timingMs;
      const expected = from + delta * springStepProgress(elapsed / 1000, 140, 24);
      assertApprox(`non-adjacent seek scrollTop ${frame.timingMs}ms spring`, frame.scrollTop, expected, tolerance);
    }
  }
  assertEqual("non-adjacent seek has no stale release rows", sample.releaseRows.length, 0);
  assertIncludes("non-adjacent seek previous row", sample.previousRow?.text ?? "", "But I, I");
  assertEqual("non-adjacent previous row is not timed release", sample.previousRow?.timedRelease, false);
  assertEqual("non-adjacent seek has no timed release row", sample.oldActiveRow, null);
  const earlyInFlight = [at50, at100, at125].find((frame) =>
    (finiteNumber(frame?.activeScale) ?? 1) > 1.004 &&
      (finiteNumber(frame?.activeScale) ?? 1.05) < 1.0499 &&
      (finiteNumber(frame?.activePaddingTop) ?? 0) > 1 &&
      (finiteNumber(frame?.activePaddingTop) ?? 12) < 11.95);
  assertTruthy("non-adjacent current line is in-flight by 125ms", earlyInFlight);
	  assertAppleLinePaddingCurve("non-adjacent seek Apple current padding curve", frames, {
	    includePrevious: false,
	  });
	  assertAppleScaleCurve("non-adjacent seek Apple current scale curve", frames, {
	    includePrevious: false,
	  });
	  assertAppleBlurCurve("non-adjacent seek Apple blur curve", frames, { includePrevious: false });
  assertAppleFadeOutCurve("non-adjacent seek Apple fade-out curve", frames);
  const earlyRelease = [at50, at100, at125].find((frame) =>
    Math.abs(finiteNumber(frame?.layoutResidualPx) ?? 0) > 2 &&
      Math.abs(finiteNumber(frame?.layoutResidualPx) ?? 0) < 23.95);
  const earlyReleaseDelta = finiteNumber(earlyRelease?.oldActiveReleaseDeltaPx) ??
    Math.abs(finiteNumber(earlyRelease?.layoutResidualPx) ?? 0);
  assertBetween("non-adjacent old active row is releasing by 125ms", earlyReleaseDelta, 2, 23.95);
  assertBetween("non-adjacent active top has in-flight old-active residual by 125ms", Math.abs(earlyRelease?.layoutResidualPx ?? 0), 2, 23.95);
  assertApprox(
    "non-adjacent residual matches in-flight old-active release",
    earlyRelease?.layoutResidualPx,
    -(finiteNumber(earlyRelease?.oldActiveReleaseDeltaSinceFirstPx) ??
      finiteNumber(earlyRelease?.oldActiveReleaseDeltaPx) ??
      Math.abs(finiteNumber(earlyRelease?.layoutResidualPx) ?? 0)),
    3,
  );
  assertTruthy(
    "non-adjacent old active row releases during seek",
    (at100?.oldActiveReleaseDeltaPx ?? 0) > 10 ||
      (mid?.oldActiveReleaseDeltaPx ?? 0) > 10,
  );
  assertBetween(
    "non-adjacent old active row final release delta",
    end?.oldActiveReleaseDeltaPx,
    20,
    28,
  );
  const nonAdjacentVisibleTop = expectedAppleVisibleTop(sample.scrollAnimation);
	  assertApprox(
	    "non-adjacent final pure-scroll top uses Apple top spacer plus margin",
	    (end?.scrollOnlyActiveTop ?? 0) + (first?.oldActiveReleaseDeltaPx ?? 0),
	    nonAdjacentVisibleTop,
	    4,
	  );
  assertApprox(
    "non-adjacent final residual matches old-active release",
    end?.layoutResidualPx,
    -(end?.oldActiveReleaseDeltaSinceFirstPx ?? end?.oldActiveReleaseDeltaPx ?? 0),
    3,
  );
	  assertApprox(
	    "non-adjacent final active top keeps Apple old-active release residual",
	    end?.activeTop,
	    (nonAdjacentVisibleTop ?? 0) - (end?.oldActiveReleaseDeltaPx ?? 0),
	    4,
	  );
	  assertCombinedActiveTopMotion("non-adjacent seek combined top motion", frames, {
	    releaseDelta: (frame) => frame?.oldActiveReleaseDeltaSinceFirstPx ?? frame?.oldActiveReleaseDeltaPx ?? 0,
	    tolerancePx: 4,
	  });
	  assertCombinedActiveTextCenterMotion("non-adjacent seek text-center motion", frames, {
	    releaseDelta: (frame) => frame?.oldActiveReleaseDeltaSinceFirstPx ?? frame?.oldActiveReleaseDeltaPx ?? 0,
	    tolerancePx: 6,
	  });
	  assertApprox("non-adjacent seek scroll finishes at target", end?.scrollTop, sample.after?.scrollTop, 4);
  for (const frame of frames) {
    assertEqual(`non-adjacent old active row not timed release ${frame.timingMs}ms`, frame.oldActiveRow?.timedRelease, false);
    assertActiveIsOnlyScrollTarget(`non-adjacent seek active scroll target ${frame.timingMs}ms`, frame.rowStack);
    assertAppleRowsUseBlock(`non-adjacent seek row block display ${frame.timingMs}ms`, frame.rowStack);
    assertNoVisibleTextOverlap(`non-adjacent seek visual text stack ${frame.timingMs}ms`, frame.rowStack);
  }
}

function assertAdjacentSeekJumpSample(sample) {
  assertIncludes("adjacent seek starts from previous lyric", sample.before.text ?? "", "You see through");
  assertIncludes("adjacent seek active text", sample.afterJump.text ?? "", "That I just wanna get with you");
  assertEqual("adjacent seek only moves one row", sample.indexDelta, 1);
  assertBetween("adjacent seek playback jump is over Apple 1000ms threshold", sample.positionDelta, 1.001, 1.2);
  assertEqual("adjacent seek latest scroll source", sample.scrollAnimation?.source, "previous-layout");
  assertEqual("adjacent seek target row index", sample.scrollAnimation?.targetIndex, 1);
  const seekScrolls = (sample.scrollHistory ?? [])
    .filter((entry) =>
      Number(entry?.targetIndex) === 1 &&
      entry?.source === "previous-layout" &&
      entry?.force === false &&
      Number(entry?.duration) === 350)
    .slice(-2);
  assertEqual("adjacent seek jump records Apple double updateScroll", seekScrolls.length, 2);
  assertEqual("adjacent first seek scroll starts with no active writer", seekScrolls[0]?.activeCountAtStart, 0);
  assertEqual("adjacent second seek scroll starts while first writer is active", seekScrolls[1]?.activeCountAtStart, 1);
  assertTruthy(
    "adjacent second seek scroll retargets immediately after first",
    Number.isFinite(seekScrolls[0]?.startedAt) &&
      Number.isFinite(seekScrolls[1]?.startedAt) &&
      seekScrolls[1].startedAt >= seekScrolls[0].startedAt &&
      seekScrolls[1].startedAt - seekScrolls[0].startedAt <= 24,
  );
  assertApprox("adjacent double updateScroll target is stable", seekScrolls[1]?.target, seekScrolls[0]?.target, 1);
  assertApprox("adjacent latest scroll is second Apple updateScroll", sample.scrollAnimation?.startedAt, seekScrolls[1]?.startedAt, 1);
}

function assertTokenAnimationFrames(frames) {
  const byLabel = new Map(frames.map((frame) => [frame.label, frame]));
  const ordinaryBefore = byLabel.get("ordinary-gradient-before-lift");
  const ordinaryMid = byLabel.get("ordinary-mid-lift");
  const ordinaryDone = byLabel.get("ordinary-finished");
  const slowBefore = byLabel.get("slow-before");
  const slowRise = byLabel.get("slow-first-rise-mid");
  const slowPeak = byLabel.get("slow-first-peak");
  const slowRelease = byLabel.get("slow-first-release-mid");
  const slowDoneTailStart = byLabel.get("slow-first-complete-tail-start");

  for (const frame of frames) {
    assertIncludes(`token frame active text ${frame.label}`, frame.activeText ?? "", "That I just wanna get with you");
    assertIncludes(`token frame ordinary text ${frame.label}`, frame.ordinary?.text ?? "", "That ");
    assertIncludes(`token frame slow text ${frame.label}`, frame.slow?.text ?? "", "wanna ");
    assertStableGradientTextPaint(`token frame ordinary stable CSS-var sweep ${frame.label}`, frame.ordinary);
    assertStableGradientTextPaint(`token frame slow first stable CSS-var sweep ${frame.label}`, frame.slow?.first);
  }

  assertApprox("ordinary before lift y", ordinaryBefore?.ordinary?.y, 0, 0.16);
  assertApprox("ordinary before lift scale", ordinaryBefore?.ordinary?.scale, 1, 0.01);
  assertApprox("ordinary gradient starts at token start", ordinaryBefore?.ordinary?.gradientProgress, 1.2, 6);

  assertApprox("ordinary mid lift y", ordinaryMid?.ordinary?.y, -1, 0.22);
  assertApprox("ordinary mid lift scale", ordinaryMid?.ordinary?.scale, 1, 0.01);
  assertApprox("ordinary mid linear gradient", ordinaryMid?.ordinary?.gradientProgress, 75.3, 7);

  assertApprox("ordinary final y", ordinaryDone?.ordinary?.y, -2, 0.16);
  assertApprox("ordinary final scale", ordinaryDone?.ordinary?.scale, 1, 0.01);
  assertStableGradientTextPaint("ordinary finished stable text paint", ordinaryDone?.ordinary);

  assertApprox("slow before first scale", slowBefore?.slow?.first?.scale, 1, 0.01);
  assertApprox("slow before first y", slowBefore?.slow?.first?.y, 0, 0.16);
  assertStableGradientTextPaint("slow before first stable text paint", slowBefore?.slow?.first);

  assertApprox("slow rise first scale", slowRise?.slow?.first?.scale, 1.025, 0.014);
  assertApprox("slow rise first y", slowRise?.slow?.first?.y, -1.03, 0.22);
  assertApprox("slow rise first glyph is fully passed by the word-wide front", slowRise?.slow?.first?.gradientProgress, 100, 2);
  assertApprox("slow rise first shadow blur", slowRise?.slow?.first?.shadow?.blur, 5, 1.4);
  assertApprox("slow rise first shadow opacity", slowRise?.slow?.first?.shadow?.opacity, 0.2, 0.1);
  assertGlowLayerHasNoBackgroundBox("slow rise first", slowRise?.slow?.first);
  assertTruthy("slow stagger second letter lags first", (slowRise?.slow?.second?.scale ?? 1) < (slowRise?.slow?.first?.scale ?? 1));
  assertApprox("slow stagger tail remains untouched", slowRise?.slow?.tail?.scale, 1, 0.01);

  assertApprox("slow peak first scale", slowPeak?.slow?.first?.scale, 1.05, 0.012);
  assertApprox("slow peak first y", slowPeak?.slow?.first?.y, -2.05, 0.18);
  assertApprox("slow peak first glyph remains behind the word-wide front", slowPeak?.slow?.first?.gradientProgress, 100, 2);
  assertApprox("slow peak first shadow blur", slowPeak?.slow?.first?.shadow?.blur, 10, 1.2);
  assertApprox("slow peak first shadow opacity", slowPeak?.slow?.first?.shadow?.opacity, 0.4, 0.08);
  assertGlowLayerHasNoBackgroundBox("slow peak first", slowPeak?.slow?.first);

  assertApprox("slow release first scale", slowRelease?.slow?.first?.scale, 1.025, 0.014);
  assertApprox("slow release first y", slowRelease?.slow?.first?.y, -2.03, 0.18);
  assertApprox("slow release first glyph remains complete", slowRelease?.slow?.first?.gradientProgress, 100, 2);
  assertApprox("slow release first shadow blur", slowRelease?.slow?.first?.shadow?.blur, 7, 1.2);
  assertApprox("slow release first shadow opacity", slowRelease?.slow?.first?.shadow?.opacity, 0.2, 0.1);
  assertGlowLayerHasNoBackgroundBox("slow release first", slowRelease?.slow?.first);

  assertApprox("slow first done scale", slowDoneTailStart?.slow?.first?.scale, 1, 0.012);
  assertApprox("slow first done y", slowDoneTailStart?.slow?.first?.y, -2, 0.18);
  assertStableGradientTextPaint("slow first done stable text paint", slowDoneTailStart?.slow?.first);
  assertApprox("slow first done shadow opacity", slowDoneTailStart?.slow?.first?.shadow?.opacity, 0, 0.02);
  assertEqual("slow trailing whitespace stays outside Apple letter animation", slowDoneTailStart?.slow?.letters?.length, 5);
  assertEqual("slow animated tail is the final visible glyph", slowDoneTailStart?.slow?.tail?.text, "a");
  assertBetween("slow visible tail just starts after Apple content-length stagger", slowDoneTailStart?.slow?.tail?.scale, 1.001, 1.012);
  assertBetween("slow visible tail y is still near start", slowDoneTailStart?.slow?.tail?.y, -0.3, -0.02);
  for (const frame of [slowRise, slowPeak, slowRelease, slowDoneTailStart]) {
    const partialLetters = (frame?.slow?.letters ?? []).filter((letter) =>
      (letter?.gradientProgress ?? -20) > -19 && (letter?.gradientProgress ?? 100) < 99);
    assertBetween(
      `slow ${frame?.label ?? "unknown"} keeps one continuous color front`,
      partialLetters.length,
      0,
      1,
    );
  }
}

function assertCjkTokenAnimationFrames(frames) {
  const byLabel = new Map(frames.map((frame) => [frame.label, frame]));
  const before = byLabel.get("cjk-slow-before");
  const rise = byLabel.get("cjk-slow-rise-mid");
  const peak = byLabel.get("cjk-slow-peak");
  const release = byLabel.get("cjk-slow-release-mid");
  const done = byLabel.get("cjk-slow-complete");

  for (const frame of frames) {
    assertIncludes(`cjk active text ${frame.label}`, frame.activeText ?? "", "慢词 now");
    assertEqual(`cjk active font weight ${frame.label}`, frame.activeFontWeight, "600");
    assertIncludes(`cjk active font family ${frame.label}`, frame.activeFontFamily ?? "", "PingFang");
    assertApprox(`cjk current scale ${frame.label}`, frame.activeScale, 1.05, 0.012);
    assertEqual(`cjk slow token text ${frame.label}`, frame.slow?.text, "慢");
    assertEqual(`cjk slow single glyph ${frame.label}`, frame.slow?.letters?.length, 1);
  }

  assertApprox("cjk slow before scale", before?.slow?.first?.scale, 1, 0.01);
  assertApprox("cjk slow before y", before?.slow?.first?.y, 0, 0.16);
  assertStableGradientTextPaint("cjk slow before stable text paint", before?.slow?.first);

  assertApprox("cjk slow rise scale", rise?.slow?.first?.scale, 1.025, 0.014);
  assertApprox("cjk slow rise y", rise?.slow?.first?.y, -1.03, 0.22);
  assertApprox("cjk slow rise word-wide gradient", rise?.slow?.first?.gradientProgress, 5.86, 5);
  assertApprox("cjk slow rise shadow blur", rise?.slow?.first?.shadow?.blur, 5, 1.4);
  assertApprox("cjk slow rise shadow opacity", rise?.slow?.first?.shadow?.opacity, 0.2, 0.1);
  assertGlowLayerHasNoBackgroundBox("cjk slow rise", rise?.slow?.first);

  assertApprox("cjk slow peak scale", peak?.slow?.first?.scale, 1.05, 0.012);
  assertApprox("cjk slow peak y", peak?.slow?.first?.y, -2.05, 0.18);
  assertApprox("cjk slow peak word-wide gradient", peak?.slow?.first?.gradientProgress, 31.72, 5);
  assertApprox("cjk slow peak shadow blur", peak?.slow?.first?.shadow?.blur, 10, 1.2);
  assertApprox("cjk slow peak shadow opacity", peak?.slow?.first?.shadow?.opacity, 0.4, 0.08);
  assertGlowLayerHasNoBackgroundBox("cjk slow peak", peak?.slow?.first);

  assertApprox("cjk slow release scale", release?.slow?.first?.scale, 1.025, 0.014);
  assertApprox("cjk slow release y", release?.slow?.first?.y, -2.03, 0.18);
  assertApprox("cjk slow release word-wide gradient", release?.slow?.first?.gradientProgress, 57.59, 5);
  assertApprox("cjk slow release shadow blur", release?.slow?.first?.shadow?.blur, 7, 1.2);
  assertApprox("cjk slow release shadow opacity", release?.slow?.first?.shadow?.opacity, 0.2, 0.1);
  assertGlowLayerHasNoBackgroundBox("cjk slow release", release?.slow?.first);

  assertApprox("cjk slow done scale", done?.slow?.first?.scale, 1, 0.012);
  assertApprox("cjk slow done y", done?.slow?.first?.y, -2, 0.18);
  assertStableGradientTextPaint("cjk slow done stable text paint", done?.slow?.first);
  assertApprox("cjk slow done shadow opacity", done?.slow?.first?.shadow?.opacity, 0, 0.02);
}

function assertHeldClockSmoothing(sample) {
  assertEqual("held clock raw fixture position stable first", sample?.first?.fixturePosition, "7.820");
  assertEqual("held clock raw fixture position stable second", sample?.second?.fixturePosition, "7.820");
  assertIncludes("held clock active text first", sample?.first?.activeText ?? "", "That I just wanna get with you");
  assertIncludes("held clock active text second", sample?.second?.activeText ?? "", "That I just wanna get with you");
  assertIncludes("held clock ordinary token", sample?.first?.ordinary?.text ?? "", "That ");
  assertIncludes("held clock ordinary token second", sample?.second?.ordinary?.text ?? "", "That ");
  const firstGradient = sample?.first?.ordinary?.gradientProgress;
  const secondGradient = sample?.second?.ordinary?.gradientProgress;
  const secondFinished = isStableGradientTextPaint(sample?.second?.ordinary);
  assertTruthy(
    "held clock ordinary gradient advances between raw updates",
    (
      Number.isFinite(firstGradient) &&
      Number.isFinite(secondGradient) &&
      secondGradient > firstGradient + 12
    ) ||
      (Number.isFinite(firstGradient) && firstGradient > 45 && secondFinished),
  );
  assertTruthy(
    "held clock ordinary lift advances between raw updates",
    (sample?.second?.ordinary?.y ?? 0) < (sample?.first?.ordinary?.y ?? 0) - 0.2,
  );
}

function assertSupplementaryForceScroll(sample) {
  assertEqual("supplementary force roles hidden before reveal", sample?.companionRolesBefore?.length ?? 0, 0);
  assertTruthy(
    "supplementary force translation visible after reveal",
    sample?.companionRolesAfter?.includes("translation"),
  );
  assertIncludes("supplementary reveal active text", sample?.revealHiddenStart?.activeText ?? "", "That I just wanna get with you");
  assertEqual("supplementary reveal hidden translation state", sample?.revealHiddenStart?.translation?.appleVisible, "false");
  assertEqual("supplementary reveal hidden romaji state", sample?.revealHiddenStart?.romaji?.appleVisible, "false");
  assertApprox("supplementary reveal hidden translation max-height", cssPx(sample?.revealHiddenStart?.translation?.maxHeight), 0, 0.2);
  assertApprox("supplementary reveal hidden translation opacity", cssPx(sample?.revealHiddenStart?.translation?.opacity), 0, 0.02);
  assertApprox("supplementary reveal hidden translation y", transformTranslateY(sample?.revealHiddenStart?.translation?.transform), -10, 0.2);
  assertApprox("supplementary reveal hidden romaji max-height", cssPx(sample?.revealHiddenStart?.romaji?.maxHeight), 0, 0.2);
  assertApprox("supplementary reveal hidden romaji opacity", cssPx(sample?.revealHiddenStart?.romaji?.opacity), 0, 0.02);
  assertApprox("supplementary reveal hidden romaji y", transformTranslateY(sample?.revealHiddenStart?.romaji?.transform), -10, 0.2);

  const mid = (sample?.revealFrames ?? []).find((frame) => frame.timingMs === 300);
  const final = (sample?.revealFrames ?? []).find((frame) => frame.timingMs === 620);
  const watcherFrame = (sample?.revealFrames ?? []).find((frame) => frame.timingMs === 750);
  const translationFinalHeight = cssPx(final?.translation?.maxHeight);
  const romajiFinalHeight = cssPx(final?.romaji?.maxHeight);
  assertEqual("supplementary reveal final translation state", final?.translation?.appleVisible, "true");
  assertEqual("supplementary reveal final romaji state", final?.romaji?.appleVisible, "true");
  assertBetween("supplementary reveal 300ms translation max-height in-flight", cssPx(mid?.translation?.maxHeight), 4, Math.max(5, translationFinalHeight - 0.5));
  assertBetween("supplementary reveal 300ms translation opacity in-flight", cssPx(mid?.translation?.opacity), 0.04, 0.44);
  assertBetween("supplementary reveal 300ms translation y in-flight", transformTranslateY(mid?.translation?.transform), -9.8, -0.2);
  assertBetween("supplementary reveal 300ms romaji max-height in-flight", cssPx(mid?.romaji?.maxHeight), 4, Math.max(5, romajiFinalHeight - 0.5));
  assertBetween("supplementary reveal 300ms romaji opacity in-flight", cssPx(mid?.romaji?.opacity), 0.04, 0.98);
  assertBetween("supplementary reveal 300ms romaji y in-flight", transformTranslateY(mid?.romaji?.transform), -9.8, -0.2);
  assertApprox("supplementary reveal final translation opacity", cssPx(final?.translation?.opacity), 0.45, 0.03);
  assertApprox("supplementary reveal final translation y", transformTranslateY(final?.translation?.transform), 0, 0.2);
  assertApprox("supplementary reveal final romaji opacity", cssPx(final?.romaji?.opacity), 1, 0.03);
  assertApprox("supplementary reveal final romaji y", transformTranslateY(final?.romaji?.transform), 0, 0.2);
  assertEqual("supplementary watcher frame translation still visible", watcherFrame?.translation?.appleVisible, "true");
  assertTruthy("supplementary force scroll fired", sample?.forceScroll?.serial);
  assertEqual("supplementary force scroll flag", sample?.forceScroll?.force, true);
  assertEqual("supplementary force scroll source", sample?.forceScroll?.source, "live-layout");
  assertApprox(
    "supplementary force scroll fullscreen offset-ratio",
    sample?.forceScroll?.offsetRatio,
    APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO,
    0.001,
  );
  assertApprox("supplementary force scroll top margin", sample?.forceScroll?.topMargin, 55, 0.1);
  assertApprox("supplementary force scroll duration", sample?.forceScroll?.duration, 350, 0.1);
  assertNoVisibleTextOverlap("supplementary force visual text stack", sample?.rowStackAfter);
  assertVisibleTextGap("supplementary force readable text gap", sample?.rowStackAfter, 24);
}

function assertEqual(label, actual, expected) {
  if (actual !== expected) failures.push(`${label}: expected ${expected}, got ${actual}`);
}

function assertNotEqual(label, actual, rejected) {
  if (actual === rejected) failures.push(`${label}: should not be ${rejected}`);
}

function assertApprox(label, actual, expected, tolerance) {
  if (!Number.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
    failures.push(`${label}: expected ${expected} +/- ${tolerance}, got ${actual}`);
  }
}

function assertBetween(label, actual, min, max) {
  if (!Number.isFinite(actual) || actual < min || actual > max) {
    failures.push(`${label}: expected ${min}..${max}, got ${actual}`);
  }
}

function assertGlowLayerHasNoBackgroundBox(label, letter) {
  if (letter?.glowBackgroundImage == null) {
    assertStableGradientTextPaint(`${label} single-layer Apple letter paint`, letter);
    return;
  }
  assertEqual(`${label} shell has no background`, letter?.shellBackgroundImage, "none");
  assertEqual(`${label} shell has no text shadow`, letter?.shellTextShadow, "none");
  assertEqual(`${label} foreground has no text shadow`, letter?.foregroundTextShadow, "none");
  assertEqual(`${label} glow has no background`, letter?.glowBackgroundImage, "none");
  assertEqual(`${label} glow fill stays transparent`, letter?.glowTextFillColor, "rgba(0, 0, 0, 0)");
}

function assertScreenshotHasNoRectangularGlowBlock(label, pngBuffer, rect) {
  if (!rect) {
    failures.push(`${label}: missing slow-letter rect for pixel check`);
    return;
  }
  const image = decodePngRgba(pngBuffer);
  const cornerPairs = [
    { ix: rect.left + 2, iy: rect.top + 2, ox: rect.left - 6, oy: rect.top - 6 },
    { ix: rect.right - 2, iy: rect.top + 2, ox: rect.right + 6, oy: rect.top - 6 },
    { ix: rect.left + 2, iy: rect.bottom - 2, ox: rect.left - 6, oy: rect.bottom + 6 },
    { ix: rect.right - 2, iy: rect.bottom - 2, ox: rect.right + 6, oy: rect.bottom + 6 },
  ];
  const deltas = cornerPairs.map((point) => colorDistance(
    samplePatchAverage(image, point.ix, point.iy, 5),
    samplePatchAverage(image, point.ox, point.oy, 5),
  ));
  const averageDelta = deltas.reduce((sum, value) => sum + value, 0) / deltas.length;
  const brightCorners = deltas.filter((delta) => delta > 36).length;
  report.pixelSamples.push({
    label,
    rect: {
      left: rect.left,
      top: rect.top,
      right: rect.right,
      bottom: rect.bottom,
      width: rect.width,
      height: rect.height,
    },
    averageDelta,
    cornerDeltas: deltas,
    brightCorners,
  });
  if (averageDelta > 32 && brightCorners >= 3) {
    failures.push(
      `${label}: rectangular glow corner artifact suspected, average delta ${averageDelta.toFixed(1)}, corners ${deltas.map((v) => v.toFixed(1)).join("/")}`,
    );
  }
}

function assertScreenshotHasOpaqueLyricBackground(label, pngBuffer, sample) {
  const image = decodePngRgba(pngBuffer);
  const viewport = sample?.viewport;
  const columnVeilRect = sample?.columnVeil?.rect;
  const columnRect = sample?.column?.rect;
  if (!viewport || !columnVeilRect || !columnRect) {
    failures.push(`${label}: missing lyric background geometry for pixel check`);
    return;
  }
  const samplePoints = [
    {
      name: "column left veil",
      x: columnVeilRect.left + 18,
      y: Math.max(18, columnRect.top + 18),
    },
    {
      name: "column right edge",
      x: Math.min(viewport.width - 18, columnVeilRect.right - 18),
      y: viewport.height * 0.5,
    },
    {
      name: "column lower edge",
      x: Math.min(viewport.width - 24, Math.max(columnRect.left + 24, columnRect.right - 24)),
      y: Math.min(viewport.height - 24, columnRect.bottom - 24),
    },
  ];
  for (const point of samplePoints) {
    const rgb = samplePatchAverage(image, point.x, point.y, 15);
    const lum = relativeLuminance(rgb);
    const maxChannel = Math.max(...rgb);
    report.pixelSamples.push({
      label: `${label} controlled lyric backdrop ${point.name}`,
      point: {
        x: point.x,
        y: point.y,
      },
      rgb,
      luminance: lum,
      maxChannel,
    });
    if (lum < 2 || maxChannel < 8) {
      failures.push(
        `${label}: ${point.name} collapsed to near-black instead of artwork-driven backdrop, rgb ${rgb.map((v) => v.toFixed(1)).join("/")}, luminance ${lum.toFixed(1)}`,
      );
    }
    if (lum > 82 || maxChannel > 132) {
      failures.push(
        `${label}: ${point.name} is too bright/transparent for readable lyrics, rgb ${rgb.map((v) => v.toFixed(1)).join("/")}, luminance ${lum.toFixed(1)}`,
      );
    }
  }
}

function rectFromTopBottom(top, bottom) {
  if (!Number.isFinite(top) || !Number.isFinite(bottom)) return null;
  return { top, bottom };
}

function rectCenterY(rect) {
  if (!rect) return null;
  const top = finiteNumber(rect.top);
  const bottom = finiteNumber(rect.bottom);
  if (top === null || bottom === null) return null;
  return (top + bottom) / 2;
}

function assertNoVerticalOverlap(label, previous, active, next) {
  const tolerance = 1.25;
  if (previous && active && previous.bottom > active.top + tolerance) {
    failures.push(
      `${label}: previous overlaps active by ${(previous.bottom - active.top).toFixed(2)}px`,
    );
  }
  if (active && next && active.bottom > next.top + tolerance) {
    failures.push(
      `${label}: active overlaps next by ${(active.bottom - next.top).toFixed(2)}px`,
    );
  }
}

function assertNoVisibleTextOverlap(label, rowStack) {
  const tolerance = 1.25;
  const visibleRect = readableLyricRect;
  const visibleRows = (rowStack ?? [])
    .filter((row) => {
      const rowRect = row?.rowStyle?.rect;
      if (rowRect && rowRect.height <= 0.5) return false;
      const rect = visibleRect(row);
      if (!rect) return false;
      if (rect.height <= 0.5 || rect.width <= 0.5) return false;
      if (rect.bottom <= 0 || rect.top >= 1400) return false;
      return true;
    })
    .sort((a, b) => visibleRect(a).top - visibleRect(b).top);

  for (let i = 1; i < visibleRows.length; i++) {
    const prev = visibleRows[i - 1];
    const next = visibleRows[i];
    const prevRect = visibleRect(prev);
    const nextRect = visibleRect(next);
    if (prevRect.bottom > nextRect.top + tolerance) {
      failures.push(
        `${label}: row ${prev.index} ${prev.kind} overlaps row ${next.index} ${next.kind} by ${(prevRect.bottom - nextRect.top).toFixed(2)}px`,
      );
    }
  }
}

function assertActiveIsOnlyScrollTarget(label, rowStack) {
  const rows = rowStack ?? [];
  const activeRows = rows.filter((row) => row?.active);
  const scrollTargetRows = rows.filter((row) => row?.scrollTarget);
  assertEqual(`${label}: one active row`, activeRows.length, 1);
  assertEqual(`${label}: one scroll target row`, scrollTargetRows.length, 1);
  if (activeRows.length === 1 && scrollTargetRows.length === 1) {
    assertEqual(`${label}: active row is scroll target`, scrollTargetRows[0]?.index, activeRows[0]?.index);
  }
}

function assertAppleRowsUseBlock(label, rowStack) {
  for (const row of rowStack ?? []) {
    if (row?.rowStyle?.display !== "block") {
      failures.push(
        `${label}: row ${row?.index} ${row?.kind} display is ${row?.rowStyle?.display ?? "missing"}, expected block`,
      );
    }
  }
}

function assertAppleFirstRowClass(label, rowStack) {
  const rows = rowStack ?? [];
  const firstRows = rows.filter((row) => row?.index === 0);
  assertEqual(`${label}: one first row`, firstRows.length, 1);
  if (firstRows.length === 1) {
    assertIncludes(`${label}: first row carries Apple is-first`, firstRows[0]?.className ?? "", "is-first");
  }
  for (const row of rows.filter((row) => row?.index !== 0)) {
    assertNotIncludes(`${label}: row ${row.index} is not first`, row.className ?? "", "is-first");
  }
}

function assertAppleWillChangePlacement(label, rowStack) {
  const appleRowWillChange = "transform, opacity, color, top, background-image";
  for (const row of rowStack ?? []) {
    const rowWillChange = row?.rowStyle?.willChange ?? "";
    const lineWillChange = row?.lineStyle?.willChange ?? "";
    if (row?.willAnimate) {
      assertEqual(`${label}: row ${row.index} outer will-change`, rowWillChange, appleRowWillChange);
    } else {
      assertEqual(`${label}: row ${row?.index} outer will-change`, rowWillChange, "auto");
    }
    assertEqual(`${label}: row ${row?.index} line will-change`, lineWillChange, "auto");
  }
}

function assertCollapsedRowsClipVisibleContent(label, rowStack) {
  for (const row of rowStack ?? []) {
    const rowHeight = row?.rowStyle?.rect?.height ?? cssPx(row?.rowStyle?.height);
    if (!Number.isFinite(rowHeight) || rowHeight > 0.5) continue;
    const lineHeight = row?.lineStyle?.rect?.height ?? cssPx(row?.lineStyle?.height);
    if (Number.isFinite(lineHeight) && lineHeight > 0.5) {
      failures.push(
        `${label}: row ${row?.index} ${row?.kind} is collapsed but line rect is ${lineHeight.toFixed(2)}px tall`,
      );
    }
    if (row?.visibleTextRect) {
      failures.push(
        `${label}: row ${row?.index} ${row?.kind} is collapsed but still has visible text`,
      );
    }
  }
}

function assertVisibleTextGap(label, rowStack, minGapPx) {
  const visibleRect = readableLyricRect;
  const visibleRows = (rowStack ?? [])
    .filter((row) => {
      const rowRect = row?.rowStyle?.rect;
      if (rowRect && rowRect.height <= 0.5) return false;
      const rect = visibleRect(row);
      if (!rect) return false;
      if (rect.height <= 0.5 || rect.width <= 0.5) return false;
      if (rect.bottom <= 0 || rect.top >= 1400) return false;
      return true;
    })
    .sort((a, b) => visibleRect(a).top - visibleRect(b).top);

  for (let i = 1; i < visibleRows.length; i++) {
    const prev = visibleRows[i - 1];
    const next = visibleRows[i];
    const gap = visibleRect(next).top - visibleRect(prev).bottom;
    if (gap < minGapPx) {
      failures.push(
        `${label}: row ${prev.index} ${prev.kind} and row ${next.index} ${next.kind} gap ${gap.toFixed(2)}px below ${minGapPx}px`,
      );
    }
  }
}

function assertWebKitRowHeightGuard(label, rowStack, minExpandedDeltaPx) {
  const guardedRows = (rowStack ?? []).filter((row) =>
    row?.kind === "yrc" || row?.kind === "lrc");
  assertTruthy(`${label}: sampled lyric rows`, guardedRows.length > 0);
  let maxDelta = 0;
  for (const row of guardedRows) {
    const base = cssCalcPx(row.rowStyle?.appleRowMinHeight);
    const guard = cssCalcPx(row.rowStyle?.appleWebkitMinHeight);
    if (!Number.isFinite(base) || !Number.isFinite(guard)) {
      failures.push(
        `${label}: row ${row?.index} missing WebKit min-height vars base=${row.rowStyle?.appleRowMinHeight ?? "missing"} guard=${row.rowStyle?.appleWebkitMinHeight ?? "missing"}`,
      );
      continue;
    }
    if (guard + 0.5 < base) {
      failures.push(
        `${label}: row ${row?.index} WebKit guard ${guard.toFixed(2)}px below base ${base.toFixed(2)}px`,
      );
    }
    maxDelta = Math.max(maxDelta, guard - base);
  }
  assertBetween(`${label}: expanded guard delta`, maxDelta, minExpandedDeltaPx, Number.POSITIVE_INFINITY);
}

function assertComputedRowGuardActive(label, rowStyle) {
  const base = cssCalcPx(rowStyle?.appleRowMinHeight);
  const guard = cssCalcPx(rowStyle?.appleWebkitMinHeight);
  const computed = cssPx(rowStyle?.minHeight);
  if (!Number.isFinite(base) || !Number.isFinite(guard) || !Number.isFinite(computed)) {
    failures.push(
      `${label}: missing row guard values base=${rowStyle?.appleRowMinHeight ?? "missing"} guard=${rowStyle?.appleWebkitMinHeight ?? "missing"} computed=${rowStyle?.minHeight ?? "missing"}`,
    );
    return;
  }
  assertBetween(`${label}: computed min-height reaches guard`, computed, guard - 0.5, Number.POSITIVE_INFINITY);
  assertBetween(`${label}: computed min-height is not below Apple base`, computed, base - 0.5, Number.POSITIVE_INFINITY);
}

function assertPreviousTokenStableGradientColorRelease(label, frames) {
  const first = frames.find((frame) => frame.timingMs === 0);
  const mid = frames.find((frame) => frame.timingMs === 50);
  const at100 = frames.find((frame) => frame.timingMs === 100);
  const end = frames.find((frame) => frame.timingMs === 350);
  const points = [first, mid, at100, end].filter(Boolean);
  for (const frame of points) {
    const token = frame.previousOrdinaryToken;
    assertStableGradientTextPaint(`${label} ${frame.timingMs}ms stable paint`, token);
  }
  assertBetween(`${label} 0ms starts bright`, cssColorAlpha(first?.previousOrdinaryToken?.color), 0.55, 0.9);
  assertBetween(`${label} 50ms is fading`, cssColorAlpha(mid?.previousOrdinaryToken?.color), 0.25, 0.75);
  assertBetween(`${label} 100ms reaches inactive`, cssColorAlpha(at100?.previousOrdinaryToken?.color), 0.2, 0.34);
  assertBetween(`${label} 350ms stays inactive`, cssColorAlpha(end?.previousOrdinaryToken?.color), 0.2, 0.34);
}

function isStableGradientTextPaint(token) {
  return token?.textFillColor === "rgba(0, 0, 0, 0)" &&
    String(token?.backgroundImage ?? "").includes("linear-gradient") &&
    String(token?.inlineBackgroundImage ?? "").includes("var(--gradient-progress)");
}

function assertStableGradientTextPaint(label, token) {
  assertEqual(`${label} text fill remains transparent`, token?.textFillColor, "rgba(0, 0, 0, 0)");
  assertIncludes(`${label} keeps gradient layer`, token?.backgroundImage ?? "", "linear-gradient");
  assertIncludes(`${label} keeps Apple CSS-var gradient`, token?.inlineBackgroundImage ?? "", "var(--gradient-progress)");
  assertTruthy(`${label} exposes gradient progress var`, String(token?.inlineGradientProgress ?? "").endsWith("%"));
}

function readableLyricRect(row) {
  return unionRects(row?.visibleTextRect ?? null, row?.lineStyle?.rect ?? null);
}

function unionRects(a, b) {
  if (!a) return b;
  if (!b) return a;
  const left = Math.min(a.left, b.left);
  const top = Math.min(a.top, b.top);
  const right = Math.max(a.right, b.right);
  const bottom = Math.max(a.bottom, b.bottom);
  return {
    x: left,
    y: top,
    left,
    top,
    right,
    bottom,
    width: right - left,
    height: bottom - top,
  };
}

function findRow(rowStack, kind, predicate = () => true) {
  return (rowStack ?? []).find((row) => row.kind === kind && predicate(row)) ?? null;
}

function assertIncludes(label, actual, expectedPart, ...alternateExpectedParts) {
  const haystack = String(actual);
  const expectedParts = [expectedPart, ...alternateExpectedParts];
  if (!expectedParts.some((part) => haystack.includes(part))) {
    failures.push(`${label}: expected to include ${expectedParts.join(" or ")}, got ${actual}`);
  }
}

function assertNotIncludes(label, actual, rejectedPart) {
  if (String(actual).includes(rejectedPart)) {
    failures.push(`${label}: should not include ${rejectedPart}, got ${actual}`);
  }
}

function assertTruthy(label, actual) {
  if (!actual) failures.push(`${label}: missing`);
}

function assertTransformScale(label, transform, expected, tolerance) {
  const scale = transformScale(transform);
  assertApprox(label, scale, expected, tolerance);
}

function transformScale(transform) {
  if (!transform || transform === "none") return 1;
  const matrix3d = /^matrix3d\((.+)\)$/.exec(transform);
  if (matrix3d) {
    const values = matrix3d[1].split(",").map((part) => Number(part.trim()));
    const a = values[0] ?? Number.NaN;
    const b = values[1] ?? 0;
    return Math.sqrt(a * a + b * b);
  }
  const match = /matrix\(([^,]+),\s*([^,]+),\s*([^,]+),\s*([^,]+)/.exec(transform);
  if (!match) return Number.NaN;
  const a = Number(match[1]);
  const b = Number(match[2]);
  return Math.sqrt(a * a + b * b);
}

function transformedRawWidth(style) {
  if (!style?.rect) return Number.NaN;
  return style.rect.width / transformScale(style.transform);
}

function expectedAppleDuetLineWidth(sample) {
  const rowWidth = sample.active?.style?.rect?.width ?? sample.numeric?.columnWidth ?? Number.NaN;
  const paddingRight = cssPx(sample.active?.style?.paddingRight) ?? 0;
  return (rowWidth - paddingRight) * 0.6;
}

function transformTranslateY(transform) {
  if (!transform || transform === "none") return 0;
  const matrix3d = /^matrix3d\((.+)\)$/.exec(transform);
  if (matrix3d) {
    const values = matrix3d[1].split(",").map((part) => Number(part.trim()));
    return values[13] ?? Number.NaN;
  }
  const match = /^matrix\((.+)\)$/.exec(transform);
  if (!match) return Number.NaN;
  const values = match[1].split(",").map((part) => Number(part.trim()));
  return values[5] ?? Number.NaN;
}

function cssPx(value) {
  if (value == null) return null;
  const parsed = Number.parseFloat(String(value));
  return Number.isFinite(parsed) ? parsed : null;
}

function cssCalcPx(value) {
  if (value == null) return null;
  const text = String(value).trim();
  if (!text.startsWith("calc(")) return cssPx(text);
  const expression = text
    .replace(/^calc\(/, "")
    .replace(/\)$/, "")
    .replace(/px/g, "")
    .trim();
  if (!/^[0-9+\-*/ ().]+$/.test(expression)) return null;
  try {
    // The expression is restricted to numbers and arithmetic operators above.
    // eslint-disable-next-line no-new-func
    const result = Function(`"use strict"; return (${expression});`)();
    return Number.isFinite(result) ? result : null;
  } catch {
    return null;
  }
}

function cssColorAlpha(value) {
  if (value == null) return null;
  const text = String(value).trim().toLowerCase();
  if (text === "transparent") return 0;
  const rgba = /^rgba\(\s*\d+(?:\.\d+)?\s*,\s*\d+(?:\.\d+)?\s*,\s*\d+(?:\.\d+)?\s*,\s*([^)]+)\)$/.exec(text);
  if (rgba) {
    const alpha = Number.parseFloat(rgba[1]);
    return Number.isFinite(alpha) ? alpha : null;
  }
  if (/^rgb\(/.test(text) || /^#[0-9a-f]{3,8}$/i.test(text)) return 1;
  return null;
}

function cssBlurPx(filter) {
  if (!filter || filter === "none") return 0;
  const match = /blur\((-?\d+(?:\.\d+)?)px\)/.exec(String(filter));
  return match ? Number(match[1]) : null;
}

function springStepProgress(timeSec, stiffness, damping) {
  const discriminant = Math.max(0, damping * damping - 4 * stiffness);
  const root = Math.sqrt(discriminant);
  const r1 = (-damping + root) / 2;
  const r2 = (-damping - root) / 2;
  if (Math.abs(r1 - r2) < 1e-6) {
    const omega = damping / 2;
    return 1 - (1 + omega * timeSec) * Math.exp(-omega * timeSec);
  }
  const a = -r2 / (r1 - r2);
  const b = r1 / (r1 - r2);
  const remaining = a * Math.exp(r1 * Math.max(0, timeSec)) +
    b * Math.exp(r2 * Math.max(0, timeSec));
  return Math.max(0, Math.min(1, 1 - remaining));
}

function cssEaseInOut(t) {
  return cubicBezier(0.42, 0, 0.58, 1, t);
}

function inverseCssEaseInOut(y) {
  let lo = 0;
  let hi = 1;
  let mid = y;
  for (let i = 0; i < 14; i += 1) {
    mid = (lo + hi) / 2;
    if (cssEaseInOut(mid) < y) lo = mid;
    else hi = mid;
  }
  return mid;
}

function cubicBezier(x1, y1, x2, y2, x) {
  const sample = (a1, a2, t) => {
    const inv = 1 - t;
    return 3 * inv * inv * t * a1 + 3 * inv * t * t * a2 + t * t * t;
  };
  let lo = 0;
  let hi = 1;
  let t = x;
  for (let i = 0; i < 14; i += 1) {
    t = (lo + hi) / 2;
    const estimate = sample(x1, x2, t);
    if (estimate < x) lo = t;
    else hi = t;
  }
  return sample(y1, y2, t);
}

function transformOriginX(value) {
  if (value == null) return null;
  const first = String(value).trim().split(/\s+/)[0];
  return cssPx(first);
}

function gradientFirstStopPercent(backgroundImage) {
  if (!backgroundImage || backgroundImage === "none") return null;
  const matches = [...String(backgroundImage).matchAll(/(-?\d+(?:\.\d+)?)%/g)];
  if (matches.length === 0) return null;
  return Number(matches[0][1]);
}

function textShadowComponents(textShadow) {
  if (!textShadow || textShadow === "none") return { blur: 0, opacity: 0 };
  const pxValues = [...String(textShadow).matchAll(/(-?\d+(?:\.\d+)?)px/g)]
    .map((match) => Number(match[1]));
  const rgba = /rgba\([^,]+,[^,]+,[^,]+,\s*([^)]+)\)/.exec(String(textShadow));
  return {
    blur: pxValues[2] ?? 0,
    opacity: rgba ? Number(rgba[1]) : 1,
  };
}

function decodePngRgba(buffer) {
  const signature = "89504e470d0a1a0a";
  if (buffer.subarray(0, 8).toString("hex") !== signature) {
    throw new Error("Invalid PNG signature");
  }
  let offset = 8;
  let width = 0;
  let height = 0;
  let colorType = 0;
  const idatChunks = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.subarray(offset + 4, offset + 8).toString("ascii");
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    offset += 12 + length;
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      const bitDepth = data[8];
      colorType = data[9];
      if (bitDepth !== 8 || (colorType !== 2 && colorType !== 6)) {
        throw new Error(`Unsupported PNG format: bitDepth=${bitDepth} colorType=${colorType}`);
      }
    } else if (type === "IDAT") {
      idatChunks.push(data);
    } else if (type === "IEND") {
      break;
    }
  }
  const channels = colorType === 6 ? 4 : 3;
  const stride = width * channels;
  const inflated = inflateSync(Buffer.concat(idatChunks));
  const rgba = Buffer.alloc(width * height * 4);
  let src = 0;
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y += 1) {
    const filter = inflated[src];
    src += 1;
    const row = Buffer.from(inflated.subarray(src, src + stride));
    src += stride;
    unfilterPngRow(row, prev, filter, channels);
    for (let x = 0; x < width; x += 1) {
      const from = x * channels;
      const to = (y * width + x) * 4;
      rgba[to] = row[from];
      rgba[to + 1] = row[from + 1];
      rgba[to + 2] = row[from + 2];
      rgba[to + 3] = channels === 4 ? row[from + 3] : 255;
    }
    prev = row;
  }
  return { width, height, rgba };
}

function unfilterPngRow(row, prev, filter, bytesPerPixel) {
  for (let i = 0; i < row.length; i += 1) {
    const left = i >= bytesPerPixel ? row[i - bytesPerPixel] : 0;
    const up = prev[i] ?? 0;
    const upLeft = i >= bytesPerPixel ? prev[i - bytesPerPixel] ?? 0 : 0;
    let value = row[i];
    if (filter === 1) value += left;
    else if (filter === 2) value += up;
    else if (filter === 3) value += Math.floor((left + up) / 2);
    else if (filter === 4) value += paeth(left, up, upLeft);
    else if (filter !== 0) throw new Error(`Unsupported PNG filter: ${filter}`);
    row[i] = value & 255;
  }
}

function paeth(left, up, upLeft) {
  const p = left + up - upLeft;
  const pa = Math.abs(p - left);
  const pb = Math.abs(p - up);
  const pc = Math.abs(p - upLeft);
  if (pa <= pb && pa <= pc) return left;
  if (pb <= pc) return up;
  return upLeft;
}

function samplePatchAverage(image, cx, cy, size) {
  const radius = Math.floor(size / 2);
  const total = [0, 0, 0];
  let count = 0;
  for (let y = Math.round(cy) - radius; y <= Math.round(cy) + radius; y += 1) {
    if (y < 0 || y >= image.height) continue;
    for (let x = Math.round(cx) - radius; x <= Math.round(cx) + radius; x += 1) {
      if (x < 0 || x >= image.width) continue;
      const index = (y * image.width + x) * 4;
      total[0] += image.rgba[index];
      total[1] += image.rgba[index + 1];
      total[2] += image.rgba[index + 2];
      count += 1;
    }
  }
  return count > 0 ? total.map((value) => value / count) : [0, 0, 0];
}

function colorDistance(a, b) {
  return Math.sqrt(
    (a[0] - b[0]) ** 2 +
    (a[1] - b[1]) ** 2 +
    (a[2] - b[2]) ** 2,
  );
}

function relativeLuminance(rgb) {
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function collectBrowserErrors(cdp) {
  return cdp.events
    .filter((event) => {
      if (event.method === "Runtime.exceptionThrown") return true;
      if (event.method === "Log.entryAdded") return event.params?.entry?.level === "error";
      if (event.method === "Runtime.consoleAPICalled") return event.params?.type === "error";
      return false;
    })
    .map((event) => {
      if (event.method === "Runtime.exceptionThrown") {
        return event.params?.exceptionDetails?.text ?? "Runtime.exceptionThrown";
      }
      if (event.method === "Log.entryAdded") {
        return event.params?.entry?.text ?? "Log.entryAdded";
      }
      return (event.params?.args ?? []).map((arg) => arg.value ?? arg.description ?? "").join(" ") || "console.error";
    })
    .filter(Boolean)
    .slice(0, 20);
}

function killProcess(proc) {
  if (!proc || proc.killed) return;
  proc.kill("SIGTERM");
}

class CdpClient {
  static async connect(url) {
    const ws = new WebSocket(url);
    const client = new CdpClient(ws);
    await client.opened;
    return client;
  }

  constructor(ws) {
    this.ws = ws;
    this.nextId = 1;
    this.pending = new Map();
    this.eventWaiters = new Map();
    this.events = [];
    this.opened = new Promise((resolve, reject) => {
      ws.addEventListener("open", resolve, { once: true });
      ws.addEventListener("error", reject, { once: true });
    });
    ws.addEventListener("message", (event) => this.handleMessage(event));
    ws.addEventListener("close", () => {
      for (const { reject } of this.pending.values()) reject(new Error("CDP socket closed"));
      this.pending.clear();
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    const payload = JSON.stringify({ id, method, params });
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(payload);
    });
  }

  waitForEvent(method, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        const waiters = this.eventWaiters.get(method) ?? [];
        this.eventWaiters.set(method, waiters.filter((waiter) => waiter.resolve !== resolve));
        reject(new Error(`Timed out waiting for ${method}`));
      }, timeoutMs);
      const waiters = this.eventWaiters.get(method) ?? [];
      waiters.push({
        resolve: (params) => {
          clearTimeout(timeout);
          resolve(params);
        },
      });
      this.eventWaiters.set(method, waiters);
    });
  }

  handleMessage(event) {
    const message = JSON.parse(event.data);
    if (message.id) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
      return;
    }
    if (message.method) {
      this.events.push({ method: message.method, params: message.params });
      const waiters = this.eventWaiters.get(message.method);
      if (waiters?.length) {
        const [waiter, ...rest] = waiters;
        this.eventWaiters.set(message.method, rest);
        waiter.resolve(message.params);
      }
    }
  }

  async close() {
    this.ws.close();
  }
}

await main();
process.exit(process.exitCode ?? 0);
