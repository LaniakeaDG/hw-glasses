package com.k2fsa.sherpa.ncnn.request
import kotlinx.serialization.Serializable

@Serializable
data class TextMessage(val msg_id: Int, val content: String, val process_time: Double ?=null, val id: String? = null, var tts: Int = 1)

