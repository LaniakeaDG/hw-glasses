package com.k2fsa.sherpa.ncnn.request

import kotlinx.serialization.Serializable

@Serializable
data class AudioResponseMessage(
    val id: String? = null,
    val msg_id: Int? = null,
    val content: String? = null,
    val process_time: Double? = null,
    val type: String? = null,
    val message: String? = null,
    val speaker: Int? = null,
)

