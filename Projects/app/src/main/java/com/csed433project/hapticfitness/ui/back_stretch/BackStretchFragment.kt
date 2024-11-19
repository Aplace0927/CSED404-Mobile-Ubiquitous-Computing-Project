package com.csed433project.hapticfitness.ui.back_stretch

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

import androidx.core.content.getSystemService
import android.content.Context

import android.os.Bundle
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.csed433project.hapticfitness.databinding.BackstretchBinding
import kotlin.math.abs
import kotlin.math.roundToInt

class BackStretchFragment : Fragment() {

    private var _binding: BackstretchBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var vibrationThread: VibrationThread
    private lateinit var vibratorManager: VibratorManager

    private lateinit var sensorManager: SensorManager
    private lateinit var rotVctSensor: Sensor
    private lateinit var rotVctSensorEventListener: SensorEventListener
    private lateinit var sensorHandlerThread: HandlerThread
    private lateinit var sensorWorker: Handler
    private val rotMat: FloatArray = floatArrayOf(
        1.0F, 0.0F, 0.0F, 0.0F,
        0.0F, 1.0F, 0.0F, 0.0F,
        0.0F, 0.0F, 1.0F, 0.0F,
        0.0F, 0.0F, 0.0F, 1.0F,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val backStretchViewModel =
            ViewModelProvider(this).get(BackStretchViewModel::class.java)

        _binding = BackstretchBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val orientationAngles = FloatArray(3)
        val nullAngles = floatArrayOf(0.0F, 0.0F, 0.0F)

        sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotVctSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) as Sensor

        sensorHandlerThread = HandlerThread("Gyroscope Event Handler")
        sensorHandlerThread.start()
        sensorWorker = Handler(sensorHandlerThread.looper)

        vibrationThread = VibrationThread()

        rotVctSensorEventListener = object: SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    SensorManager.getOrientation(rotMat, orientationAngles)
                    binding.valueText.text = "%03.3f, %03.3f, %03.3f".format(Math.toDegrees(orientationAngles[0].toDouble()), Math.toDegrees(orientationAngles[1].toDouble()), Math.toDegrees(orientationAngles[2].toDouble()))
                    if (Math.toDegrees(orientationAngles[2].toDouble()).roundToInt() >= 0) {
                        binding.progressBar.scaleY = -1.0F
                        binding.progressBar.setProgress(Math.toDegrees(orientationAngles[2].toDouble()).roundToInt(), true)
                    } else {
                        binding.progressBar.scaleY = 1.0F
                        binding.progressBar.setProgress(-Math.toDegrees(orientationAngles[2].toDouble()).roundToInt(), true)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {


            }
        }

        sensorManager.registerListener(rotVctSensorEventListener, rotVctSensor, 100000, sensorWorker)

        // Log.d("Sensor", "Orientation <" + orientationAngles[0].toString() + " , " + orientationAngles[1].toString() + " , " + orientationAngles[2].toString() + ">")
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

    inner class VibrationThread: Thread() {
        var vibInterval: Long = 0
        var vibPattern: CombinedVibration

        init {
            vibInterval = 200L // 300ms
            vibPattern = CombinedVibration.createParallel(
                VibrationEffect.createWaveform(
                    longArrayOf(0),  // Timings
                    intArrayOf(0),   // Amplitude
                    -1              // Repeat
                )
            )
        }
        override fun run() {
            vibratorManager = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            sleep(vibInterval)
            vibratorManager.vibrate(vibPattern)
        }
        fun setPatternFromEffect (effect: VibrationEffect): Unit { vibPattern = CombinedVibration.createParallel(effect) }
        fun setInterval (interval: Long): Unit { vibInterval = interval }
    }
}