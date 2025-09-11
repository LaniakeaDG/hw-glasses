package com.k2fsa.sherpa.ncnn.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Action {
    @SerialName("change_host")
    CHANGE_HOST,

    @SerialName("generate_minutes")
    GENERATE_MINUTES,

    @SerialName("generate_summary")
    GENERATE_SUMMARY
}


@Serializable
data class AudioMessage(
    val id: String,
    var msg_id: Int,
    val samples: FloatArray,
    val sample_rate: Int,
    val action: Action? = null,
    var tts: Int = 1              // 新增字段：0 不 TTS，1 走 TTS（默认 1）
)
