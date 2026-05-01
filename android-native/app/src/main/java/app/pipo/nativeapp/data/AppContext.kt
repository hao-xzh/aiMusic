package app.pipo.nativeapp.data

import java.util.Calendar

/**
 * 镜像 src/lib/app-context.ts —— 给 AI 输入的"当下"锚点。
 *
 * 字段简化版：只取 React 端 makeTimeContext + describeContext 里 LLM 真正用到的部分。
 * 没移植 weather（需要外部网络 API + 用户授权定位）。临近周末/假期靠系统日历推。
 */
object AppContext {
    /** describeContext 返回值：单行人话描述给 USER prompt 当 "当下：" 锚点 */
    fun describe(weather: Weather.Snapshot? = null): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val day = dayLabel(dow)
        val period = periodLabel(hour)
        val weekend = if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) "（周末）" else ""
        val daysToWeekend = daysUntilWeekend(dow)
        val anchor = when {
            weekend.isNotEmpty() -> ""
            daysToWeekend == 1 -> " · 明天就周末"
            daysToWeekend == 2 -> " · 还有两天周末"
            else -> ""
        }
        val w = if (weather != null) " · ${weather.summary} ${weather.tempC}°" else ""
        return "$day$period$weekend$anchor$w"
    }

    fun dayLabel(dow: Int): String = when (dow) {
        Calendar.MONDAY -> "周一"
        Calendar.TUESDAY -> "周二"
        Calendar.WEDNESDAY -> "周三"
        Calendar.THURSDAY -> "周四"
        Calendar.FRIDAY -> "周五"
        Calendar.SATURDAY -> "周六"
        else -> "周日"
    }

    fun periodLabel(hour: Int): String = when (hour) {
        in 5..10 -> "上午"
        in 11..13 -> "中午"
        in 14..17 -> "下午"
        in 18..21 -> "傍晚"
        in 22..23, 0 -> "深夜"
        else -> "凌晨"
    }

    private fun daysUntilWeekend(dow: Int): Int = when (dow) {
        Calendar.SATURDAY, Calendar.SUNDAY -> 0
        Calendar.MONDAY -> 5
        Calendar.TUESDAY -> 4
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 2
        Calendar.FRIDAY -> 1
        else -> -1
    }

    /**
     * 跨 session 记忆 digest —— 镜像 React getMemoryDigest。
     * 喂给 GREETING_SYSTEM / TRACK_COMMENT_SYSTEM / 主聊天 USER。
     *
     * 包含：陪伴痕迹（总播放数 + 上次说过的话）+ 用户自述事实 + 口味画像摘要。
     * 故意**不**包含 loveArtists / skipHotArtists —— 见 PetMemory 模块顶部注释。
     */
    suspend fun memoryDigest(userFacts: String = ""): String? {
        val profile = PipoGraph.tasteProfileStore.flow.value
        val mem = runCatching { PipoGraph.petMemory }.getOrNull()
        val behaviorTotal = runCatching { PipoGraph.behaviorLog.readAll().size }.getOrDefault(0)

        val parts = mutableListOf<String>()
        if (behaviorTotal > 0) parts.add("听过 $behaviorTotal 首")

        // 上次说过的话
        mem?.lastUtterance()?.let { last ->
            val ageMin = ((System.currentTimeMillis() / 1000 - last.tsSec) / 60).toInt()
            val ageLabel = when {
                ageMin < 5 -> "刚刚"
                ageMin < 60 -> "$ageMin 分钟前"
                ageMin < 24 * 60 -> "${ageMin / 60} 小时前"
                else -> "${ageMin / (60 * 24)} 天前"
            }
            parts.add("上次说\"${last.text.take(30)}\"($ageLabel)")
        }

        // 自述事实：先从 PetMemory，再 fallback 到传入参数
        val storedFacts = mem?.userFacts().orEmpty()
        val effective = if (storedFacts.isNotBlank()) storedFacts else userFacts
        if (effective.isNotBlank()) parts.add("自述:${effective.trim().take(120)}")

        // 口味画像摘要
        if (profile != null) {
            if (profile.topArtists.isNotEmpty()) {
                parts.add("常听：" + profile.topArtists.take(5).joinToString("、") { it.name })
            }
            if (profile.genres.isNotEmpty()) {
                parts.add("流派：" + profile.genres.take(4).joinToString("、") { it.tag })
            }
            if (profile.taglines.isNotEmpty()) parts.add(profile.taglines.first())
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
