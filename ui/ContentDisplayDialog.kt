package com.k2fsa.sherpa.ncnn.ui
import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.k2fsa.sherpa.ncnn.R

class ContentDisplayDialog(
    context: Context,
    private var keyword: String,
    private var text: String,
    private var content: String,
    private var compressedBase64: String,
    private val isLeftDialog: Boolean = true,
    private val autoCloseDelayMs: Long = 10000L // 默认10秒后自动关闭
) : Dialog(context) {

    private lateinit var tvKeyword: TextView
    private lateinit var ivContentImage: ImageView
    private lateinit var tvContentText: TextView
    private lateinit var tvTranslationContent: TextView

    private val autoCloseHandler = Handler(Looper.getMainLooper())
    private val autoCloseRunnable = Runnable {
        if (isShowing) {
            dismiss()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置无标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // 加载XML布局
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_content_display, null)
        setContentView(view)

        // 初始化视图
        initViews(view)

        // 设置数据
        setupData()

        // 设置事件监听
        setupListeners()

        // 设置弹窗属性
        setupDialogProperties()

        // 启动自动关闭定时器
        startAutoCloseTimer()
    }

    private fun startAutoCloseTimer() {
        autoCloseHandler.postDelayed(autoCloseRunnable, autoCloseDelayMs)
    }

    private fun cancelAutoCloseTimer() {
        autoCloseHandler.removeCallbacks(autoCloseRunnable)
    }

    override fun dismiss() {
        cancelAutoCloseTimer()
        super.dismiss()
    }

    /**
     * 更新弹窗内容
     * @param newKeyword 新的关键词
     * @param newText 新的文本内容
     * @param newContent 新的翻译内容
     * @param newCompressedBase64 新的图片base64字符串
     * @param updateTimer 是否重置定时器
     */
    fun updateContent(
        newKeyword: String,
        newText: String,
        newContent: String,
        newCompressedBase64: String,
        updateTimer: Boolean = true
    ) {
        try {
            // 更新数据
            keyword = newKeyword
            text = newText
            content = newContent
            compressedBase64 = newCompressedBase64

            // 更新UI
            tvKeyword.text = keyword
            tvContentText.text = text
            tvTranslationContent.text = content

            // 更新图片
            updateImage()

            // 根据updateTimer参数决定是否重置定时器
            if (updateTimer) {
                restartAutoCloseTimer()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 重新启动自动关闭定时器
     */
    private fun restartAutoCloseTimer() {
        cancelAutoCloseTimer()
        startAutoCloseTimer()
    }

    /**
     * 更新图片
     */
    private fun updateImage() {
        try {
            if (compressedBase64.isNotEmpty()) {
                val imageBytes = Base64.decode(compressedBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ivContentImage.setImageBitmap(bitmap)
            } else {
                // 设置占位符
                ivContentImage.setImageResource(android.R.drawable.ic_menu_gallery)
                ivContentImage.setBackgroundColor(Color.parseColor("#F5F5F5"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 解析失败时设置占位符
            ivContentImage.setImageResource(android.R.drawable.ic_menu_gallery)
            ivContentImage.setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
    }

    private fun initViews(view: View) {
        tvKeyword = view.findViewById(R.id.tv_keyword)
        ivContentImage = view.findViewById(R.id.iv_content_image)
        tvContentText = view.findViewById(R.id.tv_content_text)
        tvTranslationContent = view.findViewById(R.id.tv_translation_content)
    }

    private fun setupData() {
        // 设置关键词
        tvKeyword.text = keyword

        // 设置文本内容
        tvContentText.text = text

        // 设置翻译内容（使用传入的content参数）
        tvTranslationContent.text = content

        // 解析并设置图片
        setupImage()
    }

    private fun setupImage() {
        try {
            if (compressedBase64.isNotEmpty()) {
                val imageBytes = Base64.decode(compressedBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ivContentImage.setImageBitmap(bitmap)
            } else {
                // 设置占位符
                ivContentImage.setImageResource(android.R.drawable.ic_menu_gallery)
                ivContentImage.setBackgroundColor(Color.parseColor("#F5F5F5"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 解析失败时设置占位符
            ivContentImage.setImageResource(android.R.drawable.ic_menu_gallery)
            ivContentImage.setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
    }

    private fun setupListeners() {
        // 移除了按钮监听，现在只能通过点击外部或返回键关闭
    }

    private fun setupDialogProperties() {
        window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val params = window.attributes

            // 设置弹窗宽度为屏幕宽度的40%
            val screenWidth = context.resources.displayMetrics.widthPixels
            params.width = (screenWidth * 0.4).toInt()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT

            // 根据是否为左侧弹窗设置位置
            params.gravity = Gravity.CENTER_VERTICAL or Gravity.START

            // 设置水平位置
            if (isLeftDialog) {
                params.x = 50 // 左侧弹窗距离左边50px
            } else {
                params.x = screenWidth - params.width - 50 // 右侧弹窗距离右边50px
            }

            // 设置窗口类型和标志，确保两个弹窗都保持亮度
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )

            // 设置相同的层级
            params.windowAnimations = android.R.style.Animation_Dialog

            window.attributes = params
        }

        // 设置可取消
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }
}