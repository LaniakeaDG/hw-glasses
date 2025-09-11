package com.k2fsa.sherpa.ncnn.ui
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.k2fsa.sherpa.ncnn.R
import io.noties.markwon.Markwon

class MeetingSummaryDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_SUMMARY = "summary"

        fun newInstance(summary: String): MeetingSummaryDialogFragment {
            val fragment = MeetingSummaryDialogFragment()
            val args = Bundle().apply {
                putString(ARG_SUMMARY, summary)
            }
            fragment.arguments = args
            return fragment
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_meeting_summary, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 设置总结内容
//        val summary = arguments?.getString(ARG_SUMMARY) ?: ""
//        view.findViewById<TextView>(R.id.textViewSummary).text = summary

        // 初始化 Markwon
        val markwon = Markwon.create(requireContext())

        // 设置总结内容（Markdown 格式）
        val summary = arguments?.getString(ARG_SUMMARY) ?: ""
        val textViewSummaryLeft = view.findViewById<TextView>(R.id.textViewSummary_left)
        val textViewSummaryRight = view.findViewById<TextView>(R.id.textViewSummary_right)

        markwon.setMarkdown(textViewSummaryLeft, summary)
        markwon.setMarkdown(textViewSummaryRight, summary)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
}