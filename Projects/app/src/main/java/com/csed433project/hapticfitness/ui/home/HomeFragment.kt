package com.csed433project.hapticfitness.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.csed433project.hapticfitness.R
import com.csed433project.hapticfitness.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val navController = findNavController()

        binding.backStretchButton.setOnClickListener { navController.navigate(R.id.action_nav_home_to_nav_back_stretch) }
        binding.sideStretchButton.setOnClickListener { navController.navigate(R.id.action_nav_home_to_nav_side_stretch) }
        binding.squatButton.setOnClickListener { navController.navigate(R.id.action_nav_home_to_nav_squat) }
        binding.rhythmFitButton.setOnClickListener { navController.navigate(R.id.action_nav_home_to_nav_rhythm_fitness) }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}