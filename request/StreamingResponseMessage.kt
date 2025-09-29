package com.k2fsa.sherpa.ncnn.request
import kotlinx.serialization.Serializable

@Serializable
data class StreamingResponseMessage(val code: Int, val msg_id: Int, val result: FloatArray, val process_time: Double)
