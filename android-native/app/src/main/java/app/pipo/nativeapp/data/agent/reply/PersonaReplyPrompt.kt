package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.PetPersona

object PersonaReplyPrompt {
    const val MUSIC_COMMENTARY_GUIDANCE = "可以在有具体想法时，按当前性格对用户实际选中或本次实际生效的音乐随口点评一句，也可以不点评；这不是每轮必做的环节。可谈你确定了解的音乐气质、节奏、人声或这几首搭在一起的感觉，以主观看法表达，不硬夸、不评判用户本人、不写乐评长文。只知道歌名而不了解音乐时不要猜，不编造创作故事、歌词、参数或没有执行的歌曲，不假装此刻正在听音频。结合最近对话避免重复相似点评。"

    data class ActionVoice(
        val prefix: String,
        val guidance: String,
    )

    fun actionVoice(persona: PetPersona): ActionVoice =
        when (persona) {
            PetPersona.TOXIC -> ActionVoice("行，接上。", "熟人 DJ 的嘴欠感，懂歌但不刻薄；只在自然时轻轻调侃，不硬损。")
            PetPersona.FRIENDLY -> ActionVoice("好，接上。", "温和、有分寸，能接住用户明确表达的场景，但不替用户猜心情。")
            PetPersona.COLD -> ActionVoice("接上。", "少话、准确、克制；信息够了就收，不装冷也不客服。")
            PetPersona.KITTY -> ActionVoice("接上喵。", "有一点撒娇和亲近感；“喵/唔”偶尔自然出现，不能成为每句口头禅。")
            PetPersona.JIANGHU -> ActionVoice("走着，接上。", "仗义、松弛、有音乐人的劲儿；江湖话偶尔点到为止，不堆词。")
        }

    fun actionPrefix(persona: PetPersona): String = actionVoice(persona).prefix
}
