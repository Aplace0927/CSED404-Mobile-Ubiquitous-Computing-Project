package com.csed433project.hapticfitness.ui.back_stretch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

        //val textView: TextView = binding.
        //backStretchViewModel.text.observe(viewLifecycleOwner) {
        //    textView.text = it
        //}
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}