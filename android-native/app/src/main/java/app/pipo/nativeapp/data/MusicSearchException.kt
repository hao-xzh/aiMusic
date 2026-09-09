package app.pipo.nativeapp.data

/** 音乐目录搜索服务失败；与一次成功请求返回空候选严格区分。 */
class MusicSearchException(
    cause: Throwable? = null,
) : RuntimeException("music_search_unavailable", cause)
