package app.pipo.nativeapp.data

/** Platform IDs stay opaque and case-sensitive; a QQ mid is never a NetEase numeric ID. */
data class MusicSourceRef(
    val providerId: String,
    val mediaId: String,
    val mediaFileId: String? = null,
    val numericId: Long? = null,
    val songType: Int = 0,
) {
    init {
        require(providerId.matches(Regex("[a-z][a-z0-9_-]*"))) { "Invalid music provider" }
        require(mediaId.isNotBlank()) { "Missing provider media ID" }
    }

    val key: String get() = "$providerId:$mediaId"
    val neteaseId: Long?
        get() = if (providerId == MusicProviders.NETEASE) mediaId.toLongOrNull()?.takeIf { it > 0 } else null
}

object MusicProviders {
    const val NETEASE = "netease"
    const val QQ = "qq"

    fun label(providerId: String): String = when (providerId) {
        NETEASE -> "网易云"
        QQ -> "QQ 音乐"
        else -> providerId
    }
}
