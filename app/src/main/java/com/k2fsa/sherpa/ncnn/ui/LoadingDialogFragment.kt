package com.k2fsa.sherpa.ncnn.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.k2fsa.sherpa.ncnn.R
import com.k2fsa.sherpa.ncnn.ui.SpeakerRecognitionDialogFragment.Companion

class LoadingDialogFragment : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_loading, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val params = window.attributes
            val screenWidth = resources.displayMetrics.widthPixels

            // 设置宽度为屏幕的40%
            val dialogWidth = (screenWidth * 0.4).toInt()
            window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

            // 水平居中于左右半屏
            val position = arguments?.getString(ARG_POSITION) ?: "left"
            params.x = if (position == "left") {
                (screenWidth * -0.25).toInt()
            } else {
                (screenWidth * 0.25).toInt()
            }

            // 垂直居中
            params.y = 0 // 屏幕中心
            params.gravity = Gravity.CENTER // 确保垂直居中

            window.attributes = params

            // 禁用背景变暗，避免层级亮度差异
            window.setDimAmount(0f)
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }


    companion object {
        private const val ARG_POSITION = "position"
        fun newInstance(position: String): LoadingDialogFragment {
            return LoadingDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_POSITION, position) // "left" 或 "right"
                }
            }
        }
    }

}