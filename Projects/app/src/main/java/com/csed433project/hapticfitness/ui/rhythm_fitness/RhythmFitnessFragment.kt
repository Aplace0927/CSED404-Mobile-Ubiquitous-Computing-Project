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
import android.util.TypedValue
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
        gyroCirq = SensorCircularQueue(resources.getInteger(R.integer.judgement_window) / 10)

        accelSensorHandlerThread = HandlerThread("Accelerometer Event Handler")
        accelSensorHandlerThread.start()
        accelSensorWorker = Handler(accelSensorHandlerThread.looper)
        accelCirq = SensorCircularQueue(resources.getInteger(R.integer.judgement_window) / 10)

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
                binding.judgementLineR.text = judgementSymbol((judge.nextAction as String).split(" ")[1].toInt())

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
        // Rhythm fitness requires 10ms scale accuracy!
        sensorManager.registerListener(gyroSensorEventListener, gyroSensor, 10000, gyroSensorWorker)
        sensorManager.registerListener(accelSensorEventListener, accelSensor, 10000, accelSensorWorker)

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

        if (playing == PlayState.PAUSED || playing == PlayState.PLAYING)
        {
            judgementThread.interrupt()
        }

        _binding = null

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
        val actionJudge: Array<() -> JudgementGrade> = arrayOf(
            ::judgementPush,
            ::judgementPull,
            ::judgementRaise,
            ::judgementHit,
            ::judgementLeftBend,
            ::judgementRightBend
        )  // To implement each action's judgement from gyroCirq and accelCirq

        lateinit var mapPlayingReader: BufferedReader
        var nextAction: String? = ""
        var noteCount: Int = 0

        var currentScore: Double = 101.0000

        override fun run() {
            while (nextAction != null) {
                val nextActionTime: Long = nextAction?.split(" ")?.get(0)?.toLong() as Long
                val nextActionCategory: Int = nextAction?.split(" ")?.get(1)?.toInt() as Int

                if (Thread.currentThread().isInterrupted) {
                    timeStampStart = System.currentTimeMillis() - timeStampStart    // Store the elapsed time
                    playing = PlayState.PAUSED
                    binding.stateTransitButton.text = "RESUME"
                    return
                }

                if ((System.currentTimeMillis() - timeStampStart >= nextActionTime - resources.getInteger(R.integer.judgement_window) / 2) &&
                    (System.currentTimeMillis() - timeStampStart < nextActionTime - resources.getInteger(R.integer.judgement_window) / 2 + 2)) {    // Tolerance 5 Frame
                    accelCirq.scqClear()
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
                    activity?.runOnUiThread(object: Runnable {
                        override fun run() {
                            if (nextAction != null)
                            {
                                binding.judgementLineR.text = judgementSymbol(nextAction?.split(" ")?.get(1)?.toInt() as Int)
                            }
                        }
                    })
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

            activity?.runOnUiThread(object: Runnable {
                override fun run() {
                    binding.judgementText.text = judgeResultText
                    binding.judgementText.setTextColor(judgeResultColor)
                    binding.scoreText.text = "%08.4f %%".format(currentScore)
                }
            })
        }

        fun baseJudgement(maxIdx: Int, maxVal: Float): JudgementGrade {
            val ctxIntGet: (Int) -> Int = { x -> context?.resources?.getInteger(x) as Int }
            if (maxVal.absoluteValue < ctxIntGet(R.integer.judgement_threshold).toFloat() / 1000) {
                return JudgementGrade.MISS
            }

            return when (maxIdx) {
                in -25 ..< ctxIntGet(R.integer.slow_slow_great_threshold) -> JudgementGrade.SLOW
                in ctxIntGet(R.integer.slow_slow_great_threshold) ..< ctxIntGet(R.integer.slow_great_slow_perfect_threshold) -> JudgementGrade.GREAT_SLOW
                in ctxIntGet(R.integer.slow_great_slow_perfect_threshold) ..< ctxIntGet(R.integer.slow_perfect_perfect_threshold) -> JudgementGrade.PERFECT_SLOW
                in ctxIntGet(R.integer.slow_perfect_perfect_threshold) ..< ctxIntGet(R.integer.perfect_fast_perfect_threshold) -> JudgementGrade.PERFECT
                in ctxIntGet(R.integer.perfect_fast_perfect_threshold) ..< ctxIntGet(R.integer.fast_perfect_fast_great_threshold) -> JudgementGrade.PERFECT_FAST
                in ctxIntGet(R.integer.fast_perfect_fast_great_threshold) ..< ctxIntGet(R.integer.fast_great_fast_threshold) -> JudgementGrade.GREAT_FAST
                in ctxIntGet(R.integer.fast_great_fast_threshold) .. 25 -> JudgementGrade.FAST
                else -> JudgementGrade.MISS
            }
        }

        fun judgementPush(): JudgementGrade {
            val (maxIdxPos: Int, maxValPos: Float) = gyroCirq.getMaximumActivityOf { arr -> arr[0] }        // PushStart or PullStop
            val (maxIdxNeg: Int, maxValNeg: Float) = gyroCirq.getMaximumActivityOf { arr -> -1 * arr[0] }   // PullStart or PushStop
            if (maxIdxPos > maxIdxNeg) {   // PushStop -> PushStart then Pull
                return JudgementGrade.MISS
            }
            Log.d("Push", "%d %f / %d %f".format(maxIdxPos, maxValPos, maxIdxNeg, maxValNeg))
            return baseJudgement(maxIdxPos, maxValPos)
        }

        fun judgementPull(): JudgementGrade {
            val (maxIdxPos: Int, maxValPos: Float) = gyroCirq.getMaximumActivityOf { arr -> arr[0] }        // PushStart or PullStop
            val (maxIdxNeg: Int, maxValNeg: Float) = gyroCirq.getMaximumActivityOf { arr -> -1 * arr[0] }   // PullStart or PushStop
            if (maxIdxNeg > maxIdxPos) { // PullStart -> PullStop then Push
                return JudgementGrade.MISS
            }
            Log.d("Pull", "%d %f / %d %f".format(maxIdxPos, maxValPos, maxIdxNeg, maxValNeg))
            return baseJudgement(maxIdxNeg, maxValNeg)
        }

        fun judgementRaise(): JudgementGrade {
            val (maxIdxPos: Int, maxValPos: Float) = gyroCirq.getMaximumActivityOf { arr -> arr[1] }        // RaiseStart or HitStop
            val (maxIdxNeg: Int, maxValNeg: Float) = gyroCirq.getMaximumActivityOf { arr -> -1 * arr[1] }   // HitStart or RaiseStop
            Log.d("Raise", "%d %f / %d %f".format(maxIdxPos, maxValPos, maxIdxNeg, maxValNeg))
            if (maxIdxPos > maxIdxNeg) {
                return JudgementGrade.MISS
            }

            return baseJudgement(maxIdxPos, maxValPos)
        }

        fun judgementHit(): JudgementGrade {
            val (maxIdxPos: Int, maxValPos: Float) = gyroCirq.getMaximumActivityOf { arr -> arr[1] }        // RaiseStart or HitStop
            val (maxIdxNeg: Int, maxValNeg: Float) = gyroCirq.getMaximumActivityOf { arr -> -1 * arr[1] }   // HitStart or RaiseStop
            Log.d("Hit", "%d %f / %d %f".format(maxIdxPos, maxValPos, maxIdxNeg, maxValNeg))
            if (maxIdxNeg > maxIdxPos) {
                return JudgementGrade.MISS
            }
            return baseJudgement(maxIdxNeg, maxValNeg)
        }

        fun judgementLeftBend(): JudgementGrade {
            val (maxIdxPos: Int, maxValPos: Float) = gyroCirq.getMaximumActivityOf { arr -> arr[2] }        // RaiseStart or HitStop
            val (maxIdxNeg: Int, maxValNeg: Float) = gyroCirq.getMaximumActivityOf { arr -> -1 * arr[2] }   // HitStart or RaiseStop
            Log.d("Left", "%d %f / %d %f".format(maxIdxPos, maxValPos, maxIdxNeg, maxValNeg))
            if (maxIdxNeg > maxIdxPos) {
                return JudgementGrade.MISS
            }
            return baseJudgement(maxIdxNeg, maxValNeg)
        }

        fun judgementRightBend(): JudgementGrade {
            val (maxIdxPos: Int, maxValPos: Float) = gyroCirq.getMaximumActivityOf { arr -> arr[2] }        // RaiseStart or HitStop
            val (maxIdxNeg: Int, maxValNeg: Float) = gyroCirq.getMaximumActivityOf { arr -> -1 * arr[2] }   // HitStart or RaiseStop
            Log.d("Right", "%d %f / %d %f".format(maxIdxPos, maxValPos, maxIdxNeg, maxValNeg))
            if (maxIdxPos > maxIdxNeg) {
                return JudgementGrade.MISS
            }

            return baseJudgement(maxIdxPos, maxValPos)
        }




        fun returnAnyResult(judge: JudgementGrade): Pair<String, Int> {
            return when(judge) {
                JudgementGrade.MISS -> "MISS" to context?.getColor(R.color.judge_miss) as Int
                JudgementGrade.SLOW -> "SLOW" to context?.getColor(R.color.judge_slow) as Int
                JudgementGrade.GREAT_SLOW -> "GREAT-SLOW" to context?.getColor(R.color.judge_great_slow) as Int
                JudgementGrade.PERFECT_SLOW -> "PERFECT-SLOW" to context?.getColor(R.color.judge_perfect_slow) as Int
                JudgementGrade.PERFECT -> "PERFECT" to context?.getColor(R.color.judge_perfect) as Int
                JudgementGrade.PERFECT_FAST -> "PERFECT-FAST" to context?.getColor(R.color.judge_perfect_fast) as Int
                JudgementGrade.GREAT_FAST -> "GREAT-FAST" to context?.getColor(R.color.judge_great_fast) as Int
                JudgementGrade.FAST -> "FAST" to context?.getColor(R.color.judge_fast) as Int
            }
        }
    }

    inner class SensorCircularQueue (windowSize: Int) {
        var sensorArray : Array<FloatArray> = Array(windowSize) {floatArrayOf(0.0F, 0.0F, 0.0F)}
        var pointer: Int = 0
        val capacity = windowSize

        fun scqClear() {
            for (i in 0 ..< capacity) {
                sensorArray[i] = floatArrayOf(0.0F, 0.0F, 0.0F)
                pointer = 0
            }
        }

        fun scqPush(x: Float, y: Float, z: Float)  {
            // Now detects jerk
            sensorArray[pointer][0] = x - sensorArray[(pointer + capacity - 1) % capacity][0]
            sensorArray[pointer][1] = y - sensorArray[(pointer + capacity - 1) % capacity][1]
            sensorArray[pointer][2] = z - sensorArray[(pointer + capacity - 1) % capacity][2]
            pointer += 1
            pointer %= capacity
        }

        fun getMaximumActivityOf (fn: (FloatArray) -> Float ): Pair<Int, Float> {
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
            var maxIdx = sensorArray.indices.maxBy { fn(sensorArray[it]) }
            val offmaxIdx = ((maxIdx + capacity + (resources?.getInteger(R.integer.judgement_offset) as Int)) % capacity) - (capacity / 2)
            val maxIdxValue = fn(sensorArray[maxIdx])

            return Pair(offmaxIdx, maxIdxValue)
        }
    }

    fun judgementSymbol(actionCategory: Int): String {
        return when(actionCategory) {
            0 -> "←"
            1 -> "→"
            2 -> "↑"
            3 -> "↓"
            4 -> "⨀"
            5 -> "⨂"
            else -> "?"
        }
    }
}