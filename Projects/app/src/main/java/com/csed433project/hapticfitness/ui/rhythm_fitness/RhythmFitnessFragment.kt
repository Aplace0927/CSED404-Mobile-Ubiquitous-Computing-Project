package com.csed433project.hapticfitness.ui.rhythm_fitness

import android.animation.AnimatorInflater
import android.animation.ValueAnimator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.animation.AnimationUtils

import android.content.Context
import android.graphics.Color

import android.util.Log
import android.os.Bundle
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.csed433project.hapticfitness.R
import com.csed433project.hapticfitness.databinding.RhythmFitnessBinding
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.Buffer
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class RhythmFitnessFragment : Fragment() {

    private var _binding: RhythmFitnessBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var vibrator: VibrationCaller
    private lateinit var vibrationThread: HandlerThread
    private lateinit var vibratorManager: VibratorManager
    private lateinit var vibrationWorker: Handler

    private lateinit var sensorManager: SensorManager
    private lateinit var gyroSensor: Sensor
    private lateinit var gyroSensorEventListener: SensorEventListener
    private lateinit var accelSensor: Sensor
    private lateinit var accelSensorEventListener: SensorEventListener

    private lateinit var gyroSensorHandlerThread: HandlerThread
    private lateinit var gyroSensorWorker: Handler
    private lateinit var accelSensorHandlerThread: HandlerThread
    private lateinit var accelSensorWorker: Handler

    private lateinit var judge: Judge
    private lateinit var judgementThread: Thread

    private lateinit var gyroCirq: SensorCircularQueue
    private lateinit var accelCirq: SensorCircularQueue

    private var timeStampStart: Long = 0

    private var playing: PlayState = PlayState.STOPPED

    enum class PlayState {
        STOPPED, PAUSED, PLAYING
    }

    enum class JudgementGrade (val multiplier: Double){
        MISS        (0.00),
        SLOW        (0.50),
        GREAT_SLOW  (0.75),
        PERFECT_SLOW(1.00),
        PERFECT     (1.01),
        PERFECT_FAST(1.00),
        GREAT_FAST  (0.75),
        FAST        (0.50),
    }
    /*
        D E S I G N

                           ┌ accelSensorHandlerThread -┐
     ┌ SENSORS ----┐ ┌-----|> accelSensorEventListener-?- WRITES -> accelCirq ----┐
     |accelSensor -|-┘     └---------------------------┘                          |
     |gyroSensor  -|-┐     ┌ gyroSensorHandlerThread --┐                          |
     └-------------┘ └-----|> gyroSensorEventListener--?- WRITES ->  gyroCirq ----|
                           └---------------------------┘                          |
                                                                                  |
                           ┌ judgementThread --┐                                  |
                           | Judge ----- run() -----> time from map data ------?--| (time + (window / 2)
                           └-------------------┘            |                     |
                                                ┌ read -----|                     |
                            assets/mapdata -----┘           └---------------┐     |
                                                                          ┌-|-----------------┐
                                                          update score <--|-judgement Routine |
                                                                          └-------|-----------┘
                                             update UI <- runOnUiThread() --------┘

     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val backStretchViewModel =
            ViewModelProvider(this).get(RhythmFitnessViewModel::class.java)

        _binding = RhythmFitnessBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val orientationAngles = FloatArray(3)
        var angle: Int = 0

        sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) as Sensor
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) as Sensor

        gyroSensorHandlerThread = HandlerThread("Gyroscope Event Handler")
        gyroSensorHandlerThread.start()
        gyroSensorWorker = Handler(gyroSensorHandlerThread.looper)
        gyroCirq = SensorCircularQueue(resources.getInteger(R.integer.judgement_window))

        accelSensorHandlerThread = HandlerThread("Accelerometer Event Handler")
        accelSensorHandlerThread.start()
        accelSensorWorker = Handler(accelSensorHandlerThread.looper)
        accelCirq = SensorCircularQueue(resources.getInteger(R.integer.judgement_window))

        vibrationThread = HandlerThread("Vibration Thread")
        vibrationThread.start()
        vibrationWorker = Handler(vibrationThread.looper)

        judge = Judge()

        vibratorManager = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager

        vibrator = VibrationCaller()
        
        gyroSensorEventListener = object: SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
                    gyroCirq.scqPush(event.values[0], event.values[1], event.values[2])
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelSensorEventListener = object: SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                    accelCirq.scqPush(event.values[0], event.values[1], event.values[2])
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        binding.stateTransitButton.setOnClickListener({
            if (playing == PlayState.STOPPED)
            {

                playing = PlayState.PLAYING
                binding.stateTransitButton.text = "PAUSE"

                timeStampStart = System.currentTimeMillis()

                judge.currentScore = 101.0000
                judge.mapPlayingReader = BufferedReader(InputStreamReader(resources.assets.open("rhythm_map.dat")))
                judge.noteCount = judge.mapPlayingReader.readLine().toInt()
                judge.nextAction = judge.mapPlayingReader.readLine()

                judgementThread = Thread(judge)
                judgementThread.start()
            }
            else if (playing == PlayState.PAUSED)
            {
                Log.d("STATE", "Pause -> Play")
                playing = PlayState.PLAYING
                binding.stateTransitButton.text = "PAUSE"
                timeStampStart = System.currentTimeMillis() - timeStampStart    // Elapsed time is stored when interrupted
                judgementThread.run()
            }
            else if (playing == PlayState.PLAYING) {
                Log.d("STATE", "Play -> Pause")
                playing = PlayState.PAUSED
                binding.stateTransitButton.text = "RESUME"
                judgementThread.interrupt()
            }
        })
        // Rhythm fitness requires 1ms scale accuracy!
        sensorManager.registerListener(gyroSensorEventListener, gyroSensor, 1000, gyroSensorWorker)
        sensorManager.registerListener(accelSensorEventListener, accelSensor, 1000, accelSensorWorker)

        return root
    }

    override fun onDestroyView() {
        /*
            1. Release Binding (stop updating UI from ui thread)
            2. Stop Judgement thread
            2. Stop Vibrator thread (depends on sensor thread)
            3. Stop Sensor thread
            4. super.onDestroyView()
         */

        _binding = null

        judgementThread.interrupt()

        vibratorManager.cancel()
        vibrationThread.quitSafely()

        accelSensorHandlerThread.quitSafely()
        gyroSensorHandlerThread.quitSafely()
        super.onDestroyView()
    }

    inner class VibrationCaller: Runnable {
        lateinit var vibPattern: CombinedVibration
        override fun run() {
            vibratorManager.vibrate(vibPattern)
        }
    }

    inner class Judge: Runnable {
        val actionJudge: Array<() -> JudgementGrade> = arrayOf(::returnRandomJudgement)  // To implement each action's judgement from gyroCirq and accelCirq
        lateinit var mapPlayingReader: BufferedReader
        var nextAction: String? = ""
        var noteCount: Int = 0

        var currentScore: Double = 101.0000

        override fun run() {
            while (nextAction != null) {
                val nextActionTime: Long = nextAction?.split(" ")?.get(0)?.toLong() as Long
                val nextActionCategory: Int = nextAction?.split(" ")?.get(1)?.toInt() as Int

                if (Thread.currentThread().isInterrupted) {
                    Log.d("intr", "%d %d".format(System.currentTimeMillis(), timeStampStart))
                    timeStampStart = System.currentTimeMillis() - timeStampStart    // Store the elapsed time
                    playing = PlayState.PAUSED
                    binding.stateTransitButton.text = "RESUME"
                    return
                }

                if ((System.currentTimeMillis() - timeStampStart >= nextActionTime - resources.getInteger(R.integer.judgement_window) / 2) &&
                    (System.currentTimeMillis() - timeStampStart < nextActionTime - resources.getInteger(R.integer.judgement_window) / 2 + 2)) {    // Tolerance 5 Frame
                    activity?.runOnUiThread(object: Runnable {
                        override fun run() {
                            binding.judgementLineM.startAnimation(
                                AnimationUtils.loadAnimation(context, R.anim.linear_move)
                            )
                        }
                    })
                }

                if (System.currentTimeMillis() - timeStampStart > nextActionTime + resources.getInteger(R.integer.judgement_window) / 2) {
                    judgement(nextActionCategory)
                    nextAction = mapPlayingReader.readLine()
                }
            }
            playing = PlayState.STOPPED
            binding.stateTransitButton.text = "START"
            return
        }

        fun judgement(category: Int) {
            val judgeResult = actionJudge[category]()
            val (judgeResultText: String, judgeResultColor: Int) = returnAnyResult(judgeResult)
            currentScore -= ((101.0000 - judgeResult.multiplier * 100.0000) / noteCount)

            Log.d("Judge", "%s %x".format(judgeResultText, judgeResultColor))
            activity?.runOnUiThread(object: Runnable {
                override fun run() {
                    Log.d("Update", "Update %f".format(currentScore))
                    binding.judgementText.text = judgeResultText
                    binding.judgementText.setTextColor(judgeResultColor)
                    binding.scoreText.text = "%08.4f %%".format(currentScore)
                }
            })
        }

        fun returnRandomJudgement(): JudgementGrade {
            val resultGrade: List<JudgementGrade> = listOf(
                JudgementGrade.MISS,
                JudgementGrade.SLOW,
                JudgementGrade.GREAT_SLOW,
                JudgementGrade.PERFECT_SLOW,
                JudgementGrade.PERFECT,
                JudgementGrade.PERFECT_FAST,
                JudgementGrade.GREAT_FAST,
                JudgementGrade.FAST,
            )
            return resultGrade[Random.nextInt(resultGrade.size)]
        }

        fun returnAnyResult(judge: JudgementGrade): Pair<String, Int> {
            when(judge) {
                JudgementGrade.MISS -> return "MISS" to context?.getColor(R.color.judge_miss) as Int
                JudgementGrade.SLOW -> return "SLOW" to context?.getColor(R.color.judge_slow) as Int
                JudgementGrade.GREAT_SLOW -> return "GREAT-SLOW" to context?.getColor(R.color.judge_great_slow) as Int
                JudgementGrade.PERFECT_SLOW -> return "PERFECT-SLOW" to context?.getColor(R.color.judge_perfect_slow) as Int
                JudgementGrade.PERFECT -> return "PERFECT" to context?.getColor(R.color.judge_perfect) as Int
                JudgementGrade.PERFECT_FAST -> return "PERFECT-FAST" to context?.getColor(R.color.judge_perfect_fast) as Int
                JudgementGrade.GREAT_FAST -> return "GREAT-FAST" to context?.getColor(R.color.judge_great_fast) as Int
                JudgementGrade.FAST -> return "FAST" to context?.getColor(R.color.judge_fast) as Int
            }
        }
    }

    inner class SensorCircularQueue (windowSize: Int) {
        var sensorArray : Array<FloatArray> = Array(windowSize) {floatArrayOf(0.0F, 0.0F, 0.0F)}
        var pointer: Int = 0
        val capacity = windowSize

        fun scqPush(x: Float, y: Float, z: Float)  {
            sensorArray[pointer][0] = x
            sensorArray[pointer][1] = y
            sensorArray[pointer][2] = z
            pointer += 1
            pointer %= capacity
        }

        fun getMaximumActivity (): Int {
            /*
                          (1)
                      (0)  |       (2)
                       |   v        |
                FAST   v  JUST      v SLOW
                |----------|----------|
                <---- capacity ------->

                ...(0) function returns slightly negative value. little fast.
                ...(1) function returns zero - perfect job!
                ...(2) function returns fair positive value. too slow.
             */
            val getMagnitude: (FloatArray) -> Float = {arr -> arr[0].pow(2) + arr[1].pow(2) + arr[2].pow(2)}
            var maxIdx = sensorArray.indices.maxBy { getMagnitude(sensorArray[it]) }
            return ((maxIdx + capacity) % capacity) - (capacity / 2)
        }
    }
}