package com.k2fsa.sherpa.ncnn.request

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log


private fun getAveragePingTime(pingOutput: String): Double {
    return try {
        // 分割输出为行
        val lines = pingOutput.lines()

        // 匹配整数或小数，例如 time=23 ms 或 time=23.456 ms
        val regex = Regex("time=(\\d+(?:\\.\\d+)?) ms")

        // 提取所有 time 值
        val timeValues = lines
            .filter { it.contains("time=") }
            .mapNotNull { line ->
                regex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            }

        // 如果没有找到时间值，返回 0.0
        if (timeValues.isEmpty()) 0.0 else timeValues.average()
    } catch (e: Exception) {
        e.printStackTrace()
        0.0
    }
}

private suspend fun getPingRTT(host: String): String = withContext(Dispatchers.IO) {
    try {
        val process = Runtime.getRuntime().exec("/system/bin/ping -c 4 $host")
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        Log.e("rtt", output)
        reader.close()
        output
    } catch (e: Exception) {
        e.printStackTrace()
        "Ping failed"
    }
}

suspend fun getAvgRTT(host: String): Double {
    return getAveragePingTime(getPingRTT(host))
}

fun decodeUnicode(input: String): String {
    val prop = java.util.Properties()
    val reader = java.io.StringReader("key=$input")
    prop.load(reader)
    return prop.getProperty("key")
}


fun visToBitmap(vis: Array<Array<IntArray>>): Bitmap {
    val height = vis.size
    val width = vis[0].size

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val rgb = vis[y][x]
            val color = Color.rgb(rgb[0], rgb[1], rgb[2]) // R,G,B
            bitmap.setPixel(x, y, color)
        }
    }

    return bitmap
}