package run.bestmodel.dictate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating mic bubble over every app.
 * Tap: start / stop. Drag: move. Hold: close.
 * While you talk, a panel at the top shows each phrase as soon as you pause.
 */
class BubbleService : Service() {

    private enum class State { LOADING, NO_MODEL, IDLE, RECORDING, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dictate-asr") }
    private lateinit var prefs: Prefs
    private lateinit var windows: WindowManager

    private lateinit var bubble: FrameLayout
    private lateinit var bubbleBg: GradientDrawable
    private lateinit var panel: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams

    @Volatile private var engine: SpeechEngine? = null
    private var session: SpeechEngine.Session? = null
    private var recorder: Recorder? = null
    private var state = State.LOADING
    private val live = StringBuilder()
    private var lastText = ""

    private val hidePanel = Runnable { panel.visibility = View.GONE }
    private val autoStop = Runnable { if (state == State.RECORDING) stopRecording() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        windows = getSystemService(WindowManager::class.java)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        running = true
        addPanel()
        addBubble()
        loadEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY // restarting from the background is not allowed to use the mic
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recorder?.stop()
        runCatching { windows.removeView(bubble) }
        runCatching { windows.removeView(panel) }
        val openSession = session
        worker.execute {
            openSession?.finish()
            engine?.release()
            engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    // --- engine --------------------------------------------------------------------------

    private fun loadEngine() {
        val store = ModelStore(this)
        if (!store.isReady()) {
            setState(State.NO_MODEL)
            return
        }
        setState(State.LOADING)
        worker.execute {
            runCatching { SpeechEngine(store.dir) }
                .onSuccess {
                    if (!running) return@onSuccess it.release() // bubble closed while loading
                    engine = it
                    main.post { setState(State.IDLE) }
                }
                .onFailure { error ->
                    main.post {
                        toast("Falha ao carregar o modelo: ${error.message}")
                        setState(State.NO_MODEL)
                    }
                }
        }
    }

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.LOADING -> toast("Carregando o modelo…")
            State.PROCESSING -> toast("Terminando a transcrição…")
            State.NO_MODEL -> {
                toast("Baixe o modelo primeiro")
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun startRecording() {
        val engine = engine ?: return
        live.clear()
        showPanel("Ouvindo…", autoHide = false)
        val newSession = engine.newSession { phrase ->
            main.post {
                if (live.isNotEmpty()) live.append(' ')
                live.append(phrase)
                showPanel(live.toString(), autoHide = false)
            }
        }
        session = newSession
        val newRecorder = Recorder { block, level ->
            worker.execute { newSession.accept(block) }
            main.post { if (state == State.RECORDING) pulse(level) }
        }
        try {
            newRecorder.start()
        } catch (e: Exception) {
            toast(e.message ?: "Microfone indisponível")
            session = null
            hidePanel.run()
            return
        }
        recorder = newRecorder
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setState(State.RECORDING)
        main.postDelayed(autoStop, MAX_RECORDING_MS)
    }

    private fun stopRecording() {
        main.removeCallbacks(autoStop)
        recorder?.stop()
        recorder = null
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setState(State.PROCESSING)
        val finishing = session ?: return
        session = null
        val options = prefs.polishOptions
        worker.execute {
            val text = TextPolisher.polish(finishing.finish(), options)
            main.post { deliver(text) }
        }
    }

    private fun deliver(text: String) {
        if (state != State.PROCESSING) return
        setState(State.IDLE)
        if (text.isBlank()) {
            showPanel("Não ouvi nada", autoHide = true)
            return
        }
        lastText = text
        val inserted = prefs.autoInsert && InsertService.instance?.insert(text) == true
        if (!inserted) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ditado", text))
        }
        showPanel(text + if (inserted) "\n\n✓ inserido" else "\n\n✓ copiado — cole onde quiser", autoHide = true)
    }

    // --- views ---------------------------------------------------------------------------

    private fun addBubble() {
        val size = dp(60)
        bubbleBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        bubble = FrameLayout(this).apply {
            background = bubbleBg
            elevation = dp(6).toFloat()
            addView(
                ImageView(context).apply { setImageResource(R.drawable.ic_mic) },
                FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER),
            )
            contentDescription = "Ditado"
        }
        val screenWidth = resources.displayMetrics.widthPixels
        bubbleParams = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX.takeIf { it >= 0 } ?: (screenWidth - size - dp(12))
            y = prefs.bubbleY
        }
        bubble.setOnTouchListener(DragListener())
        windows.addView(bubble, bubbleParams)
    }

    private fun addPanel() {
        panel = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            maxLines = 8
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xE6202124.toInt())
            }
            visibility = View.GONE
            setOnClickListener {
                if (state == State.IDLE && lastText.isNotEmpty()) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ditado", lastText))
                    toast("Copiado")
                }
                visibility = View.GONE
            }
        }
        val width = resources.displayMetrics.widthPixels - dp(32)
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        windows.addView(panel, params)
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never take focus: the text field in the app below must keep it so we can type into it.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun showPanel(text: String, autoHide: Boolean) {
        main.removeCallbacks(hidePanel)
        panel.text = text
        panel.visibility = View.VISIBLE
        if (autoHide) main.postDelayed(hidePanel, PANEL_MS)
    }

    private fun setState(newState: State) {
        state = newState
        bubbleBg.setColor(
            when (newState) {
                State.LOADING, State.NO_MODEL -> 0xFF5F6368.toInt()
                State.IDLE -> 0xFF1A73E8.toInt()
                State.RECORDING -> 0xFFD93025.toInt()
                State.PROCESSING -> 0xFFF29900.toInt()
            },
        )
        bubble.alpha = if (newState == State.LOADING) 0.6f else 1f
        if (newState != State.RECORDING) bubble.scaleX = 1f.also { bubble.scaleY = it }
    }

    private fun pulse(level: Float) {
        val scale = 1f + (level * 6f).coerceIn(0f, 0.25f)
        bubble.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
    }

    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toast("Bolha fechada")
            stopSelf()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        windows.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        prefs.bubbleX = bubbleParams.x
                        prefs.bubbleY = bubbleParams.y
                    } else if (!longPressed && abs(event.rawX - downX) <= slop) {
                        view.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    // --- misc ----------------------------------------------------------------------------

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bolha de ditado", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Ditado ativo")
            .setContentText("Toque na bolha para falar. Tudo fica no aparelho.")
            .setContentIntent(open)
            .addAction(0, "Fechar bolha", stop)
            .setOngoing(true)
            .build()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "run.bestmodel.dictate.STOP"
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 1
        private const val MAX_RECORDING_MS = 10 * 60 * 1000L
        private const val PANEL_MS = 8_000L
        private const val LONG_PRESS_MS = 700L

        @Volatile var running = false
            private set
    }
}
