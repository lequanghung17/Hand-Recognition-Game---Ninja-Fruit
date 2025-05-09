package com.hci.ninjafruitgame

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.hci.ninjafruitgame.view.game.CountdownOverlay
import com.hci.ninjafruitgame.view.game.FruitSliceView
import com.hci.ninjafruitgame.view.game.GameView
import com.hci.ninjafruitgame.view.game.PauseMenuView
import com.hci.ninjafruitgame.view.game.SliceEffectReceiver
import com.hci.ninjafruitgame.view.game.StartScreenView
import kotlin.system.exitProcess
import androidx.core.view.isVisible
import com.hci.ninjafruitgame.posedetector.HandLandmarkListener
import com.hci.ninjafruitgame.posedetector.HandTracker
import com.hci.ninjafruitgame.posedetector.PoseDetectorProcessor
import com.hci.ninjafruitgame.preference.PreferenceUtils
import com.hci.ninjafruitgame.utils.SoundManager
import com.hci.ninjafruitgame.view.vision.CameraSource
import com.hci.ninjafruitgame.view.vision.CameraSourcePreview
import com.hci.ninjafruitgame.view.vision.GraphicOverlay
import java.io.IOException
import java.util.ArrayList
import com.hci.ninjafruitgame.model.GameState as GS

class MainActivity : AppCompatActivity() {
    private var cameraSource: CameraSource? = null
    private var preview: CameraSourcePreview? = null
    private var graphicOverlay: GraphicOverlay? = null

    private var mediaPlayer: MediaPlayer? = null

    private lateinit var fruitSliceView: FruitSliceView
    private lateinit var gameView: GameView
    private lateinit var startScreen: StartScreenView
    private lateinit var countdownOverlay: CountdownOverlay
    private lateinit var pauseMenu: PauseMenuView
    private lateinit var btnPause: ImageView

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enableFullScreen()

        fruitSliceView = findViewById(R.id.view)
        gameView = findViewById(R.id.gameView)
        startScreen = findViewById(R.id.startScreen)
        pauseMenu = findViewById(R.id.pauseMenuContent)
        countdownOverlay = findViewById(R.id.countdownOverlay)
        btnPause = findViewById(R.id.btnPause)

        preview = findViewById(R.id.preview_view)
        if (preview == null) {
            Log.d(TAG, "Preview is null")
        }

        graphicOverlay = findViewById(R.id.graphic_overlay)
        if (graphicOverlay == null) {
            Log.d(TAG, "graphicOverlay is null")
        }

        PreferenceUtils.hideDetectionInfo(this)
        updatePauseMenuBackground()

        SoundManager.init(applicationContext)
        startMusic(applicationContext)



        val bestScore = getSharedPreferences("game_prefs", Context.MODE_PRIVATE).getInt("best_score", 0)
        GS.setBestScore(bestScore)

        startScreen.onStartGame = {
            startScreen.visibility = View.GONE
            gameView.visibility = View.VISIBLE
            pauseMenu.visibility = View.GONE
            gameView.resetGame()
            GS.setGameStarted(true)
            updatePauseMenuBackground()
        }

        startScreen.onOpenSettings = {
            startScreen.playExitAnimation { pauseMenu.show() }
        }


        startScreen.onQuit = {
            AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setMessage("Are you sure you want to quit the game?")
                .setPositiveButton("Yes") { _, _ ->
                    finish()
                    exitProcess(0)
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        pauseMenu.onResume = {
            btnPause.visibility = View.VISIBLE
            if (GS.isGameStarted()) {
                countdownOverlay.startCountdown {
                    GS.setPaused(false)
                }
            } else {
                GS.setPaused(false)
            }
        }

        btnPause.setOnClickListener {
            if (countdownOverlay.isVisible) {
                countdownOverlay.cancelCountdown()
            }

            GS.setPaused(true)
            btnPause.visibility = View.GONE
            pauseMenu.show()
        }

        pauseMenu.onBackToStart = {
            gameView.resetGame()
            GS.setPaused(true)
            gameView.visibility = View.GONE
            startScreen.show()
            GS.setGameStarted(false)
            updatePauseMenuBackground()
        }

        pauseMenu.onRestart = {
            gameView.resetGame()
            GS.setPaused(true)
            countdownOverlay.startCountdown {
                GS.setPaused(false)
                GS.setGameStarted(true)
            }
            btnPause.visibility = View.VISIBLE
        }

        pauseMenu.onBackgroundChange = { index ->
            val resId = when (index) {
                1 -> R.drawable.bg1
                2 -> R.drawable.bg2
                3 -> R.drawable.bg3
                4 -> R.drawable.bg4
                5 -> R.drawable.bg5
                6 -> R.drawable.bg6
                else -> R.drawable.bg1
            }
            gameView.setBackground(resId)
            startScreen.setBackground(resId)
        }

        pauseMenu.onToggleCameraBackground = { enabled ->
            if (GS.isUseHandTracker()) {
                GS.setUseCamera(enabled)
            } else {
                GS.setUseCamera(false)
                Toast.makeText(applicationContext, "Enable hand detection to use this feature", Toast.LENGTH_SHORT).show()
            }
        }

        pauseMenu.onToggleHandDetection = { enabled ->
            GS.setUseHandTracker(enabled)
            GS.setUseCamera(enabled)
            if (enabled) {
                graphicOverlay?.visibility = View.VISIBLE
                preview?.visibility = View.VISIBLE

                createCameraSource()
                startCameraSource()
                Toast.makeText(applicationContext, "Hand detection enabled", Toast.LENGTH_SHORT).show()
            } else {
                preview?.visibility = View.GONE
                graphicOverlay?.visibility = View.GONE
                cameraSource?.stop()
                Toast.makeText(applicationContext, "Hand detection disabled", Toast.LENGTH_SHORT).show()
            }
        }

        pauseMenu.onToggleMusicEnabled = { enable ->
            setMusicEnabled(enable)
        }

        gameView.setOnPauseRequestedListener {
            if (countdownOverlay.isVisible) {
                countdownOverlay.cancelCountdown()
            }

            GS.setPaused(paused = true)
            pauseMenu.visibility = View.VISIBLE
        }

        gameView.setOnGameOverListener {
            btnPause.visibility = View.GONE
            Handler(Looper.getMainLooper()).postDelayed({
                gameView.resetGame()
                GS.setPaused(paused = true)
                gameView.visibility = View.GONE
                startScreen.show()
                updatePauseMenuBackground()
            }, 4000)

        }

        if (!allRuntimePermissionsGranted()) {
            getRuntimePermissions()
        }
    }

    /**
     * Starts or restarts the camera source, if it exists. If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private fun startCameraSource() {
        if (cameraSource != null) {
            try {
                if (preview == null) {
                    Log.d(TAG, "resume: Preview is null")
                }
                if (graphicOverlay == null) {
                    Log.d(TAG, "resume: graphOverlay is null")
                }
                preview!!.start(cameraSource, graphicOverlay)
            } catch (e: IOException) {
                Log.e(TAG, "Unable to start camera source.", e)
                cameraSource!!.release()
                cameraSource = null
            }
        }
    }

    /** Stops the camera. */
    override fun onPause() {
        super.onPause()
        if (GS.isUseHandTracker()) {
            preview?.stop()
        }
        if (GS.isMusicEnabled()) {
            mediaPlayer?.pause()
        }
    }

    public override fun onResume() {
        super.onResume()
        if (GS.isUseHandTracker()) {
            createCameraSource()
            startCameraSource()
        }
        if (GS.isMusicEnabled()) {
            mediaPlayer?.start()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (cameraSource != null) {
            cameraSource?.release()
        }
        getSharedPreferences("game_prefs", Context.MODE_PRIVATE).edit { putInt("best_score", GS.getBestScore()) }
    }


    private fun updatePauseMenuBackground() {
        if (GS.isGameStarted()) {
            btnPause.visibility = View.VISIBLE
        } else {
            btnPause.visibility = View.GONE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
//        if (handDetectionEnabled && !pauseMenu.isVisible) {
//            return true
//        }

        if (!GS.isPaused() || (GS.isPaused() && !GS.isGameStarted())) {
            fruitSliceView.onTouch(ev) // hiển thị hiệu ứng dao
        }
        if (
            ev.actionMasked == MotionEvent.ACTION_DOWN ||
            ev.actionMasked == MotionEvent.ACTION_MOVE ||
            ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            for (i in 0 until ev.pointerCount) {
                val x = ev.getX(i)
                val y = ev.getY(i)

                val receiver: SliceEffectReceiver = when {
                    pauseMenu.isVisible -> pauseMenu
                    !GS.isGameStarted() -> startScreen
                    !GS.isPaused() -> gameView
                    else -> continue
                }

                receiver.onSliceAt(x, y)
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private var handTracker: HandTracker? = null

    private fun createCameraSource() {
        // If there's no existing cameraSource, create one.
        if (cameraSource == null) {
            cameraSource = CameraSource(this, graphicOverlay)
        }
        try {
            val poseDetectorOptions = PreferenceUtils.getPoseDetectorOptionsForLivePreview(this)
            Log.i(TAG, "Using Pose Detector with options $poseDetectorOptions")
            val shouldShowInFrameLikelihood =
                PreferenceUtils.shouldShowPoseDetectionInFrameLikelihoodLivePreview(this)
            val visualizeZ = PreferenceUtils.shouldPoseDetectionVisualizeZ(this)
            val rescaleZ = PreferenceUtils.shouldPoseDetectionRescaleZForVisualization(this)

            handTracker = HandTracker(
                movementThreshold = 3f,
                listener = object : HandLandmarkListener {
                    override fun onHandLandmarksReceived(
                        leftIndexX: Float?, leftIndexY: Float?,
                        rightIndexX: Float?, rightIndexY: Float?
                    ) {
                        processHand("left", leftIndexX, leftIndexY)
                        processHand("right", rightIndexX, rightIndexY)
                    }

                    private fun processHand(
                        hand: String,
                        x: Float?,
                        y: Float?
                    ) {
                        if (x == null || y == null) return

                        when (hand) {
                            "left" -> {
                                gameView.updateLeftHandPosition(x, y)
                                startScreen.updateLeftHandPosition(x, y)
                                pauseMenu.updateLeftHandPosition(x, y)
                            }
                            "right" -> {
                                gameView.updateRightHandPosition(x, y)
                                startScreen.updateRightHandPosition(x, y)
                                pauseMenu.updateRightHandPosition(x, y)
                            }
                        }
                        fruitSliceView.registerHandSlice(if (hand == "left") 1001 else 1002, x, y) // nếu có
                        if (!GS.isGameOver()) {
                            // Gọi slice effect như dispatchTouchEvent
                            val receiver: SliceEffectReceiver = when {
                                pauseMenu.isVisible -> pauseMenu
                                !GS.isGameStarted() -> startScreen
                                !GS.isPaused() -> gameView
                                else -> return
                            }
                            receiver.onSliceAt(x, y)
                        }

                    }
                }
            )



            val processor = PoseDetectorProcessor(
                this,
                poseDetectorOptions,
                shouldShowInFrameLikelihood,
                visualizeZ,
                rescaleZ
            )

            processor.setHandLandmarkListener(object : HandLandmarkListener {
                override fun onHandLandmarksReceived(
                    leftIndexX: Float?, leftIndexY: Float?,
                    rightIndexX: Float?, rightIndexY: Float?
                ) {
                    handTracker?.update(leftIndexX, leftIndexY, rightIndexX, rightIndexY)
                }
            })

            cameraSource!!.setMachineLearningFrameProcessor(processor)
        } catch (e: Exception) {
            Log.e(TAG, "Can not create image processor", e)
            Toast.makeText(
                applicationContext,
                "Can not create image processor: " + e.message,
                Toast.LENGTH_LONG
            )
                .show()
        }
    }

    private fun enableFullScreen() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun allRuntimePermissionsGranted(): Boolean {
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(this, it)) {
                    return false
                }
            }
        }
        return true
    }

    private fun getRuntimePermissions() {
        val permissionsToRequest = ArrayList<String>()
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(this, it)) {
                    permissionsToRequest.add(permission)
                }
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUESTS
            )
        }
    }

    private fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Permission granted: $permission")
            return true
        }
        Log.i(TAG, "Permission NOT granted: $permission")
        return false
    }

    fun startMusic(context: Context) {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(context, R.raw.theme_song)
            mediaPlayer?.isLooping = true
        }
        if (!GS.isMusicEnabled()) {
            mediaPlayer?.start()
        }
    }

    fun setMusicEnabled(enable: Boolean) {
        GS.setMusicEnabled(enable)
        if (mediaPlayer == null) {
            return
        }
        if (enable){
            mediaPlayer?.start()
        } else {
            mediaPlayer?.pause()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUESTS = 1

        private val REQUIRED_RUNTIME_PERMISSIONS =
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
    }
}


