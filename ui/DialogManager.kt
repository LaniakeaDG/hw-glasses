package com.k2fsa.sherpa.ncnn.ui
import android.content.Context

class DialogManager {

    companion object {
        private var leftDialog: ContentDisplayDialog? = null
        private var rightDialog: ContentDisplayDialog? = null

        /**
         * 显示左右两个内容弹窗
         * @param context 上下文
         * @param leftData 左侧弹窗数据
         * @param rightData 右侧弹窗数据
         * @param autoCloseDelayMs 自动关闭延迟时间（毫秒），默认10秒
         * @param updateTimer 当弹窗已存在时，是否更新定时器。true=重置定时器，false=只更新内容不重置定时器
         */
        fun showContentDialogs(
            context: Context,
            leftData: Map<String, Any>,
            rightData: Map<String, Any>,
            autoCloseDelayMs: Long = 10000L,
            updateTimer: Boolean = true
        ) {
            try {
                // 提取左侧数据
                val leftKeyword = leftData["keyword"]?.toString() ?: "未知关键词"
                val leftText = leftData["text"]?.toString() ?: "暂无描述内容"
                val leftContent = leftData["content"]?.toString() ?: "翻译内容"
                val leftVis = leftData["vis"] as? Map<String, Any>
                val leftCompressed = leftVis?.get("compressed")?.toString() ?: ""

                // 提取右侧数据
                val rightKeyword = rightData["keyword"]?.toString() ?: "未知关键词"
                val rightText = rightData["text"]?.toString() ?: "暂无描述内容"
                val rightContent = rightData["content"]?.toString() ?: "翻译内容"
                val rightVis = rightData["vis"] as? Map<String, Any>
                val rightCompressed = rightVis?.get("compressed")?.toString() ?: ""

                // 检查是否已经有弹窗显示
                if (isAnyDialogShowing()) {
                    // 更新现有弹窗的内容
                    leftDialog?.updateContent(leftKeyword, leftText, leftContent, leftCompressed, updateTimer)
                    rightDialog?.updateContent(rightKeyword, rightText, rightContent, rightCompressed, updateTimer)
                } else {
                    // 创建新的弹窗实例
                    leftDialog = ContentDisplayDialog(context, leftKeyword, leftText, leftContent, leftCompressed,
                        isLeftDialog = true, autoCloseDelayMs = autoCloseDelayMs)
                    rightDialog = ContentDisplayDialog(context, rightKeyword, rightText, rightContent, rightCompressed,
                        isLeftDialog = false, autoCloseDelayMs = autoCloseDelayMs)

                    // 几乎同时显示两个弹窗，避免层级问题
                    leftDialog?.show()
                    // 使用Handler确保在下一个UI周期显示右侧弹窗
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        rightDialog?.show()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 显示左右两个内容弹窗（直接传参版本）
         * @param autoCloseDelayMs 自动关闭延迟时间（毫秒），默认10秒
         * @param updateTimer 当弹窗已存在时，是否更新定时器。true=重置定时器，false=只更新内容不重置定时器
         */
        fun showContentDialogs(
            context: Context,
            leftKeyword: String, leftText: String, leftContent: String, leftCompressed: String,
            rightKeyword: String, rightText: String, rightContent: String, rightCompressed: String,
            autoCloseDelayMs: Long = 10000L,
            updateTimer: Boolean = true
        ) {
            try {
                // 检查是否已经有弹窗显示
                if (isAnyDialogShowing()) {
                    // 更新现有弹窗的内容
                    leftDialog?.updateContent(leftKeyword, leftText, leftContent, leftCompressed, updateTimer)
                    rightDialog?.updateContent(rightKeyword, rightText, rightContent, rightCompressed, updateTimer)
                } else {
                    // 创建新的弹窗实例
                    leftDialog = ContentDisplayDialog(context, leftKeyword, leftText, leftContent, leftCompressed,
                        isLeftDialog = true, autoCloseDelayMs = autoCloseDelayMs)
                    rightDialog = ContentDisplayDialog(context, rightKeyword, rightText, rightContent, rightCompressed,
                        isLeftDialog = false, autoCloseDelayMs = autoCloseDelayMs)

                    // 几乎同时显示两个弹窗，避免层级问题
                    leftDialog?.show()
                    // 使用Handler确保在下一个UI周期显示右侧弹窗
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        rightDialog?.show()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 关闭所有弹窗
         */
        fun dismissAllDialogs() {
            leftDialog?.let {
                if (it.isShowing) {
                    it.dismiss()
                }
            }
            leftDialog = null

            rightDialog?.let {
                if (it.isShowing) {
                    it.dismiss()
                }
            }
            rightDialog = null
        }

        /**
         * 关闭左侧弹窗
         */
        fun dismissLeftDialog() {
            leftDialog?.let {
                if (it.isShowing) {
                    it.dismiss()
                }
            }
            leftDialog = null
        }

        /**
         * 关闭右侧弹窗
         */
        fun dismissRightDialog() {
            rightDialog?.let {
                if (it.isShowing) {
                    it.dismiss()
                }
            }
            rightDialog = null
        }

        /**
         * 检查是否有弹窗正在显示
         */
        fun isAnyDialogShowing(): Boolean {
            return (leftDialog?.isShowing == true) || (rightDialog?.isShowing == true)
        }

        /**
         * 检查左侧弹窗是否正在显示
         */
        fun isLeftDialogShowing(): Boolean {
            return leftDialog?.isShowing == true
        }

        /**
         * 检查右侧弹窗是否正在显示
         */
        fun isRightDialogShowing(): Boolean {
            return rightDialog?.isShowing == true
        }
    }
}