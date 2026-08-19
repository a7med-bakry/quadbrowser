package com.example.quadbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar
import java.util.concurrent.TimeUnit

private enum class DataMode(val title: String) {
    NORMAL("عادي"),
    DATA_SAVER("توفير البيانات"),
    MAX_DATA_SAVER("توفير البيانات الأقصى")
}

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PICK_IMPORT_FILE = 1001
        private const val PICK_EXPORT_FILE = 1002
        private const val MAX_LINKS = 10
        private const val PAGE_COUNT = 8
        private const val ROTATION_MINUTES = 25L
        private const val MAX_SHARED_LINK_SETS = 30L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val browsers = ArrayList<WebView>()
    private val linkInputs = ArrayList<EditText>()
    private val durationInputs = ArrayList<EditText>()

    private lateinit var drawer: FrameLayout
    private lateinit var drawerDim: View
    private lateinit var hamburger: TextView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var rotationText: TextView
    private lateinit var startTimeInput: EditText
    private lateinit var nowCheck: CheckBox
    private lateinit var refreshInput: EditText
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var currentUrlBar: TextView
    private lateinit var modeButton: Button
    private lateinit var accountTitle: TextView
    private lateinit var accountAction: Button
    private lateinit var cloudImportButton: Button
    private lateinit var cloudPublishButton: Button

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val authListener = FirebaseAuth.AuthStateListener { updateAccountUi() }

    private var scheduleRunning = false
    private var schedulePaused = false
    private var scheduleStartMs = 0L
    private var pausedAtMs = 0L
    private var currentIndex = -1
    private var currentDataMode = DataMode.NORMAL
    private var modeStartedMs = 0L
    private var pausedRemainingMs = 0L
    private var pausedModeRemainingMs = 0L
    private var refreshSeconds = 30L
    private var urls = emptyList<String>()
    private var durationsMs = emptyList<Long>()
    private var activeScheduledUrl = ""
    private var loadGeneration = 0L

    private val scheduleTask = object : Runnable {
        override fun run() {
            if (!scheduleRunning || schedulePaused) return

            val now = System.currentTimeMillis()
            if (now < scheduleStartMs) {
                statusText.text = "في الانتظار"
                countdownText.text = "يبدأ خلال ${formatDuration(scheduleStartMs - now)}"
                handler.postDelayed(this, 1000L)
                return
            }

            val elapsedFromStart = now - scheduleStartMs
            val totalDuration = durationsMs.sum()
            if (elapsedFromStart >= totalDuration) {
                finishSchedule()
                return
            }

            var elapsed = elapsedFromStart
            var index = 0
            while (index < durationsMs.size && elapsed >= durationsMs[index]) {
                elapsed -= durationsMs[index]
                index++
            }

            if (index >= urls.size) {
                finishSchedule()
                return
            }

            if (currentIndex != index) {
                currentIndex = index
                setActiveScheduledUrl(urls[index], currentDataMode)
                statusText.text = "الرابط ${index + 1} من ${urls.size}"
            }

            countdownText.text = "متبقي للرابط الحالي: ${formatDuration(durationsMs[index] - elapsed)}"
            handler.postDelayed(this, 1000L)
        }
    }

    private val refreshTask = object : Runnable {
        override fun run() {
            if (!scheduleRunning || schedulePaused) return
            if (currentIndex >= 0) browsers.forEach { it.reload() }
            handler.postDelayed(this, TimeUnit.SECONDS.toMillis(refreshSeconds.coerceAtLeast(1L)))
        }
    }

    private val rotationTask = object : Runnable {
        override fun run() {
            if (!scheduleRunning || schedulePaused || scheduleStartMs > System.currentTimeMillis()) {
                if (scheduleRunning && !schedulePaused) handler.postDelayed(this, 1000L)
                return
            }

            val now = System.currentTimeMillis()
            if (now - modeStartedMs >= TimeUnit.MINUTES.toMillis(ROTATION_MINUTES)) {
                val nextIndex = (currentDataMode.ordinal + 1) % DataMode.values().size
                currentDataMode = DataMode.values()[nextIndex]
                modeStartedMs = now
                applyDataMode(currentDataMode)
                if (currentIndex >= 0) setActiveScheduledUrl(urls[currentIndex], currentDataMode)
                updateModeLabel()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()
        restoreSettings()
        updateModeLabel()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 4
            setBackgroundColor(Color.BLACK)
            useDefaultMargins = false
        }

        repeat(PAGE_COUNT) { index ->
            val web = createWebView(index)
            browsers.add(web)
            val cell = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                setPadding(1.dp(), 1.dp(), 1.dp(), 1.dp())
                addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(index % 2, 1, 1f)
                rowSpec = GridLayout.spec(index / 2, 1, 1f)
                setMargins(1.dp(), 1.dp(), 1.dp(), 1.dp())
            }
            grid.addView(cell, params)
        }
        root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = 44.dp()
        })

        currentUrlBar = TextView(this).apply {
            text = "الرابط الحالي: —"
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(12.dp(), 0, 12.dp(), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            background = rounded(Color.parseColor("#E0101010"), 12)
            elevation = 10.dp().toFloat()
            contentDescription = "الرابط الحالي"
        }
        root.addView(currentUrlBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40.dp()).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 8.dp()
            marginStart = 68.dp()
            marginEnd = 132.dp()
        })

        modeButton = Button(this).apply {
            text = currentDataMode.title
            textSize = 10f
            setPadding(4.dp(), 0, 4.dp(), 0)
            minHeight = 0
            minWidth = 0
            background = rounded(Color.parseColor("#CC1F5FA8"), 12)
            setTextColor(Color.WHITE)
            contentDescription = "تغيير وضع البيانات"
            setOnClickListener { cycleDataMode(manual = true) }
        }
        root.addView(modeButton, FrameLayout.LayoutParams(116.dp(), 40.dp()).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 8.dp()
            marginEnd = 8.dp()
        })

        hamburger = TextView(this).apply {
            text = "☰"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#CC111111"), 14)
            elevation = 12.dp().toFloat()
            contentDescription = "فتح القائمة"
            setOnClickListener { openDrawer() }
        }
        root.addView(hamburger, FrameLayout.LayoutParams(54.dp(), 54.dp()).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 8.dp()
            marginStart = 8.dp()
        })

        buildDrawer(root)
        setContentView(root)
        auth.addAuthStateListener(authListener)
        updateAccountUi()
    }

    private fun buildDrawer(root: FrameLayout) {
        drawerDim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(drawerDim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        drawer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = 24.dp().toFloat()
            visibility = View.GONE
        }
        root.addView(drawer, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })

        val scroll = ScrollView(this)
        drawer.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 28.dp())
        }
        scroll.addView(content)

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "متصفح بكري"
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(20, 28, 36))
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        val close = TextView(this).apply {
            text = "✕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setOnClickListener { closeDrawer() }
        }
        titleRow.addView(close, LinearLayout.LayoutParams(52.dp(), 52.dp()))
        content.addView(titleRow)

        val subtitle = TextView(this).apply {
            text = "10 روابط كحد أقصى • 8 صفحات • تبديل الوضع كل 25 دقيقة: عادي → توفير البيانات → توفير البيانات الأقصى"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 12.dp())
        }
        content.addView(subtitle)

        val accountCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = rounded(Color.rgb(244, 247, 250), 12)
        }
        accountTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(25, 32, 40))
        }
        accountCard.addView(accountTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 32.dp()))
        accountAction = Button(this).apply {
            textSize = 12f
            minHeight = 0
            setOnClickListener { showAuthDialog() }
        }
        accountCard.addView(accountAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp()))
        content.addView(accountCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp() })

        val cloudRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cloudPublishButton = Button(this).apply {
            text = "حفظ للمستخدمين"
            textSize = 11f
            minHeight = 0
            setOnClickListener { publishLinksToCloud() }
        }
        cloudImportButton = Button(this).apply {
            text = "استيراد من المستخدمين"
            textSize = 11f
            minHeight = 0
            setOnClickListener { importSharedLinks() }
        }
        cloudRow.addView(cloudPublishButton, LinearLayout.LayoutParams(0, 48.dp(), 1f))
        cloudRow.addView(cloudImportButton, LinearLayout.LayoutParams(0, 48.dp(), 1f).apply { marginStart = 6.dp() })
        content.addView(cloudRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 54.dp()))

        repeat(MAX_LINKS) { index ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val number = TextView(this).apply {
                text = "${index + 1}"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(35, 94, 170), 9)
            }
            row.addView(number, LinearLayout.LayoutParams(34.dp(), 46.dp()))

            val url = EditText(this).apply {
                hint = "الرابط"
                textSize = 13f
                setTextColor(Color.rgb(25, 30, 35))
                setHintTextColor(Color.GRAY)
                setPadding(10.dp(), 0, 10.dp(), 0)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                background = roundedBorder()
            }
            linkInputs.add(url)
            row.addView(url, LinearLayout.LayoutParams(0, 46.dp(), 1f).apply { marginStart = 7.dp() })

            val duration = EditText(this).apply {
                hint = "دقائق"
                textSize = 12f
                gravity = Gravity.CENTER
                setText("90")
                setTextColor(Color.rgb(25, 30, 35))
                setHintTextColor(Color.GRAY)
                setPadding(4.dp(), 0, 4.dp(), 0)
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                background = roundedBorder()
            }
            durationInputs.add(duration)
            row.addView(duration, LinearLayout.LayoutParams(70.dp(), 46.dp()).apply { marginStart = 7.dp() })
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dp()))
        }

        content.addView(divider())
        content.addView(label("بدء التشغيل"))

        val startRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startTimeInput = EditText(this).apply {
            hint = "HH:MM"
            textSize = 14f
            setText("13:30")
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            inputType = InputType.TYPE_CLASS_DATETIME
            setSingleLine(true)
            setPadding(5.dp(), 0, 5.dp(), 0)
            background = roundedBorder()
        }
        startRow.addView(startTimeInput, LinearLayout.LayoutParams(90.dp(), 48.dp()))
        nowCheck = CheckBox(this).apply {
            text = "ابدأ الآن"
            textSize = 13f
            isChecked = true
            setOnCheckedChangeListener { _, checked -> startTimeInput.isEnabled = !checked }
        }
        startRow.addView(nowCheck, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48.dp()))
        content.addView(startRow)

        val refreshRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        refreshRow.addView(label("تحديث الصفحات كل"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48.dp()))
        refreshInput = EditText(this).apply {
            setText("30")
            hint = "ثواني"
            textSize = 14f
            gravity = Gravity.CENTER
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.DKGRAY)
            background = roundedBorder()
        }
        refreshRow.addView(refreshInput, LinearLayout.LayoutParams(75.dp(), 48.dp()).apply { marginStart = 8.dp() })
        refreshRow.addView(TextView(this).apply { text = "ثانية"; textSize = 13f; setTextColor(Color.GRAY) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48.dp()).apply { marginStart = 6.dp() })
        content.addView(refreshRow)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startButton = Button(this).apply { text = "ابدأ"; setOnClickListener { startSchedule() } }
        pauseButton = Button(this).apply { text = "إيقاف مؤقت"; isEnabled = false; setOnClickListener { togglePause() } }
        stopButton = Button(this).apply { text = "إيقاف"; isEnabled = false; setOnClickListener { stopSchedule() } }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        buttons.addView(pauseButton, LinearLayout.LayoutParams(0, 52.dp(), 1f).apply { marginStart = 6.dp() })
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, 52.dp(), 1f).apply { marginStart = 6.dp() })
        content.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60.dp()))

        val importExport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val export = Button(this).apply { text = "تصدير الروابط"; setOnClickListener { exportLinks() } }
        val import = Button(this).apply { text = "استيراد الروابط"; setOnClickListener { importLinks() } }
        importExport.addView(export, LinearLayout.LayoutParams(0, 50.dp(), 1f))
        importExport.addView(import, LinearLayout.LayoutParams(0, 50.dp(), 1f).apply { marginStart = 8.dp() })
        content.addView(importExport)

        statusText = TextView(this).apply {
            text = "جاهز"
            textSize = 13f
            setTextColor(Color.rgb(35, 94, 170))
            setTypeface(typeface, Typeface.BOLD)
        }
        content.addView(statusText)
        countdownText = TextView(this).apply {
            text = "أدخل الروابط واضغط ابدأ"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5.dp(), 0, 0)
        }
        content.addView(countdownText)
        rotationText = TextView(this).apply {
            text = "الوضع: عادي • التبديل كل 25 دقيقة"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4.dp(), 0, 0)
        }
        content.addView(rotationText)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(30, 35, 40))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(index: Int): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    view.loadUrl(request.url.toString())
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.contentDescription = "صفحة ${index + 1}: $url"
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    private fun startSchedule() {
        val parsed = ArrayList<String>()
        val durations = ArrayList<Long>()
        for (i in 0 until MAX_LINKS) {
            val rawUrl = linkInputs[i].text.toString().trim()
            if (rawUrl.isBlank()) continue
            val mins = durationInputs[i].text.toString().trim().toLongOrNull() ?: 90L
            if (mins < 1) {
                Toast.makeText(this, "مدة الرابط ${i + 1} غير صحيحة", Toast.LENGTH_SHORT).show()
                return
            }
            parsed.add(normalizeUrl(rawUrl))
            durations.add(TimeUnit.MINUTES.toMillis(mins))
        }
        if (parsed.isEmpty()) {
            Toast.makeText(this, "أدخل رابطًا واحدًا على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        refreshSeconds = refreshInput.text.toString().trim().toLongOrNull() ?: 30L
        if (refreshSeconds < 1L) {
            Toast.makeText(this, "التحديث يجب أن يكون ثانية واحدة أو أكثر", Toast.LENGTH_SHORT).show()
            return
        }

        val start = if (nowCheck.isChecked) System.currentTimeMillis() else parseStartTime(startTimeInput.text.toString().trim())
            ?: run { Toast.makeText(this, "اكتب الوقت مثل 13:30", Toast.LENGTH_SHORT).show(); return }

        urls = parsed
        durationsMs = durations
        scheduleStartMs = start
        currentIndex = -1
        // Keep the phone screen awake for as long as the schedule is running.
        // FLAG_KEEP_SCREEN_ON works while this Activity is visible and does not need a permission.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        scheduleRunning = true
        schedulePaused = false
        modeStartedMs = start
        applyDataMode(currentDataMode)
        pausedRemainingMs = 0L
        pausedModeRemainingMs = 0L

        saveSettings()
        updateButtons()
        setInputsEnabled(false)
        closeDrawer()
        handler.removeCallbacks(scheduleTask)
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(rotationTask)
        scheduleTask.run()
        handler.postDelayed(refreshTask, TimeUnit.SECONDS.toMillis(refreshSeconds))
        handler.postDelayed(rotationTask, 1000L)
    }

    private fun togglePause() {
        if (!scheduleRunning) return
        if (!schedulePaused) {
            pausedAtMs = System.currentTimeMillis()
            pausedRemainingMs = remainingTotalMs(pausedAtMs)
            val modeElapsed = (pausedAtMs - modeStartedMs).coerceAtLeast(0L)
            pausedModeRemainingMs =
                (TimeUnit.MINUTES.toMillis(ROTATION_MINUTES) - modeElapsed).coerceAtLeast(0L)
            schedulePaused = true
            handler.removeCallbacks(scheduleTask)
            handler.removeCallbacks(refreshTask)
            handler.removeCallbacks(rotationTask)
            statusText.text = "متوقف مؤقتًا"
            countdownText.text = "متبقي عند الاستئناف: ${formatDuration(remainingCurrentLinkMs(pausedAtMs))}"
        } else {
            val now = System.currentTimeMillis()
            scheduleStartMs = now - (totalDurationMs() - pausedRemainingMs)
            val modeDuration = TimeUnit.MINUTES.toMillis(ROTATION_MINUTES)
            modeStartedMs = now - (modeDuration - pausedModeRemainingMs).coerceAtLeast(0L)
            schedulePaused = false
            statusText.text = "تم الاستئناف"
            handler.removeCallbacks(scheduleTask)
            handler.removeCallbacks(refreshTask)
            handler.removeCallbacks(rotationTask)
            scheduleTask.run()
            handler.postDelayed(refreshTask, TimeUnit.SECONDS.toMillis(refreshSeconds))
            handler.postDelayed(rotationTask, 1000L)
        }
        updateButtons()
    }

    private fun stopSchedule() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scheduleRunning = false
        schedulePaused = false
        handler.removeCallbacks(scheduleTask)
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(rotationTask)
        statusText.text = "متوقف"
        countdownText.text = "تم إيقاف الجدولة."
        currentIndex = -1
        activeScheduledUrl = ""
        loadGeneration++
        browsers.forEach { web ->
            web.stopLoading()
            web.loadUrl("about:blank")
            web.clearHistory()
            web.clearFormData()
        }
        currentUrlBar.text = "الرابط الحالي: —"
        currentDataMode = DataMode.NORMAL
        applyDataMode(currentDataMode)
        updateModeLabel()
        updateButtons()
        setInputsEnabled(true)
    }

    private fun finishSchedule() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scheduleRunning = false
        schedulePaused = false
        handler.removeCallbacks(scheduleTask)
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(rotationTask)
        loadGeneration++
        statusText.text = "اكتمل الجدول"
        countdownText.text = "تم تشغيل كل الروابط المحددة."
        updateButtons()
        setInputsEnabled(true)
    }

    private fun updateButtons() {
        startButton.isEnabled = !scheduleRunning
        pauseButton.isEnabled = scheduleRunning
        stopButton.isEnabled = scheduleRunning
        pauseButton.text = if (schedulePaused) "استئناف" else "إيقاف مؤقت"
    }

    private fun setInputsEnabled(enabled: Boolean) {
        linkInputs.forEach { it.isEnabled = enabled }
        durationInputs.forEach { it.isEnabled = enabled }
        startTimeInput.isEnabled = enabled && !nowCheck.isChecked
        nowCheck.isEnabled = enabled
        refreshInput.isEnabled = enabled
    }

    private fun updateModeLabel() {
        rotationText.text = "الوضع: ${currentDataMode.title} • التبديل كل 25 دقيقة"
        if (::modeButton.isInitialized) modeButton.text = currentDataMode.title
    }

    private fun cycleDataMode(manual: Boolean = false) {
        val nextIndex = (currentDataMode.ordinal + 1) % DataMode.values().size
        currentDataMode = DataMode.values()[nextIndex]
        if (scheduleRunning) {
            modeStartedMs = System.currentTimeMillis()
            applyDataMode(currentDataMode)
            if (currentIndex >= 0 && currentIndex < urls.size) setActiveScheduledUrl(urls[currentIndex], currentDataMode)
        } else {
            applyDataMode(currentDataMode)
        }
        updateModeLabel()
        if (manual) Toast.makeText(this, "تم تغيير الوضع إلى: ${currentDataMode.title}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Applies the selected data-saving profile to every WebView.
     *
     * Normal: full page loading.
     * Data saver: images are disabled and cached resources are preferred.
     * Max data saver: images and media are disabled and cached resources are
     * preferred as aggressively as WebView allows, while JavaScript remains
     * enabled so modern sites can still function.
     */
    private fun applyDataMode(mode: DataMode) {
        browsers.forEach { web ->
            when (mode) {
                DataMode.NORMAL -> {
                    web.settings.loadsImagesAutomatically = true
                    web.settings.blockNetworkImage = false
                    web.settings.mediaPlaybackRequiresUserGesture = false
                    web.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }

                DataMode.DATA_SAVER -> {
                    web.settings.loadsImagesAutomatically = false
                    web.settings.blockNetworkImage = true
                    web.settings.mediaPlaybackRequiresUserGesture = true
                    web.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                }

                DataMode.MAX_DATA_SAVER -> {
                    web.settings.loadsImagesAutomatically = false
                    web.settings.blockNetworkImage = true
                    web.settings.mediaPlaybackRequiresUserGesture = true
                    web.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    // Keep the navigation state clean while the strictest
                    // data profile is active.
                    web.clearHistory()
                    web.clearFormData()
                }
            }
        }
    }

    /**
     * There is ONE authoritative URL for the whole 8-page grid.
     * Every transition updates this value first, then all WebViews are forced
     * to load that exact value. A short verification pass catches a WebView
     * that was still busy with the previous page and reloads it.
     */
    private fun setActiveScheduledUrl(url: String, mode: DataMode) {
        val previousUrl = activeScheduledUrl
        activeScheduledUrl = url
        loadGeneration++
        val generation = loadGeneration
        currentUrlBar.text = "الرابط الحالي: $url"

        applyDataMode(mode)

        // Hard transition: stop every previous navigation, blank the WebViews,
        // clear their navigation history, then load only the new scheduled URL.
        // This prevents the previous link from continuing after its slot ends.
        browsers.forEach { web ->
            web.stopLoading()
            web.loadUrl("about:blank")
            web.clearHistory()
            web.clearFormData()
        }

        browsers.forEachIndexed { index, web ->
            web.postDelayed({
                if (generation == loadGeneration &&
                    scheduleRunning &&
                    activeScheduledUrl == url) {
                    web.stopLoading()
                    web.loadUrl(url)
                }
            }, 150L + index * 120L)
        }

        // Verification passes catch a WebView that was still finishing the
        // previous navigation when the slot changed.
        handler.postDelayed({
            verifyAllPagesForActiveUrl(generation, url, previousUrl)
        }, 2500L)
        handler.postDelayed({
            verifyAllPagesForActiveUrl(generation, url, previousUrl)
        }, 6500L)
    }

    private fun verifyAllPagesForActiveUrl(
        generation: Long,
        targetUrl: String,
        previousUrl: String
    ) {
        if (generation != loadGeneration || !scheduleRunning || activeScheduledUrl != targetUrl) return

        browsers.forEach { web ->
            val shown = web.url.orEmpty()
            if (shown.isBlank() || shown == "about:blank") {
                web.stopLoading()
                web.loadUrl(targetUrl)
                return@forEach
            }

            // A redirect from the target is allowed. Only an exact match to
            // the previous scheduled URL is considered stale.
            if (previousUrl.isNotBlank() && sameUrl(shown, previousUrl)) {
                web.stopLoading()
                web.loadUrl(targetUrl)
            }
        }
    }

    private fun sameUrl(a: String, b: String): Boolean {
        fun normalize(value: String): String = value.trim().trimEnd('/').lowercase()
        return normalize(a) == normalize(b)
    }

    private fun remainingCurrentLinkMs(now: Long): Long {
        if (currentIndex !in durationsMs.indices) return pausedRemainingMs.coerceAtLeast(0L)
        val elapsedFromStart = (now - scheduleStartMs).coerceAtLeast(0L)
        var elapsed = elapsedFromStart
        var index = 0
        while (index < durationsMs.size && elapsed >= durationsMs[index]) {
            elapsed -= durationsMs[index]
            index++
        }
        return if (index in durationsMs.indices) (durationsMs[index] - elapsed).coerceAtLeast(0L) else 0L
    }

    private fun remainingTotalMs(now: Long): Long {
        return (totalDurationMs() - (now - scheduleStartMs)).coerceAtLeast(0L)
    }

    private fun totalDurationMs(): Long = durationsMs.sum()

    private fun parseStartTime(value: String): Long? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun updateAccountUi() {
        val user = auth.currentUser
        val signedIn = user != null
        if (!::accountTitle.isInitialized) return
        if (signedIn) {
            accountTitle.text = "الحساب: ${user?.displayName?.ifBlank { null } ?: user?.email ?: "مستخدم"}"
            accountAction.text = "تسجيل الخروج"
            accountAction.setOnClickListener { auth.signOut(); Toast.makeText(this, "تم تسجيل الخروج", Toast.LENGTH_SHORT).show() }
            cloudPublishButton.isEnabled = true
            cloudImportButton.isEnabled = true
        } else {
            accountTitle.text = "الحساب: غير مسجل"
            accountAction.text = "تسجيل الدخول / إنشاء حساب"
            accountAction.setOnClickListener { showAuthDialog() }
            cloudPublishButton.isEnabled = false
            cloudImportButton.isEnabled = false
        }
    }

    private fun showAuthDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 8.dp(), 24.dp(), 4.dp())
        }
        val name = EditText(this).apply { hint = "الاسم (لإنشاء حساب جديد فقط)"; setSingleLine(true) }
        val email = EditText(this).apply { hint = "البريد الإلكتروني"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setSingleLine(true) }
        val password = EditText(this).apply { hint = "كلمة المرور"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true) }
        box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dp()))
        box.addView(email, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dp()))
        box.addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dp()))

        val dialog = AlertDialog.Builder(this)
            .setTitle("حساب متصفح بكري")
            .setView(box)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("دخول", null)
            .setNeutralButton("إنشاء حساب", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val e = email.text.toString().trim()
                val p = password.text.toString()
                if (e.isBlank() || p.length < 6) { Toast.makeText(this, "اكتب بريدًا صحيحًا وكلمة مرور 6 أحرف على الأقل", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                auth.signInWithEmailAndPassword(e, p).addOnSuccessListener { dialog.dismiss(); Toast.makeText(this, "تم تسجيل الدخول", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { Toast.makeText(this, "فشل الدخول: ${it.localizedMessage ?: "تحقق من البيانات"}", Toast.LENGTH_LONG).show() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val n = name.text.toString().trim()
                val e = email.text.toString().trim()
                val p = password.text.toString()
                if (n.isBlank() || e.isBlank() || p.length < 6) { Toast.makeText(this, "اكتب الاسم والبريد وكلمة مرور 6 أحرف على الأقل", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                auth.createUserWithEmailAndPassword(e, p).addOnSuccessListener { result ->
                    result.user?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(n).build())
                    dialog.dismiss()
                    Toast.makeText(this, "تم إنشاء الحساب", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener { Toast.makeText(this, "تعذر إنشاء الحساب: ${it.localizedMessage ?: "تحقق من البيانات"}", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }

    private fun publishLinksToCloud() {
        val user = auth.currentUser ?: run { showAuthDialog(); return }
        val entries = (0 until MAX_LINKS).mapNotNull { i ->
            val url = linkInputs[i].text.toString().trim()
            if (url.isBlank()) null else hashMapOf<String, Any>("url" to normalizeUrl(url), "duration" to (durationInputs[i].text.toString().trim().toLongOrNull() ?: 90L))
        }
        if (entries.isEmpty()) { Toast.makeText(this, "أدخل رابطًا واحدًا على الأقل", Toast.LENGTH_SHORT).show(); return }
        val data = hashMapOf<String, Any>(
            "ownerUid" to user.uid,
            "ownerName" to (user.displayName?.ifBlank { null } ?: "مستخدم"),
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "links" to entries
        )
        cloudPublishButton.isEnabled = false
        firestore.collection("shared_links").add(data)
            .addOnSuccessListener { Toast.makeText(this, "تم حفظ مجموعة الروابط للمستخدمين", Toast.LENGTH_SHORT).show(); cloudPublishButton.isEnabled = true }
            .addOnFailureListener { Toast.makeText(this, "تعذر الحفظ: ${it.localizedMessage ?: "تحقق من Firestore"}", Toast.LENGTH_LONG).show(); cloudPublishButton.isEnabled = true }
    }

    private fun importSharedLinks() {
        if (auth.currentUser == null) { showAuthDialog(); return }
        cloudImportButton.isEnabled = false
        firestore.collection("shared_links").limit(MAX_SHARED_LINK_SETS).get()
            .addOnSuccessListener { snapshot ->
                cloudImportButton.isEnabled = true
                if (snapshot.isEmpty) { Toast.makeText(this, "لا توجد مجموعات روابط منشورة بعد", Toast.LENGTH_SHORT).show(); return@addOnSuccessListener }
                val docs = snapshot.documents
                val labels = docs.map { doc -> "${doc.getString("ownerName") ?: "مستخدم"} • ${doc.id.take(6)}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("استيراد روابط من مستخدم")
                    .setItems(labels) { _, which -> applySharedDocument(docs[which]) }
                    .setNegativeButton("إلغاء", null).show()
            }
            .addOnFailureListener { cloudImportButton.isEnabled = true; Toast.makeText(this, "تعذر جلب الروابط: ${it.localizedMessage ?: "تحقق من Firestore"}", Toast.LENGTH_LONG).show() }
    }

    private fun applySharedDocument(doc: com.google.firebase.firestore.DocumentSnapshot) {
        val raw = doc.get("links") as? List<*> ?: return
        var count = 0
        raw.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            if (count >= MAX_LINKS) return@forEach
            val url = map["url"]?.toString().orEmpty()
            if (url.isBlank()) return@forEach
            linkInputs[count].setText(url)
            linkInputs[count].setSelection(linkInputs[count].text.length)
            durationInputs[count].setText((map["duration"] as? Number)?.toLong()?.toString() ?: "90")
            count++
        }
        for (i in count until MAX_LINKS) { linkInputs[i].setText(""); durationInputs[i].setText("90") }
        saveSettings()
        Toast.makeText(this, "تم استيراد $count رابط", Toast.LENGTH_SHORT).show()
        closeDrawer()
    }

    private fun importLinks() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        startActivityForResult(intent, PICK_IMPORT_FILE)
    }

    private fun exportLinks() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "متصفح_بكري_الروابط.txt")
        }
        startActivityForResult(intent, PICK_EXPORT_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            PICK_IMPORT_FILE -> readImportedLinks(uri)
            PICK_EXPORT_FILE -> writeExportedLinks(uri)
        }
    }

    private fun readImportedLinks(uri: Uri) {
        try {
            val lines = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readLines()
            } ?: emptyList()
            val entries = lines.mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                val url = parts.getOrNull(0)?.trim().orEmpty()
                val duration = parts.getOrNull(1)?.trim().orEmpty()
                if (url.isBlank()) null else Pair(url, duration.ifBlank { "90" })
            }
            for (i in 0 until MAX_LINKS) {
                if (i < entries.size) {
                    linkInputs[i].setText(entries[i].first)
                    durationInputs[i].setText(entries[i].second)
                } else {
                    linkInputs[i].setText("")
                }
            }
            saveSettings()
            Toast.makeText(this, "تم استيراد ${entries.size.coerceAtMost(MAX_LINKS)} رابط", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر قراءة الملف", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeExportedLinks(uri: Uri) {
        try {
            val text = buildString {
                for (i in 0 until MAX_LINKS) {
                    val url = linkInputs[i].text.toString().trim()
                    if (url.isNotBlank()) {
                        val duration = durationInputs[i].text.toString().trim().ifBlank { "90" }
                        append(url).append("|").append(duration).append("\n")
                    }
                }
            }
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "تم تصدير الروابط", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر تصدير الروابط", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreSettings() {
        val prefs = getSharedPreferences("quad_settings", MODE_PRIVATE)
        for (i in 0 until MAX_LINKS) {
            linkInputs[i].setText(prefs.getString("url_$i", ""))
            durationInputs[i].setText(prefs.getString("duration_$i", "90"))
        }
        refreshInput.setText(prefs.getString("refresh", "30"))
        startTimeInput.setText(prefs.getString("start", "13:30"))
        nowCheck.isChecked = prefs.getBoolean("now", true)
        startTimeInput.isEnabled = !nowCheck.isChecked
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("quad_settings", MODE_PRIVATE).edit()
        for (i in 0 until MAX_LINKS) {
            prefs.putString("url_$i", linkInputs[i].text.toString())
            prefs.putString("duration_$i", durationInputs[i].text.toString())
        }
        prefs.putString("refresh", refreshInput.text.toString())
        prefs.putString("start", startTimeInput.text.toString())
        prefs.putBoolean("now", nowCheck.isChecked)
        prefs.apply()
    }

    private fun normalizeUrl(raw: String): String = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp.dp().toFloat()
    }

    private fun roundedBorder(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(247, 248, 250))
        setStroke(1.dp(), Color.rgb(210, 216, 222))
        cornerRadius = 10.dp().toFloat()
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.rgb(225, 228, 232))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()).apply {
            topMargin = 14.dp()
            bottomMargin = 14.dp()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) closeDrawer() else super.onBackPressed()
    }

    private fun openDrawer() {
        drawer.visibility = View.VISIBLE
        drawerDim.visibility = View.VISIBLE
        hamburger.visibility = View.GONE
    }

    private fun closeDrawer() {
        if (::drawer.isInitialized) {
            drawer.visibility = View.GONE
            drawerDim.visibility = View.GONE
            hamburger.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scheduleRunning = false
        loadGeneration++
        handler.removeCallbacksAndMessages(null)
        auth.removeAuthStateListener(authListener)
        browsers.forEach {
            it.stopLoading()
            it.destroy()
        }
        browsers.clear()
        super.onDestroy()
    }
}
