package app.pipo.nativeapp.data.agent.intent

data class MusicDescriptorProfile(
    val descriptors: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val textures: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val energy: String = "any",
    val tempoFeel: String = "any",
    val emotionalDirection: String? = null,
    val searchQueries: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = descriptors.isEmpty() &&
            moods.isEmpty() &&
            scenes.isEmpty() &&
            textures.isEmpty() &&
            genres.isEmpty() &&
            searchQueries.isEmpty() &&
            energy == "any" &&
            tempoFeel == "any" &&
            emotionalDirection == null
}

object MusicDescriptorLexicon {
    fun profileFor(text: String): MusicDescriptorProfile {
        val key = normalize(text)
        val descriptors = mutableListOf<String>()
        val moods = mutableListOf<String>()
        val scenes = mutableListOf<String>()
        val textures = mutableListOf<String>()
        val genres = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var energy = "any"
        var tempo = "any"
        var direction: String? = null

        fun has(vararg cues: String): Boolean = cues.any { it in key }
        fun mark(name: String) {
            if (name !in descriptors) descriptors.add(name)
        }

        if (has("嗨", "嗨点", "高能", "燃", "动感", "派对", "party", "提神")) {
            mark("high_energy")
            moods += "energetic"
            scenes += listOf("party", "drive")
            textures += "upbeat"
            energy = "high"
            tempo = "fast"
            direction = "brighter"
            queries += listOf("华语流行 动感", "热门 高能 歌曲", "派对 音乐", "运动 歌曲")
        }
        if (has("happy", "开心", "快乐", "欢乐")) {
            mark("happy")
            moods += listOf("happy", "bright")
            textures += "upbeat"
            if (energy == "any") energy = "high"
            if (tempo == "any") tempo = "medium"
            direction = direction ?: "brighter"
            queries += listOf("开心 快乐 歌曲", "happy 华语 歌曲", "欢乐 流行 歌曲")
        }
        if (has("安静", "舒缓", "放松", "睡前", "轻柔")) {
            mark("calm")
            moods += listOf("calm", "relaxed")
            scenes += "night"
            textures += "soft"
            energy = "low"
            tempo = "slow"
            direction = direction ?: "softer"
            queries += listOf("安静 舒缓 歌曲", "放松 华语 歌曲", "睡前 音乐")
        }
        if (has("流行")) genres += "pop"
        if (has("摇滚")) genres += "rock"
        if (has("电子")) genres += "electronic"
        if (has("嘻哈", "说唱", "hiphop", "rap")) genres += "hiphop"
        if (has("民谣")) genres += "folk"
        if (has("爵士", "jazz")) genres += "jazz"

        return MusicDescriptorProfile(
            descriptors = descriptors.distinct(),
            moods = moods.distinct(),
            scenes = scenes.distinct(),
            textures = textures.distinct(),
            genres = genres.distinct(),
            energy = energy,
            tempoFeel = tempo,
            emotionalDirection = direction,
            searchQueries = queries.distinct(),
        )
    }

    fun isDescriptorOnly(text: String): Boolean {
        val key = normalize(text)
        if (key.isBlank()) return false
        val stripped = key
            .replace(Regex("^(一些|一点|几首|几首歌|歌曲|音乐|来点|来些|播放|放点|听点|想听)"), "")
            .replace(Regex("(一点|一些|的歌|的音乐|的歌曲|的|歌曲|音乐|歌)$"), "")
            .trim()
        if (stripped.isBlank()) return true
        val profile = profileFor(stripped)
        return !profile.isEmpty && stripped.length <= 16
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[\\s　]+"), "")
            .replace("，", ",")
            .replace("。", ".")
            .trim()
}
