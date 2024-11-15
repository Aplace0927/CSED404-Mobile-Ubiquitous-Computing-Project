package com.csed433project.hapticfitness.ui.back_stretch

import android.content.Context
import android.os.Bundle
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.csed433project.hapticfitness.databinding.BackstretchBinding

class BackStretchFragment : Fragment() {

    private var _binding: BackstretchBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val backStretchViewModel =
            ViewModelProvider(this).get(BackStretchViewModel::class.java)

        _binding = BackstretchBinding.inflate(inflater, container, false)

        val root: View = binding.root
        var counter: Int = 0

        // Will be used for more stronger haptic feedbacks or custom-defined haptic feedbacks.
        // val vibrator = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val progressBar: ProgressBar = binding.progressBar

        progressBar.setOnTouchListener { _ : View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    counter = 0
                    progressBar.setProgress(counter, true)
                    view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS) ?: false
                }
                MotionEvent.ACTION_UP -> {
                    view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) ?: false
                }
                MotionEvent.ACTION_MOVE -> {
                    counter += 1
                    counter %= 360
                    progressBar.setProgress(counter, true)
                    view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) ?: false
                }
                else -> {
                    view?.performHapticFeedback(HapticFeedbackConstants.NO_HAPTICS) ?: false
                }
            }
        }


        backStretchViewModel.text.observe(viewLifecycleOwner) {
            progressBar.setProgress(counter, true)
        }
        //backStretchViewModel.pro.observe(viewLifecycleOwner) {
        //    textView.text = it
        //}
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}