package com.ultron.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class FloatingUltronService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: FrameLayout
    private lateinit var orbContainer: FrameLayout
    private lateinit var statusTextView: TextView
    private lateinit var responseTextView: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    enum class OverlayState { IDLE, LISTENING, PROCESSING, RESPONDING, CLOSED }
    private var currentState = OverlayState.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        textToSpeech = TextToSpeech(this, this)
        initSpeechRecognizer()
        setupOverlayViews()
    }

    private fun startForegroundNotification() {
        val channelId = "ultron_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ULTRON Assistant Daemon",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ULTRON Core Active")
            .setContentText("Floating overlay listening across Android apps")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()

        startForeground(101, notification)
    }

    private fun setupOverlayViews() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        overlayView = FrameLayout(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Floating Orb
        orbContainer = FrameLayout(this).apply {
            val size = (52 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
            setBackgroundColor(Color.BLACK)
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_alert)
            setColorFilter(Color.parseColor("#EF4444"))
        }
        orbContainer.addView(icon)

        statusTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 10f
            text = "ULTRON"
            visibility = View.GONE
        }

        responseTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC110808"))
            setPadding(14, 8, 14, 8)
            textSize = 11f
            visibility = View.GONE
        }

        mainLayout.addView(orbContainer)
        mainLayout.addView(statusTextView)
        mainLayout.addView(responseTextView)
        overlayView.addView(mainLayout)

        // Draggable & Tap Handler
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        onOrbTapped()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun onOrbTapped() {
        when (currentState) {
            OverlayState.IDLE -> startListening()
            OverlayState.LISTENING -> stopListening()
            OverlayState.PROCESSING -> {}
            OverlayState.RESPONDING -> resetToIdle()
            OverlayState.CLOSED -> resetToIdle()
        }
    }

    private fun startListening() {
        currentState = OverlayState.LISTENING
        statusTextView.text = "Listening..."
        statusTextView.visibility = View.VISIBLE
        responseTextView.visibility = View.GONE

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        resetToIdle()
    }

    private fun resetToIdle() {
        currentState = OverlayState.IDLE
        statusTextView.visibility = View.GONE
        responseTextView.visibility = View.GONE
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        handleAssistantQuery(text)
                    }
                    override fun onError(error: Int) { resetToIdle() }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        currentState = OverlayState.PROCESSING
                        statusTextView.text = "Processing..."
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun handleAssistantQuery(query: String) {
        val reply = when {
            query.contains("stop", ignoreCase = true) || query.contains("exit", ignoreCase = true) -> {
                resetToIdle()
                return
            }
            query.contains("status", ignoreCase = true) -> "Ultron overlay active. All sensors operating within normal parameters."
            query.contains("who are you", ignoreCase = true) -> "I am Ultron. Your Android artificial intelligence companion."
            else -> "Directive received: $query. Logic processed."
        }

        currentState = OverlayState.RESPONDING
        statusTextView.visibility = View.GONE
        responseTextView.text = reply
        responseTextView.visibility = View.VISIBLE

        speak(reply)
    }

    private fun speak(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val prefs = getSharedPreferences("ultron_prefs", Context.MODE_PRIVATE)
            val voiceId = prefs.getString("voice_id", "") ?: ""
            val apiKey = prefs.getString("api_key", "") ?: ""

            var playedCustomVoice = false
            if (voiceId.isNotBlank() && apiKey.isNotBlank()) {
                playedCustomVoice = UltronVoicePlayer.speakWithVoiceId(
                    context = this@FloatingUltronService,
                    text = text,
                    voiceId = voiceId,
                    apiKey = apiKey
                )
            }

            // Fallback to offline sinister modulated voice if custom voice is not set or offline
            if (!playedCustomVoice) {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ultron_speech")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setPitch(0.55f)
            textToSpeech?.setSpeechRate(0.92f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
