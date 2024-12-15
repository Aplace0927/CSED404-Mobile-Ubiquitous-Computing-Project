package com.csed433project.hapticfitness.ui.rhythm_fitness

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.animation.AnimationUtils

import android.content.Context
import android.media.MediaPlayer

import android.util.Log
import android.os.Bundle
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.VibratorManager
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.csed433project.hapticfitness.R
import com.csed433project.hapticfitness.databinding.RhythmFitnessBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.absoluteValue

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

    private lateinit var accelSensor: Sensor
    private lateinit var accelSensorEventListener: SensorEventListener
    private lateinit var accelSensorHandlerThread: HandlerThread
    private lateinit var accelSensorWorker: Handler

    private lateinit var judge: Judge
    private lateinit var judgementThread: Thread

    private lateinit var accelMovAvg: SensorMovAvg

    private lateinit var mediaPlayer: MediaPlayer

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
     └-------------┘                                                              |                       |
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
        val rhythmFitnessViewModel =
            ViewModelProvider(this).get(RhythmFitnessViewModel::class.java)

        _binding = RhythmFitnessBinding.inflate(inflater, container, false)
        val root: View = binding.root

        sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) as Sensor

        accelSensorHandlerThread = HandlerThread("Accelerometer Event Handler")
        accelSensorHandlerThread.start()
        accelSensorWorker = Handler(accelSensorHandlerThread.looper)
        accelMovAvg = SensorMovAvg()

        vibrationThread = HandlerThread("Vibration Thread")
        vibrationThread.start()
        vibrationWorker = Handler(vibrationThread.looper)

        judge = Judge()

        vibratorManager = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibrator = VibrationCaller()

        accelSensorEventListener = object: SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                    accelMovAvg.movAvgUpdate(event.values[0], event.values[1], event.values[2])
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

                judge.setupArrayQueue()
                judge.resetJudgeResult()
                judge.currentScore = 101.0000

                judge.mapPlayingReader = BufferedReader(InputStreamReader(resources.assets.open("rhythm_map.dat")))
                judge.noteCount = judge.mapPlayingReader.readLine().toInt()
                judge.nextAction = judge.mapPlayingReader.readLine()

                binding.judgementLineR.text = judgementSymbol((judge.nextAction as String).split(" ")[1].toInt())
                binding.scoreText.text = "%08.4f %%".format(judge.currentScore)

                judgementThread = Thread(judge)

                mediaPlayer = MediaPlayer.create(context, R.raw.remix10)
                mediaPlayer.start()
                judgementThread.start()
            }
            else if (playing == PlayState.PAUSED)
            {
                Log.d("STATE", "Pause -> Play")
                playing = PlayState.PLAYING
                binding.stateTransitButton.text = "PAUSE"
                timeStampStart = System.currentTimeMillis() - timeStampStart    // Elapsed time is stored when interrupted
                judgementThread.run()
                mediaPlayer.start()
            }
            else if (playing == PlayState.PLAYING) {
                Log.d("STATE", "Play -> Pause")
                playing = PlayState.PAUSED
                binding.stateTransitButton.text = "RESUME"
                judgementThread.interrupt()
                mediaPlayer.pause()
            }
        })

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
        super.onDestroyView()
    }

    inner class VibrationCaller: Runnable {
        lateinit var vibPattern: CombinedVibration
        fun generateWithJudgement(judge: JudgementGrade) {
            val ctxIntGet: (Int) -> Int = {x -> (context?.resources?.getInteger(x) as Int)}
            val strength: Int = when(judge) {
                JudgementGrade.MISS -> ctxIntGet(R.integer.miss_vib_strength)
                JudgementGrade.FAST -> ctxIntGet(R.integer.slow_fast_vib_strength)
                JudgementGrade.GREAT_FAST -> ctxIntGet(R.integer.great_vib_strength)
                JudgementGrade.PERFECT_FAST -> ctxIntGet(R.integer.perfect_vib_strength)
                JudgementGrade.PERFECT -> ctxIntGet(R.integer.justice_vib_strength)
                JudgementGrade.PERFECT_SLOW -> ctxIntGet(R.integer.perfect_vib_strength)
                JudgementGrade.GREAT_SLOW -> ctxIntGet(R.integer.great_vib_strength)
                JudgementGrade.SLOW -> ctxIntGet(R.integer.slow_fast_vib_strength)
            }
            vibPattern = CombinedVibration.createParallel(VibrationEffect.createOneShot(50, strength))
        }
        override fun run() {
            vibratorManager.vibrate(vibPattern)
        }
    }

    inner class Judge: Runnable {

        lateinit var mapPlayingReader: BufferedReader
        var nextAction: String? = ""
        var noteCount: Int = 0

        var judgeResultMiss: Int = 0
        var judgeResultFast: Int = 0
        var judgeResultGreatFast: Int = 0
        var judgeResultPerfectFast: Int = 0
        var judgeResultPerfect: Int = 0
        var judgeResultPerfectSlow: Int = 0
        var judgeResultGreatSlow: Int = 0
        var judgeResultSlow: Int = 0

        var currentAnimObject: Int = 0
        var currentScore: Double = 101.0000
        val rhythmThreshold = resources.getInteger(R.integer.judgement_threshold).toFloat()

        var actionTimeQueue: ArrayDeque<Long> = ArrayDeque<Long>()

        fun resetJudgeResult () {
            judgeResultMiss = 0
            judgeResultFast = 0
            judgeResultGreatFast = 0
            judgeResultPerfectFast = 0
            judgeResultPerfect = 0
            judgeResultPerfectSlow = 0
            judgeResultGreatSlow = 0
            judgeResultSlow = 0
        }

        fun setupArrayQueue () {
            val mapReader = BufferedReader(InputStreamReader(resources.assets.open("rhythm_map.dat")))
            mapReader.readLine()
            var nextAction = mapReader.readLine()
            while (nextAction != null) {
                actionTimeQueue.addLast(nextAction.split(" ")[0].toLong())
                nextAction = mapReader.readLine()
            }
        }

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

                val nearestAction = if (actionTimeQueue.isEmpty()) { 0 } else {actionTimeQueue.first()}
                if ((System.currentTimeMillis() - timeStampStart >= nearestAction - resources.getInteger(R.integer.animation_window) / 2) &&
                    (System.currentTimeMillis() - timeStampStart < nearestAction - resources.getInteger(R.integer.animation_window) / 2 + 2)) {    // Tolerance 5 Frame
                    currentAnimObject = (currentAnimObject + 1) % 5
                    if (actionTimeQueue.isNotEmpty()) {
                        actionTimeQueue.removeFirst()
                    }
                    if (actionTimeQueue.size != 1) {
                        activity?.runOnUiThread(object: Runnable {
                            override fun run() {
                                when (currentAnimObject) {
                                    0 -> binding.judgementLineM0.startAnimation(AnimationUtils.loadAnimation(context, R.anim.linear_move))
                                    1 -> binding.judgementLineM1.startAnimation(AnimationUtils.loadAnimation(context, R.anim.linear_move))
                                    2 -> binding.judgementLineM2.startAnimation(AnimationUtils.loadAnimation(context, R.anim.linear_move))
                                    3 -> binding.judgementLineM3.startAnimation(AnimationUtils.loadAnimation(context, R.anim.linear_move))
                                    4 -> binding.judgementLineM4.startAnimation(AnimationUtils.loadAnimation(context, R.anim.linear_move))
                                    else -> binding.judgementLineM0.startAnimation(AnimationUtils.loadAnimation(context, R.anim.linear_move))
                                }
                            }
                        })
                    }
                }

                val currentElapsed = System.currentTimeMillis() - timeStampStart

                /*
                    Debugging mode for adding charts.
                    Un-comment the LinearLayout containing 6 Buttons to enable this feature.

                binding.buttonPush.setOnClickListener({Log.d("Chart", "Push %d".format(currentElapsed))})
                binding.buttonPull.setOnClickListener({Log.d("Chart", "Pull %d".format(currentElapsed))})
                binding.buttonRaise.setOnClickListener({Log.d("Chart", "Raise %d".format(currentElapsed))})
                binding.buttonHit.setOnClickListener({Log.d("Chart", "Hit %d".format(currentElapsed))})
                binding.buttonLeft.setOnClickListener({Log.d("Chart", "Left %d".format(currentElapsed))})
                binding.buttonRight.setOnClickListener({Log.d("Chart", "Right %d".format(currentElapsed))})
                */

                if (nextActionCategory == -1 && (currentElapsed - nextActionTime).absoluteValue < resources.getInteger(R.integer.judgement_window) / 2) { // Map finish
                    nextAction = mapPlayingReader.readLine()
                }
                else if (accelMovAvg.movX > rhythmThreshold && nextActionCategory == 0 && (currentElapsed - nextActionTime).absoluteValue < resources.getInteger(R.integer.judgement_window) / 2) {
                    Log.d("Push", "Push [%d]-[%d]".format(nextActionTime,  currentElapsed))
                    judgement(nextActionTime - currentElapsed)
                    nextAction = mapPlayingReader.readLine()
                }
                else if (accelMovAvg.movX < -rhythmThreshold && nextActionCategory == 1 && (currentElapsed - nextActionTime).absoluteValue < resources.getInteger(R.integer.judgement_window) / 2) {
                    Log.d("Pull", "Pull [%d]-[%d]".format(nextActionTime,  currentElapsed))
                    judgement(nextActionTime - currentElapsed)
                    nextAction = mapPlayingReader.readLine()
                }
                else if (accelMovAvg.movY > rhythmThreshold && nextActionCategory == 2 && (currentElapsed - nextActionTime).absoluteValue < resources.getInteger(R.integer.judgement_window) / 2) {
                    Log.d("Raise", "Raise [%d]-[%d]".format(nextActionTime, currentElapsed))
                    judgement(nextActionTime - currentElapsed)
                    nextAction = mapPlayingReader.readLine()
                }
                else if (accelMovAvg.movY < -rhythmThreshold && nextActionCategory == 3 && (currentElapsed - nextActionTime).absoluteValue < resources.getInteger(R.integer.judgement_window) / 2) {
                    Log.d("Hit", "Hit [%d]-[%d]".format(nextActionTime, currentElapsed))
                    judgement(nextActionTime - currentElapsed)
                    nextAction = mapPlayingReader.readLine()
                }
                else if (accelMovAvg.movZ > rhythmThreshold && nextActionCategory == 4 && (currentElapsed - nextActionTime).absoluteValue < resources.getInteger(R.integer.judgement_window) / 2) {
                    Log.d("Left", "Left [%d]-[%d]".format(nextActionTime,  currentElapsed))
                    judgement(nextActionTime - currentElapsed)
                    nextAction = mapPlayingReader.readLine()
                }
                else if (accelMovAvg.movZ < -rhythmThreshold && nextActionCategory == 5 && (currentElapsed - nextActionTime).absoluteValue < resources.getInteger(R.integer.judgement_window) / 2) {
                    Log.d("Right", "Right [%d]-[%d]".format(nextActionTime,  currentElapsed))
                    judgement(nextActionTime - currentElapsed)
                    nextAction = mapPlayingReader.readLine()
                }
                else if (currentElapsed - nextActionTime > resources.getInteger(R.integer.judgement_window) / 2) {
                    Log.d("MISS", "[%d]-[%d]".format(nextActionTime,  currentElapsed))
                    judgement(nextActionTime - currentElapsed)
                    nextAction = mapPlayingReader.readLine()
                }
            }

            playing = PlayState.STOPPED
            binding.stateTransitButton.text = "START"
            mediaPlayer.stop()
            mediaPlayer.release()


            activity?.runOnUiThread(object: Runnable {
                override fun run() {
                    var popupWindow = Dialog(context as Context)
                    var rankString = accuracyToRank(currentScore)
                    var rankColor = accuracyToColor(currentScore)
                    popupWindow.setContentView(R.layout.popupwindow)
                    popupWindow.findViewById<TextView>(R.id.accuracy_value).text = "%08.4f %%".format(currentScore)
                    popupWindow.findViewById<TextView>(R.id.accuracy_value).setTextColor(rankColor)
                    popupWindow.findViewById<TextView>(R.id.rank_value).text = rankString
                    popupWindow.findViewById<TextView>(R.id.rank_value).setTextColor(rankColor)
                    popupWindow.findViewById<TextView>(R.id.miss_count).text = "%d".format(judgeResultMiss)
                    popupWindow.findViewById<TextView>(R.id.slow_count).text = "%d".format(judgeResultSlow)
                    popupWindow.findViewById<TextView>(R.id.great_slow_count).text = "%d".format(judgeResultGreatSlow)
                    popupWindow.findViewById<TextView>(R.id.perfect_slow_count).text = "%d".format(judgeResultPerfectSlow)
                    popupWindow.findViewById<TextView>(R.id.perfect_count).text = "%d".format(judgeResultPerfect)
                    popupWindow.findViewById<TextView>(R.id.perfect_fast_count).text = "%d".format(judgeResultPerfectFast)
                    popupWindow.findViewById<TextView>(R.id.great_fast_count).text = "%d".format(judgeResultGreatFast)
                    popupWindow.findViewById<TextView>(R.id.fast_count).text = "%d".format(judgeResultFast)

                    popupWindow.findViewById<Button>(R.id.dismiss_button).setOnClickListener {popupWindow.dismiss()}
                    popupWindow.show()
                }
            })

            return
        }

        fun judgement(timeElapsed: Long) {
            val judgeResult = baseJudgement(timeElapsed + resources.getInteger(R.integer.judgement_offset).toLong())
            updateJudgeCount(judgeResult)
            val (judgeResultText: String, judgeResultColor: Int) = returnGradeAsset(judgeResult)

            currentScore -= ((101.0000 - judgeResult.multiplier * 100.0000) / noteCount)
            currentScore = currentScore.coerceAtLeast(0.0)


            vibrator.generateWithJudgement(judgeResult)
            vibrationWorker.post(vibrator)

            activity?.runOnUiThread(object: Runnable {
                override fun run() {
                    binding.judgementText.text = judgeResultText
                    binding.judgementText.setTextColor(judgeResultColor)
                    binding.scoreText.text = "%08.4f %%".format(currentScore)
                    binding.scoreText.setTextColor(accuracyToColor(currentScore))

                    if (nextAction != null) {
                        binding.judgementLineR.text = judgementSymbol(nextAction?.split(" ")?.get(1)?.toInt() as Int)
                    }
                }
            })
        }

        fun baseJudgement(maxVal: Long): JudgementGrade {
            val ctxIntGet: (Int) -> Long = {x -> (context?.resources?.getInteger(x) as Int).toLong()}
            return when (maxVal) {
                in ctxIntGet(R.integer.miss_slow_threshold) ..< ctxIntGet(R.integer.slow_slow_great_threshold) -> JudgementGrade.SLOW
                in ctxIntGet(R.integer.slow_slow_great_threshold) ..< ctxIntGet(R.integer.slow_great_slow_perfect_threshold) -> JudgementGrade.GREAT_SLOW
                in ctxIntGet(R.integer.slow_great_slow_perfect_threshold) ..< ctxIntGet(R.integer.slow_perfect_perfect_threshold) -> JudgementGrade.PERFECT_SLOW
                in ctxIntGet(R.integer.slow_perfect_perfect_threshold) ..< ctxIntGet(R.integer.perfect_fast_perfect_threshold) -> JudgementGrade.PERFECT
                in ctxIntGet(R.integer.perfect_fast_perfect_threshold) ..< ctxIntGet(R.integer.fast_perfect_fast_great_threshold) -> JudgementGrade.PERFECT_FAST
                in ctxIntGet(R.integer.fast_perfect_fast_great_threshold) ..< ctxIntGet(R.integer.fast_great_fast_threshold) -> JudgementGrade.GREAT_FAST
                in ctxIntGet(R.integer.fast_great_fast_threshold) ..< ctxIntGet(R.integer.fast_miss_threshold) -> JudgementGrade.FAST
                else -> JudgementGrade.MISS
            }
        }

        fun updateJudgeCount(judge: JudgementGrade) {
            when(judge) {
                JudgementGrade.MISS -> judgeResultMiss += 1
                JudgementGrade.SLOW -> judgeResultSlow += 1
                JudgementGrade.GREAT_SLOW -> judgeResultGreatSlow += 1
                JudgementGrade.PERFECT_SLOW -> judgeResultPerfectSlow += 1
                JudgementGrade.PERFECT -> judgeResultPerfect += 1
                JudgementGrade.PERFECT_FAST -> judgeResultPerfectFast += 1
                JudgementGrade.GREAT_FAST -> judgeResultGreatFast += 1
                JudgementGrade.FAST -> judgeResultFast += 1
            }
        }

        fun returnGradeAsset(judge: JudgementGrade): Pair<String, Int> {
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

    inner class SensorMovAvg () {
        var movX: Float = 0.0F
        var movY: Float = 0.0F
        var movZ: Float = 0.0F

        fun movAvgUpdate(x: Float, y: Float, z: Float)  {
            movX = movX * 0.3F + x * 0.7F
            movY = movY * 0.3F + y * 0.7F
            movZ = movZ * 0.3F + z * 0.7F
        }
    }

    fun judgementSymbol(actionCategory: Int): String {
        return when(actionCategory) {
            -1 -> "◀"
            0 -> "←"
            1 -> "→"
            2 -> "↑"
            3 -> "↓"
            4 -> "⨀"
            5 -> "⨂"
            else -> "?"
        }
    }

    fun accuracyToRank (acc: Double): String {
        return when(acc) {
            in 100.50 .. 101.00 -> "SSS+"
            in 100.00 ..< 100.50 -> "SSS"
            in 99.50 ..< 100.00 -> "SS+"
            in 99.00 ..< 99.50 -> "SS"
            in 98.00 ..< 99.00 -> "S+"
            in 97.00 ..< 98.00 -> "S"
            in 94.00 ..< 97.00 -> "AAA"
            in 90.00 ..< 94.00 -> "AA"
            in 80.00 ..< 90.00 -> "A"
            in 75.00 ..< 80.00 -> "BBB"
            in 70.00 ..< 75.00 -> "BB"
            in 60.00 ..< 70.00 -> "B"
            in 50.00 ..< 60.00 -> "C"
            in 0.00 ..< 50.00 -> "D"
            else -> "F"
        }
    }

    fun accuracyToColor (acc: Double): Int {
        return when(acc) {
            in 100.50 .. 101.00 -> context?.resources?.getColor(R.color.rank_sssp) as Int
            in 100.00 ..< 100.50 -> context?.resources?.getColor(R.color.rank_sss) as Int
            in 99.50 ..< 100.00 -> context?.resources?.getColor(R.color.rank_ssp) as Int
            in 99.00 ..< 99.50 -> context?.resources?.getColor(R.color.rank_ss) as Int
            in 98.00 ..< 99.00 -> context?.resources?.getColor(R.color.rank_sp) as Int
            in 97.00 ..< 98.00 -> context?.resources?.getColor(R.color.rank_s) as Int
            in 94.00 ..< 97.00 -> context?.resources?.getColor(R.color.rank_aaa) as Int
            in 90.00 ..< 94.00 -> context?.resources?.getColor(R.color.rank_aa) as Int
            in 80.00 ..< 90.00 -> context?.resources?.getColor(R.color.rank_a) as Int
            in 75.00 ..< 80.00 -> context?.resources?.getColor(R.color.rank_bbb) as Int
            in 70.00 ..< 75.00 -> context?.resources?.getColor(R.color.rank_bb) as Int
            in 60.00 ..< 70.00 -> context?.resources?.getColor(R.color.rank_b) as Int
            in 50.00 ..< 60.00 -> context?.resources?.getColor(R.color.rank_c) as Int
            in 0.00 ..< 50.00 ->  context?.resources?.getColor(R.color.rank_d) as Int
            else -> context?.resources?.getColor(R.color.rank_f) as Int
        }
    }
}