package com.k2fsa.sherpa.ncnn.request

import kotlinx.serialization.Serializable

@Serializable
class VisualPromptMessage(val keyword: String, val text: String, val vis: Vis)
