package com.k2fsa.sherpa.ncnn.utils
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class FileLogger private constructor(private val context: Context) {

    private val logDir: File by lazy {
        File(context.getExternalFilesDir(null), "logs").apply {
            if (!exists()) mkdirs()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: FileLogger? = null

        fun getInstance(context: Context): FileLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FileLogger(context.applicationContext).also { INSTANCE = it }
            }
        }

        // CSV文件头定义
        private const val POWER_CSV_HEADER = "time,scenario,strategy,current,voltage,power"
        private const val TEMPERATURE_CSV_HEADER = "time,scenario,strategy,temperature"
        private const val LATENCY_CSV_HEADER = "time,scenario,strategy,latency"
    }

    /**
     * 写入CSV格式数据到文件（异步）
     */
    private fun writeCsvToFile(fileName: String, header: String, data: String, tag: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val logFile = File(logDir, fileName)
                val fileExists = logFile.exists()
                val writer = FileWriter(logFile, true)

                // 如果文件不存在，先写入表头
                if (!fileExists) {
                    writer.append(header).append("\n")
                }

                // 写入数据
                writer.append(data).append("\n")
                writer.close()

                // 同时输出到Logcat
                Log.d(tag, data.replace(",", " | "))

            } catch (e: IOException) {
                Log.e("FileLogger", "写入CSV文件失败: ${e.message}")
            }
        }
    }

    /**
     * 获取当前时间戳（CSV格式）
     */
    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    /**
     * 记录能耗数据到CSV
     */
    fun logPowerUsage(current: Float, voltage: Float, power: Float, soc:Int, scenario: String = "default", strategy: String = "default") {
        val timestamp = getCurrentTimestamp()
        val csvData = "$timestamp,$scenario,$strategy,$current,$voltage,$power,$soc"
        writeCsvToFile("power_usage.csv", POWER_CSV_HEADER, csvData, "power")
    }

    /**
     * 记录温度数据到CSV
     */
    fun logTemperature(celsius: Float, scenario: String = "default", strategy: String = "default") {
        val timestamp = getCurrentTimestamp()
        val csvData = "$timestamp,$scenario,$strategy,$celsius"
        writeCsvToFile("temperature.csv", TEMPERATURE_CSV_HEADER, csvData, "temperature")
    }

    /**
     * 记录时延数据到CSV
     */
    fun logLatency(latency: String, scenario: String = "default", strategy: String = "default") {
        val timestamp = getCurrentTimestamp()
        val csvData = "$timestamp,$scenario,$strategy,$latency"
        writeCsvToFile("latency.csv", LATENCY_CSV_HEADER, csvData, "latency")
    }

    /**
     * 清理旧日志文件
     */
    fun clearOldLogs(daysToKeep: Int = 7) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

                logDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoffTime) {
                        file.delete()
                        Log.d("FileLogger", "删除旧日志文件: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "清理日志文件失败", e)
            }
        }
    }

    /**
     * 获取日志文件路径
     */
    fun getLogFilePath(fileName: String): String {
        return File(logDir, fileName).absolutePath
    }

    /**
     * 获取所有可用的日志文件
     */
    fun getAllLogFiles(): List<File> {
        return logDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * 读取指定CSV文件的最后几行
     */
    fun readLastLines(fileName: String, lineCount: Int = 10): List<String> {
        return try {
            val logFile = File(logDir, fileName)
            if (!logFile.exists()) return emptyList()

            val lines = logFile.readLines()
            if (lines.size <= lineCount) lines else lines.takeLast(lineCount)
        } catch (e: Exception) {
            Log.e("FileLogger", "读取CSV文件失败", e)
            emptyList()
        }
    }

    /**
     * 获取CSV文件的总记录数（不包括表头）
     */
    fun getCsvRecordCount(fileName: String): Int {
        return try {
            val logFile = File(logDir, fileName)
            if (!logFile.exists()) return 0

            val lines = logFile.readLines()
            maxOf(0, lines.size - 1) // 减去表头行
        } catch (e: Exception) {
            Log.e("FileLogger", "获取CSV记录数失败", e)
            0
        }
    }
}