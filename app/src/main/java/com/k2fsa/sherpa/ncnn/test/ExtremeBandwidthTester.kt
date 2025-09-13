package com.k2fsa.sherpa.ncnn.test
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random


// 极限性能版本
class ExtremeBandwidthTester {
    private val maxThreads = 64
    private val megaChunkSize = 5 * 1024 * 1024 // 5MB chunks
    private var isActive = false
    private val clients = mutableListOf<OkHttpClient>()

    init {
        // 创建8个高性能客户端
        repeat(8) {
            val client = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(50, 120, TimeUnit.SECONDS))
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false) // 禁用重试以最大化速度
                .build()
            clients.add(client)
        }
    }

    fun unleashMaximumSpeed(endpoints: List<String>, callback: (Long) -> Unit) {
        isActive = true

        // 预生成多个大数据块
        val preGeneratedChunks = mutableListOf<ByteArray>()
        repeat(10) {
            preGeneratedChunks.add(createMegaChunk(megaChunkSize))
        }

        // 启动极限并发
        repeat(maxThreads) { threadIndex ->
            Thread {
                val client = clients[threadIndex % clients.size]
                var bytesSent = 0L

                while (isActive) {
                    try {
                        val chunk = preGeneratedChunks[threadIndex % preGeneratedChunks.size]
                        val endpoint = endpoints[Random.nextInt(endpoints.size)]

                        val request = Request.Builder()
                            .url(endpoint)
                            .post(chunk.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                            .header("X-Extreme-Thread", threadIndex.toString())
                            .build()

                        client.newCall(request).enqueue(object : Callback {
                            override fun onResponse(call: Call, response: Response) {
                                response.close() // 立即关闭响应
                                bytesSent += chunk.size
                                callback(bytesSent)
                            }

                            override fun onFailure(call: Call, e: IOException) {
                                // 忽略失败，继续发送
                            }
                        })

                        // 无延迟，立即发送下一个

                    } catch (e: Exception) {
                        // 极短延迟后继续
                        Thread.sleep(1)
                    }
                }
            }.start()
        }
    }

    private fun createMegaChunk(size: Int): ByteArray {
        val chunk = ByteArray(size)
        val timestamp = System.nanoTime()

        for (i in chunk.indices) {
            // 创建复杂的不可压缩模式
            chunk[i] = ((Random.nextInt(256) xor
                    (i % 256) xor
                    ((timestamp + i) % 256).toInt()) and 0xFF).toByte()
        }

        return chunk
    }

    fun stop() {
        isActive = false
    }
}