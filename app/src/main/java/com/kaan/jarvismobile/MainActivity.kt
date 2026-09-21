package com.kaan.jarvismobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var core: AuroraCoreView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var scanButton: Button
    private lateinit var sendButton: Button
    private lateinit var pttButton: Button
    private lateinit var quickRow: LinearLayout
    private var baseUrl = ""
    private var session = ""
    private var pairId = ""
    private var lastSeq = 0
    private var pollGeneration = 0
    private var speech: SpeechRecognizer? = null

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        beginPairing(text)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) openScanner() else toast("Camera permission is required for QR pairing.") }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startPtt() else toast("Microphone permission is required for PTT.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreSession()
        intent?.data?.toString()?.takeIf { it.startsWith("jarvis://pair") }?.let(::beginPairing)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { it.startsWith("jarvis://pair") }?.let(::beginPairing)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(1, 6, 12))
        }
        val title = TextView(this).apply { text = "JARVIS MOBILE // V1"; setTextColor(Color.rgb(191, 245, 255)); textSize = 18f; letterSpacing = .13f }
        status = TextView(this).apply { text = "OFFLINE"; setTextColor(Color.rgb(93, 255, 199)); textSize = 11f; gravity = Gravity.END }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(title, LinearLayout.LayoutParams(0, dp(42), 1f)); addView(status, LinearLayout.LayoutParams(dp(120), dp(42))) }
        root.addView(head)
        core = AuroraCoreView(this)
        root.addView(core, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)))
        scanButton = button("SCAN PC QR") { ensureCamera() }
        root.addView(scanButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(2, 13, 23)) }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        listOf("FILM" to "Film zamanı", "VOL+" to "TV sesini yükselt", "VOL−" to "TV sesini azalt", "HOME" to "TV ana ekrana dön").forEach { (label, command) ->
            quickRow.addView(button(label) { sendCommand(command) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(quickRow)
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "JARVIS'a yaz…"; setHintTextColor(Color.rgb(72, 125, 143)); setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(3, 22, 36)); setPadding(dp(12), 0, dp(12), 0); imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true
            setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false }
        }
        pttButton = button("PTT") { ensureMic() }
        sendButton = button("SEND") { sendFromInput() }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        composer.addView(pttButton, LinearLayout.LayoutParams(dp(70), dp(52)))
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(78), dp(52)))
        root.addView(composer)
        setContentView(root)
        setConnectedUi(false)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.rgb(183, 242, 255)); setBackgroundColor(Color.rgb(4, 42, 62)); textSize = 10f; setOnClickListener { onClick() }
    }

    private fun beginPairing(payload: String) {
        try {
            val uri = Uri.parse(payload.trim())
            require(uri.scheme == "jarvis" && uri.host == "pair") { "Invalid JARVIS QR" }
            val host = uri.getQueryParameter("host") ?: error("Missing host")
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: error("Missing port")
            val token = uri.getQueryParameter("token") ?: error("Missing token")
            require(isPrivateLanHost(host)) { "PC address is not a private LAN address" }
            baseUrl = "http://$host:$port"
            status("PAIRING // PC APPROVAL")
            io.execute {
                try {
                    val body = JSONObject().put("token", token).put("deviceName", android.os.Build.MODEL ?: "Android Phone")
                    val response = request("POST", "$baseUrl/api/pair-request", body)
                    pairId = response.optString("id")
                    if (pairId.isBlank()) error(response.optString("error", "Pair request rejected"))
                    ui.post { addMessage("system", "Pair request sent. Approve this phone on the PC.") }
                    pollPairing()
                } catch (e: Exception) { ui.post { fail("Pairing failed: ${e.message}") } }
            }
        } catch (e: Exception) { fail(e.message ?: "Invalid QR") }
    }

    private fun pollPairing() {
        val generation = ++pollGeneration
        fun next() {
            if (generation != pollGeneration || pairId.isBlank()) return
            io.execute {
                try {
                    val j = request("GET", "$baseUrl/api/pair-status?id=${Uri.encode(pairId)}")
                    if (j.optString("status") == "connected") {
                        session = j.optString("session")
                        if (session.isBlank()) error("Session missing")
                        getPreferences(MODE_PRIVATE).edit().putString("baseUrl", baseUrl).putString("session", session).apply()
                        ui.post { status("CONNECTED"); setConnectedUi(true); addMessage("system", "PHONE CONNECTED // PC JARVIS online."); pollEvents() }
                    } else ui.postDelayed({ next() }, 850)
                } catch (_: Exception) { ui.postDelayed({ next() }, 1100) }
            }
        }
        next()
    }

    private fun pollEvents() {
        val generation = ++pollGeneration
        fun next() {
            if (generation != pollGeneration || session.isBlank()) return
            io.execute {
                try {
                    val j = request("GET", "$baseUrl/api/events?session=${Uri.encode(session)}&since=$lastSeq")
                    val arr = j.optJSONArray("messages")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i); lastSeq = maxOf(lastSeq, item.optInt("seq")); val role = item.optString("role", "system"); val content = item.optString("content")
                        ui.post { addMessage(role, content) }
                    }
                    ui.postDelayed({ next() }, 850)
                } catch (e: Exception) {
                    ui.post { status("RECONNECTING") }
                    ui.postDelayed({ next() }, 1400)
                }
            }
        }
        next()
    }

    private fun restoreSession() {
        val prefs = getPreferences(MODE_PRIVATE)
        baseUrl = prefs.getString("baseUrl", "") ?: ""
        session = prefs.getString("session", "") ?: ""
        if (baseUrl.isBlank() || session.isBlank()) return
        status("RECONNECTING")
        setConnectedUi(true)
        pollEvents()
    }

    private fun sendFromInput() { val text = input.text.toString().trim(); if (text.isNotEmpty()) { input.setText(""); addMessage("user", text); sendCommand(text) } }
    private fun sendCommand(text: String) {
        if (session.isBlank()) { toast("Pair with the PC first."); return }
        io.execute {
            try { val j = request("POST", "$baseUrl/api/message", JSONObject().put("session", session).put("text", text)); if (!j.optBoolean("accepted")) error(j.optString("error", "Rejected")) }
            catch (e: Exception) { ui.post { addMessage("system", "SEND ERROR // ${e.message}") } }
        }
    }

    private fun ensureCamera() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner() else cameraPermission.launch(Manifest.permission.CAMERA) }
    private fun openScanner() { scanner.launch(ScanOptions().setPrompt("Scan the QR shown by JARVIS on the PC").setBeepEnabled(false).setOrientationLocked(true).setDesiredBarcodeFormats(ScanOptions.QR_CODE)) }
    private fun ensureMic() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startPtt() else micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    private fun startPtt() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Speech recognition is not available on this phone."); return }
        speech?.destroy(); speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { pttButton.text = "LISTEN"; status("LISTENING") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { pttButton.text = "PTT" }
            override fun onError(error: Int) { pttButton.text = "PTT"; status(if (session.isBlank()) "OFFLINE" else "CONNECTED") }
            override fun onResults(results: Bundle?) { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty(); pttButton.text = "PTT"; status("CONNECTED"); if (text.isNotBlank()) { addMessage("user", text); sendCommand(text) } }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) }
        speech?.startListening(intent)
    }

    private fun request(method: String, url: String, body: JSONObject? = null): JSONObject {
        val target = URL(url)
        require(isPrivateLanHost(target.host)) { "Non-LAN address blocked" }
        val c = target.openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 3500; c.readTimeout = 5500; c.setRequestProperty("User-Agent", "JARVIS-Mobile/1.0"); c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrDefault("HTTP ${c.responseCode}"))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun isPrivateLanHost(host: String): Boolean = try {
        val a = InetAddress.getByName(host).address.map { it.toInt() and 0xff }
        a.size == 4 && (a[0] == 10 || (a[0] == 172 && a[1] in 16..31) || (a[0] == 192 && a[1] == 168) || a[0] == 127)
    } catch (_: Exception) { false }

    private fun addMessage(role: String, text: String) {
        if (text.isBlank()) return
        val view = TextView(this).apply { setPadding(dp(10), dp(9), dp(10), dp(9)); setTextColor(if (role == "system") Color.rgb(255, 222, 151) else Color.rgb(214, 248, 255)); textSize = 13f; this.text = "${role.uppercase()} // $text"; setBackgroundColor(if (role == "user") Color.rgb(5, 37, 31) else Color.rgb(4, 27, 43)) }
        messages.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun setConnectedUi(connected: Boolean) { input.isEnabled = connected; sendButton.isEnabled = connected; pttButton.isEnabled = connected; quickRow.visibility = if (connected) View.VISIBLE else View.GONE; scanButton.text = if (connected) "PAIR ANOTHER PC" else "SCAN PC QR" }
    private fun status(text: String) { status.text = text; core.setState(text) }
    private fun fail(text: String) { status("ERROR"); addMessage("system", text); toast(text) }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { super.onDestroy(); pollGeneration++; speech?.destroy(); io.shutdownNow() }
}
