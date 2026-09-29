package com.pragon.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity(), RemoteBridge.Listener {

    // PhoneView-matching palette (see pragon_phoneview/static/app.html: --cyan:#00d4ff; --bg:#0a0a0f;)
    private val cCyan = Color.parseColor("#00D4FF")
    private val cBg = Color.parseColor("#0A0A0F")
    private val cPanel = Color.parseColor("#12161F")
    private val cBorder = Color.parseColor("#3300D4FF")
    private val cTextDim = Color.parseColor("#5E6A7E")
    private val cTextMain = Color.parseColor("#DDE3ED")

    private fun pill(filled: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 999f
            setStroke(2, cCyan)
            setColor(if (filled) cCyan else Color.parseColor("#0D1017"))
        }

    private fun panelBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 18f
            setColor(cPanel)
            setStroke(1, cBorder)
        }

    private lateinit var statusTv: TextView
    private lateinit var permsTv: TextView
    private lateinit var hostEt: EditText
    private lateinit var keyEt: EditText

    // Remote screen (PhoneView-style two-way control)
    private lateinit var remoteSection: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatBox: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var micBtn: Button
    private lateinit var modelRow: LinearLayout
    private var currentModel = ""
    private var openPhoneView = false // guards against acting on a late/unsolicited web_login reply

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleMic() else toast("Microphone permission is needed to talk to Pragon from here.")
        }

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(text.trim())
        val host = uri.host
        val key = uri.getQueryParameter("key")
        if (host.isNullOrBlank() || key.isNullOrBlank()) {
            toast("That QR code isn't from Pragon.")
            return@registerForActivityResult
        }
        startPairing(host, if (uri.port > 0) uri.port else 8000, key)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(cBg)
        }

        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4)
            setTextColor(cTextMain)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
            setTextColor(cTextMain); background = pill(false)
        }

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "P.R.A.G.O.N"; textSize = 24f; setTextColor(cCyan)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.04f
        })
        root.addView(headerRow)
        root.addView(TextView(this).apply { text = "MOBILE"; textSize = 11f; setTextColor(cTextDim); letterSpacing = 0.2f })
        statusTv = label(Bridge.status, 14f).apply { setTextColor(cTextDim) }
        root.addView(statusTv)

        root.addView(button("Scan QR from Pragon (Connect Phone)") {
            scan.launch(
                ScanOptions()
                    .setPrompt("Scan the QR shown in Pragon > Remote - PhoneView")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        })

        root.addView(label("Or enter it manually:"))
        hostEt = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.2:8000"
            if (Prefs.host(this@MainActivity).isNotBlank())
                setText("${Prefs.host(this@MainActivity)}:${Prefs.port(this@MainActivity)}")
        }
        keyEt = EditText(this).apply { hint = "Pairing key shown under the QR" }
        root.addView(hostEt)
        root.addView(keyEt)
        root.addView(button("Connect") {
            val parts = hostEt.text.toString().trim().split(":")
            val host = parts.getOrNull(0).orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8000
            val key = keyEt.text.toString().trim()
            if (host.isBlank() || key.isBlank()) toast("Enter the PC address and the key.")
            else startPairing(host, port, key)
        })

        root.addView(label("Permissions (needed once):", 16f))
        permsTv = label("")
        root.addView(permsTv)
        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Find PragonMobile in the list and turn it on.")
        })
        root.addView(button("2. Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Battery: don't restrict this app") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(label(" "))
        root.addView(button("Disconnect and forget this PC") {
            val i = Intent(this, PragonService::class.java).setAction(PragonService.ACTION_STOP)
            ContextCompat.startForegroundService(this, i)
            Prefs.clear(this)
            statusTv.text = "Not paired yet - scan the QR from Pragon"
            remoteSection.visibility = View.GONE
        })

        root.addView(label(" "))
        root.addView(View(this).apply { setBackgroundColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2) })
        root.addView(buildRemoteSection(pad))

        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); addView(root) })

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Prefs.token(this).isNotBlank()) {
            ContextCompat.startForegroundService(this, Intent(this, PragonService::class.java))
        }
        remoteSection.visibility = if (Prefs.token(this).isNotBlank()) View.VISIBLE else View.GONE
    }

    /** Remote screen: chat with Pragon, model pills, mic, and PC-control buttons
     * (open app / lock / volume / media on the PC) - mirrors PhoneView's web UI,
     * but two-way: this device can also be told what to do (see CommandExecutor). */
    private fun buildRemoteSection(pad: Int): LinearLayout {
        val dp = resources.displayMetrics.density
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        remoteSection = section

        val divider = View(this).apply {
            setBackgroundColor(cBorder)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .apply { topMargin = (pad * 1.2f).toInt(); bottomMargin = pad }
        }
        section.addView(divider)

        val hdrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hdrRow.addView(TextView(this).apply { text = "REMOTE"; textSize = 16f; setTextColor(cCyan); setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.08f })
        section.addView(hdrRow)
        section.addView(TextView(this).apply {
            text = "Two-way: control the PC from here, or let Pragon on the PC control this phone."
            textSize = 11f; setTextColor(cTextDim); setPadding(0, 2, 0, pad / 2)
        })
        section.addView(pcButton("OPEN PHONEVIEW (same UI as the PC)") {
            openPhoneView = true
            RemoteBridge.requestWebLogin()
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) })
        section.addView(TextView(this).apply {
            text = "Opens the exact PhoneView web app - chat, mic, wake, camera, draw, files."
            textSize = 10f; setTextColor(cTextDim); setPadding(0, 2, 0, pad / 2)
        })

        // model pills (PRAGON / JARVIS / FRIDAY / GHOST) — same row style as the web UI
        modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        section.addView(HorizontalScrollView(this).apply { addView(modelRow) })

        section.addView(sectionLabel("PC control", pad))
        val pcRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pcRow1.addView(pcButton("WAKE") { RemoteBridge.wakePc() })
        pcRow1.addView(pcButton("LOCK") { RemoteBridge.pcControl("lock") })
        pcRow1.addView(pcButton("PLAY/PAUSE") { RemoteBridge.pcControl("play_pause") })
        section.addView(pcRow1)
        val pcRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pcRow2.addView(pcButton("VOL -") { RemoteBridge.pcControl("volume_down") })
        pcRow2.addView(pcButton("VOL +") { RemoteBridge.pcControl("volume_up") })
        pcRow2.addView(pcButton("NEXT") { RemoteBridge.pcControl("next") })
        section.addView(pcRow2)
        val openRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val openEt = themedInput("App/site to open on the PC").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        openRow.addView(openEt)
        openRow.addView(pcButton("OPEN") {
            val v = openEt.text.toString().trim()
            if (v.isNotEmpty()) { RemoteBridge.pcControl("open_app", v); openEt.setText("") }
        })
        section.addView(openRow)

        // mouse trackpad (joystick-style pad) + click buttons
        section.addView(sectionLabel("Mouse", pad))
        val trackpad = TrackpadView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (160 * dp).toInt())
            onMove = { dx, dy -> RemoteBridge.pcControl("mouse_move", dx = dx.toDouble(), dy = dy.toDouble()) }
            onTap = { RemoteBridge.pcControl("mouse_click") }
        }
        section.addView(TextView(this).apply {
            text = "Drag to move the cursor · tap to left-click"
            textSize = 10f; setTextColor(cTextDim); setPadding(0, pad / 4, 0, pad / 4)
        })
        section.addView(trackpad)
        val clickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clickRow.addView(pcButton("LEFT CLICK") { RemoteBridge.pcControl("mouse_click") })
        clickRow.addView(pcButton("RIGHT CLICK") { RemoteBridge.pcControl("mouse_right_click") })
        section.addView(clickRow)

        // chat
        section.addView(sectionLabel("Chat", pad))
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply {
            addView(chatBox)
            background = panelBg()
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (220 * dp).toInt())
        }
        section.addView(chatScroll)

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, pad / 2, 0, 0) }
        chatInput = themedInput("Send a command...").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(chatInput)
        micBtn = pcButton("MIC") { onMicButton() }
        inputRow.addView(micBtn)
        inputRow.addView(pcButton("SEND") {
            val t = chatInput.text.toString().trim()
            if (t.isNotEmpty()) { RemoteBridge.sendChat(t); chatInput.setText("") }
        })
        section.addView(inputRow)
        return section
    }

    private fun sectionLabel(t: String, pad: Int) = TextView(this).apply {
        text = t.uppercase(); textSize = 11f; setTextColor(cTextDim); letterSpacing = 0.1f
        setPadding(0, pad, 0, pad / 4)
    }

    private fun themedInput(hintText: String) = EditText(this).apply {
        hint = hintText; setTextColor(cTextMain); setHintTextColor(cTextDim)
        background = panelBg()
        val p = (10 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
    }

    private fun pcButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 11f; setOnClickListener { onClick() }
        setTextColor(cCyan); background = pill(false)
        val hp = (10 * resources.displayMetrics.density).toInt()
        setPadding(hp, 0, hp, 0)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(4, 4, 4, 4); layoutParams = lp
    }

    private fun smallButton(t: String, onClick: () -> Unit) = pcButton(t, onClick)

    private fun onMicButton() {
        if (!MicStreamer.hasPermission(this)) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        toggleMic()
    }

    private fun toggleMic() {
        if (MicStreamer.isRunning()) { MicStreamer.stop(); micBtn.text = "Mic: off" }
        else { MicStreamer.start(this); micBtn.text = "Mic: ON" }
    }

    private fun addChatLine(fromPhone: Boolean, text: String) {
        val dp = resources.displayMetrics.density
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(if (fromPhone) cTextMain else cCyan)
            textSize = 13f
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f
                setColor(if (fromPhone) Color.parseColor("#1A1F2A") else Color.parseColor("#0D2229"))
            }
            val hp = (10 * dp).toInt(); val vp = (8 * dp).toInt()
            setPadding(hp, vp, hp, vp)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromPhone) Gravity.END else Gravity.START
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            layoutParams = lp
            addView(bubble)
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = if (fromPhone) Gravity.END else Gravity.START
            addView(row)
        }
        chatBox.addView(outer)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // RemoteBridge.Listener -------------------------------------------------
    override fun onChatLine(fromPhone: Boolean, text: String) = addChatLine(fromPhone, text)

    override fun onModel(current: String, choices: List<String>) {
        currentModel = current
        modelRow.removeAllViews()
        val names = if (choices.isEmpty()) listOf("pragon", "jarvis", "friday", "ghost") else choices
        val dp = resources.displayMetrics.density
        for (m0 in names) {
            val label = if (m0 == "ollama") "ghost" else m0
            val selected = label == current || (label == "ghost" && current == "ollama")
            modelRow.addView(Button(this).apply {
                text = label.uppercase(); isAllCaps = false; textSize = 12f
                setTextColor(if (selected) Color.parseColor("#05050A") else cCyan)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = pill(selected)
                val hp = (14 * dp).toInt(); val vp = (6 * dp).toInt()
                setPadding(hp, vp, hp, vp)
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(4, 4, 4, 4)
                    ?: run { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(4, 4, 4, 4) } }
                setOnClickListener { RemoteBridge.setModel(if (label == "ghost") "ollama" else label) }
            })
        }
    }

    override fun onConnection(connected: Boolean) {
        remoteSection.visibility = if (connected) View.VISIBLE else View.GONE
        if (!connected) MicStreamer.stop()
    }

    override fun onWebLoginPath(path: String) {
        if (!openPhoneView) return
        openPhoneView = false
        if (path.isBlank()) { toast("Couldn't open PhoneView."); return }
        val host = Prefs.host(this); val port = Prefs.port(this)
        val url = "http://$host:$port$path"
        startActivity(Intent(this, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_URL, url))
    }

    override fun onResume() {
        super.onResume()
        Bridge.listener = { statusTv.text = it; refreshPerms() }
        statusTv.text = Bridge.status
        refreshPerms()
        RemoteBridge.listener = this
        remoteSection.visibility = if (RemoteBridge.socket != null) View.VISIBLE else View.GONE
        if (RemoteBridge.socket != null) RemoteBridge.requestModel()
    }

    override fun onPause() {
        Bridge.listener = null
        RemoteBridge.listener = null
        super.onPause()
    }

    private fun refreshPerms() {
        val a11y = PragonAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(PowerManager::class.java)
        val batt = pm.isIgnoringBatteryOptimizations(packageName)
        fun s(b: Boolean) = if (b) "ON" else "OFF"
        permsTv.text = "Accessibility: ${s(a11y)}\nDisplay over other apps: ${s(overlay)}\nBattery unrestricted: ${s(batt)}"
    }

    private fun startPairing(host: String, port: Int, key: String) {
        Prefs.save(this, host, port, "")
        hostEt.setText("$host:$port")
        val i = Intent(this, PragonService::class.java)
            .putExtra(PragonService.EXTRA_KEY, key.trim().uppercase())
        ContextCompat.startForegroundService(this, i)
        Bridge.set("Pairing with $host:$port ...")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
