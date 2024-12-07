package com.csed433project.hapticfitness.ui.back_stretch

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

import android.content.Context

import android.os.Bundle
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.csed433project.hapticfitness.databinding.BackstretchBinding
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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

    
    fun actionHandlerExerciseZone (angle: Int): CombinedVibration? {
        /*
            Inverse proportional: (angle) 45-120deg -> (intensity) 1-255.
         */
        if (45 < angle && angle <= 120) {
            val interval: Long = 100
            val strength: Int = 1.coerceAtLeast(
                255.coerceAtMost(((254 * (angle - 45) / (120 - 45)).toDouble().roundToInt()))
            )
            return CombinedVibration.createParallel(VibrationEffect.createOneShot(interval, strength))
        }
        return null
    }

    fun actionHandlerDangerZone (angle: Int): CombinedVibration? {
        /*
            If angle > 120 -> Dangerous signal.
         */

        if (angle > 120) {
            val interval: Long = 30
            val strength: Int = 255
            return CombinedVibration.createParallel(VibrationEffect.createOneShot(interval, strength))
        }
        return null
    }
    
    private val actionHandlerArray: Array<(Int) -> CombinedVibration?> = arrayOf(::actionHandlerExerciseZone, ::actionHandlerDangerZone)

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

        vibratorManager = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager

        vibrator = VibrationCaller()
        
        rotVctSensorEventListener = object: SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    SensorManager.getOrientation(rotMat, orientationAngles)

                    angle = Math.toDegrees(orientationAngles[2].toDouble()).roundToInt()

                    binding.valueText.text = "%03d".format(angle.absoluteValue)
                    binding.progressBar.scaleY = if (angle >= 0) -1.0F else 1.0F
                    binding.progressBar.setProgress(angle.absoluteValue, true)

                    actionHandlerArray.forEach { fn ->
                        val vibEff = fn(angle.absoluteValue)
                        if (vibEff != null) {
                            vibrator.vibPattern = vibEff
                            vibrationWorker.post(vibrator)
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(rotVctSensorEventListener, rotVctSensor, 100000, sensorWorker)

        return root
    }

    override fun onDestroyView() {
        /*
            1. Release Binding (stop updating UI from ui thread)
            2. Stop Vibrator thread (depends on sensor thread)
            3. Stop Sensor thread
            4. super.onDestroyView()
         */

        _binding = null
        vibratorManager.cancel()
        vibrationThread.quitSafely()

        sensorHandlerThread.quitSafely()
        super.onDestroyView()
    }

    inner class VibrationCaller: Runnable {
        lateinit var vibPattern: CombinedVibration
        override fun run() {
            vibratorManager.vibrate(vibPattern)
        }

    }
}