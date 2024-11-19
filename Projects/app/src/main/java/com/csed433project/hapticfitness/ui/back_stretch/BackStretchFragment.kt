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
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class BackStretchFragment : Fragment() {

    private var _binding: BackstretchBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var vibrator: VibrationCaller
    private lateinit var vibrationThread: HandlerThread
    private lateinit var vibratorManager: VibratorManager
    private lateinit var vibrationWorker: Handler

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
        var angle: Int = 0

        sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotVctSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) as Sensor

        sensorHandlerThread = HandlerThread("Gyroscope Event Handler")
        sensorHandlerThread.start()
        sensorWorker = Handler(sensorHandlerThread.looper)

        vibrationThread = HandlerThread("Vibration Thread")
        vibrationThread.start()
        vibrationWorker = Handler(vibrationThread.looper)

        vibrator = VibrationCaller()

        rotVctSensorEventListener = object: SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    SensorManager.getOrientation(rotMat, orientationAngles)
                    binding.valueText.text = "%03.3f, %03.3f, %03.3f".format(Math.toDegrees(orientationAngles[0].toDouble()), Math.toDegrees(orientationAngles[1].toDouble()), Math.toDegrees(orientationAngles[2].toDouble()))
                    angle = Math.toDegrees(orientationAngles[2].toDouble()).roundToInt()

                    if (angle >= 0) {
                        binding.progressBar.scaleY = -1.0F
                        binding.progressBar.setProgress(Math.toDegrees(orientationAngles[2].toDouble()).roundToInt(), true)
                    } else {
                        binding.progressBar.scaleY = 1.0F
                        binding.progressBar.setProgress(-Math.toDegrees(orientationAngles[2].toDouble()).roundToInt(), true)
                    }

                    // Default Pattern, 500ms @ 45deg -> 10ms @ 180deg
                    Log.d("VIB", "%s %d %d".format(vibrator.vibEnable.toString(), vibrator.vibInterval, vibrator.vibIntensity))
                    vibrator.vibEnable = (angle.absoluteValue >= 45)
                    vibrator.vibInterval = 30
                    vibrator.vibIntensity = Math.max(1, Math.min(255, ((254 * (angle.absoluteValue - 45) / 135).toDouble().roundToInt())))
                    vibrationWorker.post(vibrator)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {


            }
        }

        sensorManager.registerListener(rotVctSensorEventListener, rotVctSensor, 100000, sensorWorker)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class VibrationCaller: Runnable {
        var vibEnable: Boolean = false
        var vibInterval: Long = 0
        var vibIntensity: Int = 1
        var vibPattern: CombinedVibration

        init {
            vibEnable = false
            vibInterval = 30 // 10ms
            vibIntensity = 1
            vibPattern = CombinedVibration.createParallel(VibrationEffect.createOneShot(vibInterval, vibIntensity))
        }
        override fun run() {
            vibratorManager = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            if (vibEnable) {
                vibPattern = CombinedVibration.createParallel(VibrationEffect.createOneShot(vibInterval, vibIntensity))
                vibratorManager.vibrate(vibPattern)
            }
        }

    }
}