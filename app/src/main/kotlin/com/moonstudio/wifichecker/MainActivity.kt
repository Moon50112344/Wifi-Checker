package com.moonstudio.wifichecker

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    // =========================================================
    // ACTIVATION CONFIG
    // =========================================================

    private val prefsName = "moon_activation"
    private val activatedKey = "activated"

    private val workerUrl =
        "https://moon501access.moon10512344.workers.dev"

    // =========================================================
    // UPDATE CONFIG
    // =========================================================

    // ĐỔI TEN_USER/TEN_REPO thành GitHub repo thật của mày
    private val updateApkUrl =
        "https://github.com/TEN_USER/TEN_REPO/releases/latest/download/WifiChecker.apk"

    private val updatePrefs =
        "wifi_checker_update"

    private val updateRemoteTagKey =
        "remote_tag"

    private val handler =
        Handler(Looper.getMainLooper())

    private var musicPlayer: MediaPlayer? = null

    private var activationLink = ""
    private var linkTextView: TextView? = null
    private var linkCard: LinearLayout? = null

    // =========================================================
    // WIFI CHECKER CONFIG
    // =========================================================

    private val tcpPorts =
        listOf(80, 443, 8080, 8000, 22)

    private val maxWorkers = 16
    private val monitorInterval = 1000L
    private val newDeviceInterval = 10000L
    private val tcpTimeout = 150

    // =========================================================
    // WIFI CHECKER DATA
    // =========================================================

    data class Device(
        val ip: String,
        var name: String = "Unknown",
        var ping: Long? = null,
        var tcp: Int? = null
    )

    data class UpdateResult(
        val ip: String,
        val ping: Long?,
        val tcp: Int?
    )

    private val devices =
        ConcurrentHashMap<String, Device>()

    private val workerPool: ExecutorService =
        Executors.newFixedThreadPool(maxWorkers)

    @Volatile
    private var running = false

    private var monitorThread: Thread? = null
    private var scanThread: Thread? = null

    // =========================================================
    // WIFI CHECKER UI
    // =========================================================

    private lateinit var root: LinearLayout
    private lateinit var terminal: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var statsText: TextView
    private lateinit var deviceText: TextView

    private lateinit var titleText: TextView
    private lateinit var betaText: TextView
    private lateinit var terminalTitle: TextView
    private lateinit var statsTitle: TextView

    private lateinit var terminalButton: TextView
    private lateinit var settingButton: TextView

    private var darkMode = false
    private var pingWarningShown = false

    // =========================================================
    // COLORS
    // =========================================================

    private val lightBackground = Color.rgb(245, 244, 239)
    private val lightTerminal = Color.rgb(247, 247, 244)
    private val lightTerminalBox = Color.rgb(235, 234, 229)
    private val lightText = Color.rgb(30, 30, 30)
    private val lightGray = Color.rgb(110, 110, 105)

    private val darkBackground = Color.rgb(25, 25, 25)
    private val darkTerminal = Color.rgb(12, 12, 12)
    private val darkTerminalBox = Color.rgb(35, 35, 35)
    private val darkText = Color.rgb(235, 235, 235)
    private val darkGray = Color.rgb(170, 170, 170)

    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        )

        if (prefs.getBoolean(activatedKey, false)) {
            launchWifiChecker()
        } else {
            showActivation()
        }
    }

    // =========================================================
    // LAUNCH WIFI CHECKER
    // =========================================================

    private fun launchWifiChecker() {

        createUI()

        appendTerminal(
            "[DEBUG] Checking for updates..."
        )

        appendTerminal(
            "[INFO] Wifi Checker started."
        )

        appendTerminal(
            "[INFO] Developed by Moon Studio."
        )

        appendTerminal(
            "[INFO] WiFi Checker Support: https://wificheckersupport.wasmer.app/"
        )

        appendTerminal(
            "[INFO] Preparing LAN scanner..."
        )

        // Kiểm tra bản cập nhật tự động
        checkForUpdate()

        startScanner()
    }

    // =========================================================
    // UPDATE — CHECK
    // =========================================================

    private fun checkForUpdate() {

        Thread {

            var connection: HttpURLConnection? = null

            try {

                val url = URL(updateApkUrl)

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "HEAD"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true

                val responseCode =
                    connection.responseCode

                if (responseCode !in 200..399) {
                    return@Thread
                }

                /*
                 * GitHub/CDN thường trả ETag.
                 * Nếu không có thì dùng Last-Modified.
                 * Cuối cùng dùng Content-Length.
                 */
                val remoteTag =
                    connection.getHeaderField("ETag")
                        ?: connection.getHeaderField("Last-Modified")
                        ?: connection.getHeaderField("Content-Length")

                if (remoteTag.isNullOrBlank()) {
                    return@Thread
                }

                val prefs =
                    getSharedPreferences(
                        updatePrefs,
                        MODE_PRIVATE
                    )

                val oldTag =
                    prefs.getString(
                        updateRemoteTagKey,
                        null
                    )

                if (oldTag == null) {

                    /*
                     * Lần đầu kiểm tra:
                     * ghi nhận APK hiện tại trên GitHub
                     * để không bắt người dùng update ngay.
                     */
                    prefs.edit()
                        .putString(
                            updateRemoteTagKey,
                            remoteTag
                        )
                        .apply()

                    appendTerminal(
                        "[UPDATE] Current APK recorded."
                    )

                    return@Thread
                }

                if (oldTag != remoteTag) {

                    handler.post {
                        showUpdateDialog(remoteTag)
                    }

                } else {

                    appendTerminal(
                        "[UPDATE] No new version found."
                    )
                }

            } catch (_: Exception) {

                appendTerminal(
                    "[UPDATE] Update check failed."
                )

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    // =========================================================
    // UPDATE — DIALOG
    // =========================================================

    private fun showUpdateDialog(
        remoteTag: String
    ) {

        if (isFinishing) {
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                "Bạn đang sử dụng bản beta cũ"
            )
            .setMessage(
                "Nếu bạn muốn cập nhật bản mới nhất.\n\n" +
                "Bản cập nhật này sẽ không bắt bạn get code.\n" +
                "Sẽ không thay đổi về tác dụng.\n" +
                "Chỉ thay đổi về giao diện."
            )
            .setPositiveButton("Cập nhật") {
                    _, _ ->

                downloadUpdate(remoteTag)
            }
            .setNeutralButton("Để sau", null)
            .setNegativeButton("Bỏ qua") {
                    _, _ ->

                /*
                 * Bỏ qua đúng bản hiện tại.
                 * Nếu sau này APK trên GitHub thay đổi,
                 * remoteTag sẽ khác và app sẽ hỏi lại.
                 */
                getSharedPreferences(
                    updatePrefs,
                    MODE_PRIVATE
                )
                    .edit()
                    .putString(
                        updateRemoteTagKey,
                        remoteTag
                    )
                    .apply()
            }
            .create()

        dialog.show()
    }

    // =========================================================
    // UPDATE — DOWNLOAD
    // =========================================================

    private fun downloadUpdate(
        remoteTag: String
    ) {

        appendTerminal(
            "[UPDATE] Downloading new APK..."
        )

        Thread {

            var connection: HttpURLConnection? = null

            try {

                val url = URL(updateApkUrl)

                connection =
                    url.openConnection() as HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                connection.connect()

                val responseCode =
                    connection.responseCode

                if (responseCode !in 200..299) {

                    handler.post {
                        appendTerminal(
                            "[UPDATE] Download failed: HTTP $responseCode"
                        )
                    }

                    return@Thread
                }

                val apkFile =
                    File(
                        cacheDir,
                        "WifiChecker-update.apk"
                    )

                if (apkFile.exists()) {
                    apkFile.delete()
                }

                connection.inputStream.use { input ->

                    apkFile.outputStream().use { output ->

                        val buffer =
                            ByteArray(8192)

                        var bytesRead: Int

                        while (
                            input.read(buffer).also {
                                bytesRead = it
                            } != -1
                        ) {

                            output.write(
                                buffer,
                                0,
                                bytesRead
                            )
                        }
                    }
                }

                if (!apkFile.exists() ||
                    apkFile.length() <= 0
                ) {

                    handler.post {
                        appendTerminal(
                            "[UPDATE] APK file is invalid."
                        )
                    }

                    return@Thread
                }

                /*
                 * Ghi nhận bản mới sau khi tải thành công.
                 */
                getSharedPreferences(
                    updatePrefs,
                    MODE_PRIVATE
                )
                    .edit()
                    .putString(
                        updateRemoteTagKey,
                        remoteTag
                    )
                    .apply()

                handler.post {

                    appendTerminal(
                        "[UPDATE] Download complete."
                    )

                    installUpdate(apkFile)
                }

            } catch (_: Exception) {

                handler.post {
                    appendTerminal(
                        "[UPDATE] Unable to download update."
                    )
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    // =========================================================
    // UPDATE — INSTALL
    // =========================================================

    private fun installUpdate(
        apkFile: File
    ) {

        try {

            val uri =
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    apkFile
                )

            val intent =
                Intent(Intent.ACTION_VIEW).apply {

                    setDataAndType(
                        uri,
                        "application/vnd.android.package-archive"
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            startActivity(intent)

        } catch (_: Exception) {

            appendTerminal(
                "[UPDATE] Cannot open APK installer."
            )
        }
    }

    // =========================================================
    // ACTIVATION
    // =========================================================

    private fun showActivation() {

        activationLink = ""
        linkTextView = null
        linkCard = null

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(dp(20), dp(20), dp(20), dp(20))
        root.setBackgroundColor(Color.rgb(15, 23, 42))

        val scroll = ScrollView(this)
        scroll.setFillViewport(true)
        scroll.setBackgroundColor(Color.TRANSPARENT)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        container.setPadding(dp(4), dp(20), dp(4), dp(20))

        val title = TextView(this)
        title.text = "Wifi checker"
        title.textSize = 30f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        container.addView(title, matchWrap())

        val subtitle = TextView(this)
        subtitle.text = "Activation system for WiFi Checker."
        subtitle.textSize = 14f
        subtitle.setTextColor(Color.rgb(148, 163, 184))
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, dp(8), 0, dp(24))
        container.addView(subtitle, matchWrap())

        val outerCard = LinearLayout(this)
        outerCard.orientation = LinearLayout.VERTICAL
        outerCard.setPadding(dp(1), dp(1), dp(1), dp(1))
        outerCard.background = roundedBackground(
            Color.rgb(51, 65, 85),
            Color.TRANSPARENT,
            0
        )

        val innerCard = LinearLayout(this)
        innerCard.orientation = LinearLayout.VERTICAL
        innerCard.setPadding(dp(20), dp(20), dp(20), dp(20))
        innerCard.background = roundedBackground(
            Color.rgb(15, 23, 42),
            Color.TRANSPARENT,
            0
        )

        val cardTitle = TextView(this)
        cardTitle.text = "Activation Code"
        cardTitle.textSize = 20f
        cardTitle.setTextColor(Color.WHITE)
        cardTitle.typeface = Typeface.DEFAULT_BOLD
        innerCard.addView(cardTitle, matchWrap())

        val cardLabel = TextView(this)
        cardLabel.text = "Activation Code"
        cardLabel.textSize = 13f
        cardLabel.setTextColor(Color.rgb(148, 163, 184))
        cardLabel.setPadding(0, dp(18), 0, dp(7))
        innerCard.addView(cardLabel, matchWrap())

        val input = EditText(this)
        input.hint = "Enter your activation code"
        input.textSize = 15f
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.rgb(100, 116, 139))
        input.setSingleLine(true)
        input.setPadding(dp(14), dp(12), dp(14), dp(12))
        input.background = roundedBackground(
            Color.rgb(30, 41, 59),
            Color.rgb(71, 85, 105),
            1
        )

        innerCard.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        val errorText = TextView(this)
        errorText.text = ""
        errorText.textSize = 12f
        errorText.setTextColor(Color.rgb(248, 113, 113))
        errorText.setMinHeight(dp(28))
        errorText.setPadding(0, dp(7), 0, 0)
        innerCard.addView(errorText, matchWrap())

        val getCode = TextView(this)
        getCode.text =
            "If you do not have an activation code? Get code"
        getCode.textSize = 13f
        getCode.setTextColor(Color.rgb(148, 163, 184))
        getCode.setClickable(true)
        getCode.setFocusable(true)
        getCode.setPadding(0, dp(4), 0, dp(18))
        innerCard.addView(getCode, matchWrap())

        val verifyButton = Button(this)
        verifyButton.text = "Verify"
        verifyButton.textSize = 14f
        verifyButton.setTextColor(Color.WHITE)
        verifyButton.isAllCaps = false
        verifyButton.background = roundedBackground(
            Color.rgb(37, 99, 235),
            Color.TRANSPARENT,
            0
        )

        innerCard.addView(
            verifyButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )

        outerCard.addView(innerCard, matchWrap())
        container.addView(outerCard, matchWrap())

        val createdLinkCard = createLinkCard()
        createdLinkCard.setVisibility(View.GONE)
        linkCard = createdLinkCard

        container.addView(
            createdLinkCard,
            matchWrapWithTopMargin(dp(14))
        )

        val footer = TextView(this)
        footer.text =
            "© 2026 MoonActivation.\nActivation System"
        footer.textSize = 12f
        footer.setTextColor(Color.rgb(100, 116, 139))
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, dp(25), 0, dp(5))
        container.addView(footer, matchWrap())

        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)

        getCode.setOnClickListener {

            getCode.isEnabled = false
            getCode.text = "Loading..."

            getActivationUrl(
                onSuccess = { url ->

                    activationLink = url
                    linkTextView?.text =
                        activationLink

                    linkCard?.setVisibility(
                        View.VISIBLE
                    )

                    getCode.isEnabled = true
                    getCode.text =
                        "If you do not have an activation code? Get code"
                },

                onError = { message ->

                    getCode.isEnabled = true
                    getCode.text =
                        "If you do not have an activation code? Get code"

                    errorText.text = message
                }
            )
        }

        verifyButton.setOnClickListener {

            val key =
                input.text.toString().trim()

            if (key.isEmpty()) {

                errorText.text =
                    "Please enter an activation code."

                return@setOnClickListener
            }

            if (!key.matches(
                    Regex(
                        "[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}"
                    )
                )
            ) {

                errorText.text =
                    "Invalid activation code format."

                return@setOnClickListener
            }

            verifyButton.isEnabled = false
            verifyButton.text = "Verifying..."
            errorText.text = ""

            verifyActivation(
                key = key,

                onSuccess = {

                    getSharedPreferences(
                        prefsName,
                        MODE_PRIVATE
                    )
                        .edit()
                        .putBoolean(
                            activatedKey,
                            true
                        )
                        .apply()

                    verifyButton.isEnabled = true
                    verifyButton.text = "Verify"

                    showWelcomeIntro()
                },

                onError = { message ->

                    verifyButton.isEnabled = true
                    verifyButton.text = "Verify"
                    errorText.text = message
                }
            )
        }
    }

    // =========================================================
    // LINK CARD
    // =========================================================

    private fun createLinkCard(): LinearLayout {

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(18), dp(18), dp(18))

        card.background = roundedBackground(
            Color.rgb(30, 41, 59),
            Color.rgb(51, 65, 85),
            1
        )

        val title = TextView(this)
        title.text = "Your link"
        title.textSize = 18f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        card.addView(title, matchWrap())

        val description = TextView(this)
        description.text = "Your activation link"
        description.textSize = 12f
        description.setTextColor(Color.rgb(148, 163, 184))
        description.setPadding(0, dp(5), 0, dp(8))
        card.addView(description, matchWrap())

        val linkText = TextView(this)
        linkText.text = ""
        linkText.textSize = 12f
        linkText.setTextColor(Color.rgb(96, 165, 250))
        linkText.setPadding(
            dp(12),
            dp(11),
            dp(12),
            dp(11)
        )

        linkText.background = roundedBackground(
            Color.rgb(15, 23, 42),
            Color.rgb(71, 85, 105),
            1
        )

        card.addView(
            linkText,
            matchWrap()
        )

        linkTextView = linkText

        val buttons = LinearLayout(this)
        buttons.orientation =
            LinearLayout.HORIZONTAL

        buttons.gravity =
            Gravity.CENTER_VERTICAL

        buttons.setPadding(
            0,
            dp(12),
            0,
            0
        )

        val copy = Button(this)
        copy.text = "Copy"
        copy.isAllCaps = false
        copy.textSize = 13f
        copy.setTextColor(Color.WHITE)

        copy.background = roundedBackground(
            Color.rgb(51, 65, 85),
            Color.TRANSPARENT,
            0
        )

        buttons.addView(
            copy,
            weightButton()
        )

        val open = Button(this)
        open.text = "Open"
        open.isAllCaps = false
        open.textSize = 13f
        open.setTextColor(Color.WHITE)

        open.background = roundedBackground(
            Color.rgb(51, 65, 85),
            Color.TRANSPARENT,
            0
        )

        buttons.addView(
            open,
            weightButton()
        )

        val close = Button(this)
        close.text = "Close"
        close.isAllCaps = false
        close.textSize = 13f
        close.setTextColor(Color.WHITE)

        close.background = roundedBackground(
            Color.rgb(71, 85, 105),
            Color.TRANSPARENT,
            0
        )

        buttons.addView(
            close,
            weightButton()
        )

        card.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        copy.setOnClickListener {

            if (activationLink.isNotBlank()) {

                val clipboard =
                    getSystemService(
                        CLIPBOARD_SERVICE
                    ) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "Activation link",
                        activationLink
                    )
                )

                copy.text = "Copied"

                handler.postDelayed(
                    {
                        copy.text = "Copy"
                    },
                    1200L
                )
            }
        }

        open.setOnClickListener {

            if (activationLink.isNotBlank()) {

                try {

                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(activationLink)
                    )

                    startActivity(intent)

                } catch (_: Exception) {
                }
            }
        }

        close.setOnClickListener {
            card.setVisibility(View.GONE)
        }

        return card
    }

    // =========================================================
    // GET ACTIVATION URL
    // =========================================================

    private fun getActivationUrl(
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        Thread {

            var connection:
                HttpURLConnection? = null

            try {

                val url =
                    URL("$workerUrl/api/session")

                connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode =
                    connection.responseCode

                val stream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response =
                    if (stream != null) {

                        BufferedReader(
                            InputStreamReader(stream)
                        ).use {
                            it.readText()
                        }

                    } else {
                        ""
                    }

                if (responseCode !in 200..299) {

                    handler.post {
                        onError(
                            "Unable to get activation code."
                        )
                    }

                    return@Thread
                }

                val json =
                    JSONObject(response)

                val activationUrl =
                    json.optString("url")
                        .ifBlank {
                            json.optString(
                                "activationUrl"
                            )
                        }

                if (activationUrl.isBlank()) {

                    handler.post {
                        onError(
                            "Activation URL was not found."
                        )
                    }

                    return@Thread
                }

                handler.post {
                    onSuccess(activationUrl)
                }

            } catch (_: Exception) {

                handler.post {
                    onError(
                        "Connection error. Please try again."
                    )
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    // =========================================================
    // VERIFY ACTIVATION
    // =========================================================

    private fun verifyActivation(
        key: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        Thread {

            var connection:
                HttpURLConnection? = null

            try {

                val url =
                    URL("$workerUrl/api/verify-key")

                connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                val body =
                    JSONObject()
                        .put("key", key)
                        .toString()

                connection.outputStream.use { output ->
                    output.write(
                        body.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                val responseCode =
                    connection.responseCode

                val stream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response =
                    if (stream != null) {

                        BufferedReader(
                            InputStreamReader(stream)
                        ).use {
                            it.readText()
                        }

                    } else {
                        ""
                    }

                var message =
                    "Activation failed."

                if (response.isNotBlank()) {

                    try {

                        val json =
                            JSONObject(response)

                        message =
                            json.optString(
                                "message",
                                message
                            )

                    } catch (_: Exception) {

                        if (response.isNotBlank()) {
                            message = response
                        }
                    }
                }

                handler.post {

                    if (responseCode in 200..299) {
                        onSuccess()
                    } else {
                        onError(message)
                    }
                }

            } catch (_: Exception) {

                handler.post {
                    onError(
                        "Connection error. Please try again."
                    )
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    // =========================================================
    // WELCOME INTRO
    // =========================================================

    private fun showWelcomeIntro() {

        val content =
            findViewById<ViewGroup>(
                android.R.id.content
            )

        if (content.width <= 0 ||
            content.height <= 0
        ) {

            window.decorView.postDelayed(
                {
                    showWelcomeIntro()
                },
                100L
            )

            return
        }

        val bitmap =
            Bitmap.createBitmap(
                content.width,
                content.height,
                Bitmap.Config.ARGB_8888
            )

        content.draw(
            Canvas(bitmap)
        )

        val introRoot =
            FrameLayout(this)

        introRoot.setBackgroundColor(
            Color.BLACK
        )

        val blurredBackground =
            ImageView(this)

        blurredBackground.setImageBitmap(
            bitmap
        )

        blurredBackground.scaleType =
            ImageView.ScaleType.CENTER_CROP

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            blurredBackground.setRenderEffect(
                android.graphics.RenderEffect
                    .createBlurEffect(
                        18f,
                        18f,
                        android.graphics.Shader.TileMode.CLAMP
                    )
            )
        }

        introRoot.addView(
            blurredBackground,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val dim = View(this)

        dim.setBackgroundColor(
            Color.argb(
                175,
                0,
                0,
                0
            )
        )

        introRoot.addView(
            dim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val welcome = TextView(this)

        welcome.text =
            "Welcome to Wifi Checker"

        welcome.textSize = 34f

        welcome.setTextColor(
            Color.WHITE
        )

        welcome.typeface =
            Typeface.create(
                Typeface.MONOSPACE,
                Typeface.BOLD
            )

        welcome.gravity =
            Gravity.CENTER

        welcome.alpha = 0f

        introRoot.addView(
            welcome,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(introRoot)

        welcome.animate()
            .alpha(1f)
            .setDuration(700L)
            .start()

        handler.postDelayed(
            {

                welcome.animate()
                    .alpha(0f)
                    .setDuration(700L)
                    .withEndAction {

                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }

                        playMusic()

                        launchWifiChecker()
                    }
                    .start()

            },
            6300L
        )
    }

    // =========================================================
    // MUSIC
    // =========================================================

    private fun playMusic() {

        try {

            musicPlayer?.release()
            musicPlayer = null

            musicPlayer =
                MediaPlayer.create(
                    this,
                    R.raw.musicapp
                )

            musicPlayer?.setOnCompletionListener {
                    player ->

                    player.release()

                    if (
                        musicPlayer === player
                    ) {
                        musicPlayer = null
                    }
                }

            musicPlayer?.start()

        } catch (_: Exception) {

            musicPlayer = null
        }
    }

    // =========================================================
    // WIFI CHECKER — CREATE UI
    // =========================================================

    private fun createUI() {

        root = LinearLayout(this)

        root.orientation =
            LinearLayout.VERTICAL

        root.setPadding(
            dp(18),
            dp(18),
            dp(18),
            dp(18)
        )

        setContentView(root)

        val titleRow =
            LinearLayout(this)

        titleRow.orientation =
            LinearLayout.HORIZONTAL

        titleRow.gravity =
            Gravity.CENTER_VERTICAL

        titleText =
            TextView(this)

        titleText.text =
            "Wifi checker"

        titleText.textSize = 25f

        titleText.typeface =
            Typeface.DEFAULT_BOLD

        titleRow.addView(
            titleText,
            LinearLayout.LayoutParams(
                -2,
                -2
            )
        )

        betaText =
            TextView(this)

        betaText.text =
            " beta"

        betaText.textSize = 12f

        titleRow.addView(
            betaText,
            LinearLayout.LayoutParams(
                -2,
                -2
            )
        )

        root.addView(
            titleRow,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            )
        )

        val mainRow =
            LinearLayout(this)

        mainRow.orientation =
            LinearLayout.HORIZONTAL

        mainRow.gravity =
            Gravity.TOP

        root.addView(
            mainRow,
            LinearLayout.LayoutParams(
                -1,
                dp(320)
            )
        )

        val terminalBox =
            LinearLayout(this)

        terminalBox.orientation =
            LinearLayout.VERTICAL

        terminalBox.setPadding(
            dp(6),
            dp(5),
            dp(6),
            dp(5)
        )

        terminalTitle =
            TextView(this)

        terminalTitle.text =
            "Terminal"

        terminalTitle.textSize = 14f

        terminalBox.addView(
            terminalTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(27)
            )
        )

        terminalScroll =
            ScrollView(this)

        terminalScroll.isFillViewport =
            true

        terminal =
            TextView(this)

        terminal.textSize = 9f

        terminal.typeface =
            Typeface.MONOSPACE

        terminal.gravity =
            Gravity.TOP or Gravity.START

        terminal.setPadding(
            dp(6),
            dp(6),
            dp(6),
            dp(6)
        )

        terminalScroll.addView(
            terminal,
            ViewGroup.LayoutParams(
                -1,
                -2
            )
        )

        terminalBox.addView(
            terminalScroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        val commandRow =
            LinearLayout(this)

        commandRow.orientation =
            LinearLayout.HORIZONTAL

        commandRow.gravity =
            Gravity.CENTER_VERTICAL

        commandInput =
            EditText(this)

        commandInput.hint =
            "Send command..."

        commandInput.textSize = 10f

        commandInput.setSingleLine(
            true
        )

        commandInput.setPadding(
            dp(7),
            0,
            dp(5),
            0
        )

        commandRow.addView(
            commandInput,
            LinearLayout.LayoutParams(
                0,
                dp(36),
                1f
            )
        )

        val send =
            TextView(this)

        send.text = "▷"
        send.textSize = 18f
        send.gravity = Gravity.CENTER

        send.setOnClickListener {
            sendCommand()
        }

        commandRow.addView(
            send,
            LinearLayout.LayoutParams(
                dp(38),
                dp(36)
            )
        )

        commandInput.setOnEditorActionListener {
                _, _, _ ->

            sendCommand()
            true
        }

        terminalBox.addView(
            commandRow
        )

        mainRow.addView(
            terminalBox,
            LinearLayout.LayoutParams(
                0,
                -1,
                1f
            )
        )

        val buttonColumn =
            LinearLayout(this)

        buttonColumn.orientation =
            LinearLayout.VERTICAL

        buttonColumn.gravity =
            Gravity.TOP

        buttonColumn.setPadding(
            dp(12),
            0,
            0,
            0
        )

        terminalButton =
            makeButton("Terminal")

        terminalButton.setOnClickListener {
            appendTerminal(
                "[INFO] Terminal selected."
            )
        }

        settingButton =
            makeButton("Setting")

        settingButton.setOnClickListener {
            showSettings()
        }

        buttonColumn.addView(
            terminalButton,
            LinearLayout.LayoutParams(
                dp(105),
                dp(52)
            )
        )

        buttonColumn.addView(
            settingButton,
            LinearLayout.LayoutParams(
                dp(105),
                dp(52)
            )
        )

        mainRow.addView(
            buttonColumn
        )

        statsTitle =
            TextView(this)

        statsTitle.text =
            "LAN status"

        statsTitle.textSize = 15f

        statsTitle.typeface =
            Typeface.DEFAULT_BOLD

        root.addView(
            statsTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(30)
            )
        )

        statsText =
            TextView(this)

        statsText.textSize = 10f

        statsText.text =
            "Waiting for scan..."

        root.addView(
            statsText,
            LinearLayout.LayoutParams(
                -1,
                dp(60)
            )
        )

        deviceText =
            TextView(this)

        deviceText.textSize = 9f

        deviceText.typeface =
            Typeface.MONOSPACE

        val deviceScroll =
            ScrollView(this)

        deviceScroll.addView(
            deviceText
        )

        root.addView(
            deviceScroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        applyTheme()
    }

    // =========================================================
    // WIFI CHECKER — BUTTON
    // =========================================================

    private fun makeButton(
        text: String
    ): TextView {

        val button =
            TextView(this)

        button.text = text
        button.textSize = 17f
        button.gravity = Gravity.CENTER
        button.typeface =
            Typeface.DEFAULT_BOLD

        return button
    }

    // =========================================================
    // WIFI CHECKER — SEND COMMAND
    // =========================================================

    private fun sendCommand(): Boolean {

        val command =
            commandInput.text.toString().trim()

        if (command.isEmpty()) {
            return false
        }

        appendTerminal(
            "[CMD] $command"
        )

        commandInput.text.clear()

        handleCommand(command)

        return true
    }

    // =========================================================
    // WIFI CHECKER — TERMINAL
    // =========================================================

    private fun appendTerminal(
        text: String
    ) {

        runOnUiThread {

            if (!::terminal.isInitialized) {
                return@runOnUiThread
            }

            terminal.append(
                "$text\n"
            )

            terminal.post {

                if (
                    ::terminalScroll.isInitialized
                ) {

                    terminalScroll.fullScroll(
                        ScrollView.FOCUS_DOWN
                    )
                }
            }
        }
    }

    // =========================================================
    // WIFI CHECKER — LOCAL IP
    // =========================================================

    private fun getLocalIp(): String? {

        return try {

            val interfaces =
                NetworkInterface
                    .getNetworkInterfaces()

            while (
                interfaces.hasMoreElements()
            ) {

                val network =
                    interfaces.nextElement()

                if (
                    network.isLoopback ||
                    !network.isUp
                ) {
                    continue
                }

                val addresses =
                    network.inetAddresses

                while (
                    addresses.hasMoreElements()
                ) {

                    val address =
                        addresses.nextElement()

                    if (
                        address.isLoopbackAddress
                    ) {
                        continue
                    }

                    val host =
                        address.hostAddress

                    if (
                        host != null &&
                        host.indexOf(':') == -1 &&
                        host.matches(
                            Regex(
                                """\d+\.\d+\.\d+\.\d+"""
                            )
                        )
                    ) {

                        return host
                    }
                }
            }

            null

        } catch (_: Exception) {
            null
        }
    }

    // =========================================================
    // WIFI CHECKER — NETWORK PREFIX
    // =========================================================

    private fun getNetworkPrefix(
        ip: String
    ): String? {

        val parts =
            ip.split(".")

        if (parts.size != 4) {
            return null
        }

        return try {

            parts.forEach {
                it.toInt()
            }

            "${parts[0]}.${parts[1]}.${parts[2]}"

        } catch (_: Exception) {
            null
        }
    }

    // =========================================================
    // WIFI CHECKER — PING
    // =========================================================

    private fun pingIp(
        ip: String
    ): Long? {

        return try {

            val process =
                ProcessBuilder(
                    "ping",
                    "-c",
                    "1",
                    "-W",
                    "1",
                    ip
                )
                    .redirectErrorStream(true)
                    .start()

            val output =
                process.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val exitCode =
                process.waitFor()

            if (exitCode != 0) {
                return null
            }

            val regex =
                Regex(
                    """time[=<]([\d.]+)\s*ms""",
                    RegexOption.IGNORE_CASE
                )

            val match =
                regex.find(output)

            if (match != null) {

                match.groupValues[1]
                    .toDoubleOrNull()
                    ?.roundToInt()
                    ?.toLong()

            } else {
                null
            }

        } catch (_: Exception) {

            if (!pingWarningShown) {

                pingWarningShown = true

                appendTerminal(
                    "[WARNING] Thiết bị của bạn không cho phép ứng dụng sử dụng ICMP ping."
                )

                appendTerminal(
                    "[WARNING] Do giới hạn của Android, kết quả ping có thể không chính xác."
                )

                appendTerminal(
                    "[INFO] Wifi Checker được phát triển bởi Moon Studio."
                )

                appendTerminal(
                    "[INFO] Tính năng ping nâng cao sẽ được cải thiện trong các bản cập nhật sau."
                )
            }

            null
        }
    }

    // =========================================================
    // WIFI CHECKER — TCP
    // =========================================================

    private fun tcpCheck(
        ip: String
    ): Pair<Long?, Int?> {

        for (port in tcpPorts) {

            var socket: Socket? = null

            try {

                socket = Socket()

                val start =
                    System.nanoTime()

                socket.connect(
                    InetSocketAddress(
                        ip,
                        port
                    ),
                    tcpTimeout
                )

                val elapsed =
                    (
                        System.nanoTime() -
                            start
                        ) / 1_000_000L

                socket.close()

                return Pair(
                    elapsed,
                    port
                )

            } catch (_: Exception) {

                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }

        return Pair(
            null,
            null
        )
    }

    // =========================================================
    // WIFI CHECKER — DEVICE NAME
    // =========================================================

    private fun getDeviceName(
        ip: String
    ): String {

        return try {

            val address =
                InetAddress.getByName(ip)

            val host =
                address.canonicalHostName

            if (
                host.isNotBlank() &&
                host != ip
            ) {
                host
            } else {
                "Unknown"
            }

        } catch (_: Exception) {
            "Unknown"
        }
    }

    // =========================================================
    // WIFI CHECKER — SCAN ONE IP
    // =========================================================

    private fun scanIp(
        ip: String
    ): Device? {

        val ping =
            pingIp(ip)

        if (ping != null) {

            val tcp =
                tcpCheck(ip)

            return Device(
                ip = ip,
                name = getDeviceName(ip),
                ping = ping,
                tcp = tcp.second
            )
        }

        return null
    }

    // =========================================================
    // WIFI CHECKER — FULL LAN SCAN
    // =========================================================

    private fun scanLan(
        prefix: String
    ): List<Device> {

        val found =
            mutableListOf<Device>()

        val futures =
            mutableListOf<Future<Device?>>()

        for (i in 1..254) {

            val ip =
                "$prefix.$i"

            futures.add(
                workerPool.submit<Device?> {
                    scanIp(ip)
                }
            )
        }

        for (future in futures) {

            try {

                val result =
                    future.get()

                if (result != null) {
                    found.add(result)
                }

            } catch (_: Exception) {
            }
        }

        found.sortWith(
            Comparator { a, b ->
                compareIp(
                    a.ip,
                    b.ip
                )
            }
        )

        return found
    }

    // =========================================================
    // WIFI CHECKER — IP SORT
    // =========================================================

    private fun compareIp(
        a: String,
        b: String
    ): Int {

        val aa =
            a.split(".")
                .map {
                    it.toIntOrNull() ?: 0
                }

        val bb =
            b.split(".")
                .map {
                    it.toIntOrNull() ?: 0
                }

        for (
            i in 0 until
            minOf(
                aa.size,
                bb.size
            )
        ) {

            if (aa[i] != bb[i]) {
                return aa[i] - bb[i]
            }
        }

        return aa.size - bb.size
    }

    // =========================================================
    // WIFI CHECKER — STATUS
    // =========================================================

    private fun getStatus(
        ping: Long?
    ): String {

        if (ping == null) {
            return "🔴 OFFLINE"
        }

        return when {

            ping < 50L ->
                "🟢 ONLINE"

            ping < 150L ->
                "🟡 UNSTABLE"

            else ->
                "🟡 LAG"
        }
    }

    // =========================================================
    // WIFI CHECKER — START SCANNER
    // =========================================================

    private fun startScanner() {

        if (running) {

            appendTerminal(
                "[INFO] Scanner already running."
            )

            return
        }

        running = true

        scanThread =
            Thread {

                val localIp =
                    getLocalIp()

                if (localIp == null) {

                    appendTerminal(
                        "[ERROR] Cannot get LAN IP."
                    )

                    running = false
                    return@Thread
                }

                val prefix =
                    getNetworkPrefix(localIp)

                if (prefix == null) {

                    appendTerminal(
                        "[ERROR] Cannot determine LAN."
                    )

                    running = false
                    return@Thread
                }

                appendTerminal(
                    "[INFO] Local IP: $localIp"
                )

                appendTerminal(
                    "[INFO] LAN: $prefix.0/24"
                )

                appendTerminal(
                    "[INFO] Ping: ICMP"
                )

                appendTerminal(
                    "[INFO] TCP ports: 80, 443, 8080, 8000, 22"
                )

                appendTerminal(
                    "[SCAN] Initial scan started..."
                )

                val found =
                    scanLan(prefix)

                if (!running) {
                    return@Thread
                }

                devices.clear()

                for (device in found) {
                    devices[device.ip] =
                        device
                }

                appendTerminal(
                    "[SCAN] Found ${found.size} device(s)."
                )

                updateAllUI()

                startMonitor(prefix)

            }.also {
                it.start()
            }
    }

    // =========================================================
    // WIFI CHECKER — MONITOR
    // =========================================================

    private fun startMonitor(
        prefix: String
    ) {

        monitorThread =
            Thread {

                var lastNewScan =
                    System.currentTimeMillis()

                while (running) {

                    val start =
                        System.currentTimeMillis()

                    updateKnownDevices()
                    updateAllUI()

                    val now =
                        System.currentTimeMillis()

                    if (
                        now - lastNewScan >=
                        newDeviceInterval
                    ) {

                        appendTerminal(
                            "[SCAN] Searching for new devices..."
                        )

                        val found =
                            scanLan(prefix)

                        var added = 0

                        for (device in found) {

                            if (
                                !devices.containsKey(
                                    device.ip
                                )
                            ) {

                                devices[device.ip] =
                                    device

                                added++
                            }
                        }

                        appendTerminal(
                            "[SCAN] New devices: $added"
                        )

                        lastNewScan =
                            System.currentTimeMillis()

                        updateAllUI()
                    }

                    val elapsed =
                        System.currentTimeMillis() -
                            start

                    val sleep =
                        monitorInterval -
                            elapsed

                    if (sleep > 0) {

                        try {

                            Thread.sleep(sleep)

                        } catch (
                            _: InterruptedException
                        ) {
                            break
                        }
                    }
                }

            }.also {
                it.start()
            }
    }

    // =========================================================
    // WIFI CHECKER — UPDATE KNOWN DEVICES
    // =========================================================

    private fun updateKnownDevices() {

        val current =
            devices.keys.toList()

        if (current.isEmpty()) {
            return
        }

        val futures =
            mutableListOf<
                Future<UpdateResult>
            >()

        for (ip in current) {

            futures.add(
                workerPool.submit<UpdateResult> {

                    val ping =
                        pingIp(ip)

                    var tcpPort:
                        Int? = null

                    if (ping != null) {
                        tcpPort =
                            tcpCheck(ip).second
                    }

                    UpdateResult(
                        ip = ip,
                        ping = ping,
                        tcp = tcpPort
                    )
                }
            )
        }

        for (future in futures) {

            try {

                val result =
                    future.get()

                val device =
                    devices[result.ip]

                if (device != null) {

                    device.ping =
                        result.ping

                    device.tcp =
                        result.tcp
                }

            } catch (_: Exception) {
            }
        }
    }

    // =========================================================
    // WIFI CHECKER — UPDATE UI
    // =========================================================

    private fun updateAllUI() {

        runOnUiThread {

            if (!isFinishing) {

                updateStats()
                updateDeviceList()
            }
        }
    }

    // =========================================================
    // WIFI CHECKER — STATS
    // =========================================================

    private fun updateStats() {

        val list =
            devices.values.toList()

        var online = 0
        var unstable = 0
        var offline = 0

        val pings =
            mutableListOf<Long>()

        for (device in list) {

            val ping =
                device.ping

            if (ping == null) {

                offline++

            } else {

                pings.add(ping)

                if (ping < 50) {
                    online++
                } else {
                    unstable++
                }
            }
        }

        val avg =
            if (pings.isNotEmpty()) {
                pings.average()
            } else {
                0.0
            }

        val minimum =
            pings.minOrNull()

        val maximum =
            pings.maxOrNull()

        val variation =
            if (
                minimum != null &&
                maximum != null
            ) {
                maximum - minimum
            } else {
                0
            }

        val assessment =
            when {

                pings.isEmpty() ->
                    "❌ Không có thiết bị phản hồi"

                avg < 50 ->
                    "🟢 LAN RẤT ỔN ĐỊNH"

                avg < 100 ->
                    "🟢 LAN ỔN ĐỊNH"

                avg < 200 ->
                    "🟡 LAN HƠI CHẬM"

                else ->
                    "🔴 LAN ĐANG LAG"
            }

        statsText.text =
            "Thiết bị: ${list.size}    " +
            "Online: $online    " +
            "Chập chờn/Lag: $unstable    " +
            "Offline: $offline\n" +

            "Ping TB: ${formatPing(avg)}    " +
            "Thấp nhất: ${minimum ?: "N/A"} ms    " +
            "Cao nhất: ${maximum ?: "N/A"} ms    " +
            "Dao động: $variation ms\n" +

            "Đánh giá: $assessment"
    }

    // =========================================================
    // WIFI CHECKER — DEVICE LIST
    // =========================================================

    private fun updateDeviceList() {

        val sorted =
            devices.values
                .toList()
                .sortedWith(
                    Comparator { a, b ->
                        compareIp(
                            a.ip,
                            b.ip
                        )
                    }
                )

        val builder =
            StringBuilder()

        builder.append(
            "IP               NAME                 PING       TCP   STATUS\n"
        )

        builder.append(
            "──────────────────────────────────────────────────────────────\n"
        )

        for (device in sorted) {

            val name =
                device.name.take(19)

            val ping =
                device.ping
                    ?.let { "$it ms" }
                    ?: "N/A"

            val tcp =
                device.tcp?.toString()
                    ?: "N/A"

            builder.append(
                String.format(
                    "%-16s %-20s %-10s %-5s %s\n",
                    device.ip,
                    name,
                    ping,
                    tcp,
                    getStatus(
                        device.ping
                    )
                )
            )
        }

        deviceText.text =
            builder.toString()
    }

    // =========================================================
    // WIFI CHECKER — FORMAT PING
    // =========================================================

    private fun formatPing(
        value: Double
    ): String {

        return if (value <= 0) {
            "N/A"
        } else {
            "${value.roundToInt()} ms"
        }
    }

    // =========================================================
    // WIFI CHECKER — COMMAND
    // =========================================================

    private fun handleCommand(
        command: String
    ) {

        when (command.lowercase()) {

            "scan" -> {

                appendTerminal(
                    "[CMD] Manual scan started..."
                )

                manualScan()
            }

            "devices" -> {

                if (devices.isEmpty()) {

                    appendTerminal(
                        "[INFO] No devices."
                    )

                } else {

                    for (
                        device in
                        devices.values
                    ) {

                        appendTerminal(
                            "${device.ip} | " +
                            "${device.name} | " +
                            "${device.ping ?: "N/A"} ms | " +
                            "${device.tcp ?: "N/A"} | " +
                            getStatus(
                                device.ping
                            )
                        )
                    }
                }
            }

            "clear" -> {

                terminal.text = ""

                appendTerminal(
                    "[INFO] Terminal cleared."
                )
            }

            "stop" ->
                stopScanner()

            "start" ->
                startScanner()

            "help" -> {

                appendTerminal(
                    "[HELP] scan - scan LAN"
                )

                appendTerminal(
                    "[HELP] devices - show devices"
                )

                appendTerminal(
                    "[HELP] clear - clear terminal"
                )

                appendTerminal(
                    "[HELP] stop - stop monitor"
                )

                appendTerminal(
                    "[HELP] start - start monitor"
                )

                appendTerminal(
                    "[HELP] settings - open settings"
                )
            }

            "settings" ->
                showSettings()

            else -> {

                appendTerminal(
                    "[ERROR] Unknown command: $command"
                )
            }
        }
    }

    // =========================================================
    // WIFI CHECKER — MANUAL SCAN
    // =========================================================

    private fun manualScan() {

        Thread {

            val localIp =
                getLocalIp()

            if (localIp == null) {

                appendTerminal(
                    "[ERROR] Cannot get local IP."
                )

                return@Thread
            }

            val prefix =
                getNetworkPrefix(localIp)

            if (prefix == null) {

                appendTerminal(
                    "[ERROR] Cannot determine network."
                )

                return@Thread
            }

            val found =
                scanLan(prefix)

            for (device in found) {
                devices[device.ip] =
                    device
            }

            appendTerminal(
                "[SCAN] Scan finished: ${found.size} device(s)."
            )

            updateAllUI()

        }.start()
    }

    // =========================================================
    // WIFI CHECKER — SETTINGS
    // =========================================================

    private fun showSettings() {

        val overlay =
            LinearLayout(this)

        overlay.orientation =
            LinearLayout.VERTICAL

        overlay.setPadding(
            dp(22),
            dp(22),
            dp(22),
            dp(22)
        )

        val settingsTitle =
            TextView(this)

        settingsTitle.text =
            "Settings"

        settingsTitle.textSize = 25f

        settingsTitle.typeface =
            Typeface.DEFAULT_BOLD

        overlay.addView(
            settingsTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(55)
            )
        )

        val themeTitle =
            TextView(this)

        themeTitle.text =
            "Appearance"

        themeTitle.textSize = 16f

        themeTitle.typeface =
            Typeface.DEFAULT_BOLD

        overlay.addView(
            themeTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(40)
            )
        )

        val themeButton =
            Button(this)

        themeButton.text =
            if (darkMode) {
                "Dark mode"
            } else {
                "Light mode"
            }

        themeButton.setOnClickListener {

            darkMode = !darkMode

            applyTheme()

            themeButton.text =
                if (darkMode) {
                    "Dark mode"
                } else {
                    "Light mode"
                }

            appendTerminal(
                "[INFO] Theme changed: " +
                if (darkMode) {
                    "Dark"
                } else {
                    "Light"
                }
            )
        }

        overlay.addView(
            themeButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        val info =
            TextView(this)

        info.text =
            "\nWifi Checker\n" +
            "Developed by Moon Studio\n\n" +
            "Terminal logs are preserved when\n" +
            "changing the application theme."

        info.textSize = 13f

        overlay.addView(
            info,
            LinearLayout.LayoutParams(
                -1,
                dp(120)
            )
        )

        val close =
            Button(this)

        close.text =
            "Close"

        overlay.addView(
            close,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        val dialog =
            android.app.Dialog(this)

        dialog.setContentView(
            overlay
        )

        close.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window
            ?.setBackgroundDrawableResource(
                android.R.color.transparent
            )

        dialog.show()
    }

    // =========================================================
    // WIFI CHECKER — APPLY THEME
    // =========================================================

    private fun applyTheme() {

        if (!::root.isInitialized) {
            return
        }

        if (darkMode) {

            root.setBackgroundColor(
                darkBackground
            )

            terminal.setBackgroundColor(
                darkTerminal
            )

            terminalTitle.setTextColor(
                darkText
            )

            titleText.setTextColor(
                darkText
            )

            betaText.setTextColor(
                darkGray
            )

            statsTitle.setTextColor(
                darkText
            )

            statsText.setTextColor(
                darkGray
            )

            deviceText.setTextColor(
                darkText
            )

            terminalButton.setTextColor(
                darkText
            )

            settingButton.setTextColor(
                darkText
            )

            commandInput.setTextColor(
                darkText
            )

            commandInput.setHintTextColor(
                darkGray
            )

        } else {

            root.setBackgroundColor(
                lightBackground
            )

            terminal.setBackgroundColor(
                lightTerminal
            )

            terminalTitle.setTextColor(
                lightText
            )

            titleText.setTextColor(
                lightText
            )

            betaText.setTextColor(
                lightGray
            )

            statsTitle.setTextColor(
                lightText
            )

            statsText.setTextColor(
                lightGray
            )

            deviceText.setTextColor(
                lightText
            )

            terminalButton.setTextColor(
                lightText
            )

            settingButton.setTextColor(
                lightText
            )

            commandInput.setTextColor(
                lightText
            )

            commandInput.setHintTextColor(
                lightGray
            )
        }
    }

    // =========================================================
    // WIFI CHECKER — STOP
    // =========================================================

    private fun stopScanner() {

        running = false

        try {
            monitorThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            scanThread?.interrupt()
        } catch (_: Exception) {
        }

        appendTerminal(
            "[INFO] Scanner stopped."
        )
    }

    // =========================================================
    // LAYOUT HELPERS
    // =========================================================

    private fun matchWrap():
        LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun matchWrapWithTopMargin(
        margin: Int
    ): LinearLayout.LayoutParams {

        val params =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

        params.topMargin =
            margin

        return params
    }

    private fun weightButton():
        LinearLayout.LayoutParams {

        val params =
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

        params.weight = 1f

        params.setMargins(
            dp(3),
            0,
            dp(3),
            0
        )

        return params
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        strokeWidth: Int
    ): android.graphics.drawable.GradientDrawable {

        val drawable =
            android.graphics.drawable.GradientDrawable()

        drawable.setColor(
            fillColor
        )

        drawable.cornerRadius =
            dp(12).toFloat()

        if (strokeWidth > 0) {

            drawable.setStroke(
                dp(strokeWidth),
                strokeColor
            )
        }

        return drawable
    }

    private fun dp(
        value: Int
    ): Int {

        val density =
            resources.displayMetrics.density

        return (
            value * density + 0.5f
            ).toInt()
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(
            null
        )

        musicPlayer?.release()
        musicPlayer = null

        running = false

        try {
            monitorThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            scanThread?.interrupt()
        } catch (_: Exception) {
        }

        workerPool.shutdownNow()

        super.onDestroy()
    }
}