package app.pipo.nativeapp.data

import kotlin.math.abs
import kotlin.math.min

/**
 * 两两曲目接续风险评分 —— 镜像 src/lib/transition-score.ts。
 *
 * Android AudioFeatures 字段比 React TrackAnalysis 少（没有 introVocalDensity /
 * outroLowEnergy / vocalEntryS / drumEntryS / energyPerSec），所以 vocalClash /
 * lowClash 等高级评分回退成中性。等 Rust 端补上对应字段后，自动就生效。
 */
object TransitionScore {

    data class Scored(val track: NativeTrack, val features: AudioFeatures?)

    data class FitScore(val score: Double, val reason: String, val style: TransitionStyle)

    enum class TransitionStyle { HardCut, Tight, Soft, SilenceBreath }

    /** 数值越大越不适合衔接 */
    fun risk(a: Scored, b: Scored, sameArtistPenalty: Double = 0.0): Double {
        var risk = 0.0

        val aBpm = a.features?.bpm
        val bBpm = b.features?.bpm
        if (aBpm != null && bBpm != null) {
            val diff = abs(aBpm - bBpm)
            risk += when {
                diff <= 8 -> (diff / 8) * 0.35
                diff <= 15 -> 0.35 + ((diff - 8) / 7) * 0.75
                else -> 1.3 + min(2.2, (diff - 15) / 18.0)
            }
        } else risk += 0.28

        val aEnergy = a.features?.outroEnergy
        val bEnergy = b.features?.introEnergy
        if (aEnergy != null && bEnergy != null) {
            val diff = abs(aEnergy - bEnergy)
            risk += when {
                diff <= 0.18 -> (diff / 0.18) * 0.35
                diff <= 0.25 -> 0.35 + ((diff - 0.18) / 0.07) * 0.7
                else -> 1.1 + min(1.8, diff * 3.2)
            }
        } else risk += 0.28

        val fit = fitScore(a, b)
        risk -= fit.score * 0.85

        if (sameArtistPenalty > 0 && sameArtist(a.track, b.track)) risk += sameArtistPenalty
        if (sameAlbum(a.track, b.track)) risk -= 0.2

        return kotlin.math.max(-1.2, risk)
    }

    fun fitScore(a: Scored, b: Scored): FitScore {
        val aa = a.features
        val bb = b.features
        if (aa == null || bb == null) return FitScore(0.25, "缺少音频分析", TransitionStyle.Soft)

        val bpmDelta = if (aa.bpm != null && bb.bpm != null) abs(aa.bpm - bb.bpm) else Double.POSITIVE_INFINITY
        val bpmReliable = aa.bpm != null && bb.bpm != null &&
            aa.bpmConfidence > 0.25 && bb.bpmConfidence > 0.25
        val energyDelta = abs(aa.outroEnergy - bb.introEnergy)
        // 没有 vocalDensity / lowEnergy 字段时按"中性"处理
        val vocalClash = 0.0
        val lowClash = 0.0
        val cleanOutro = aa.tailSilenceS > 0.2
        val playableIntro = bb.headSilenceS > 0.2

        if (sameAlbum(a.track, b.track) && cleanOutro && playableIntro &&
            energyDelta <= 0.24 && vocalClash <= 0.08
        ) {
            return FitScore(0.98, "同专辑边界干净", TransitionStyle.HardCut)
        }
        if (energyDelta >= 0.34) {
            val penalty = clamp01((energyDelta - 0.25) * 1.4)
            return FitScore(clamp01(0.42 - penalty * 0.25), "反差大，适合留白", TransitionStyle.SilenceBreath)
        }

        var score = 0.28
        val reasons = mutableListOf<String>()
        if (bpmReliable) {
            when {
                bpmDelta <= 4 -> { score += 0.3; reasons.add("BPM 很近") }
                bpmDelta <= 8 -> { score += 0.2; reasons.add("BPM 接近") }
                bpmDelta <= 14 -> score += 0.08
                else -> score -= 0.18
            }
        }
        when {
            energyDelta <= 0.1 -> { score += 0.2; reasons.add("能量贴合") }
            energyDelta <= 0.2 -> score += 0.12
            energyDelta > 0.28 -> score -= 0.15
        }
        if (cleanOutro && playableIntro) { score += 0.18; reasons.add("尾头干净") }
        else if (cleanOutro || playableIntro) score += 0.08

        val style = if (bpmReliable && bpmDelta <= 8 && energyDelta <= 0.22)
            TransitionStyle.Tight else TransitionStyle.Soft
        return FitScore(clamp01(score), if (reasons.isEmpty()) "普通慢溶" else reasons.joinToString(" / "), style)
    }

    fun orderDriftPenalty(fromIndex: Int, toIndex: Int, total: Int): Double {
        val drift = abs(toIndex - fromIndex)
        return min(0.45, (drift.toDouble() / kotlin.math.max(1, total)) * 0.5)
    }

    private fun sameArtist(a: NativeTrack, b: NativeTrack): Boolean {
        val aArtists = a.artist.split("/", "&", ",").map { it.trim().lowercase() }.toSet()
        val bArtists = b.artist.split("/", "&", ",").map { it.trim().lowercase() }.toSet()
        return aArtists.any { it.isNotBlank() && it in bArtists }
    }

    private fun sameAlbum(a: NativeTrack, b: NativeTrack): Boolean {
        val an = a.album.trim().lowercase()
        val bn = b.album.trim().lowercase()
        return an.isNotBlank() && an == bn
    }

    private fun clamp01(x: Double): Double = if (x < 0) 0.0 else if (x > 1) 1.0 else x
}
