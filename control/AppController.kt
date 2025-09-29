package com.k2fsa.sherpa.ncnn.control
import android.provider.Settings
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.ncnn.Gender
import com.k2fsa.sherpa.ncnn.GenderDetector
import com.k2fsa.sherpa.ncnn.gesture.GestureDetector
import com.k2fsa.sherpa.ncnn.translation.LlmTranslator
import com.k2fsa.sherpa.ncnn.recorder.AudioRecorder
import com.k2fsa.sherpa.ncnn.recorder.SpeechRecognizer
import com.k2fsa.sherpa.ncnn.video.CameraManager
import com.k2fsa.sherpa.ncnn.video.VideoFrameProcessor
import com.k2fsa.sherpa.ncnn.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.k2fsa.sherpa.ncnn.algorithm.Algorithm
import com.k2fsa.sherpa.ncnn.recorder.calculateRMS
import com.k2fsa.sherpa.ncnn.recorder.normalizeRMS
import com.k2fsa.sherpa.ncnn.request.Action
import com.k2fsa.sherpa.ncnn.request.AudioMessage
import com.k2fsa.sherpa.ncnn.request.AudioResponseMessage
import com.k2fsa.sherpa.ncnn.request.Request
import com.k2fsa.sherpa.ncnn.request.TextMessage
import com.k2fsa.sherpa.ncnn.request.RegisterMessage
import com.k2fsa.sherpa.ncnn.request.StreamingMessage
import com.k2fsa.sherpa.ncnn.request.StreamingResponseMessage
import com.k2fsa.sherpa.ncnn.request.SummaryMessage
import com.k2fsa.sherpa.ncnn.request.TranslatedText
import com.k2fsa.sherpa.ncnn.request.VisualPromptMessage
import com.k2fsa.sherpa.ncnn.request.decodeUnicode
import com.k2fsa.sherpa.ncnn.request.getAvgRTT
import com.k2fsa.sherpa.ncnn.speaker.AudioPlayer
import com.k2fsa.sherpa.ncnn.status.BatteryStats
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.k2fsa.sherpa.ncnn.status.StatusMonitor
import com.k2fsa.sherpa.ncnn.translation.isChinese
import com.k2fsa.sherpa.ncnn.translation.isEnglish
import com.k2fsa.sherpa.ncnn.translation.tokenizeChinese
import com.k2fsa.sherpa.ncnn.translation.tokenizeEnglish
import com.k2fsa.sherpa.ncnn.ui.Message
import com.k2fsa.sherpa.ncnn.utils.FileLogger
import com.k2fsa.sherpa.onnx.KokoroTTS
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale


/**
 * 查看时延 - tag:latency
 * 查看电量/CPU/内存/温度 - tag:usage
 * 查看控制逻辑 - tag:controller
 * 查看模块结果 - tag:result
 * 查看websocket - tag:websocket
 * 查看各种异常 - tag:exception
 * 查看算法输出结果 - tag:algorithm
 */
class AppController private constructor() {

    private lateinit var id: String


    /**
     * 选择场景
     * S1: 8-12Mbps
     * S2: 0.8-1.2Mbps
     * S3: 0.0-10.0Mbps
     */
    private var scenario = Scenario.S1
    /**
     * 选择初始策略
     * B1: Device-only
     * B2: Edge-only
     * B3: static
     * B4: 11100
     * B5: 00100
     * B6: 11-0-
     * B7: 01-0-
     * SYS: Glossaglass
     */

    private var strategy = Strategy.B3


    private lateinit var outVector: IntArray

    private var sttProcessingTime: Long = 0L
    private var bandWidth: Double = 0.0
    private var battery: Double = 0.0

    // Modules

    private val logger = Logger(this::class.java.simpleName)
    private val eventBus = EventBus()
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var cameraManager: CameraManager
    private lateinit var videoFrameProcessor: VideoFrameProcessor
    private lateinit var gestureDetector: GestureDetector
    private lateinit var genderDetector: GenderDetector

    // 扬声器
    private lateinit var audioPlayer: AudioPlayer

    // 卸载算法
    private lateinit var algorithm: Algorithm
    // 控制逻辑
    private lateinit var unloadController: UnloadController

    // 翻译器
    private lateinit var translationManager: LlmTranslator


    // Callbacks
    private var recognitionCallback: ((Message) -> Unit)? = null
    private var progressBarCallback: ((Float) -> Unit)? = null
    private var translationCallback: ((String) -> Unit)? = null
    private var gestureCallback: ((GestureDetector.GestureType) -> Unit)? = null
    private var genderCallback: ((Gender) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null
    private var usageCallback: ((String) -> Unit)? = null
    private var bandWidthCallback: ((String) -> Unit)? = null
    private var rttCallback: ((String) -> Unit)? = null
    private var batteryCallback: ((String) -> Unit)? = null
    private var algorithmCallback: ((String) -> Unit)? = null
    private var firstTokenCallback: ((String) -> Unit)? = null
    private var powerCallback: ((String) -> Unit)? = null
    private var temperatureCallback: ((String) -> Unit)? = null
    private var startRecordingCallback: ((String) -> Unit)? = null
    private var identificationCompletedCallback: ((String) -> Unit)? = null
    private var summaryCallback: ((String) -> Unit)? = null
    private var summaryCloseCallback: ((String) -> Unit)? = null
    private var loadingCallback: ((String) -> Unit)? = null
    private var visualPromptCallback: ((VisualPromptMessage) -> Unit)? = null
    private var visualPromptRefreshCallback: ((String) -> Unit)? = null

    // State management
    private var isRecording = false
    private var videoCaptureJob: Job? = null
    private var isInitialized = false

    // Initialize modules with activity context
    //, assets: AssetManager
    private lateinit var context: Context

    // 功耗检测
    private lateinit var statusMonitor: StatusMonitor

    // request请求类
    private var request = Request()

    // websocket
    private lateinit var translateWebSocketSession: DefaultClientWebSocketSession
    private lateinit var soundToTextWebSocketSession: DefaultClientWebSocketSession
    private lateinit var streamingMediaWebSocketSession: DefaultClientWebSocketSession
    private lateinit var summaryWebSocketSession: DefaultClientWebSocketSession
    private lateinit var visualPromptWebSocketSession: DefaultClientWebSocketSession

    private var webSocketClient: HttpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    private val _textResultFlow = MutableSharedFlow<String>()
    private val textResultFlow = _textResultFlow.asSharedFlow()


    private val _soundResultFlow = MutableSharedFlow<AudioMessage>()
    private val soundResultFlow = _soundResultFlow.asSharedFlow()

    private val _streamResultFlow = MutableSharedFlow<String>()
    private val streamResultFlow = _streamResultFlow.asSharedFlow()



    // 性别
    private var paramGender = "male"

    // 中英互译的流控制
    private var messageId: Int = 0
    private var isStart = true
    private var translatedText: TranslatedText = TranslatedText("", messageId, "")
    private var flowStartTime1: Long = 0L
    private var flowStartTime3: Long = 0L
    private var flowEnd = true
    private var flowStartTime5: Long = 0L
    private var firstTokenPartOne: Double = 0.0
    // 文本转语音
    // TODO: 搜索这个变量
//    private lateinit var kokoroTTS: KokoroTTS


    // FileLogger
    private lateinit var fileLogger: FileLogger


    // rtt
    private var rtt: Double = 0.0
    private fun setRtt() {
        mainScope.launch {
            rtt = getAvgRTT(request.baseUrl)
        }
    }
    /**
     *  TTS 开关标志
     * - 在卸载策略 B6 (11-0-) 和 B7 (01-0-) 下，强制关闭 TTS，返回 0
     * - 其它策略默认开启 TTS，返回 1
     *
     * 作用：用于控制发往 10003 WebSocket 的 AudioMessage 中的 tts 字段
     */
    private fun currentTtsFlag(): Int =
        if (strategy == Strategy.B6 || strategy == Strategy.B7) 0 else 1

    private fun isGenderEnabled(): Boolean =
        strategy != Strategy.B6 && strategy != Strategy.B7

    private var canSwitch = true

    /**
     * 初始化
     */
    fun initialize(activity: AppCompatActivity) {
        if (isInitialized) return

        context = activity
        // 初始化日志工具
        fileLogger = FileLogger.getInstance(context)
        // 获取日志文件路径
        val powerLogPath = fileLogger.getLogFilePath("power_usage.csv")
        val tempLogPath = fileLogger.getLogFilePath("temperature.csv")
        val latencyPath = fileLogger.getLogFilePath("latency.csv")

        Log.d("LogPaths", "能耗日志路径: $powerLogPath")
        Log.d("LogPaths", "温度日志路径: $tempLogPath")
        Log.d("LogPaths", "时延日志路径: $latencyPath")

        /**
         * 初始化卸载向量
         * [0]: 语音转文本
         * [1]: 中英互译
         * [2]: 文本转语音
         * [3]: 图像识别男女
         * [4]: 图像识别手势
         */
        eventBus.publish(EventBus.Event.USAGE, strategy.toString())
        outVector = when (strategy) {
            Strategy.B1 -> intArrayOf(0, 0, 0, 0, 0)
            Strategy.B2 -> intArrayOf(1, 1, 1, 1, 1)
            Strategy.B3 -> intArrayOf(0, 1, 1, 0, 0)
            Strategy.B4 -> intArrayOf(1, 1, 1, 0, 0)
            Strategy.B5 -> intArrayOf(0, 0, 1, 0, 0)
            Strategy.B6 -> intArrayOf(1, 1, 0, 0, 0) // 11-0-
            Strategy.B7 -> intArrayOf(0, 1, 0, 0, 0) // 01-0-
            Strategy.SYS -> intArrayOf(1, 1, 1, 0, 0)
        }


        val assets = activity.assets

        // Initialize audio modules
        audioRecorder = AudioRecorder(activity)
        speechRecognizer = SpeechRecognizer(assets)

        // Initialize video modules
        cameraManager = CameraManager(activity)
        videoFrameProcessor = VideoFrameProcessor(activity.baseContext,debugSaveImages = false)

        // Initialize AI modules
        gestureDetector = GestureDetector(activity)
        genderDetector = GenderDetector(activity)
        translationManager = LlmTranslator

        // Initialize Speaker modules
        audioPlayer = AudioPlayer()


//        kokoroTTS = KokoroTTS(activity.baseContext)

        algorithm = Algorithm(eventBus)

        // 初始化卸载控制器
        unloadController = UnloadController(outVector)



        /**
         * 电源监控相关初始化
         */
        // 初始化监控器
        statusMonitor = StatusMonitor(mainScope, context, eventBus)
        // 初始化状态检测接收器
        statusMonitor.registerBatteryReceiver(activity)



        setupEventListeners()

        isInitialized = true
    }

    private fun checkInitialization() {
        if (!isInitialized) {
            throw IllegalStateException("AppController has not been initialized with an activity. Call initialize() first.")
        }
    }

    /**
     * 设置事件监听
     */
    private val audioJob = Job() // 创建一个 Job 用于控制协程
    private val audioScope = CoroutineScope(Dispatchers.IO + audioJob)
    private val audioMutex = Mutex()
    private fun setupEventListeners() {
        eventBus.subscribe(EventBus.Event.SOUND_INTENSITY) { data ->
            val soundIntensity = data as Float
            progressBarCallback?.invoke(soundIntensity)
        }


        // Handle speech recognition results
        eventBus.subscribe(EventBus.Event.SPEECH_RESULT) { data ->
            val recognizedText = data as Message
            recognitionCallback?.invoke(recognizedText)
        }

        // Handle translation results
        eventBus.subscribe(EventBus.Event.TRANSLATION_RESULT) { data ->
            val translatedText = data as String
            translationCallback?.invoke(translatedText)
        }

        // 处理文本转语音
        eventBus.subscribe(EventBus.Event.TEXT_TO_SOUND) { data ->
            val translatedTextTemp = data as TranslatedText
//            audioScope.launch {
//                val text = translatedText.text
//                val language = translatedText.language
//                val id = translatedText.id
//                translatedText.clear() // 清空临时数据
//                try {
//
//                    if (outVector[2] == 0) {
//                        audioMutex.withLock {
//
//                            if (paramGender == "male") {
//                                kokoroTTS.speak(text, 60)
//                            } else if (paramGender == "female") {
//                                kokoroTTS.speak(text, 0)
//                            }
//
//                        }
//                    } else if (outVector[2] == 1) {
//                        _streamResultFlow.emit(text)
//
//                    }
//
//                } catch (e: Exception) {
//                    Log.e("exception", "Error processing audio: ${e.message}")
//                }
//            }

        }

        eventBus.subscribe(EventBus.Event.SUMMARY) { data ->
            val summaryMessage = data as String
            summaryCallback?.invoke(summaryMessage)
        }

        eventBus.subscribe(EventBus.Event.SUMMARY_CLOSE) {
            summaryCloseCallback?.invoke("")
        }

        eventBus.subscribe(EventBus.Event.USAGE) { data ->
            val usage = data as String
            usageCallback?.invoke(usage)
        }

        eventBus.subscribe(EventBus.Event.LOADING) { data ->
            val msg = data as String
            loadingCallback?.invoke(msg)
        }

        // Handle gesture detection results
        eventBus.subscribe(EventBus.Event.GESTURE_DETECTED) { data ->
            val gesture = data as GestureDetector.GestureType
            gestureCallback?.invoke(gesture)
        }

        // Handle gender detection results
        eventBus.subscribe(EventBus.Event.GENDER_DETECTED) { data ->
            val gender = data as Gender
            genderCallback?.invoke(gender)
        }

        eventBus.subscribe(EventBus.Event.BAND_WIDTH) { data ->
            val bandWidth = data as String
            this.bandWidth = bandWidth.toDouble()
            bandWidthCallback?.invoke(bandWidth)
        }
        eventBus.subscribe(EventBus.Event.RTT) { data ->
            val rtt = data as String
            rttCallback?.invoke(rtt)
        }
        eventBus.subscribe(EventBus.Event.BATTERY) { data ->
            val battery = data as String
            this.battery = battery.toDouble()
            batteryCallback?.invoke(battery)
        }
        eventBus.subscribe(EventBus.Event.ALGORITHM) { data ->
            val algorithm = data as String
            algorithmCallback?.invoke(algorithm)
        }
        eventBus.subscribe(EventBus.Event.FIRST_TOKEN) { data ->
            val firstToken = data as String
            fileLogger.logLatency(firstToken, scenario.toString(), strategy.toString())
            firstTokenCallback?.invoke(firstToken)
        }
        eventBus.subscribe(EventBus.Event.POWER) { data ->
            val batteryStats = data as BatteryStats
            val power = batteryStats.power.toFloat()
            val current = batteryStats.current.toFloat()
            val voltage = batteryStats.voltage.toFloat()
            val soc = batteryStats.soc
            fileLogger.logPowerUsage(current, voltage, power, soc, scenario.toString(), strategy.toString())

            powerCallback?.invoke(String.format(Locale.US, "%.3f", power))
        }
        eventBus.subscribe(EventBus.Event.TEMPERATURE) { data ->
            val temperature = data as Float
            fileLogger.logTemperature(temperature, scenario.toString(), strategy.toString())
            temperatureCallback?.invoke("%.1f".format(temperature))
        }


        eventBus.subscribe(EventBus.Event.START_RECORDING) { data ->
            val mes = data as String
            startRecordingCallback?.invoke(mes)
        }
        eventBus.subscribe(EventBus.Event.IDENTIFICATION_COMPLETED) { data ->
            val mes = data as String
            identificationCompletedCallback?.invoke(mes)
        }
        eventBus.subscribe(EventBus.Event.VISUAL_PROMPT) { data ->
            val mes = data as VisualPromptMessage
            visualPromptCallback?.invoke(mes)
        }
        eventBus.subscribe(EventBus.Event.VISUAL_PROMPT_REFRESH) { data ->
            val mes = data as String
            visualPromptRefreshCallback?.invoke(mes)
        }

    }


    /**
     * 捕获音频
     */
    private val recordJob = Job() // 创建一个 Job 用于控制协程
    private val recordScope = CoroutineScope(Dispatchers.IO + recordJob)
    fun startRecording() {

        Log.e("controller", "开始识别语音")

        checkInitialization()
        if (isRecording) return

        isRecording = true
        statusCallback?.invoke("Recording started")

        // 每次开始识别语音时，关闭总结框
        eventBus.publish(EventBus.Event.SUMMARY_CLOSE, "")

        if (outVector[0] == 1) { // 语音转文本在边测，才会有说话人识别
            // 每次开始识别语音时，弹出识别说话人的框
            eventBus.publish(EventBus.Event.START_RECORDING, "start recording")
            recordScope.launch {
                _soundResultFlow.emit(AudioMessage("", -1, FloatArray(0), -1, Action.CHANGE_HOST))
            }
        } else {
            // 直接提示识别完成
            eventBus.publish(EventBus.Event.IDENTIFICATION_COMPLETED, "identification completed")

        }





        var interval = 0.1 // 本地文本转语音 默认间隔100ms
        if (outVector[0] == 1) {
            // 端侧文本转语音 -> 中英互译 -> 设置间隔为400ms
//            interval = 0.4  // 一般环境
            interval = 0.1 // 服务器环境
        }

        audioRecorder.startRecording(interval) { samples ->

            recordScope.launch {

                if (outVector[0] == -1)
                    return@launch

                // 先进行语音转文本
                val startTime = System.currentTimeMillis()
                val soundToTextResult: String = unloadController.soundToText(speechRecognizer, eventBus, request, samples)
                sttProcessingTime = System.currentTimeMillis() - startTime


                if (soundToTextResult == "<|WS|>") { // 特殊控制字符，边测文本转语音+中英互译
                    // 计算样本的 RMS（均方根）值
                    val rms = calculateRMS(samples)
                    val volume = normalizeRMS(rms)


//                    val silenceThreshold = 0.01f // 一般环境
//                    val silenceThreshold = 0.04f // 手机测试

                    val silenceThreshold = 0.001f // 眼镜测试

                    // 判断是否为空语音
                    if (rms < silenceThreshold) {
                        canSwitch = true
                        // 空语音
//                        _soundResultFlow.emit(FloatArray(0)) // 发送空数组到流，同时指定id = -1（断句标识）
                        if (!flowEnd) {
                            flowStartTime3 = System.currentTimeMillis()
                            flowStartTime5 = System.currentTimeMillis()
                            setRtt()
                            flowEnd = true
                        }
                    } else {
                        canSwitch = false
                        // 非空语音
//                        _soundResultFlow.emit(samples) // 发送 samples (FloatArray) 到流
                        flowEnd = false
                    }
                    _soundResultFlow.emit(AudioMessage("", -1, samples, -1))


                } else if (soundToTextResult != "") { // 本地文本转语音，且转出来的值不是空
                    Log.e("latency", "端侧 语音转文本--经过时间: $sttProcessingTime ms")
                    val sttStart = System.currentTimeMillis() // 单位：毫秒
                    val localResultText = unloadController.translate(soundToTextResult, translationManager) {
                        // 服务端执行
                        _textResultFlow.emit(soundToTextResult) // 发送 soundToTextResult (String) 到流
                    }
                    val elapsedTime = System.currentTimeMillis() - sttStart
                    Log.e("latency", "端测 中英互译---经过时间: $elapsedTime ms")
                    Log.e("latency", "路径1/4 首Token时延: ${sttProcessingTime + elapsedTime} ms")
                    eventBus.publish(EventBus.Event.FIRST_TOKEN, "${sttProcessingTime + elapsedTime}")

                    if (localResultText != "") {
                        // 本地非流式输出
                        eventBus.publish(EventBus.Event.TRANSLATION_RESULT, "<|STX|>")
                        translatedText.text = localResultText
                        translatedText.id = -1
                        if (isChinese(localResultText)) {
                            translatedText.language = "zh"
                            val tokens = tokenizeChinese(localResultText)
                            tokens.forEach {
                                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, it)
                                delay(100)
                            }
                        } else {
                            translatedText.language = "en"
                            val tokens = tokenizeEnglish(localResultText)
                            tokens.forEach {
                                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, "$it ")
                                delay(100)
                            }
                        }
                        // 文本转语音
                        eventBus.publish(EventBus.Event.TEXT_TO_SOUND, translatedText)
                    }
                }
            }
        }

    }

    /**
     * 停止捕获音频
     */
    fun stopRecording() {

        checkInitialization()
        if (!isRecording) return

        isRecording = false
        statusCallback?.invoke("Recording stopped")

        // Stop audio recording
        audioRecorder.stopRecording()

    }

    /**
     * 挂载生命周期
     */
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeJob = Job()
    private val timeScope = CoroutineScope(Dispatchers.IO + timeJob)

    fun onResume() {

        checkInitialization()
        // Start periodic video capture for gesture and gender detection
        startPeriodicVideoCapture()

        // 状态监控
        statusMonitor.startPowerMonitoring()
//        statusMonitor.startMemoryMonitoring()
        statusMonitor.startTemperatureMonitoring()
        id = getDeviceId(context)

        setRtt()

        // websocket建立连接
        if (outVector[0] == 1 || outVector[1] == 1) {
            coroutineScope.launch {
                initTranslateWebSocket()
            }
            coroutineScope.launch {
                initSoundToTextWebSocket()
            }
        }
        coroutineScope.launch {
            initSummaryWebSocket()
        }
        coroutineScope.launch {
            initStreamingMediaWebSocket()
        }
        coroutineScope.launch {
            initVisualPromptWebSocket()
        }


        // 卸载算法
        // TODO: 真正修改this@AppController.outVector

        if (strategy == Strategy.SYS) {
            timeScope.launch {

                while (true) {
                    // 发送1Mb的包
                    val bandwidth = request.testSpeed(1000 * 1000) / 1024 // Mb/s"
                    Log.e("speed", "当前网络带宽约为 $bandwidth Mb/s")
                    val result = algorithm.strategy(bandwidth)
                    if (result == 2 && canSwitch) {
                        outVector = intArrayOf(1, 1, 1, 1, 1)
                    } else if (result == 3 && canSwitch) {
                        outVector = intArrayOf(0, 1, 1, 0, 0)
                    }

                    delay(5 * 1000)

                }
            }
        }


        timeScope.launch {
            // 断线自动重连
            while (true) {
                if (::translateWebSocketSession.isInitialized && !isWebSocketConnected(translateWebSocketSession)) {
                    Log.e("websocket", "translateWebSocketSession链接断开，重连中")
                    translateWebSocketSession.close()
                    coroutineScope.launch {
                        initTranslateWebSocket()
                    }
                }
                if (::soundToTextWebSocketSession.isInitialized && !isWebSocketConnected(soundToTextWebSocketSession)) {
                    Log.e("websocket", "soundToTextWebSocketSession链接断开，重连中")
                    soundToTextWebSocketSession.close()
                    coroutineScope.launch {
                        initSoundToTextWebSocket()
                    }
                }
                if (::streamingMediaWebSocketSession.isInitialized && !isWebSocketConnected(streamingMediaWebSocketSession)) {
                    Log.e("websocket", "streamingMediaWebSocketSession链接断开，重连中")
                    streamingMediaWebSocketSession.close()
                    coroutineScope.launch {
                        initStreamingMediaWebSocket()
                    }
                }
                if (::summaryWebSocketSession.isInitialized && !isWebSocketConnected(summaryWebSocketSession)) {
                    Log.e("websocket", "summaryWebSocketSession链接断开，重连中")
                    summaryWebSocketSession.close()
                    coroutineScope.launch {
                        initSummaryWebSocket()
                    }
                }
                if (::visualPromptWebSocketSession.isInitialized && !isWebSocketConnected(visualPromptWebSocketSession)) {
                    Log.e("websocket", "visualPromptWebSocketSession链接断开，重连中")
                    visualPromptWebSocketSession.close()
                    coroutineScope.launch {
                        initVisualPromptWebSocket()
                    }
                }
                delay(5 * 1000)
            }
        }


        // 网络带宽 / RTT：每2分钟执行一次
//        timeScope.launch {
//            while (true) {
//                val bandwidth = request.testSpeed(1000 * 1000) // 1MB
//                Log.e("speed", "当前网络带宽约为 ${bandwidth / 1024} Mb/s")
//                val rtt = getAvgRTT(request.baseUrl)
//                Log.e("speed", "RTT: $rtt ms")
//                eventBus.publish(EventBus.Event.BAND_WIDTH, String.format(Locale.US, "%.3f", bandwidth / 1024))
//                eventBus.publish(EventBus.Event.RTT, String.format(Locale.US, "%.3f", rtt))
//                delay(2 * 60 * 1000)
//            }
//        }

    }

    /**
     * 暂停生命周期
     */
    fun onPause() {
        checkInitialization()
        // Stop video capture
        stopPeriodicVideoCapture()

        // Ensure recording is stopped
        if (isRecording) {
            stopRecording()
        }
    }


    /**
     * 销毁生命周期
     */
    fun onDestroy() {
        checkInitialization()
        // Clean up resources
        stopPeriodicVideoCapture()
        eventBus.clear()

        audioRecorder.release()
        speechRecognizer.shutdown()
        cameraManager.release()
        videoFrameProcessor.shutdown()
        gestureDetector.close()
        genderDetector.close()

        // 关闭文本转语音模块
//        kokoroTTS.destroy()

        // 关闭状态监控
        statusMonitor.unregisterBatteryReceiver()
        statusMonitor.stopPowerMonitoring()
        statusMonitor.stopMemoryMonitoring()
        statusMonitor.stopTemperatureMonitoring()

        // 清理协程
        cameraJob.cancel()
        recordScope.cancel()
        coroutineScope.cancel() // 取消所有协程
        webSocketClient.close() // 关闭 WebSocket 客户端
    }



    /**
     * 捕获图像
     */
    private val cameraJob = Job() // 创建一个 Job 用于控制协程
    private val cameraScope = CoroutineScope(Dispatchers.IO + cameraJob)

    private fun startPeriodicVideoCapture() {
        stopPeriodicVideoCapture()
        videoCaptureJob = mainScope.launch {
            while (true) {
                captureAndProcessVideoFrames()
                delay(5_000) // Wait 5 seconds before next capture
            }
        }
    }

    private fun stopPeriodicVideoCapture() {
        videoCaptureJob?.cancel()
        videoCaptureJob = null
    }

    // 处理摄像头捕获的图像（每5秒这个函数会被调用一次）
    private fun captureAndProcessVideoFrames() {
        cameraManager.captureFrames(1) { frames ->

            // 对每个帧进行处理
            frames.forEach { frameInfo ->
                videoFrameProcessor.addFrame(
                    frameInfo.data,
                    frameInfo.width,
                    frameInfo.height
                )
            }

            // 只取一帧frame
            val frame = videoFrameProcessor.getFramesForAllDetection(1)

            // 先进行手势识别
            cameraScope.launch {
                // 暂存改变前的isRecording
                val preIsRecording = isRecording

                val gesture: String = unloadController.gestureRecognition(gestureDetector, eventBus, request, frame)

                Log.e("result", "手势识别结果为$gesture")

                if (gesture == "none") {

                    // 识别到none，此时可能正在录音，或还没开始录音
                    if (isRecording) {

                        if (isGenderEnabled()) {
                            // 正在录音，识别男女
                            val gender: String = unloadController.genderRecognition(genderDetector, eventBus, request, frame)
                            paramGender = gender
                            val sendGender = gender.replaceFirstChar { it.uppercaseChar() }
                            request.testGender(id, sendGender)
                            Log.e("controller", "正在录音，识别男女")
                            Log.e("result", "识别男女结果$gender")
                        } else {
                            Log.e("controller", "策略为B6/B7，跳过性别识别（正在录音）")
                        }
                    } else {
                        // 不需要识别男女
                        Log.e("controller", "还没开始录音，不需要识别男女")
                    }

                } else if (gesture == "fist") {
                    // 识别到fist，此时可能想要结束录音，或想要开始录音
                    if (preIsRecording) {
                        // 结束录音，不需要识别男女
                        Log.e("controller", "录音结束，不需要识别男女")
                        Log.e("controller", "总结会议")

                        if (outVector[0] == 1) {
                            // 只有语音转文本在边测，才有会议总结
                            eventBus.publish(EventBus.Event.LOADING, "等待会议总结")
                            _soundResultFlow.emit(AudioMessage("", -1, FloatArray(0), -1, Action.GENERATE_SUMMARY))
                        } else {
                            // 展示空的会议总结，仅提示用户
                            eventBus.publish(EventBus.Event.SUMMARY, "抱歉！在该卸载策略下不支持会议总结功能")
                        }

                    } else {
                        if (isGenderEnabled()) {
                            // 开始录音，识别男女
                            val gender: String = unloadController.genderRecognition(genderDetector, eventBus, request, frame)
                            paramGender = gender
                            val sendGender = gender.replaceFirstChar { it.uppercaseChar() }
                            request.testGender(id, sendGender)
                            Log.e("controller", "开始录音，识别男女")
                            Log.e("result", "识别男女结果$gender")
                        } else {
                            Log.e("controller", "策略为B6/B7，跳过性别识别（开始录音）")
                        }
                    }
                }
            }

        }
    }


    /**
     * 获取设备ID
     */
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }



    /**
     * websocket逻辑，为了方便集成到AppController
     */
    private suspend fun initTranslateWebSocket() {
        try {
            val port = 8765 // 8765
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                translateWebSocketSession = this
                Log.e("websocket", "已连接到翻译 WebSocket 服务器 (8765)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage(id, "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")

                coroutineScope {
                    launch { sendTranslateData(this@webSocket) }
                    launch { receiveTranslateData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }

    private suspend fun initSoundToTextWebSocket() {
        try {
            val port = 10003 // 7
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                soundToTextWebSocketSession = this
                Log.e("websocket", "已连接到语音转文本 WebSocket 服务器 (10003)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage(id, "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")
                // 10003 只需要发不需要收
                coroutineScope {
                    launch { sendSoundToTextData(this@webSocket) }
                    launch { receiveSoundData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }

    private suspend fun initStreamingMediaWebSocket() {
        try {
            val port = 10009
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {

                streamingMediaWebSocketSession = this

                Log.e("websocket", "已连接到流媒体服务器 (10009)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage(id, "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")

                coroutineScope {
                    launch { sendStreamingMediaData(this@webSocket) }
                    launch { receiveStreamingMediaData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }

    private suspend fun initSummaryWebSocket() {
        try {
            val port = 8764
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                summaryWebSocketSession = this
                Log.e("websocket", "已连接到Summary WebSocket 服务器 (8764)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage(id, "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")

                // 8764只收不发
                coroutineScope {
                    launch { }
                    launch { receiveSummaryData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }

    private suspend fun initVisualPromptWebSocket() {
        try {
            val port = 8767
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                visualPromptWebSocketSession = this
                Log.e("websocket", "已连接到Visual Prompt WebSocket 服务器 (8767)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage(id, "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")
                // 8767只收不发
                coroutineScope {
                    launch { }
                    launch { receiveVisualPromptData(this@webSocket) }
                }
            }

        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }

    // 关闭连接
    private suspend fun closeTranslateWebSocket() {
        try {
            translateWebSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, "正常关闭"))
        } catch (e: Exception) {
            Log.e("websocket", "关闭连接时出现异常: ${e.message}")
        }
    }

    private suspend fun closeSoundToTextWebSocket() {
        try {
            soundToTextWebSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, "正常关闭"))
        } catch (e: Exception) {
            Log.e("websocket", "关闭连接时出现异常: ${e.message}")
        }
    }

    private suspend fun closeStreamingMediaWebSocket() {
        try {
            streamingMediaWebSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, "正常关闭"))
        } catch (e: Exception) {
            Log.e("websocket", "关闭连接时出现异常: ${e.message}")
        }
    }

    private suspend fun closeSummaryWebSocket() {
        try {
            summaryWebSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, "正常关闭"))
        } catch (e: Exception) {
            Log.e("websocket", "关闭连接时出现异常: ${e.message}")
        }
    }

    private suspend fun closeVisualPromptWebSocket() {
        try {
            visualPromptWebSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, "正常关闭"))
        } catch (e: Exception) {
            Log.e("websocket", "关闭连接时出现异常: ${e.message}")
        }
    }

    // 检查连接状态
    private fun isWebSocketConnected(websocket: DefaultClientWebSocketSession): Boolean {
        return websocket.isActive
    }


    // 翻译数据生成
    private fun generateTranslateData(textResult: String, msgId: Int): Flow<TextMessage> = flow {
        emit(TextMessage(msgId, textResult, id=id, tts = currentTtsFlag()))
    }
    // 发送翻译数据
    private suspend fun sendTranslateData(session: DefaultClientWebSocketSession) {
        try {
            textResultFlow.collect { textResult ->
                generateTranslateData(textResult, messageId).collect { data ->
                    val json = Json.encodeToString(data)
                    Log.e("websocket", "发送中: $json")
                    session.send(json)
                    translatedText.id = messageId
                    messageId++
                    flowStartTime1 = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "发送翻译数据时出错: ${e.message}")
        }
    }
    // 接收翻译数据
    private suspend fun receiveTranslateData(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {

                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<TextMessage>(response)
                        Log.e("websocket", "8765收到: msg_id=${data.msg_id}, content=${data.content}, time=${data.process_time}")

                        if (data.content == "<|EN|>") {
                            isStart = true
                            translatedText.language = "en"
                            eventBus.publish(EventBus.Event.TEXT_TO_SOUND, translatedText)

                        } else if (data.content == "<|CN|>") {
                            isStart = true
                            translatedText.language = "zh"
                            eventBus.publish(EventBus.Event.TEXT_TO_SOUND, translatedText)
                        } else  {
                            // 不是结束符
                            if (isStart) {
                                // 首token，清空屏幕，也是计时的标志
                                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, "<|STX|>")
                                isStart = false
                                if (outVector[0] == 0) { // 边测翻译
                                    val firstToken = System.currentTimeMillis() - flowStartTime1
                                    if (data.process_time != null) {
                                        Log.e("latency", "路径2 首token时延: ${sttProcessingTime + data.process_time + rtt} ms")
                                        eventBus.publish(EventBus.Event.FIRST_TOKEN, "${sttProcessingTime + data.process_time + rtt}")
                                    }
//                                    Log.e("latency", "路径2 首token时延: ${sttProcessingTime + firstToken} ms")
//                                    eventBus.publish(EventBus.Event.FIRST_TOKEN, "${sttProcessingTime + firstToken}")

                                } else if (outVector[0] == 1) { // 端测翻译
                                    var firstToken: Int = 0
//                                    val firstToken = System.currentTimeMillis() - flowStartTime3
                                    if (data.process_time != null)
                                        firstToken = (rtt + firstTokenPartOne + data.process_time).toInt()
                                    Log.e("latency", "路径3 首token时延: $firstToken ms")
                                    eventBus.publish(EventBus.Event.FIRST_TOKEN, "$firstToken")
                                }
                            }

                            translatedText.text += data.content
                        }
                        eventBus.publish(EventBus.Event.TRANSLATION_RESULT, data.content)
                        eventBus.publish(EventBus.Event.VISUAL_PROMPT_REFRESH, data.content)
                    } catch (e: Exception) {
                        Log.e("exception", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "接收数据时出错: ${e.message}")
        }
    }

    // 语音数据生成
    private fun generateSoundData(id: String, msgId: Int, samples: FloatArray, sampleRate: Int, action: Action?): Flow<AudioMessage> = flow {
        emit(AudioMessage(id, msgId, samples, sampleRate, action, tts=currentTtsFlag()))
    }
    // 发送语音数据 or Whisper数据
    private suspend fun sendSoundToTextData(session: DefaultClientWebSocketSession) {
        try {
            // emit之后转到以下代码
            soundResultFlow.collect { audioMessage ->

                val soundResult = audioMessage.samples
                val action = audioMessage.action
                generateSoundData(id, messageId, soundResult, 16000, action).collect { data ->

                    if (soundResult.isEmpty()) {
                        data.msg_id = -1
                    }
                    val json = Json.encodeToString(data)
                    Log.e("websocket", "发送中: $json")
                    session.send(json)
                    translatedText.id = messageId
                    if (soundResult.isNotEmpty()) {
                        messageId++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "发送语音数据时出错: ${e.message}")
        }
    }
    // 接收语音转文本结果
    private suspend fun receiveSoundData(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<AudioResponseMessage>(response)

                        // 路径3）
                        if (data.type == null) {
                            // 常规消息
                            Log.e("websocket", "10003收到: msg_id=${data.msg_id}, content=${data.content}, speak=${data.speaker}, process_time=${data.process_time}")
                            // 10003返回的是原文
                            if (data.content == "<|ZH|>" || data.content == "<|EN|>") {
                                val receivedOriginalText = System.currentTimeMillis() - flowStartTime3
                                if (data.process_time != null)
                                    firstTokenPartOne = data.process_time * 1000
                                Log.e("latency", "路径3 接收到原文时延: $receivedOriginalText ms")

                            } else {
                                // TODO: 测试用，删除说话人检测
//                                    eventBus.publish(EventBus.Event.SPEECH_RESULT, Message(data.content as String, (data.speaker==0)))
                                eventBus.publish(EventBus.Event.SPEECH_RESULT, Message(data.content as String, false))
                            }

//                                eventBus.publish(EventBus.Event.SPEECH_RESULT, data.content as String)
                        } else {
                            Log.e("websocket", "10003收到: type=${data.type}, data=${data.message}")
                            // 特殊消息
                            if (data.type == "host_changed") {
                                // 切换机主
                                eventBus.publish(EventBus.Event.IDENTIFICATION_COMPLETED, "identification completed")

                            }
                        }

                    } catch (e: Exception) {
                        Log.e("exception", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "接收数据时出错: ${e.message}")
        }
    }


    // 流媒体文本生成
    private fun generateStreamingMediaData(input: String): Flow<StreamingMessage> = flow {
        emit(StreamingMessage(messageId, input, 0.0, id))
    }
    // 发送文本转语音数据（流媒体）
    private suspend fun sendStreamingMediaData(session: DefaultClientWebSocketSession) {
        try {
            streamResultFlow.collect { textResult ->
                val language = translatedText.language
                generateStreamingMediaData(textResult).collect { data ->
                    val json = Json.encodeToString(data)
                    Log.e("websocket", "发送中: $json")
                    session.send(json)
                }
//                var sendEndPoint = ""
//                if (language == "zh") {
//                    sendEndPoint = "<|ZH|>"
//                } else {
//                    sendEndPoint = "<|EN|>"
//                }
//                generateStreamingMediaData(sendEndPoint).collect { data ->
//                    val json = Json.encodeToString(data)
//                    Log.e("websocket", "发送中: $json")
//                    session.send(json)
//                }
            }
        } catch (e: Exception) {
            Log.e("exception", "发送流媒体数据时出错: ${e.message}")
        }
    }
    private suspend fun receiveStreamingMediaData(session: DefaultClientWebSocketSession) {
        // 配置 AudioTrack
        val sampleRate = 24000 // 假设采样率为 24000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO // 单声道
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16 位 PCM
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        // 请求音频焦点
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.requestAudioFocus(
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
        )

        try {
            audioTrack.play()
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<StreamingResponseMessage>(response)

                        val wav = data.result
                        Log.e("websocket", "10009收到: msg_id=${data.msg_id}, content=${wav}")
                        // 将 FloatArray 转换为 ShortArray
                        val pcmData = wav.map { (it * Short.MAX_VALUE).toInt().toShort() }.toShortArray()

                        // 写入 AudioTrack 进行播放
                        audioTrack.write(pcmData, 0, pcmData.size)
                    } catch (e: Exception) {
                        Log.e("exception", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "接收数据时出错: ${e.message}")
        } finally {
            // 释放 AudioTrack 资源
            audioTrack.stop()
            audioTrack.release()
        }
    }


    // 接受总结消息
    private suspend fun receiveSummaryData(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {

                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<SummaryMessage>(response)
                        val summaryMessage = decodeUnicode(data.content)
                        Log.e("websocket", "8764收到(原文): content=${data.content}")
                        Log.e("websocket", "8764收到(解码): content=${summaryMessage}")
                        eventBus.publish(EventBus.Event.SUMMARY, data.content)
                    } catch (e: Exception) {
                        Log.e("exception", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "接收数据时出错: ${e.message}")
        }
    }

    // 接收视觉提示消息
    private suspend fun receiveVisualPromptData(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {

                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<VisualPromptMessage>(response)
                        Log.e("websocket", "8767收到: keyword=${data.keyword}")
                        Log.e("websocket", "8767收到: text=${data.text}")
                        Log.e("websocket", "8767收到: vis=${data.vis}")
                        eventBus.publish(EventBus.Event.VISUAL_PROMPT, data)

                    } catch (e: Exception) {
                        Log.e("exception", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "接收数据时出错: ${e.message}")
        }
    }


    fun setRecognitionCallback(callback: (Message) -> Unit) {
        recognitionCallback = callback
    }

    fun setTranslationCallback(callback: (String) -> Unit) {
        translationCallback = callback
    }

    fun setGestureCallback(callback: (GestureDetector.GestureType) -> Unit) {
        gestureCallback = callback
    }

    fun setGenderCallback(callback: (Gender) -> Unit) {
        genderCallback = callback
    }

    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }

    fun setUsageCallback(callback: (String) -> Unit) {
        usageCallback = callback
    }

    fun setBandWidthCallback(callback: (String) -> Unit) {
        bandWidthCallback = callback
    }
    fun setRttCallback(callback: (String) -> Unit) {
        rttCallback = callback
    }
    fun setBatteryCallback(callback: (String) -> Unit) {
        batteryCallback = callback
    }
    fun setAlgorithmCallback(callback: (String) -> Unit) {
        algorithmCallback = callback
    }
    fun setFirstTokenCallback(callback: (String) -> Unit) {
        firstTokenCallback = callback
    }
    fun setPowerCallback(callback: (String) -> Unit) {
        powerCallback = callback
    }
    fun setTemperatureCallback(callback: (String) -> Unit) {
        temperatureCallback = callback
    }
    fun setProgressBar(callback: (Float) -> Unit) {
        progressBarCallback = callback
    }
    fun setStartRecordingCallback(callback: (String) -> Unit) {
        startRecordingCallback = callback
    }
    fun setIdentificationCompletedCallback(callback: (String) -> Unit) {
        identificationCompletedCallback = callback
    }
    fun setSummaryCallback(callback: (String) -> Unit) {
        summaryCallback = callback
    }
    fun setSummaryCloseCallback(callback: (String) -> Unit) {
        summaryCloseCallback = callback
    }
    fun setLoadingCallback(callback: (String) -> Unit) {
        loadingCallback = callback
    }
    fun setVisualPromptCallback(callback: (VisualPromptMessage) -> Unit) {
        visualPromptCallback = callback
    }
    fun setVisualPromptRefreshCallback(callback: (String) -> Unit) {
        visualPromptRefreshCallback = callback
    }

    companion object {
        @Volatile
        private var instance: AppController? = null

        fun getInstance(): AppController {
            return instance ?: synchronized(this) {
                instance ?: AppController().also { instance = it }
            }
        }
    }
}