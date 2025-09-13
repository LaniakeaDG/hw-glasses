package com.k2fsa.sherpa.ncnn.test

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class BandwidthTester {

    interface BandwidthTestListener {
        fun onSpeedUpdate(speedMbps: Double, totalMB: Long, elapsedSeconds: Double)
        fun onStatusChanged(status: String)
        fun onError(error: String)
    }

    // 测试配置
    data class TestConfig(
        val concurrentConnections: Int = 8,
        val chunkSizeMB: Int = 1,
        val maxConnections: Int = 32,
        val timeoutSeconds: Int = 30,
        val endpoints: List<String> = listOf(
            "https://httpbin.org/post",
            "https://jsonplaceholder.typicode.com/posts",
            "https://reqres.in/api/users"
        )
    )

    private var isRunning = false
    private var totalBytesSent = 0L
    private var startTime = 0L
    private var listener: BandwidthTestListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = mutableListOf<OkHttpClient>()
    private val executorService = Executors.newFixedThreadPool(64)

    init {
        initializeHttpClients()
    }

    private fun initializeHttpClients() {
        // 创建多个优化的HTTP客户端
        repeat(4) { clientIndex ->
            val client = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(32, 60, TimeUnit.SECONDS))
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build()
            clients.add(client)
        }
    }

    fun setListener(listener: BandwidthTestListener) {
        this.listener = listener
    }

    fun startTest(config: TestConfig = TestConfig()) {
        if (isRunning) {
            listener?.onError("测试已在运行中")
            return
        }

        isRunning = true
        totalBytesSent = 0L
        startTime = System.currentTimeMillis()

        listener?.onStatusChanged("正在启动带宽测试...")

        // 启动多个并发发送任务
        launchConcurrentSenders(config)

        // 启动速度监控
        startSpeedMonitoring()

        listener?.onStatusChanged("带宽测试运行中 - ${config.concurrentConnections}个并发连接")
    }

    private fun launchConcurrentSenders(config: TestConfig) {
        repeat(config.concurrentConnections) { connectionId ->
            scope.launch {
                performContinuousUpload(config, connectionId)
            }
        }

        // 额外启动高速发送线程
        repeat(16) { threadId ->
            executorService.submit {
                performHighSpeedSending(config, threadId)
            }
        }
    }

    private suspend fun performContinuousUpload(config: TestConfig, connectionId: Int) {
        val client = clients[connectionId % clients.size]

        while (isRunning) {
            try {
                val dataSize = config.chunkSizeMB * 1024 * 1024
                val randomData = generateOptimizedPayload(dataSize)
                val endpoint = config.endpoints.random()

                val requestBody = randomData.toRequestBody(
                    "application/octet-stream".toMediaTypeOrNull()
                )

                val request = Request.Builder()
                    .url(endpoint)
                    .post(requestBody)
                    .header("User-Agent", "BandwidthTester-Co-$connectionId")
                    .header("Connection", "keep-alive")
                    .header("Content-Length", dataSize.toString())
                    .header("X-Connection-ID", connectionId.toString())
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        synchronized(this@BandwidthTester) {
                            totalBytesSent += dataSize
                        }
                        // 立即发起下一个请求，无延迟
                    }
                }

            } catch (e: Exception) {
                // 忽略单个错误，继续发送
                delay(10)
            }
        }
    }

    private fun performHighSpeedSending(config: TestConfig, threadId: Int) {
        val client = clients[threadId % clients.size]

        while (isRunning) {
            try {
                // 使用较小的块但发送频率更高
                val quickData = generateQuickPayload(256 * 1024) // 256KB
                val endpoint = config.endpoints.random()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(quickData.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .header("User-Agent", "SpeedTester-$threadId")
                    .header("X-Thread-ID", threadId.toString())
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            synchronized(this@BandwidthTester) {
                                totalBytesSent += quickData.size
                            }
                        }
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        // 忽略错误，继续
                    }
                })

                // 无延迟连续发送

            } catch (e: Exception) {
                Thread.sleep(1)
            }
        }
    }

    private fun generateOptimizedPayload(size: Int): ByteArray {
        // 生成难以压缩的高熵数据
        val data = ByteArray(size)
        val random = Random.Default

        // 使用复杂模式避免压缩
        for (i in data.indices) {
            data[i] = (random.nextInt(256) xor
                    (i % 256) xor
                    ((i / 256) % 256) xor
                    (System.nanoTime() % 256).toInt()).toByte()
        }

        return data
    }

    private fun generateQuickPayload(size: Int): ByteArray {
        // 快速生成模式
        return ByteArray(size) { i ->
            (Random.nextInt(256) xor (i % 256)).toByte()
        }
    }

    private fun startSpeedMonitoring() {
        scope.launch {
            while (isRunning) {
                updateSpeedStats()
                delay(500) // 每500ms更新一次
            }
        }
    }

    private fun updateSpeedStats() {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        if (elapsed > 0) {
            val speed = totalBytesSent / elapsed
            val speedMbps = (speed * 8) / (1024 * 1024)
            val totalMB = totalBytesSent / (1024 * 1024)

            listener?.onSpeedUpdate(speedMbps, totalMB, elapsed)
        }
    }

    fun stopTest() {
        isRunning = false
        listener?.onStatusChanged("正在停止测试...")

        scope.coroutineContext[Job]?.cancelChildren()
        executorService.shutdownNow()

        listener?.onStatusChanged("测试已停止")
    }

    fun isTestRunning(): Boolean = isRunning

    fun getTestStats(): TestStats {
        val elapsed = if (startTime > 0) (System.currentTimeMillis() - startTime) / 1000.0 else 0.0
        val speedMbps = if (elapsed > 0) (totalBytesSent / elapsed * 8) / (1024 * 1024) else 0.0

        return TestStats(
            totalBytesSent = totalBytesSent,
            elapsedSeconds = elapsed,
            speedMbps = speedMbps,
            isRunning = isRunning
        )
    }

    data class TestStats(
        val totalBytesSent: Long,
        val elapsedSeconds: Double,
        val speedMbps: Double,
        val isRunning: Boolean
    )

    fun destroy() {
        stopTest()
        scope.cancel()
        clients.forEach { client ->
            client.dispatcher.executorService.shutdown()
        }
    }
}