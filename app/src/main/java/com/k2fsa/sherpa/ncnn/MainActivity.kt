package com.k2fsa.sherpa.ncnn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.k2fsa.sherpa.ncnn.control.AppController
import com.k2fsa.sherpa.ncnn.gesture.GestureDetector
import com.k2fsa.sherpa.ncnn.request.Request
import com.k2fsa.sherpa.ncnn.request.Vis
import com.k2fsa.sherpa.ncnn.request.VisualPromptMessage
import com.k2fsa.sherpa.ncnn.test.ExtremeBandwidthTester
import com.k2fsa.sherpa.ncnn.ui.DialogManager
import com.k2fsa.sherpa.ncnn.ui.LoadingDialogFragment
import com.k2fsa.sherpa.ncnn.ui.MeetingSummaryDialogFragment
import com.k2fsa.sherpa.ncnn.ui.Message
import com.k2fsa.sherpa.ncnn.ui.MessageAdapter
import com.k2fsa.sherpa.ncnn.ui.RecognitionCompleteDialogFragment
import com.k2fsa.sherpa.ncnn.ui.SpeakerRecognitionDialogFragment
import com.k2fsa.sherpa.ncnn.utils.Logger


class MainActivity : AppCompatActivity() {
    private val logger = Logger(this::class.java.simpleName)
    private lateinit var appController: AppController

    // 网络能耗测试
    private lateinit var extremeTester: ExtremeBandwidthTester

    // 左半边
    private lateinit var strategyLeft: TextView
    private lateinit var latencyLeft: TextView
    private lateinit var powerLeft: TextView
    private lateinit var hintLeft: TextView
    private lateinit var originLeft: TextView
    private lateinit var translateLeft: TextView


    // 右半边
    private lateinit var strategyRight: TextView
    private lateinit var latencyRight: TextView
    private lateinit var powerRight: TextView
    private lateinit var hintRight: TextView
    private lateinit var originRight: TextView
    private lateinit var translateRight: TextView


    private var isRecording = false
    // 视觉提示控制
    private var visualPromptMessage: VisualPromptMessage = VisualPromptMessage("", "", Vis("", ""))
    private var content: String = ""

    // 现代权限请求方式
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeViews()
        checkAndRequestPermissions()
        initializeController()
        setupListeners()
        supportActionBar?.hide() // 隐藏整个 ActionBar，包括标题



        // 网络能耗测试
        extremeTester = ExtremeBandwidthTester()

        /*
        // 开始最大速度测试
        val urls = listOf("http://10.129.47.131:5201")
//        val urls = listOf("http://192.168.2.2:5201")
        extremeTester.unleashMaximumSpeed(urls) { bytesSent ->
            Log.e("test", "$bytesSent")
        }
        */
    }


    /**
     * 显示内容弹窗的方法
     */
    private fun showContentDialog(visualPromptMessage: VisualPromptMessage, updateTimer: Boolean) {
        val data = mapOf(
            "keyword" to visualPromptMessage.keyword,
            "content" to content,
            "text" to visualPromptMessage.text,
            "vis" to mapOf(
                "url" to visualPromptMessage.vis.url,
                "compressed" to visualPromptMessage.vis.compressed
            )
        )
        DialogManager.showContentDialogs(this, data, data, updateTimer=updateTimer)
    }
    /**
     * 关闭内容弹窗的方法
     */
    private fun closeContentDialog() {
        DialogManager.dismissAllDialogs()
    }

    // 显示总结框
    private fun showMeetingSummary(summary: String) {
        val dialog = MeetingSummaryDialogFragment.newInstance(summary)
        dialog.show(supportFragmentManager, "MeetingSummaryDialog")
    }
    // 关闭总结框
    private fun closeMeetingSummary() {
        val dialog = supportFragmentManager.findFragmentByTag("MeetingSummaryDialog") as? MeetingSummaryDialogFragment
        dialog?.dismiss()
    }


    // 显示加载框
    private fun showLoadingDialog() {
        val dialogLeft = LoadingDialogFragment.newInstance("left")
        val dialogRight = LoadingDialogFragment.newInstance("right")
        dialogLeft.show(supportFragmentManager, "LoadingDialogLeft")
        dialogRight.show(supportFragmentManager, "LoadingDialogRight")
    }
    // 关闭加载框
    private fun dismissLoadingDialog() {
        val dialogLeft = supportFragmentManager.findFragmentByTag("LoadingDialogLeft") as? LoadingDialogFragment
        val dialogRight = supportFragmentManager.findFragmentByTag("LoadingDialogRight") as? LoadingDialogFragment
        dialogLeft?.dismiss()
        dialogRight?.dismiss()
    }


    private fun showSpeakerRecognitionDialog() {
        val dialogLeft = SpeakerRecognitionDialogFragment.newInstance("left")
        val dialogRight = SpeakerRecognitionDialogFragment.newInstance("right")
        dialogLeft.show(supportFragmentManager, "SpeakerRecognitionDialogLeft")
        dialogRight.show(supportFragmentManager, "SpeakerRecognitionDialogRight")
    }
    private fun dismissSpeakerRecognitionDialog() {
        val dialogLeft = supportFragmentManager.findFragmentByTag("SpeakerRecognitionDialogLeft") as? SpeakerRecognitionDialogFragment
        val dialogRight = supportFragmentManager.findFragmentByTag("SpeakerRecognitionDialogRight") as? SpeakerRecognitionDialogFragment
        dialogLeft?.dismiss()
        dialogRight?.dismiss()
    }

    private fun showRecognitionCompleteDialog() {
        val dialogLeft = RecognitionCompleteDialogFragment.newInstance("left")
        val dialogRight = RecognitionCompleteDialogFragment.newInstance("right")
        dialogLeft.show(supportFragmentManager, "RecognitionCompleteDialogLeft")
        dialogRight.show(supportFragmentManager, "RecognitionCompleteDialogRight")
    }

    private fun dismissRecognitionCompleteDialog() {
        val dialogLeft = supportFragmentManager.findFragmentByTag("RecognitionCompleteDialogLeft") as? RecognitionCompleteDialogFragment
        val dialogRight = supportFragmentManager.findFragmentByTag("RecognitionCompleteDialogRight") as? RecognitionCompleteDialogFragment
        dialogLeft?.dismiss()
        dialogRight?.dismiss()

    }



    private fun initializeViews() {

        // 左半边
        strategyLeft = findViewById<TextView>(R.id.strategy_left)
        latencyLeft = findViewById<TextView>(R.id.latency_left)
        powerLeft = findViewById<TextView>(R.id.power_left)
        hintLeft = findViewById<TextView>(R.id.dialog_hint_left)
        originLeft = findViewById<TextView>(R.id.origin_content_left);
        translateLeft = findViewById<TextView>(R.id.translate_content_left);

        // 右半边
        strategyRight = findViewById<TextView>(R.id.strategy_right)
        latencyRight = findViewById<TextView>(R.id.latency_right)
        powerRight = findViewById<TextView>(R.id.power_right)
        hintRight = findViewById<TextView>(R.id.dialog_hint_right)
        originRight = findViewById<TextView>(R.id.origin_content_right);
        translateRight = findViewById<TextView>(R.id.translate_content_right);
    }

    private fun checkAndRequestPermissions() {
        if (!hasRequiredPermissions()) {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission( this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Some permissions are required for full functionality",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun initializeController() {
        appController = AppController.getInstance()

        appController.initialize(this)

        appController.setRecognitionCallback { result ->
            if (!result.isSentByMe) {
                originLeft.text = result.content
                originRight.text = result.content
            }
//            messages.add(Message(result.content, result.isSentByMe))
//            messageAdapter.notifyItemInserted(messages.size - 1)
//            recyclerViewMessages.scrollToPosition(messages.size - 1)
        }

        appController.setTranslationCallback { result ->
//            if (result != "<|STX|>") {
//
//                if (result == "<|EN|>" || result == "<|CN|>") {
//
//                    messages.add(Message(translatedText, false))
//                    messageAdapter.notifyItemInserted(messages.size - 1)
//                    recyclerViewMessages.scrollToPosition(messages.size - 1)
//
//                    translatedText = ""
//
//                } else {
//                    translatedText += result
//
//                }
//            }
            if (result == "<|STX|>") {
                translateLeft.text = ""
                translateRight.text = ""
                content = "translation content: "
            } else {
                if (result == "<|EN|>" || result == "<|CN|>") {

                } else {
                    translateLeft.text = "${translateLeft.text}${result}"
                    translateRight.text = "${translateRight.text}${result}"
                    content = "${content}${result}"
                }

            }

        }

        appController.setGestureCallback { gesture ->
            runOnUiThread {

                when (gesture) {
                    GestureDetector.GestureType.FIST -> {
                        if (isRecording) {
                            stopRecording()
                            logger.debug("Gesture detected: FIST, stopping recording.")
//                            isRecordTextLeft.text = "是否开始录音：否"
                        } else {
                            startRecording()
                            logger.debug("Gesture detected: FIST, starting recording.")
//                            isRecordTextLeft.text = "是否开始录音：是"
                        }
                    }
                    else -> logger.debug("Unhandled gesture: $gesture")
                }
            }
        }

        appController.setGenderCallback { gender ->
            runOnUiThread { }
        }

        appController.setUsageCallback { usage ->
            runOnUiThread {
                strategyLeft.text = "Usage Strategy: $usage"
                strategyRight.text = "Usage Strategy: $usage"
            }
        }

        appController.setStatusCallback { status ->
            runOnUiThread { }
        }

        appController.setLoadingCallback { msg ->
            showLoadingDialog()
        }

        /**
         * add
         */
//        appController.setBandWidthCallback { bandWidth ->
//            runOnUiThread {
//                bandwidthTextLeft.text = "带宽：$bandWidth Mbps"
//            }
//        }
//        appController.setRttCallback { rtt ->
//            runOnUiThread {
//                rttTextLeft.text = "RTT：$rtt ms"
//            }
//        }
//        appController.setBatteryCallback { battery ->
//            runOnUiThread {
//                batteryTextLeft.text = "电量：$battery %"
//            }
//        }
        appController.setAlgorithmCallback { algorithm ->
            runOnUiThread {
                strategyLeft.text = "Usage Strategy: $algorithm";
                strategyRight.text = "Usage Strategy: $algorithm";
            }
        }
        appController.setPowerCallback { power ->
            runOnUiThread {
                powerLeft.text = "Power Consumption: $power w"
                powerRight.text = "Power Consumption: $power w"
            }
        }
//        appController.setTemperatureCallback { temperature ->
//            runOnUiThread {
//                temperatureTextLeft.text = "CPU温度：$temperature °C"
//            }
//        }
        appController.setFirstTokenCallback { firstToken ->
            runOnUiThread {
                latencyLeft.text = "First Token Latency: $firstToken ms"
                latencyRight.text = "First Token Latency: $firstToken ms"
            }
        }

        appController.setStartRecordingCallback { mes ->
            showSpeakerRecognitionDialog()
        }
        appController.setIdentificationCompletedCallback { mes ->
            dismissSpeakerRecognitionDialog()
            showRecognitionCompleteDialog()
            Handler(Looper.getMainLooper()).postDelayed({
                dismissRecognitionCompleteDialog()
            }, 3000)
        }

        appController.setSummaryCallback { summaryMessage ->
            dismissLoadingDialog()
            showMeetingSummary(summaryMessage)
        }

        appController.setSummaryCloseCallback { mes ->
            closeMeetingSummary()
        }

        appController.setVisualPromptCallback { mes ->
            visualPromptMessage = mes
            showContentDialog(visualPromptMessage, true)
        }

        appController.setVisualPromptRefreshCallback { mes ->
            if (DialogManager.isAnyDialogShowing()) {
                showContentDialog(visualPromptMessage, false)
            }

        }

    }

    private fun setupListeners() {

    }

    private fun startRecording() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
            requestPermissionLauncher.launch(requiredPermissions)
            return
        }

        appController.startRecording()
        isRecording = true
        updateButtonState(isRecording = isRecording)
    }

    private fun stopRecording() {
        appController.stopRecording()
        isRecording = false
        updateButtonState(isRecording = isRecording)
    }

    private fun updateButtonState(isRecording: Boolean) {

    }


    override fun onResume() {
        super.onResume()
        appController.onResume()
    }

    override fun onPause() {
        appController.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        appController.onDestroy()
        super.onDestroy()
    }

}