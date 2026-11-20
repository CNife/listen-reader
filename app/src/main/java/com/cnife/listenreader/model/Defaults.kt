package com.cnife.listenreader.model

enum class TtsVendor(val displayName: String) {
    STEPFUN("StepFun"),
    QWEN("千问"),
}

object TtsModels {
    const val MINI = "step-tts-mini"
    const val QWEN_FLASH = "qwen-audio-3.0-tts-flash"
    const val DEFAULT = MINI
    val ALL = listOf(MINI, QWEN_FLASH)

    fun vendorOf(model: String): TtsVendor = when (model) {
        QWEN_FLASH -> TtsVendor.QWEN
        else -> TtsVendor.STEPFUN
    }

    fun normalize(model: String?): String =
        if (model != null && model in ALL) model else DEFAULT
}

data class VoiceCatalogEntry(
    val id: String,
    val name: String,
    val vendor: TtsVendor,
    val subtitle: String = "",
)

object Voices {
    const val WENROU = "wenrounvsheng"
    const val CIXING = "cixingnansheng"
    const val YUANQI = "yuanqishaonv"
    const val LINJIA = "linjiajiejie"
    const val LONGANHUAN = "longanhuan_v3.6"
    const val LONGANFENGYUE = "longanfengyue"
    const val LOONGJOHN = "loongjohn"
    const val DEFAULT = WENROU

    /** 阶跃官方文档中支持 step-tts-mini 的全部 27 个官方音色。 */
    val STEPFUN_MINI_VOICES: List<VoiceCatalogEntry> = listOf(
        VoiceCatalogEntry(WENROU, "温柔女声", TtsVendor.STEPFUN, "有声书、情感陪伴"),
        VoiceCatalogEntry(CIXING, "磁性男声", TtsVendor.STEPFUN, "有声书、情感陪伴"),
        VoiceCatalogEntry(YUANQI, "元气少女", TtsVendor.STEPFUN, "有声书、情感陪伴、语音助手"),
        VoiceCatalogEntry(LINJIA, "邻家姐姐", TtsVendor.STEPFUN, "口播、情感陪伴、语音助手、视频配音"),
        VoiceCatalogEntry("elegantgentle-female", "气质温婉", TtsVendor.STEPFUN, "客服、口播、教育与培训、情感陪伴"),
        VoiceCatalogEntry("livelybreezy-female", "活力轻快", TtsVendor.STEPFUN, "情感陪伴、客服、教育与培训、营销"),
        VoiceCatalogEntry("wenrounansheng", "温柔男声", TtsVendor.STEPFUN, "口播、情感陪伴、客服、教育与培训"),
        VoiceCatalogEntry("wenrougongzi", "温柔公子", TtsVendor.STEPFUN, "情感陪伴、有声书"),
        VoiceCatalogEntry("yuanqinansheng", "元气男声", TtsVendor.STEPFUN, "有声书、口播、客服"),
        VoiceCatalogEntry("jingdiannvsheng", "经典女声", TtsVendor.STEPFUN, "客服、情感陪伴"),
        VoiceCatalogEntry("wenroushunv", "温柔熟女", TtsVendor.STEPFUN, "客服、口播、教育与培训"),
        VoiceCatalogEntry("tianmeinvsheng", "甜美女声", TtsVendor.STEPFUN, "情感陪伴、客服"),
        VoiceCatalogEntry("qingchunshaonv", "清纯少女", TtsVendor.STEPFUN, "客服、语音助手"),
        VoiceCatalogEntry("zhengpaiqingnian", "正派青年", TtsVendor.STEPFUN, "营销、有声书"),
        VoiceCatalogEntry("qingniandaxuesheng", "青年大学生", TtsVendor.STEPFUN, "口播"),
        VoiceCatalogEntry("boyinnansheng", "播音男声", TtsVendor.STEPFUN, "有声书、口播"),
        VoiceCatalogEntry("ruyananshi", "儒雅男士", TtsVendor.STEPFUN, "有声书、情感陪伴、口播、语音助手"),
        VoiceCatalogEntry("shenchennanyin", "深沉男音", TtsVendor.STEPFUN, "情感陪伴、有声书"),
        VoiceCatalogEntry("qinqienvsheng", "亲切女声", TtsVendor.STEPFUN, "口播"),
        VoiceCatalogEntry("jilingshaonv", "机灵少女", TtsVendor.STEPFUN, "语音助手、口播"),
        VoiceCatalogEntry("ruanmengnvsheng", "软萌女声", TtsVendor.STEPFUN, "情感陪伴、语音助手、视频配音"),
        VoiceCatalogEntry("youyanvsheng", "优雅女声", TtsVendor.STEPFUN, "视频配音"),
        VoiceCatalogEntry("lengyanyujie", "冷艳御姐", TtsVendor.STEPFUN, "视频配音"),
        VoiceCatalogEntry("shuangkuaijiejie", "爽快姐姐", TtsVendor.STEPFUN, "口播"),
        VoiceCatalogEntry("wenjingxuejie", "文静学姐", TtsVendor.STEPFUN, "口播"),
        VoiceCatalogEntry("linjiameimei", "邻家妹妹", TtsVendor.STEPFUN, "视频配音、口播、语音助手"),
        VoiceCatalogEntry("zhixingjiejie", "知性姐姐", TtsVendor.STEPFUN, "视频配音、口播、语音助手"),
    )

    /** 千问官方文档中 qwen-audio-3.0-tts-flash 的全部 12 个系统音色。 */
    val QWEN_FLASH_VOICES: List<VoiceCatalogEntry> = listOf(
        VoiceCatalogEntry(LONGANFENGYUE, "龙安风悦", TtsVendor.QWEN, "自然亲切女声 · 中英"),
        VoiceCatalogEntry("longanyuanfei", "龙安元妃", TtsVendor.QWEN, "高傲妃子女声 · 中英"),
        VoiceCatalogEntry("longanlingxi", "龙安灵希", TtsVendor.QWEN, "可爱甜美女声 · 中英"),
        VoiceCatalogEntry("longanxiaoxin", "龙安小昕", TtsVendor.QWEN, "亲切活泼女声 · 中英"),
        VoiceCatalogEntry(LONGANHUAN, "龙安欢", TtsVendor.QWEN, "知性陪伴女声 · 中英"),
        VoiceCatalogEntry("longjielidou_v3.6", "龙杰力豆", TtsVendor.QWEN, "天真男童 · 中英"),
        VoiceCatalogEntry("longpaopao_v3.6", "龙泡泡", TtsVendor.QWEN, "软糯可爱女童 · 中英"),
        VoiceCatalogEntry("longhuohuo_v3.6", "龙火火", TtsVendor.QWEN, "顽皮少年男声 · 中英"),
        VoiceCatalogEntry("longchuanshu_v3.6", "龙川叔", TtsVendor.QWEN, "川普大叔男声 · 中英"),
        VoiceCatalogEntry("loongmary", "loongmary", TtsVendor.QWEN, "温暖英音女声 · 仅英文"),
        VoiceCatalogEntry("loongeva_v3.6", "loongeva", TtsVendor.QWEN, "高智美音女声 · 仅英文"),
        VoiceCatalogEntry(LOONGJOHN, "loongJohn", TtsVendor.QWEN, "沉稳亲切美音男声 · 仅英文"),
    )

    val CATALOG: List<VoiceCatalogEntry> = STEPFUN_MINI_VOICES + QWEN_FLASH_VOICES
    val DISPLAY: Map<String, String> = CATALOG.associate { it.id to it.name }

    fun entriesForModel(model: String): List<VoiceCatalogEntry> = when (TtsModels.vendorOf(model)) {
        TtsVendor.QWEN -> QWEN_FLASH_VOICES
        TtsVendor.STEPFUN -> STEPFUN_MINI_VOICES
    }

    fun forModel(model: String): List<String> = entriesForModel(model).map { it.id }

    fun defaultForModel(model: String): String = when (TtsModels.vendorOf(model)) {
        TtsVendor.QWEN -> LONGANHUAN
        TtsVendor.STEPFUN -> WENROU
    }

    fun normalizeForModel(model: String, voice: String?): String {
        val allowed = forModel(model)
        return if (voice != null && voice in allowed) voice else defaultForModel(model)
    }
}

object LlmDefaults {
    const val ENDPOINT = "https://openrouter.ai/api/v1"
    const val MODEL = "openai/gpt-4.1-mini"
}

object StepFunDefaults {
    const val BASE_URL = "https://api.stepfun.com/v1"
    const val SAMPLE_RATE = 24000
}

object QwenDefaults {
    const val BASE_URL = "https://maas.qianwenaiapi.com/api/v1"
    const val SAMPLE_RATE = 24000
}
