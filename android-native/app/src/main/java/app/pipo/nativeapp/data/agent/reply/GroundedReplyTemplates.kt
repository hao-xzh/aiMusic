package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetPersona

class GroundedReplyTemplates {
    fun grounded(facts: ReplyFacts, persona: PetPersona): String {
        if (!facts.success) return safeFallback(facts, persona)
        return when (facts.actionType) {
            "play_queue", "replace_queue", "play_now" -> playQueue(facts, persona)
            "insert_next" -> insert(facts, persona)
            "skip" -> skip(persona)
            "like" -> like(facts, persona)
            "playlist" -> playlist(facts, persona)
            "say", "clarify" -> facts.errorMessage.ifBlank { "嗯。" }
            else -> safeFallback(facts, persona)
        }
    }

    fun playQueueOpening(persona: PetPersona, firstTrack: NativeTrack?): String {
        val prefix = PersonaReplyPrompt.actionPrefix(persona)
        return if (firstTrack == null) prefix else "$prefix 先听${formatTrack(firstTrack)}。"
    }

    fun insert(track: NativeTrack?, jumpNow: Boolean): String {
        val formatted = track?.let(::formatTrack).orEmpty()
        return if (jumpNow) {
            if (formatted.isBlank()) "切歌请求接上。" else "切歌请求接上，$formatted。"
        } else {
            if (formatted.isBlank()) "下一首接上，不打断现在这首。" else "下一首接$formatted，不打断现在这首。"
        }
    }

    fun formatTrack(track: NativeTrack): String {
        val title = track.title.trim().ifBlank { "这首歌" }
        val artist = track.artist.trim()
        return if (artist.isBlank()) "《$title》" else "《$title》-$artist"
    }

    fun safeFallback(facts: ReplyFacts, persona: PetPersona): String {
        val track = formatTrack(facts.firstTrackTitle, facts.firstTrackArtist)
        if (!facts.success) {
            return when (persona) {
                PetPersona.TOXIC -> when {
                    facts.requiredArtist.isNotBlank() -> "${facts.requiredArtist}这组没接上，不硬塞别的。"
                    facts.errorMessage.isNotBlank() -> "这步没接上，别让我硬编。"
                    else -> "这步没成，不硬装。"
                }
                PetPersona.FRIENDLY -> when {
                    facts.requiredArtist.isNotBlank() -> "${facts.requiredArtist}这组没接上，我不乱塞别的。"
                    facts.errorMessage.isNotBlank() -> "这步没接上，我不硬说完成。"
                    else -> "这步没成，我不乱来。"
                }
                PetPersona.COLD -> "未接上。"
                PetPersona.KITTY -> when {
                    facts.requiredArtist.isNotBlank() -> "${facts.requiredArtist}没接上喵，不乱塞别的。"
                    else -> "这步没成喵。"
                }
                PetPersona.JIANGHU -> when {
                    facts.requiredArtist.isNotBlank() -> "${facts.requiredArtist}这把没接上，不硬塞别的。"
                    else -> "这把没成，不硬来。"
                }
            }
        }
        return when (persona) {
            PetPersona.TOXIC -> when {
                facts.requiredArtist.isNotBlank() -> "行，${facts.requiredArtist}专场，别乱塞。"
                track.isNotBlank() -> "行，先从${track}来。"
                else -> "行，这步我接了。"
            }
            PetPersona.FRIENDLY -> when {
                facts.requiredArtist.isNotBlank() -> "好，给你排一组${facts.requiredArtist}。"
                track.isNotBlank() -> "好，先从${track}来。"
                else -> "好，这步处理好了。"
            }
            PetPersona.COLD -> when {
                facts.requiredArtist.isNotBlank() -> "${facts.requiredArtist}，队列接上。"
                track.isNotBlank() -> "$track，接上。"
                else -> "好了。"
            }
            PetPersona.KITTY -> when {
                facts.requiredArtist.isNotBlank() -> "${facts.requiredArtist}来啦喵。"
                track.isNotBlank() -> "先从${track}来喵。"
                else -> "弄好啦喵。"
            }
            PetPersona.JIANGHU -> when {
                facts.requiredArtist.isNotBlank() -> "走着，${facts.requiredArtist}这局排上。"
                track.isNotBlank() -> "走着，先从${track}来。"
                else -> "妥了。"
            }
        }
    }

    fun formatTrack(title: String, artist: String = ""): String {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return ""
        val cleanArtist = artist.trim()
        return if (cleanArtist.isBlank()) "《$cleanTitle》" else "《$cleanTitle》-$cleanArtist"
    }

    private fun playQueue(facts: ReplyFacts, persona: PetPersona): String {
        val track = formatTrack(facts.firstTrackTitle, facts.firstTrackArtist)
        val strictArtist = facts.requiredArtist.takeIf {
            it.isNotBlank() && facts.artistScope == "Strict" && facts.validationPassed
        }
        val base = when (persona) {
            PetPersona.TOXIC -> when {
                strictArtist != null && track.isNotBlank() -> "行，${strictArtist}专场，先从${track}来。"
                strictArtist != null -> "行，${strictArtist}专场，别乱塞。"
                track.isNotBlank() -> "行，先从${track}来。"
                else -> "行，队列接上。"
            }
            PetPersona.FRIENDLY -> when {
                strictArtist != null && track.isNotBlank() -> "好，给你排一组${strictArtist}，先从${track}来。"
                strictArtist != null -> "好，给你排一组${strictArtist}。"
                track.isNotBlank() -> "好，队列接上，先从${track}来。"
                else -> "好，队列接上了。"
            }
            PetPersona.COLD -> when {
                strictArtist != null -> "${strictArtist}，队列接上。"
                track.isNotBlank() -> "$track，接上。"
                else -> "队列接上。"
            }
            PetPersona.KITTY -> when {
                strictArtist != null && track.isNotBlank() -> "${strictArtist}来啦，先从${track}来喵。"
                strictArtist != null -> "${strictArtist}来啦喵。"
                track.isNotBlank() -> "先从${track}来喵。"
                else -> "队列接上啦喵。"
            }
            PetPersona.JIANGHU -> when {
                strictArtist != null -> "走着，${strictArtist}这局排上。"
                track.isNotBlank() -> "走着，先从${track}来。"
                else -> "走着，队列接上。"
            }
        }
        val extras = mutableListOf<String>()
        if (facts.reorderedForSeamless) extras.add("顺序按接歌顺过。")
        if (facts.closerTitle.isNotBlank()) extras.add("最后用《${facts.closerTitle}》收住。")
        return (base + extras.joinToString("")).take(120)
    }

    private fun insert(facts: ReplyFacts, persona: PetPersona): String {
        val track = formatTrack(facts.insertedTitle, facts.insertedArtist)
            .ifBlank { formatTrack(facts.firstTrackTitle, facts.firstTrackArtist) }
        return when (persona) {
            PetPersona.TOXIC -> if (track.isBlank()) "下一首给你接上，别催。" else "下一首接$track，别打断。"
            PetPersona.FRIENDLY -> if (track.isBlank()) "下一首给你接上，不打断现在这首。" else "下一首接$track，不打断现在这首。"
            PetPersona.COLD -> if (track.isBlank()) "下一首接上。" else "下一首：$track。"
            PetPersona.KITTY -> if (track.isBlank()) "下一首接上喵。" else "下一首接${track}喵。"
            PetPersona.JIANGHU -> if (track.isBlank()) "下一首接上，稳着。" else "下一首接$track，稳着。"
        }
    }

    private fun skip(persona: PetPersona): String =
        when (persona) {
            PetPersona.TOXIC -> "行，换掉。"
            PetPersona.FRIENDLY -> "好，换下一首。"
            PetPersona.COLD -> "下一首。"
            PetPersona.KITTY -> "换下一首喵。"
            PetPersona.JIANGHU -> "走着，换。"
        }

    private fun like(facts: ReplyFacts, persona: PetPersona): String {
        val title = facts.likedTitle.ifBlank { facts.firstTrackTitle }
        return when (persona) {
            PetPersona.TOXIC -> if (title.isBlank()) "行，收藏这口味。" else "行，《$title》记上。"
            PetPersona.FRIENDLY -> if (title.isBlank()) "好，收藏好了。" else "好，《$title》收藏好了。"
            PetPersona.COLD -> if (title.isBlank()) "已收藏。" else "《$title》，已收藏。"
            PetPersona.KITTY -> if (title.isBlank()) "收藏好啦喵。" else "《$title》收好啦喵。"
            PetPersona.JIANGHU -> if (title.isBlank()) "妥，收藏。" else "妥，《$title》收着。"
        }
    }

    private fun playlist(facts: ReplyFacts, persona: PetPersona): String {
        val name = facts.playlistName.ifBlank { "歌单" }
        return when (persona) {
            PetPersona.TOXIC -> "行，$name 处理了。"
            PetPersona.FRIENDLY -> "好，$name 处理好了。"
            PetPersona.COLD -> "$name，已处理。"
            PetPersona.KITTY -> "$name 弄好啦喵。"
            PetPersona.JIANGHU -> "妥，$name 处理好。"
        }
    }
}
