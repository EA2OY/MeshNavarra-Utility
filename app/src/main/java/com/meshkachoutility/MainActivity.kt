/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.Portnums.PortNum
import org.meshtastic.proto.TelemetryProtos
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), UsbConnectionManager.ConnectionListener {

    /**
     * Applies the user-selected UI language (pref "app_lang": "es"/"en").
     * Empty = follow the system locale. Applied on every recreation so the
     * whole UI re-renders with the new locale.
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_LANG, "") ?: ""
        super.attachBaseContext(if (lang.isEmpty()) newBase else wrapLocale(newBase, lang))
    }

    private fun wrapLocale(base: Context, lang: String): ContextWrapper {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale(lang))
        return ContextWrapper(base.createConfigurationContext(config))
    }

    private lateinit var usbConnectionManager: UsbConnectionManager
    private lateinit var bleConnectionManager: BleConnectionManager
    private var bleTransportActive = false

    // Auto-reconnect after an unexpected disconnect (node reboot, USB detach,
    // BLE drop). Only kicks in after a real connection existed and is cancelled
    // on user-initiated connects or when the attempts are exhausted.
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var everConnected = false
    private var userInitiatedDisconnect = false
    private var usbPermRequestedThisCycle = false
    private var appInBackground = false
    private var usbPermPending = false

    private var pendingDownloadAssetName: String? = null
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        val assetName = pendingDownloadAssetName ?: return@registerForActivityResult
        pendingDownloadAssetName = null
        if (uri != null) {
            saveAssetToUri(assetName, uri)
        }
    }

    private lateinit var streamApiUnframer: StreamApiUnframer
    private lateinit var statusText: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var connectBluetoothButton: Button
    private lateinit var disconnectBluetoothButton: MaterialButton
    private lateinit var logText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectUsbButton: MaterialButton
    private lateinit var clearLogButton: MaterialButton
    private lateinit var helpButton: MaterialButton
    private lateinit var targetNodeInput: TextInputEditText
    private lateinit var queryNodeButton: MaterialButton
    private lateinit var rebootButton: MaterialButton
    private lateinit var wipeNodeDbButton: MaterialButton
    private lateinit var factoryResetButton: MaterialButton
    private lateinit var keepFavoritesCheckbox: MaterialCheckBox
    private lateinit var favoriteButton: MaterialButton
    private lateinit var favoriteInput: TextInputEditText
    private lateinit var unsetFavoriteButton: MaterialButton
    private lateinit var ignoredButton: MaterialButton
    private lateinit var ignoredInput: TextInputEditText
    private lateinit var unsetIgnoredButton: MaterialButton
    private lateinit var removeNodeInput: TextInputEditText
    private lateinit var removeNodeButton: MaterialButton
    private lateinit var adminKeysText: TextView
    private lateinit var adminKeyInput: TextInputEditText
    private lateinit var adminKeyAddButton: MaterialButton
    private lateinit var masterConvertButton: MaterialButton
    private lateinit var lowImpactSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var hopInput: TextInputEditText
    private lateinit var hopApplyButton: MaterialButton
    private lateinit var freqInput: TextInputEditText
    private lateinit var freqApplyButton: MaterialButton
    private lateinit var dutyCycleSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var sensorEnvSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var sensorPowerSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var sensorStatusText: TextView
    private lateinit var presetApplyButton: MaterialButton
    private var awaitingSensorRead: Int? = null
    private var awaitingSensorAck: Int? = null
    private var sensorPendingEnv = false
    private var sensorPendingPower = false
    private lateinit var batterySpinner: Spinner
    private lateinit var navadminTestStatus: TextView
    private lateinit var navadminTestButton: MaterialButton
    private lateinit var navadminTestStopButton: MaterialButton
    private data class AuditStep(val label: String, val action: () -> ByteArray?)
    private val auditHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val auditDialogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var auditBatteryRunning = false
    private var auditIndex = 0
    private var auditName = ""
    private var auditIntervalMs = 0L
    private var auditSteps: List<AuditStep> = emptyList()
    private var auditFile: File? = null
    private var auditDialog: androidx.appcompat.app.AlertDialog? = null
    private var auditConsoleText: TextView? = null
    private var lowImpactApplied = false
    private var savedLowImpactLora: org.meshtastic.proto.ConfigProtos.Config.LoRaConfig? = null
    private var pendingAdminKey: String? = null
    private var pendingMasterKey: String? = null
    private var awaitingAdminKeyRead: Int? = null
    private var awaitingAdminKeyAck: Int? = null
    private var lastSecurityConfig: ConfigProtos.Config.SecurityConfig? = null

    private lateinit var adminPanel: ScrollView
    private lateinit var commandsPanel: ScrollView
    private lateinit var nodesPanel: ScrollView
    private lateinit var nodesListContainer: LinearLayout
    private lateinit var logPanel: ScrollView
    private lateinit var debugPanel: ScrollView
    private lateinit var logFileText: TextView
    private lateinit var bpPanel: ScrollView
    private lateinit var bottomTabs: TabLayout
    private lateinit var tabContent: FrameLayout
    private lateinit var tabHintLeft: ImageView
    private lateinit var tabHintRight: ImageView
    private lateinit var cmdTargetInput: TextInputEditText
    private lateinit var cmdTextInput: TextInputEditText
    private lateinit var sendCmdButton: MaterialButton
    private lateinit var cmdTelemetryButton: MaterialButton
    private lateinit var cmdPositionButton: MaterialButton
    private lateinit var cmdTraceButton: MaterialButton
    private lateinit var cmdSetOwnerButton: MaterialButton
    private lateinit var bpTargetInput: TextInputEditText
    private lateinit var bpHelpButton: MaterialButton
    private lateinit var bpApplyButton: MaterialButton
    private lateinit var bpBackupButton: MaterialButton
    private lateinit var bpRestoreButton: MaterialButton
    private lateinit var bpStatusText: TextView
    private lateinit var chatPanel: LinearLayout
    private lateinit var chatChannelLabel: TextView
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private var chatAutoScrollPaused = false
    private val chatScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var chatReplyInput: TextInputEditText
    private lateinit var topArea: LinearLayout
    private var syncingTarget = false
    private lateinit var chatPauseButton: MaterialButton
    private lateinit var chatSendButton: MaterialButton
    private lateinit var navaPanel: LinearLayout
    private lateinit var navaTargetInput: TextInputEditText
    private lateinit var navaCategorySpinner: Spinner
    private lateinit var navaCommandSpinner: Spinner
    private lateinit var navaArgInput: TextInputEditText
    private lateinit var navaOptionSpinner: Spinner
    private lateinit var navaRouteToggle: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var navaRouteCh: com.google.android.material.button.MaterialButton
    private lateinit var navaRouteDm: com.google.android.material.button.MaterialButton
    private lateinit var navaDescText: TextView
    private lateinit var navaPreviewText: TextView
    private lateinit var navaHelpButton: MaterialButton
    private lateinit var navaSendButton: MaterialButton
    private lateinit var navaMessagesContainer: LinearLayout
    private lateinit var navaArgLayout: TextInputLayout
    private lateinit var nodesImportInput: TextInputEditText
    private lateinit var nodesImportButton: MaterialButton

    private var pendingQueryRequestId: Int? = null
    private var pendingConfigPhase: Int = 0
    private var nodeInfoCount = 0
    private var totalNodeInfos = 0
    private var localNodeNum: Int? = null
    private var localLongName = ""
    private var localShortName = ""
    private val nodeInfoLines = mutableListOf<String>()

    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressLabel: TextView? = null

    private data class NodeEntry(
        val num: Int,
        val name: String,
        val isFavorite: Boolean,
        val battery: Int = -1,
        val voltage: Float = 0f,
        val snr: Float = 0f,
        val lastHeard: Long = 0L,
        val hops: Int = -1,
        val cached: Boolean = false,
        val pubKey: String? = null
    )
    private val nodeEntries = LinkedHashMap<Int, NodeEntry>()
    private val nodeInfos = LinkedHashMap<Int, org.meshtastic.proto.MeshProtos.NodeInfo>()

    private var nodePopupNum = -1
    private var nodePopupDialog: androidx.appcompat.app.AlertDialog? = null
    private var nodePopupBody: TextView? = null
    private var nodePopupConsole: TextView? = null
    private var nodePopupUseNava = false

    private var pendingResponseAction: String? = null
    private var responseDialog: androidx.appcompat.app.AlertDialog? = null
    private var responseTextRef: TextView? = null

    private data class NavaCmd(val label: String, val cmd: String, val argType: String, val mode: String, val desc: String, val options: List<String> = emptyList(), val warn: String = "")
    private data class NavaCat(val label: String, val cmds: List<NavaCmd>)
    private var navaCategories = listOf<NavaCat>()
    private data class NavaMsg(val from: Int, val text: String, val time: String, val sent: Boolean, val route: String = "ch")
    private val navaMessages = mutableListOf<NavaMsg>()
    private var navaTargetNode = -1
    private var navaCmdAdapter: NavaCommandAdapter? = null
    private var navaLastValidCommandPos = 0
    private var navaRevertingCommand = false
    // Long NavaTastic replies arrive fragmented (max 190 chars / 12 s apart). This
    // key tracks the last fragment so consecutive ones from the same node+route
    // get concatenated into a single conversation message instead of split lines.
    private var navaFragmentKey = ""
    private var navaFragmentTime = 0L

    private data class ChatMessage(
        val from: Int,
        val text: String,
        val channel: Int,
        val time: String,
        val packetId: Int = -1,
        val status: String = "",
        val routingError: String = "",
        val relays: Int = 0
    )
    private val channelNames = mutableListOf<String>()
    private var navadminChannelIndex = 1
    private var navadminChannelSeen = false
    private val chatMessages = mutableListOf<ChatMessage>()
    private var currentChatChannel = 0
    private var chatPaused = false
    private var chatSpinnerUpdating = false
    private val chatCollapsedChannels = mutableSetOf<Int>()
    private lateinit var chatChannelSpinner: Spinner

    private data class ConfigJob(
        val label: String,
        val section: org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType,
        val modify: (org.meshtastic.proto.ConfigProtos.Config.Builder) -> Unit
    )
    private var configJobs = mutableListOf<ConfigJob>()
    private var configJobIndex = 0
    private var configTarget = -1
    private var awaitingConfigAck: Int? = null
    private var awaitingConfigRead: Int? = null
    private val configHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private data class SectionItem(val isModule: Boolean, val type: Int)
    private var backupPlan = mutableListOf<SectionItem>()
    private var backupIndex = 0
    private var backupResults = mutableListOf<ByteArray>()
    private var backupRunning = false
    private var waitingBackupSection = false

    private var restorePlan = mutableListOf<Triple<Boolean, Int, ByteArray>>()
    private var restoreIndex = 0
    private var restoreRunning = false

    private val configSectionTypes = listOf(
        AdminMessage.ConfigType.DEVICE_CONFIG, AdminMessage.ConfigType.POSITION_CONFIG,
        AdminMessage.ConfigType.POWER_CONFIG, AdminMessage.ConfigType.NETWORK_CONFIG,
        AdminMessage.ConfigType.DISPLAY_CONFIG, AdminMessage.ConfigType.LORA_CONFIG,
        AdminMessage.ConfigType.BLUETOOTH_CONFIG, AdminMessage.ConfigType.SECURITY_CONFIG
    )
    private val moduleSectionTypes = listOf(
        AdminMessage.ModuleConfigType.MQTT_CONFIG, AdminMessage.ModuleConfigType.SERIAL_CONFIG,
        AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG, AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG,
        AdminMessage.ModuleConfigType.RANGETEST_CONFIG, AdminMessage.ModuleConfigType.TELEMETRY_CONFIG,
        AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG, AdminMessage.ModuleConfigType.AUDIO_CONFIG,
        AdminMessage.ModuleConfigType.REMOTEHARDWARE_CONFIG, AdminMessage.ModuleConfigType.NEIGHBORINFO_CONFIG,
        AdminMessage.ModuleConfigType.AMBIENTLIGHTING_CONFIG, AdminMessage.ModuleConfigType.DETECTIONSENSOR_CONFIG,
        AdminMessage.ModuleConfigType.PAXCOUNTER_CONFIG, AdminMessage.ModuleConfigType.STATUSMESSAGE_CONFIG
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "meshkacho"
        private const val PREF_LANG = "app_lang"
        private const val PREF_LOW_IMPACT = "low_impact"
        private const val AUDIT_POPUP_AUTOCLOSE_MS = 6000L
        private const val CONFIG_ACK_TIMEOUT_MS = 20000L
        private const val REQ_BLE_PERMISSIONS = 2001
        private const val DOWNLOAD_TIMEOUT_MS = 40000L
        private const val BACKUP_SECTION_TIMEOUT_MS = 4000L
        // Two-phase config handshake nonces (firmware PhoneAPI.h): 69420 = config +
        // channels + own NodeInfo (no file manifest), 69421 = only the node DB.
        private const val CONFIG_PHASE1_NONCE = 69420
        private const val CONFIG_PHASE2_NONCE = 69421
        private const val CHAT_HISTORY_PER_CHANNEL = 100
        // Firmware fragments long /nava replies at 190 chars every 12 s; treat
        // fragments from the same node+route within 15 s as one message.
        const val NAVA_FRAGMENT_WINDOW_MS = 15000L
        const val CHAT_STATUS_ENROUTE = "enroute"
        const val CHAT_STATUS_DELIVERED = "delivered"
        const val CHAT_STATUS_RECEIVED = "received"
        const val CHAT_STATUS_ERROR = "error"
    private const val MAX_PICKER_ROWS = 150
    private const val MAX_NODES_TAB_ROWS = 150
    private const val BUILD_DATE = "2026-08-15"
    private const val RECONNECT_DELAY_MS = 5000L
    private const val RECONNECT_MAX_ATTEMPTS = 5
    private const val CHAT_AUTOSCROLL_RESUME_MS = 10000L
    private const val NAVADMIN_TEST_INTERVAL_MS = 32000L
    private const val B_ID = 0x3a89ac94
    private val NAVADMIN_TEST_COMMANDS = listOf(
        "/nava ping",
        "/nava status",
        "/nava env",
        "/nava channel",
        "/nava peers",
        "/nava bat",
        "/nava help",
        "/nava help fav",
        "/nava route !5cfaaca9",
        "/nava trace !5cfaaca9"
    )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge (enforced by Android 15 with targetSdk 35): draw behind
        // the system bars and pad the root so the header and the tab bar never
        // sit under the status/navigation bars. Base padding keeps the 16dp design.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val base = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }

        // The top area (header + status card) is reparented into the selected
        // panel's scrollable content, so it scrolls away like any other element.
        topArea = findViewById(R.id.topArea)

        // Binding Material 3 components
        statusText = findViewById(R.id.statusText)
        attachDebugTabGesture()
        statusProgress = findViewById(R.id.statusProgress)
        connectBluetoothButton = findViewById(R.id.connectBluetoothButton)
        disconnectBluetoothButton = findViewById(R.id.disconnectBluetoothButton)
        logText = findViewById(R.id.logText)
        connectButton = findViewById(R.id.connectButton)
        disconnectUsbButton = findViewById(R.id.disconnectUsbButton)
        helpButton = findViewById(R.id.helpButton)
        targetNodeInput = findViewById(R.id.targetNodeInput)
        queryNodeButton = findViewById(R.id.queryNodeButton)
        rebootButton = findViewById(R.id.rebootButton)
        wipeNodeDbButton = findViewById(R.id.wipeNodeDbButton)
        factoryResetButton = findViewById(R.id.factoryResetButton)
        keepFavoritesCheckbox = findViewById(R.id.keepFavoritesCheckbox)
        favoriteButton = findViewById(R.id.favoriteButton)
        favoriteInput = findViewById(R.id.favoriteInput)
        unsetFavoriteButton = findViewById(R.id.unsetFavoriteButton)
        ignoredButton = findViewById(R.id.ignoredButton)
        ignoredInput = findViewById(R.id.ignoredInput)
        unsetIgnoredButton = findViewById(R.id.unsetIgnoredButton)
        removeNodeInput = findViewById(R.id.removeNodeInput)
        removeNodeButton = findViewById(R.id.removeNodeButton)
        adminKeysText = findViewById(R.id.adminKeysText)
        adminKeyInput = findViewById(R.id.adminKeyInput)
        adminKeyAddButton = findViewById(R.id.adminKeyAddButton)
        masterConvertButton = findViewById(R.id.masterConvertButton)
        lowImpactSwitch = findViewById(R.id.lowImpactSwitch)
        hopInput = findViewById(R.id.hopInput)
        hopApplyButton = findViewById(R.id.hopApplyButton)
        freqInput = findViewById(R.id.freqInput)
        freqApplyButton = findViewById(R.id.freqApplyButton)
        dutyCycleSwitch = findViewById(R.id.dutyCycleSwitch)
        sensorEnvSwitch = findViewById(R.id.sensorEnvSwitch)
        sensorPowerSwitch = findViewById(R.id.sensorPowerSwitch)
        sensorStatusText = findViewById(R.id.sensorStatus)
        presetApplyButton = findViewById(R.id.presetApplyButton)
        navadminTestStatus = findViewById(R.id.navadminTestStatus)
        navadminTestButton = findViewById(R.id.navadminTestButton)
        navadminTestStopButton = findViewById(R.id.navadminTestStopButton)
        batterySpinner = findViewById(R.id.batterySpinner)

        adminPanel = findViewById(R.id.adminPanel)
        debugPanel = findViewById(R.id.debugPanel)
        commandsPanel = findViewById(R.id.commandsPanel)
        nodesPanel = findViewById(R.id.nodesPanel)
        nodesListContainer = findViewById(R.id.nodesListContainer)
        nodesImportInput = findViewById(R.id.nodesImportInput)
        nodesImportButton = findViewById(R.id.nodesImportButton)
        logPanel = findViewById(R.id.logPanel)
        clearLogButton = findViewById(R.id.clearLogButton)
        logFileText = findViewById(R.id.logFileText)
        bpPanel = findViewById(R.id.bpPanel)
        bpHelpButton = findViewById(R.id.bpHelpButton)
        bpTargetInput = findViewById(R.id.bpTargetInput)
        bpApplyButton = findViewById(R.id.bpApplyButton)
        bpBackupButton = findViewById(R.id.bpBackupButton)
        bpRestoreButton = findViewById(R.id.bpRestoreButton)
        bpStatusText = findViewById(R.id.bpStatusText)
        chatPanel = findViewById(R.id.chatPanel)
        chatChannelLabel = findViewById(R.id.chatChannelLabel)
        chatChannelSpinner = findViewById(R.id.chatChannelSpinner)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        chatScroll = findViewById(R.id.chatScroll)
        chatReplyInput = findViewById(R.id.chatReplyInput)
        chatPauseButton = findViewById(R.id.chatPauseButton)
        chatSendButton = findViewById(R.id.chatSendButton)
        navaPanel = findViewById(R.id.navaPanel)
        navaTargetInput = findViewById(R.id.navaTargetInput)
        navaCategorySpinner = findViewById(R.id.navaCategorySpinner)
        navaCommandSpinner = findViewById(R.id.navaCommandSpinner)
        navaArgInput = findViewById(R.id.navaArgInput)
        navaOptionSpinner = findViewById(R.id.navaOptionSpinner)
        navaRouteToggle = findViewById(R.id.navaRouteToggle)
        navaRouteCh = findViewById(R.id.navaRouteCh)
        navaRouteDm = findViewById(R.id.navaRouteDm)
        navaDescText = findViewById(R.id.navaDescText)
        navaPreviewText = findViewById(R.id.navaPreviewText)
        navaHelpButton = findViewById(R.id.navaHelpButton)
        navaSendButton = findViewById(R.id.navaSendButton)
        navaMessagesContainer = findViewById(R.id.navaMessagesContainer)
        navaArgLayout = findViewById(R.id.navaArgLayout)
        bottomTabs = findViewById(R.id.bottomTabs)
        tabContent = findViewById(R.id.tabContent)
        tabHintLeft = findViewById(R.id.tabHintLeft)
        tabHintRight = findViewById(R.id.tabHintRight)
        cmdTargetInput = findViewById(R.id.cmdTargetInput)
        cmdTextInput = findViewById(R.id.cmdTextInput)
        sendCmdButton = findViewById(R.id.sendCmdButton)
        cmdTelemetryButton = findViewById(R.id.cmdTelemetryButton)
        cmdPositionButton = findViewById(R.id.cmdPositionButton)
        cmdTraceButton = findViewById(R.id.cmdTraceButton)
        cmdSetOwnerButton = findViewById(R.id.cmdSetOwnerButton)

        // Shared target node across tabs: every target field mirrors the others
        // until the user changes it or clears it with the X.
        val targetWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                syncTargetInputs(s?.toString() ?: "")
            }
        }
        targetNodeInput.addTextChangedListener(targetWatcher)
        cmdTargetInput.addTextChangedListener(targetWatcher)
        bpTargetInput.addTextChangedListener(targetWatcher)
        navaTargetInput.addTextChangedListener(targetWatcher)

        setupBottomTabs()
        attachHeaderToCurrentPanel()
        setupCommands()
        setupNodePickers()
        setupGoodPractices()
        setupChat()
        setupNavaTastic()
        RemoteControlReceiver.handler = { cmd, num, arg, arg2 ->
            runOnUiThread { handleRemoteCommand(cmd, num, arg, arg2) }
        }
        applyPressAnimations()
        connectBluetoothButton.setOnClickListener {
            if (demoMode) demoSimulateConnect() else {
                userInitiatedDisconnect = true
                cancelReconnect()
                connectViaBluetooth()
            }
        }
        disconnectBluetoothButton.setOnClickListener {
            pressFeedback(disconnectBluetoothButton)
            disconnectBluetooth()
        }
        showDisclaimerIfNeeded()
        loadNodeCache()

        // Safety net: the startup reparent must also happen after the first
        // layout pass, whatever order the early events fired in.
        topArea.post {
            if (bottomTabs.selectedTabPosition >= 0) {
                applyTabVisibility(bottomTabs.selectedTabPosition)
            }
        }

        usbConnectionManager = UsbConnectionManager(this, this)
        bleConnectionManager = BleConnectionManager(this, this, onLog = { appendLog(it) })

        // Instantiate stream unframer with parsing callbacks
        streamApiUnframer = StreamApiUnframer(object : StreamApiUnframer.Callback {
            override fun onFromRadioDecoded(fromRadio: FromRadio) {
                handleFromRadio(fromRadio)
            }

            override fun onDecodingError(exception: Exception) {
                appendLog(getString(R.string.log_decoding_error, exception.localizedMessage))
            }
        })

        // User Manual / Help Dialog wiring (manual + author + self-test)
        helpButton.setOnClickListener {
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
            } catch (e: Exception) {
                "1.0.0"
            }
            val textView = TextView(this).apply {
                text = getString(R.string.user_manual_content)
                setPadding(dp(20), dp(16), dp(20), dp(8))
                textSize = 14f
                movementMethod = ScrollingMovementMethod()
            }
            // Footer with a clickable e-mail (opens the user's mail app with the
            // address pre-filled and a fixed subject) + version + license + source.
            val email = getString(R.string.app_email)
            val githubUrl = getString(R.string.app_github_url)
            val footerText = getString(R.string.app_author) + "\n" +
                    getString(R.string.app_version_fork, versionName, BUILD_DATE) + "\n" +
                    getString(R.string.app_license_name) + "\n" +
                    getString(R.string.app_github_hint)
            val footerSpannable = android.text.SpannableString(footerText)
            val emailAt = footerText.indexOf(email)
            if (emailAt >= 0) {
                footerSpannable.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            try {
                                startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("mailto:$email")
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.app_email_subject))
                                    }
                                )
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, getString(R.string.log_error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    emailAt, emailAt + email.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val urlAt = footerText.indexOf(githubUrl)
            if (urlAt >= 0) {
                footerSpannable.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            try {
                                startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(githubUrl))
                                )
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, getString(R.string.log_error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    urlAt, urlAt + githubUrl.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val footer = TextView(this).apply {
                text = footerSpannable
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setLinkTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
                setPadding(dp(20), dp(4), dp(20), dp(8))
                textSize = 12f
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            // Reinforced disclaimer shown inside Help too (not only first launch).
            val disclaimerView = TextView(this).apply {
                text = getString(R.string.disclaimer_body)
                setPadding(dp(20), dp(4), dp(20), dp(8))
                textSize = 12f
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            val licenseBtn = MaterialButton(this).apply {
                text = getString(R.string.license_button)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColorAttr(com.google.android.material.R.attr.colorSecondaryContainer)
                )
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSecondaryContainer))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(8)) }
            }
            val appManual = MaterialButton(this).apply {
                text = getString(R.string.cmd_app_manual)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColorAttr(com.google.android.material.R.attr.colorSecondaryContainer)
                )
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSecondaryContainer))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(8)) }
            }
            val navaManual = MaterialButton(this).apply {
                text = getString(R.string.cmd_nava_manual)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColorAttr(com.google.android.material.R.attr.colorSecondaryContainer)
                )
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSecondaryContainer))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(8)) }
            }
            val navaManualUso = MaterialButton(this).apply {
                text = getString(R.string.cmd_nava_manual_uso)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColorAttr(com.google.android.material.R.attr.colorSecondaryContainer)
                )
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSecondaryContainer))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(8)) }
            }
            val demoBtn = MaterialButton(this).apply {
                text = getString(R.string.cmd_demo)
                setIconResource(android.R.drawable.ic_media_play)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColorAttr(com.google.android.material.R.attr.colorPrimary)
                )
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(8)) }
            }
            val demo2Btn = MaterialButton(this).apply {
                text = getString(R.string.cmd_demo2)
                setIconResource(android.R.drawable.ic_media_play)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColorAttr(com.google.android.material.R.attr.colorPrimary)
                )
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(8)) }
            }
            val audit = MaterialButton(this).apply {
                text = getString(R.string.cmd_audit)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(12)) }
            }
            val langRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), 0, dp(20), dp(12)) }
                val current = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_LANG, "") ?: ""
                val esBtn = MaterialButton(this@MainActivity).apply {
                    text = getString(R.string.lang_es)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(4) }
                    if (current == "es") backgroundTintList = android.content.res.ColorStateList.valueOf(
                        getColorAttr(com.google.android.material.R.attr.colorPrimary)
                    )
                }
                val enBtn = MaterialButton(this@MainActivity).apply {
                    text = getString(R.string.lang_en)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(4) }
                    if (current == "en") backgroundTintList = android.content.res.ColorStateList.valueOf(
                        getColorAttr(com.google.android.material.R.attr.colorPrimary)
                    )
                }
                addView(esBtn)
                addView(enBtn)
                esBtn.setOnClickListener {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_LANG, "es").apply()
                    recreate()
                }
                enBtn.setOnClickListener {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_LANG, "en").apply()
                    recreate()
                }
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(textView)
                addView(footer)
                addView(disclaimerView)
                addView(licenseBtn)
                addView(appManual)
                addView(navaManual)
                addView(navaManualUso)
                addView(demoBtn)
                addView(demo2Btn)
                if (debugTabEnabled()) addView(audit)
                addView(langRow)
            }
            val scroll = ScrollView(this).apply { addView(container) }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.user_manual_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show()
                .let { dialog ->
                    capDialogScroll(scroll, dialog)
                    demoBtn.setOnClickListener {
                        dialog.dismiss()
                        startDemo()
                    }
                    demo2Btn.setOnClickListener {
                        dialog.dismiss()
                        startDemo2()
                    }
                    appManual.setOnClickListener {
                        showManualActionDialog(getString(R.string.cmd_app_manual), "Manual_app_MeshNavarra.pdf")
                    }
                    navaManual.setOnClickListener {
                        showManualActionDialog(getString(R.string.cmd_nava_manual), "Manual_NavaTastic.pdf")
                    }
                    navaManualUso.setOnClickListener {
                        showManualActionDialog(getString(R.string.cmd_nava_manual_uso), "Manual_uso_NavaTastic_4.2.pdf")
                    }
                    licenseBtn.setOnClickListener {
                        dialog.dismiss()
                        showLicenseDialog()
                    }
                    audit.setOnClickListener {
                        dialog.dismiss()
                        runAudit()
                    }
                }
        }

        connectButton.setOnClickListener {
            if (demoMode) {
                demoSimulateConnect()
                return@setOnClickListener
            }
            userInitiatedDisconnect = true
            cancelReconnect()
            val devices = usbConnectionManager.discoverDevices()
            if (devices.isEmpty()) {
                val errorMsg = getString(R.string.no_devices_found)
                appendLog(errorMsg)
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val device = devices.first()
            appendLog(getString(R.string.log_device_discovered, device.deviceName, device.vendorId))

            if (usbConnectionManager.hasPermission(device)) {
                connectToDevice(device)
            } else {
                appendLog(getString(R.string.log_requesting_usb_permission, device.deviceName))
                usbConnectionManager.requestPermission(device)
            }
        }

        disconnectUsbButton.setOnClickListener {
            pressFeedback(disconnectUsbButton)
            disconnectUsb()
        }

        clearLogButton.setOnClickListener {
            pressFeedback(clearLogButton)
            clearLogs()
        }

        // Query Node / Get Info logic (equivalent to `meshtastic --info`)
        queryNodeButton.setOnClickListener { sendWantConfig() }

        // Reboot Node logic
        rebootButton.setOnClickListener {
            val targetInputText = targetNodeInput.text.toString().trim()
            val targetNodeId = parseTargetNodeId(targetInputText)
            
            val destLabel = if (targetNodeId == -1) {
                getString(R.string.log_dest_local)
            } else {
                getString(R.string.log_dest_hex, Integer.toHexString(targetNodeId))
            }
            appendLog(getString(R.string.log_preparing_reboot, destLabel))
            
            val packet = MeshPacketBuilder.buildRebootPacket(seconds = 10, targetNodeId = targetNodeId)
            val bytes = sendToRadio(packet)
            val success = bytes != null
            if (success) {
                appendLog(getString(R.string.log_reboot_sent, bytes.size))
                Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
                appendLog(getString(R.string.log_reboot_reconnect))
                // The node reboots in ~10-15 s; reconnect automatically afterwards.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (bleTransportActive) {
                        bleConnectionManager.reconnect()
                    }
                }, 18000)
            } else {
                appendLog(getString(R.string.log_reboot_failed))
            }
        }

        // Wipe NodeDB logic
        wipeNodeDbButton.setOnClickListener {
            val targetInputText = targetNodeInput.text.toString().trim()
            val targetNodeId = parseTargetNodeId(targetInputText)
            val keepFavorites = keepFavoritesCheckbox.isChecked
            
            val destLabel = if (targetNodeId == -1) {
                getString(R.string.log_dest_local)
            } else {
                getString(R.string.log_dest_hex, Integer.toHexString(targetNodeId))
            }
            appendLog(getString(R.string.log_preparing_nodedb, keepFavorites, destLabel))
            
            val packet = MeshPacketBuilder.buildNodeDbResetPacket(keepFavorites = keepFavorites, targetNodeId = targetNodeId)
            val bytes = sendToRadio(packet)
            val success = bytes != null
            if (success) {
                appendLog(getString(R.string.log_nodedb_sent, bytes.size))
                Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
            } else {
                appendLog(getString(R.string.log_nodedb_failed))
            }
        }

        // Factory Reset logic (dangerous: two confirmations, backup reminder)
        factoryResetButton.setOnClickListener {
            val targetNodeId = parseTargetNodeId(targetNodeInput.text.toString().trim())
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.factory_reset_warn_title)
                .setMessage(R.string.factory_reset_warn_body)
                .setPositiveButton(R.string.continue_anyway) { _, _ -> confirmFactoryReset(targetNodeId) }
                .setNeutralButton(R.string.make_backup_first) { _, _ -> runBackup() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        favoriteButton.setOnClickListener {
            val targetInputText = targetNodeInput.text.toString().trim()
            val targetNodeId = parseTargetNodeId(targetInputText)
            
            val inputText = favoriteInput.text.toString().trim()
            if (inputText.isEmpty()) {
                favoriteInput.error = getString(R.string.please_enter_node)
                return@setOnClickListener
            }

            try {
                val nodeNum = parseNodeId(inputText)
                val destLabel = if (targetNodeId == -1) {
                    getString(R.string.log_dest_local)
                } else {
                    getString(R.string.log_dest_hex, Integer.toHexString(targetNodeId))
                }
                appendLog(getString(R.string.log_preparing_favorite, inputText, nodeNum, destLabel))
                
                val packet = MeshPacketBuilder.buildSetFavoritePacket(nodeNum = nodeNum, targetNodeId = targetNodeId)
                val bytes = sendToRadio(packet)
                val success = bytes != null
                if (success) {
                    appendLog(getString(R.string.log_favorite_sent, bytes.size))
                    Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
                    favoriteInput.text?.clear()
                } else {
                    appendLog(getString(R.string.log_favorite_failed))
                }
            } catch (e: NumberFormatException) {
                favoriteInput.error = getString(R.string.invalid_number_format)
                appendLog(getString(R.string.log_parse_number_error, e.message))
            }
        }

        // Set Ignored Node logic
        ignoredButton.setOnClickListener {
            val targetInputText = targetNodeInput.text.toString().trim()
            val targetNodeId = parseTargetNodeId(targetInputText)
            
            val inputText = ignoredInput.text.toString().trim()
            if (inputText.isEmpty()) {
                ignoredInput.error = getString(R.string.please_enter_node)
                return@setOnClickListener
            }

            try {
                val nodeNum = parseNodeId(inputText)
                val destLabel = if (targetNodeId == -1) {
                    getString(R.string.log_dest_local)
                } else {
                    getString(R.string.log_dest_hex, Integer.toHexString(targetNodeId))
                }
                appendLog(getString(R.string.log_preparing_ignored, inputText, nodeNum, destLabel))
                
                val packet = MeshPacketBuilder.buildSetIgnoredPacket(nodeNum = nodeNum, targetNodeId = targetNodeId)
                val bytes = sendToRadio(packet)
                val success = bytes != null
                if (success) {
                    appendLog(getString(R.string.log_ignored_sent, bytes.size))
                    Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
                    ignoredInput.text?.clear()
                } else {
                    appendLog(getString(R.string.log_ignored_failed))
                }
            } catch (e: NumberFormatException) {
                ignoredInput.error = getString(R.string.invalid_number_format)
                appendLog(getString(R.string.log_parse_number_error, e.message))
            }
        }

        // Remove Favorite Node logic
        unsetFavoriteButton.setOnClickListener {
            val targetInputText = targetNodeInput.text.toString().trim()
            val targetNodeId = parseTargetNodeId(targetInputText)

            val inputText = favoriteInput.text.toString().trim()
            if (inputText.isEmpty()) {
                favoriteInput.error = getString(R.string.please_enter_node)
                return@setOnClickListener
            }

            try {
                val nodeNum = parseNodeId(inputText)
                val destLabel = if (targetNodeId == -1) {
                    getString(R.string.log_dest_local)
                } else {
                    getString(R.string.log_dest_hex, Integer.toHexString(targetNodeId))
                }
                appendLog(getString(R.string.log_preparing_unset_favorite, inputText, nodeNum, destLabel))

                val packet = MeshPacketBuilder.buildRemoveFavoritePacket(nodeNum = nodeNum, targetNodeId = targetNodeId)
                val bytes = sendToRadio(packet)
                if (bytes != null) {
                    appendLog(getString(R.string.log_favorite_sent, bytes.size))
                    Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
                    favoriteInput.text?.clear()
                } else {
                    appendLog(getString(R.string.log_favorite_failed))
                }
            } catch (e: NumberFormatException) {
                favoriteInput.error = getString(R.string.invalid_number_format)
                appendLog(getString(R.string.log_parse_number_error, e.message))
            }
        }

        // Remove Ignored Node logic
        unsetIgnoredButton.setOnClickListener {
            val targetInputText = targetNodeInput.text.toString().trim()
            val targetNodeId = parseTargetNodeId(targetInputText)

            val inputText = ignoredInput.text.toString().trim()
            if (inputText.isEmpty()) {
                ignoredInput.error = getString(R.string.please_enter_node)
                return@setOnClickListener
            }

            try {
                val nodeNum = parseNodeId(inputText)
                val destLabel = if (targetNodeId == -1) {
                    getString(R.string.log_dest_local)
                } else {
                    getString(R.string.log_dest_hex, Integer.toHexString(targetNodeId))
                }
                appendLog(getString(R.string.log_preparing_unset_ignored, inputText, nodeNum, destLabel))

                val packet = MeshPacketBuilder.buildRemoveIgnoredPacket(nodeNum = nodeNum, targetNodeId = targetNodeId)
                val bytes = sendToRadio(packet)
                if (bytes != null) {
                    appendLog(getString(R.string.log_ignored_sent, bytes.size))
                    Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
                    ignoredInput.text?.clear()
                } else {
                    appendLog(getString(R.string.log_ignored_failed))
                }
            } catch (e: NumberFormatException) {
                ignoredInput.error = getString(R.string.invalid_number_format)
                appendLog(getString(R.string.log_parse_number_error, e.message))
            }
        }

        // Remove Node from NodeDB logic (no reboot; clears the entry + its key).
        removeNodeButton.setOnClickListener {
            val targetInputText = targetNodeInput.text.toString().trim()
            val targetNodeId = parseTargetNodeId(targetInputText)

            val inputText = removeNodeInput.text.toString().trim()
            if (inputText.isEmpty()) {
                removeNodeInput.error = getString(R.string.please_enter_node)
                return@setOnClickListener
            }

            try {
                val nodeNum = parseNodeId(inputText)
                val destLabel = if (targetNodeId == -1) {
                    getString(R.string.log_dest_local)
                } else {
                    getString(R.string.log_dest_hex, Integer.toHexString(targetNodeId))
                }
                appendLog(getString(R.string.log_preparing_remove_node, inputText, nodeNum, destLabel))

                val packet = MeshPacketBuilder.buildRemoveNodePacket(nodeNum = nodeNum, targetNodeId = targetNodeId)
                val bytes = sendToRadio(packet)
                if (bytes != null) {
                    appendLog(getString(R.string.log_remove_node_sent, bytes.size))
                    Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
                    removeNodeInput.text?.clear()
                } else {
                    appendLog(getString(R.string.log_remove_node_failed))
                }
            } catch (e: NumberFormatException) {
                removeNodeInput.error = getString(R.string.invalid_number_format)
                appendLog(getString(R.string.log_parse_number_error, e.message))
            }
        }

        // Import a node business card by URL (SharedContact URL -> app-side cache only).
        nodesImportButton.setOnClickListener {
            val url = nodesImportInput.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                nodesImportInput.error = getString(R.string.please_enter_node)
                return@setOnClickListener
            }
            importContactFromUrl(url)
            nodesImportInput.text?.clear()
        }

        // Add Admin Key logic (read-modify-write of the security section, local only)
        adminKeyAddButton.setOnClickListener {
            val inputText = adminKeyInput.text.toString().trim()
            val keyBytes = try {
                android.util.Base64.decode(inputText, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            }
            if (inputText.isEmpty() || keyBytes == null || keyBytes.isEmpty()) {
                adminKeyInput.error = getString(R.string.admin_keys_error)
                return@setOnClickListener
            }
            if (pendingAdminKey != null || awaitingAdminKeyRead != null || awaitingAdminKeyAck != null) {
                Toast.makeText(this, getString(R.string.admin_keys_busy), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingAdminKey = inputText
            val adminTarget = localNodeNum ?: -1
            val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
            val packet = MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.SECURITY_CONFIG, adminTarget, packetId)
            val bytes = sendToRadio(packet)
            if (bytes != null) {
                awaitingAdminKeyRead = packetId
                appendLog(getString(R.string.admin_keys_reading))
                configHandler.removeCallbacksAndMessages(null)
                configHandler.postDelayed({ onAdminKeyTimeout() }, CONFIG_ACK_TIMEOUT_MS)
            } else {
                pendingAdminKey = null
                appendLog(getString(R.string.admin_keys_failed))
            }
        }

        // Convert to Master Node (rescue) logic — writes ONLY the private key.
        masterConvertButton.setOnClickListener { confirmMasterConversion() }

        // Low-impact (debug) switch: cap everything at 1 hop and force the node to hop 1.
        val lowImpactPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lowImpactSwitch.isChecked = lowImpactPrefs.getBoolean(PREF_LOW_IMPACT, false)
        MeshPacketBuilder.lowImpactHopLimit = if (lowImpactSwitch.isChecked) 1 else 0
        lowImpactSwitch.setOnCheckedChangeListener { _, checked ->
            lowImpactPrefs.edit().putBoolean(PREF_LOW_IMPACT, checked).apply()
            MeshPacketBuilder.lowImpactHopLimit = if (checked) 1 else 0
            if (checked) {
                lowImpactApplied = true
                applySingleLoraJob(getString(R.string.low_impact_label)) { b ->
                    savedLowImpactLora = b.getLora().toBuilder().build()
                    b.setLora(b.getLora().toBuilder().setHopLimit(1).build())
                }
            } else {
                restoreLowImpactConfig()
            }
        }
        lowImpactSwitch.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.low_impact_label), getString(R.string.help_low_impact))
            true
        }

        // Manual LoRa hop limit (read-modify-write of the LORA section).
        hopApplyButton.setOnClickListener {
            val v = hopInput.text.toString().trim().toIntOrNull()
            if (v == null || v < 1 || v > 7) {
                hopInput.error = getString(R.string.hop_invalid)
                return@setOnClickListener
            }
            hopInput.error = null
            applySingleLoraJob(getString(R.string.hop_apply) + " = $v") { b ->
                b.setLora(b.getLora().toBuilder().setHopLimit(v).build())
            }
        }

        // Manual frequency override (isolated test band, e.g. 869.545 MHz).
        freqApplyButton.setOnClickListener {
            val v = freqInput.text.toString().trim().toDoubleOrNull()
            if (v == null || v < 863.0 || v > 870.0) {
                freqInput.error = getString(R.string.freq_invalid)
                return@setOnClickListener
            }
            freqInput.error = null
            applySingleLoraJob(getString(R.string.freq_apply) + " = $v") { b ->
                b.setLora(
                    b.getLora().toBuilder()
                        .setUsePreset(false)
                        .setOverrideFrequency(v.toFloat())
                        .setBandwidth(62)
                        .setSpreadFactor(7)
                        .setCodingRate(5)
                        .setChannelNum(4)
                        .build()
                )
            }
        }
        hopInput.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.hop_hint), getString(R.string.help_hop_input))
            true
        }
        freqInput.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.freq_hint), getString(R.string.help_freq_input))
            true
        }

        // Duty-cycle override (100% = no TX limit, test band only).
        dutyCycleSwitch.setOnCheckedChangeListener { _, checked ->
            applySingleLoraJob(
                if (checked) getString(R.string.duty_cycle_on) else getString(R.string.duty_cycle_off)
            ) { b ->
                b.setLora(
                    b.getLora().toBuilder()
                        .setOverrideDutyCycle(checked)
                        .build()
                )
            }
        }
        dutyCycleSwitch.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.duty_cycle_label), getString(R.string.help_duty_cycle))
            true
        }

        // Sensor toggles: read-modify-write of the telemetry module config (local node).
        sensorStatusText.text = getString(R.string.sensor_status_idle)
        sensorEnvSwitch.setOnCheckedChangeListener { _, _ -> applySensorToggle() }
        sensorPowerSwitch.setOnCheckedChangeListener { _, _ -> applySensorToggle() }
        sensorEnvSwitch.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.sensor_env_label), getString(R.string.help_sensor_env))
            true
        }
        sensorPowerSwitch.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.sensor_power_label), getString(R.string.help_sensor_power))
            true
        }

        navadminTestButton.setOnClickListener { startAuditBattery() }
        navadminTestStopButton.setOnClickListener { stopAuditBattery() }

        batterySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.battery_navadmin),
                getString(R.string.battery_commands),
                getString(R.string.battery_admin_local),
                getString(R.string.battery_chat),
                getString(R.string.battery_admin_remote),
                getString(R.string.battery_dm_control),
                getString(R.string.battery_config_get)
            )
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        batterySpinner.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.audit_title), getString(R.string.help_audit_spinner))
            true
        }
    }

    /**
     * Builds the selected audit battery: a list of steps, each sending one
     * command. Navadmin paces at 32 s (30 s rate limit + jitter margin); the
     * protobuf batteries use shorter intervals.
     */
    private fun selectedAuditSteps(): Triple<String, List<AuditStep>, Long> {
        val local = localNodeNum ?: -1
        return when (batterySpinner.selectedItemPosition) {
            1 -> Triple(
                getString(R.string.battery_commands),
                listOf(
                    AuditStep("request-telemetry(B)", { sendToRadio(MeshPacketBuilder.buildRequestTelemetryPacket(B_ID)) }),
                    AuditStep("request-position(B)", { sendToRadio(MeshPacketBuilder.buildRequestPositionPacket(B_ID)) }),
                    AuditStep("traceroute(B)", { sendToRadio(MeshPacketBuilder.buildTraceRoutePacket(B_ID)) })
                ), 10000L
            )
            2 -> Triple(
                getString(R.string.battery_admin_local),
                listOf(
                    AuditStep("set-favorite(A)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildSetFavoritePacket(local, -1)) else null }),
                    AuditStep("reboot(A,10s)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildRebootPacket(10, -1)) else null })
                ), 15000L
            )
            3 -> Triple(
                getString(R.string.battery_chat),
                listOf(
                    AuditStep("chat-msg(ch${currentChatChannel})", { sendToRadio(MeshPacketBuilder.buildTextPacket(getString(R.string.audit_chat_msg), -1, currentChatChannel)) })
                ), 8000L
            )
            4 -> Triple(
                getString(R.string.battery_admin_remote),
                listOf(
                    AuditStep("set-favorite(A->B)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildSetFavoritePacket(local, B_ID)) else null })
                ), 10000L
            )
            5 -> Triple(
                getString(R.string.battery_dm_control),
                listOf(
                    AuditStep("fav ls (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava fav ls", B_ID, 0, pkiEncrypted = true)) }),
                    AuditStep("fav add A (B)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildTextPacket("/nava fav add !${Integer.toHexString(local)}", B_ID, 0, pkiEncrypted = true)) else null }),
                    AuditStep("fav rm A (B)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildTextPacket("/nava fav rm !${Integer.toHexString(local)}", B_ID, 0, pkiEncrypted = true)) else null }),
                    AuditStep("ign ls (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava ign ls", B_ID, 0, pkiEncrypted = true)) }),
                    AuditStep("pos (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava pos", B_ID, 0, pkiEncrypted = true)) }),
                    AuditStep("nodeinfo (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava nodeinfo", B_ID, 0, pkiEncrypted = true)) }),
                    AuditStep("sendtel (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava sendtel", B_ID, 0, pkiEncrypted = true)) }),
                    AuditStep("bell (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava bell", B_ID, 0, pkiEncrypted = true)) }),
                    AuditStep("admin_ls (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava admin_ls", B_ID, 0, pkiEncrypted = true)) }),
                    AuditStep("txon (B)", { sendToRadio(MeshPacketBuilder.buildTextPacket("/nava txon", B_ID, 0, pkiEncrypted = true)) })
                ), 10000L
            )
            6 -> Triple(
                getString(R.string.battery_config_get),
                listOf(
                    AuditStep("get lora (A)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.LORA_CONFIG, local)) else null }),
                    AuditStep("get device (A)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.DEVICE_CONFIG, local)) else null }),
                    AuditStep("get position (A)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.POSITION_CONFIG, local)) else null }),
                    AuditStep("get power (A)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.POWER_CONFIG, local)) else null }),
                    AuditStep("get network (A)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.NETWORK_CONFIG, local)) else null }),
                    AuditStep("get security (A)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.SECURITY_CONFIG, local)) else null })
                ), 8000L
            )
            else -> Triple(
                getString(R.string.battery_navadmin),
                NAVADMIN_TEST_COMMANDS.map { cmd ->
                    AuditStep(cmd, { sendToRadio(MeshPacketBuilder.buildTextPacket(cmd, -1, navadminChannelIndex)) })
                }, NAVADMIN_TEST_INTERVAL_MS
            )
        }
    }

    private fun startAuditBattery() {
        if (auditBatteryRunning) {
            navadminTestStatus.text = getString(R.string.audit_already)
            return
        }
        if (demoMode || !isReady()) {
            navadminTestStatus.text = getString(R.string.audit_not_ready)
            return
        }
        val (name, steps, intervalMs) = selectedAuditSteps()
        auditBatteryRunning = true
        auditName = name
        auditIndex = 0
        auditSteps = steps
        auditIntervalMs = intervalMs
        navadminTestStatus.text = getString(R.string.audit_started, name, steps.size, intervalMs / 1000)
        appendLog(getString(R.string.audit_started, name, steps.size, intervalMs / 1000))
        showAuditPopup(name)
        openAuditFile(name)
        runNextAuditStep()
    }

    private fun runNextAuditStep() {
        if (!auditBatteryRunning) return
        if (demoMode || !isReady()) {
            navadminTestStatus.text = getString(R.string.audit_aborted)
            appendLog(getString(R.string.audit_aborted))
            stopAuditBattery()
            return
        }
        if (auditIndex >= auditSteps.size) {
            navadminTestStatus.text = getString(R.string.audit_done, auditName)
            appendLog(getString(R.string.audit_done, auditName))
            stopAuditBattery()
            return
        }
        val step = auditSteps[auditIndex]
        auditIndex++
        val bytes = try { step.action() } catch (e: Exception) { null }
        val result = if (bytes != null) "OK(${bytes.size}B)" else "FAILED"
        navadminTestStatus.text = getString(R.string.audit_progress, auditName, auditIndex, auditSteps.size, step.label)
        appendLog("AUDIT[$auditName] $auditIndex/${auditSteps.size} ${step.label} -> $result")
        auditHandler.postDelayed({ runNextAuditStep() }, auditIntervalMs)
    }

    private fun stopAuditBattery() {
        auditBatteryRunning = false
        auditHandler.removeCallbacksAndMessages(null)
        closeAuditFile()
        // Auto-close the live console a few seconds after the battery finishes
        // so the user can read the result without tapping (and popups never stack).
        auditDialogHandler.removeCallbacksAndMessages(null)
        auditDialogHandler.postDelayed({
            val d = auditDialog
            if (d != null && d.isShowing) {
                d.dismiss()
            }
        }, AUDIT_POPUP_AUTOCLOSE_MS)
    }

    /**
     * Live audit console: a popup that mirrors every log line produced while a
     * battery runs, colour-coded so the user can spot problems at a glance
     * (cyan = outgoing commands, green = OK/PONG/ACK, red = failures/errors).
     */
    private fun showAuditPopup(name: String) {
        auditConsoleText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply {
            addView(auditConsoleText)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(520)
            )
        }
        auditDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.audit_title) + ": $name")
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener {
                auditConsoleText = null
                auditDialog = null
            }
            .create()
        auditDialog?.show()
    }

    private fun auditConsoleAppend(line: String) {
        val tv = auditConsoleText ?: return
        val lineColor = auditLineColor(line)
        val span = android.text.SpannableString(line + "\n").apply {
            setSpan(
                android.text.style.ForegroundColorSpan(lineColor),
                0, line.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val builder = android.text.SpannableStringBuilder(tv.text ?: "").append(span)
        tv.text = builder
        (tv.parent as? ScrollView)?.post { (tv.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun auditLineColor(line: String): Int = when {
        line.contains("FAILED", true) || line.contains("error", true) || line.contains("Error") ->
            0xFFF4511E.toInt()
        line.contains("AUDIT") || line.contains(">>") || line.contains("NAVA >>") ->
            0xFF4FC3F7.toInt()
        line.contains("PONG") || line.contains("ACK") || line.contains("OK(") ->
            0xFF81C784.toInt()
        else -> getColorAttr(com.google.android.material.R.attr.colorOnSurface)
    }

    private fun openAuditFile(name: String) {
        try {
            val base = getExternalFilesDir(null) ?: filesDir
            val dir = File(base, "audits").apply { mkdirs() }
            auditFile = File(
                dir,
                "audit_${name.replace(Regex("[^A-Za-z0-9]"), "_")}_" +
                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            )
            auditFile?.writeText("=== Audit: $name ===\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open audit file", e)
            auditFile = null
        }
    }

    private fun closeAuditFile() {
        try {
            auditFile?.appendText("=== End of audit ===\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finalise audit file", e)
        }
        auditFile = null
    }

    /**
     * Runs a single read-modify-write config job on the local node (reuses the
     * Good Practices sequencing: get_config -> modify -> set_config -> ACK).
     */
    private fun applySingleLoraJob(label: String, modify: (ConfigProtos.Config.Builder) -> Unit) {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            Toast.makeText(this, getString(R.string.log_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        if (warnIfAdminOverBle()) return
        configTarget = localNodeNum ?: -1
        configJobs.clear()
        configJobs.add(ConfigJob(label, AdminMessage.ConfigType.LORA_CONFIG, modify))
        configJobIndex = 0
        awaitingConfigAck = null
        awaitingConfigRead = null
        bpAppendStatus(getString(R.string.bp_status_applying, 1, 1, label))
        configReadNext()
    }

    /**
     * Restores the node's LoRa config after low-impact mode is turned off.
     * Order matters per user rule: hops FIRST, then frequency. Uses the snapshot
     * captured when low-impact was enabled (read-modify-write, local node).
     */
    private fun restoreLowImpactConfig() {
        val saved = savedLowImpactLora
        if (saved == null) {
            appendLog(getString(R.string.low_impact_restore_none))
            return
        }
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            return
        }
        if (warnIfAdminOverBle()) return
        configTarget = localNodeNum ?: -1
        configJobs.clear()
        configJobs.add(
            ConfigJob(getString(R.string.low_impact_restore_hops), AdminMessage.ConfigType.LORA_CONFIG) { b ->
                b.setLora(b.getLora().toBuilder().setHopLimit(saved.hopLimit).build())
            }
        )
        configJobs.add(
            ConfigJob(getString(R.string.low_impact_restore_freq), AdminMessage.ConfigType.LORA_CONFIG) { b ->
                val cur = b.getLora().toBuilder()
                cur.setUsePreset(saved.usePreset)
                cur.setOverrideFrequency(saved.overrideFrequency)
                cur.setChannelNum(saved.channelNum)
                cur.setBandwidth(saved.bandwidth)
                cur.setSpreadFactor(saved.spreadFactor)
                cur.setCodingRate(saved.codingRate)
                cur.setTxPower(saved.txPower)
                cur.setOverrideDutyCycle(saved.overrideDutyCycle)
                b.setLora(cur.build())
            }
        )
        configJobIndex = 0
        awaitingConfigAck = null
        awaitingConfigRead = null
        bpAppendStatus(getString(R.string.bp_status_applying, 1, configJobs.size, configJobs[0].label))
        configReadNext()
    }

    /**
     * Applies the Debug-tab sensor toggles: read-modify-write of the TELEMETRY
     * module config on the local node (get_module_config -> modify -> set).
     */
    private fun applySensorToggle() {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            sensorStatusText.text = getString(R.string.sensor_status_failed, getString(R.string.log_not_connected))
            return
        }
        if (awaitingSensorRead != null || awaitingSensorAck != null) {
            appendLog("SENSOR: busy, ignoring toggle")
            return
        }
        sensorPendingEnv = sensorEnvSwitch.isChecked
        sensorPendingPower = sensorPowerSwitch.isChecked
        val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val packet = MeshPacketBuilder.buildGetModuleConfigRequestPacket(
            AdminMessage.ModuleConfigType.TELEMETRY_CONFIG, localNodeNum ?: -1, packetId
        )
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            awaitingSensorRead = packetId
            sensorStatusText.text = getString(R.string.sensor_status_writing)
            appendLog("SENSOR: reading telemetry module (env=${sensorPendingEnv}, power=${sensorPendingPower})")
            configHandler.removeCallbacksAndMessages(null)
            configHandler.postDelayed({ onSensorTimeout() }, CONFIG_ACK_TIMEOUT_MS)
        } else {
            sensorStatusText.text = getString(R.string.sensor_status_failed, getString(R.string.log_cmd_write_failed))
        }
    }

    private fun onSensorTimeout() {
        awaitingSensorRead = null
        awaitingSensorAck = null
        sensorStatusText.text = getString(R.string.sensor_status_failed, "timeout")
    }

    /**
     * Sets up the bottom tabs (Excel-style) that switch between the
     * Administration and Commands panels.
     */
    private var tabListener: TabLayout.OnTabSelectedListener? = null

    private fun setupBottomTabs() {
        // Order: Good Practices, Commands, Administration, NavaTastic CLI, Chat, Nodes, Log.
        // The Debug tab (developer tools, LoRa test overrides) is hidden by
        // default and only added when explicitly enabled (see setDebugTabEnabled).
        buildBottomTabs()
        setupTabSwipe()
        setupTabEdgeHints()
        // Explicit initial selection: TabLayout auto-selects the first tab while
        // the listener is not attached yet, leaving every panel hidden. Select
        // again so the listener fires and the header gets reparented.
        bottomTabs.getTabAt(0)?.select()
    }

    /** (Re)builds the tab bar. The Debug tab only exists when enabled. */
    private fun buildBottomTabs() {
        bottomTabs.removeAllTabs()
        tabListener?.let { bottomTabs.removeOnTabSelectedListener(it) }
        bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_bp))
        bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_commands))
        bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_admin))
        bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_navatastic))
        bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_chat))
        bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_nodes))
        bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_log))
        if (debugTabEnabled()) bottomTabs.addTab(bottomTabs.newTab().setText(R.string.tab_debug))
        val listener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                applyTabVisibility(tab.position)
                when (tab.position) {
                    3 -> {
                        refreshNava()
                        if (!remoteTabSwitch) showNavaTasticIntro()
                    }
                    4 -> refreshChat()
                    5 -> refreshNodesList()
                    6 -> refreshLogTab()
                }
                showTabExplainer(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                applyTabVisibility(tab.position)
                showCinematicTabExplainer(tab.position, forceShow = true)
            }
        }
        tabListener = listener
        bottomTabs.addOnTabSelectedListener(listener)
    }

    /** Debug tab is a developer-only tool: hidden unless explicitly enabled. */
    private fun debugTabEnabled(): Boolean =
        getSharedPreferences("meshkacho", MODE_PRIVATE).getBoolean("debug_tab_enabled", false)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && bottomTabs.selectedTabPosition >= 0) {
            applyTabVisibility(bottomTabs.selectedTabPosition)
        }
    }

    private fun setDebugTabEnabled(enabled: Boolean) {
        getSharedPreferences("meshkacho", MODE_PRIVATE).edit().putBoolean("debug_tab_enabled", enabled).apply()
        buildBottomTabs()
        appendLog(if (enabled) "DEBUG: pestaña Debug activada" else "DEBUG: pestaña Debug ocultada")
    }

    private var lastStatusTap = 0L
    private var statusTapCount = 0

    /** 7 quick taps on the status line toggle the hidden developer Debug tab. */
    private fun attachDebugTabGesture() {
        statusText.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastStatusTap <= 600L) statusTapCount++ else statusTapCount = 1
            lastStatusTap = now
            if (statusTapCount >= 7) {
                statusTapCount = 0
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.debug_tab_dialog_title)
                    .setMessage(R.string.debug_tab_dialog_body)
                    .setPositiveButton(
                        if (debugTabEnabled()) R.string.debug_tab_hide else R.string.debug_tab_show
                    ) { _, _ -> setDebugTabEnabled(!debugTabEnabled()) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** The very first automatic selection (app start) does not trigger popups. */
    private var initialTabSelected = true

    /** Suppresses tab explainer/intro popups while a remote tab switch runs. */
    private var remoteTabSwitch = false

    data class CinematicSlide(
        val punchlineRes: Int,
        val descRes: Int,
        val promptText: String,
        val outputText: String
    )

    data class CinematicTabContent(
        val tabPosition: Int,
        val iconRes: Int,
        val titleRes: Int,
        val badgeRes: Int,
        val slides: List<CinematicSlide>,
        val technicalDetailsRes: Int
    )

    private fun getCinematicTabContent(position: Int): CinematicTabContent? = when (position) {
        0 -> CinematicTabContent(
            tabPosition = 0,
            iconRes = R.drawable.ic_bolt,
            titleRes = R.string.tab_info_title_gp,
            badgeRes = R.string.cinematic_badge_gp,
            slides = listOf(
                CinematicSlide(R.string.slide_gp_1_punch, R.string.slide_gp_1_desc, getString(R.string.slide_gp_1_prompt), getString(R.string.slide_gp_1_output)),
                CinematicSlide(R.string.slide_gp_2_punch, R.string.slide_gp_2_desc, getString(R.string.slide_gp_2_prompt), getString(R.string.slide_gp_2_output)),
                CinematicSlide(R.string.slide_gp_3_punch, R.string.slide_gp_3_desc, getString(R.string.slide_gp_3_prompt), getString(R.string.slide_gp_3_output)),
                CinematicSlide(R.string.slide_gp_4_punch, R.string.slide_gp_4_desc, getString(R.string.slide_gp_4_prompt), getString(R.string.slide_gp_4_output)),
                CinematicSlide(R.string.slide_gp_5_punch, R.string.slide_gp_5_desc, getString(R.string.slide_gp_5_prompt), getString(R.string.slide_gp_5_output)),
                CinematicSlide(R.string.slide_gp_6_punch, R.string.slide_gp_6_desc, getString(R.string.slide_gp_6_prompt), getString(R.string.slide_gp_6_output)),
                CinematicSlide(R.string.slide_gp_7_punch, R.string.slide_gp_7_desc, getString(R.string.slide_gp_7_prompt), getString(R.string.slide_gp_7_output)),
                CinematicSlide(R.string.slide_gp_8_punch, R.string.slide_gp_8_desc, getString(R.string.slide_gp_8_prompt), getString(R.string.slide_gp_8_output)),
                CinematicSlide(R.string.slide_gp_9_punch, R.string.slide_gp_9_desc, getString(R.string.slide_gp_9_prompt), getString(R.string.slide_gp_9_output)),
                CinematicSlide(R.string.slide_gp_10_punch, R.string.slide_gp_10_desc, getString(R.string.slide_gp_10_prompt), getString(R.string.slide_gp_10_output))
            ),
            technicalDetailsRes = R.string.tab_info_body_gp
        )
        1 -> CinematicTabContent(
            tabPosition = 1,
            iconRes = R.drawable.ic_terminal,
            titleRes = R.string.tab_info_title_cmd,
            badgeRes = R.string.cinematic_badge_cmd,
            slides = listOf(
                CinematicSlide(R.string.slide_cmd_1_punch, R.string.slide_cmd_1_desc, getString(R.string.slide_cmd_1_prompt), getString(R.string.slide_cmd_1_output)),
                CinematicSlide(R.string.slide_cmd_2_punch, R.string.slide_cmd_2_desc, getString(R.string.slide_cmd_2_prompt), getString(R.string.slide_cmd_2_output)),
                CinematicSlide(R.string.slide_cmd_3_punch, R.string.slide_cmd_3_desc, getString(R.string.slide_cmd_3_prompt), getString(R.string.slide_cmd_3_output)),
                CinematicSlide(R.string.slide_cmd_4_punch, R.string.slide_cmd_4_desc, getString(R.string.slide_cmd_4_prompt), getString(R.string.slide_cmd_4_output)),
                CinematicSlide(R.string.slide_cmd_5_punch, R.string.slide_cmd_5_desc, getString(R.string.slide_cmd_5_prompt), getString(R.string.slide_cmd_5_output)),
                CinematicSlide(R.string.slide_cmd_6_punch, R.string.slide_cmd_6_desc, getString(R.string.slide_cmd_6_prompt), getString(R.string.slide_cmd_6_output)),
                CinematicSlide(R.string.slide_cmd_7_punch, R.string.slide_cmd_7_desc, getString(R.string.slide_cmd_7_prompt), getString(R.string.slide_cmd_7_output)),
                CinematicSlide(R.string.slide_cmd_8_punch, R.string.slide_cmd_8_desc, getString(R.string.slide_cmd_8_prompt), getString(R.string.slide_cmd_8_output))
            ),
            technicalDetailsRes = R.string.tab_info_body_cmd
        )
        2 -> CinematicTabContent(
            tabPosition = 2,
            iconRes = R.drawable.ic_shield,
            titleRes = R.string.tab_info_title_admin,
            badgeRes = R.string.cinematic_badge_admin,
            slides = listOf(
                CinematicSlide(R.string.slide_admin_1_punch, R.string.slide_admin_1_desc, getString(R.string.slide_admin_1_prompt), getString(R.string.slide_admin_1_output)),
                CinematicSlide(R.string.slide_admin_2_punch, R.string.slide_admin_2_desc, getString(R.string.slide_admin_2_prompt), getString(R.string.slide_admin_2_output)),
                CinematicSlide(R.string.slide_admin_3_punch, R.string.slide_admin_3_desc, getString(R.string.slide_admin_3_prompt), getString(R.string.slide_admin_3_output)),
                CinematicSlide(R.string.slide_admin_4_punch, R.string.slide_admin_4_desc, getString(R.string.slide_admin_4_prompt), getString(R.string.slide_admin_4_output)),
                CinematicSlide(R.string.slide_admin_5_punch, R.string.slide_admin_5_desc, getString(R.string.slide_admin_5_prompt), getString(R.string.slide_admin_5_output)),
                CinematicSlide(R.string.slide_admin_6_punch, R.string.slide_admin_6_desc, getString(R.string.slide_admin_6_prompt), getString(R.string.slide_admin_6_output)),
                CinematicSlide(R.string.slide_admin_7_punch, R.string.slide_admin_7_desc, getString(R.string.slide_admin_7_prompt), getString(R.string.slide_admin_7_output)),
                CinematicSlide(R.string.slide_admin_8_punch, R.string.slide_admin_8_desc, getString(R.string.slide_admin_8_prompt), getString(R.string.slide_admin_8_output))
            ),
            technicalDetailsRes = R.string.tab_info_body_admin
        )
        3 -> CinematicTabContent(
            tabPosition = 3,
            iconRes = R.drawable.ic_terminal,
            titleRes = R.string.tab_info_title_nava,
            badgeRes = R.string.cinematic_badge_nava,
            slides = listOf(
                CinematicSlide(R.string.slide_nava_1_punch, R.string.slide_nava_1_desc, getString(R.string.slide_nava_1_prompt), getString(R.string.slide_nava_1_output)),
                CinematicSlide(R.string.slide_nava_2_punch, R.string.slide_nava_2_desc, getString(R.string.slide_nava_2_prompt), getString(R.string.slide_nava_2_output)),
                CinematicSlide(R.string.slide_nava_3_punch, R.string.slide_nava_3_desc, getString(R.string.slide_nava_3_prompt), getString(R.string.slide_nava_3_output)),
                CinematicSlide(R.string.slide_nava_4_punch, R.string.slide_nava_4_desc, getString(R.string.slide_nava_4_prompt), getString(R.string.slide_nava_4_output)),
                CinematicSlide(R.string.slide_nava_5_punch, R.string.slide_nava_5_desc, getString(R.string.slide_nava_5_prompt), getString(R.string.slide_nava_5_output)),
                CinematicSlide(R.string.slide_nava_6_punch, R.string.slide_nava_6_desc, getString(R.string.slide_nava_6_prompt), getString(R.string.slide_nava_6_output)),
                CinematicSlide(R.string.slide_nava_7_punch, R.string.slide_nava_7_desc, getString(R.string.slide_nava_7_prompt), getString(R.string.slide_nava_7_output)),
                CinematicSlide(R.string.slide_nava_8_punch, R.string.slide_nava_8_desc, getString(R.string.slide_nava_8_prompt), getString(R.string.slide_nava_8_output)),
                CinematicSlide(R.string.slide_nava_9_punch, R.string.slide_nava_9_desc, getString(R.string.slide_nava_9_prompt), getString(R.string.slide_nava_9_output)),
                CinematicSlide(R.string.slide_nava_10_punch, R.string.slide_nava_10_desc, getString(R.string.slide_nava_10_prompt), getString(R.string.slide_nava_10_output)),
                CinematicSlide(R.string.slide_nava_11_punch, R.string.slide_nava_11_desc, getString(R.string.slide_nava_11_prompt), getString(R.string.slide_nava_11_output)),
                CinematicSlide(R.string.slide_nava_12_punch, R.string.slide_nava_12_desc, getString(R.string.slide_nava_12_prompt), getString(R.string.slide_nava_12_output)),
                CinematicSlide(R.string.slide_nava_13_punch, R.string.slide_nava_13_desc, getString(R.string.slide_nava_13_prompt), getString(R.string.slide_nava_13_output)),
                CinematicSlide(R.string.slide_nava_14_punch, R.string.slide_nava_14_desc, getString(R.string.slide_nava_14_prompt), getString(R.string.slide_nava_14_output)),
                CinematicSlide(R.string.slide_nava_15_punch, R.string.slide_nava_15_desc, getString(R.string.slide_nava_15_prompt), getString(R.string.slide_nava_15_output)),
                CinematicSlide(R.string.slide_nava_16_punch, R.string.slide_nava_16_desc, getString(R.string.slide_nava_16_prompt), getString(R.string.slide_nava_16_output))
            ),
            technicalDetailsRes = R.string.tab_info_body_nava
        )
        4 -> CinematicTabContent(
            tabPosition = 4,
            iconRes = R.drawable.ic_chat,
            titleRes = R.string.tab_info_title_chat,
            badgeRes = R.string.cinematic_badge_chat,
            slides = listOf(
                CinematicSlide(R.string.slide_chat_1_punch, R.string.slide_chat_1_desc, getString(R.string.slide_chat_1_prompt), getString(R.string.slide_chat_1_output)),
                CinematicSlide(R.string.slide_chat_2_punch, R.string.slide_chat_2_desc, getString(R.string.slide_chat_2_prompt), getString(R.string.slide_chat_2_output)),
                CinematicSlide(R.string.slide_chat_3_punch, R.string.slide_chat_3_desc, getString(R.string.slide_chat_3_prompt), getString(R.string.slide_chat_3_output)),
                CinematicSlide(R.string.slide_chat_4_punch, R.string.slide_chat_4_desc, getString(R.string.slide_chat_4_prompt), getString(R.string.slide_chat_4_output)),
                CinematicSlide(R.string.slide_chat_5_punch, R.string.slide_chat_5_desc, getString(R.string.slide_chat_5_prompt), getString(R.string.slide_chat_5_output)),
                CinematicSlide(R.string.slide_chat_6_punch, R.string.slide_chat_6_desc, getString(R.string.slide_chat_6_prompt), getString(R.string.slide_chat_6_output)),
                CinematicSlide(R.string.slide_chat_7_punch, R.string.slide_chat_7_desc, getString(R.string.slide_chat_7_prompt), getString(R.string.slide_chat_7_output))
            ),
            technicalDetailsRes = R.string.tab_info_body_chat
        )
        5 -> CinematicTabContent(
            tabPosition = 5,
            iconRes = R.drawable.ic_nodes,
            titleRes = R.string.tab_info_title_nodes,
            badgeRes = R.string.cinematic_badge_nodes,
            slides = listOf(
                CinematicSlide(R.string.slide_nodes_1_punch, R.string.slide_nodes_1_desc, getString(R.string.slide_nodes_1_prompt), getString(R.string.slide_nodes_1_output)),
                CinematicSlide(R.string.slide_nodes_2_punch, R.string.slide_nodes_2_desc, getString(R.string.slide_nodes_2_prompt), getString(R.string.slide_nodes_2_output)),
                CinematicSlide(R.string.slide_nodes_3_punch, R.string.slide_nodes_3_desc, getString(R.string.slide_nodes_3_prompt), getString(R.string.slide_nodes_3_output)),
                CinematicSlide(R.string.slide_nodes_4_punch, R.string.slide_nodes_4_desc, getString(R.string.slide_nodes_4_prompt), getString(R.string.slide_nodes_4_output)),
                CinematicSlide(R.string.slide_nodes_5_punch, R.string.slide_nodes_5_desc, getString(R.string.slide_nodes_5_prompt), getString(R.string.slide_nodes_5_output)),
                CinematicSlide(R.string.slide_nodes_6_punch, R.string.slide_nodes_6_desc, getString(R.string.slide_nodes_6_prompt), getString(R.string.slide_nodes_6_output)),
                CinematicSlide(R.string.slide_nodes_7_punch, R.string.slide_nodes_7_desc, getString(R.string.slide_nodes_7_prompt), getString(R.string.slide_nodes_7_output)),
                CinematicSlide(R.string.slide_nodes_8_punch, R.string.slide_nodes_8_desc, getString(R.string.slide_nodes_8_prompt), getString(R.string.slide_nodes_8_output))
            ),
            technicalDetailsRes = R.string.tab_info_body_nodes
        )
        6 -> CinematicTabContent(
            tabPosition = 6,
            iconRes = R.drawable.ic_log,
            titleRes = R.string.tab_info_title_log,
            badgeRes = R.string.cinematic_badge_log,
            slides = listOf(
                CinematicSlide(R.string.slide_log_1_punch, R.string.slide_log_1_desc, getString(R.string.slide_log_1_prompt), getString(R.string.slide_log_1_output)),
                CinematicSlide(R.string.slide_log_2_punch, R.string.slide_log_2_desc, getString(R.string.slide_log_2_prompt), getString(R.string.slide_log_2_output)),
                CinematicSlide(R.string.slide_log_3_punch, R.string.slide_log_3_desc, getString(R.string.slide_log_3_prompt), getString(R.string.slide_log_3_output)),
                CinematicSlide(R.string.slide_log_4_punch, R.string.slide_log_4_desc, getString(R.string.slide_log_4_prompt), getString(R.string.slide_log_4_output)),
                CinematicSlide(R.string.slide_log_5_punch, R.string.slide_log_5_desc, getString(R.string.slide_log_5_prompt), getString(R.string.slide_log_5_output)),
                CinematicSlide(R.string.slide_log_6_punch, R.string.slide_log_6_desc, getString(R.string.slide_log_6_prompt), getString(R.string.slide_log_6_output)),
                CinematicSlide(R.string.slide_log_7_punch, R.string.slide_log_7_desc, getString(R.string.slide_log_7_prompt), getString(R.string.slide_log_7_output))
            ),
            technicalDetailsRes = R.string.tab_info_body_log
        )
        else -> null
    }

    /**
     * Shows a dynamic cinematic trailer-style explainer popup for the selected tab.
     * Features rich feature slides with animated scale effects, simulated live HUD,
     * adaptive reading timers, and expandable in-depth technical documentation.
     */
    private fun showCinematicTabExplainer(position: Int, forceShow: Boolean = false, startExpanded: Boolean = false) {
        if (remoteTabSwitch) return
        if (demoSuppressExplainers) return
        if (initialTabSelected) {
            initialTabSelected = false
            return
        }
        val content = getCinematicTabContent(position) ?: return
        val prefs = getSharedPreferences("meshkacho", MODE_PRIVATE)
        val key = "tab_explain_$position"
        if (!forceShow && prefs.getInt(key, 0) >= 15 && !demoMode) return

        val view = layoutInflater.inflate(R.layout.dialog_cinematic_explainer, null)
        val headerIcon = view.findViewById<ImageView>(R.id.headerIcon)
        val headerBadge = view.findViewById<TextView>(R.id.headerBadge)
        val headerTitle = view.findViewById<TextView>(R.id.headerTitle)
        val btnCloseTop = view.findViewById<MaterialButton>(R.id.btnCloseTop)

        val storyProgressContainer = view.findViewById<LinearLayout>(R.id.storyProgressContainer)
        storyProgressContainer.removeAllViews()
        val totalSlides = content.slides.size
        val storyBars = mutableListOf<ProgressBar>()
        for (i in 0 until totalSlides) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f).apply {
                    if (i < totalSlides - 1) marginEnd = dp(2)
                }
                max = 1000
                progress = 0
                progressDrawable = getDrawable(R.drawable.bg_story_segment_active)
                isClickable = true
                isFocusable = true
            }
            storyProgressContainer.addView(pb)
            storyBars.add(pb)
        }

        val stageTouchContainer = view.findViewById<FrameLayout>(R.id.stageTouchContainer)
        val textHoldHint = view.findViewById<TextView>(R.id.textHoldHint)
        val stageTextPrompt = view.findViewById<TextView>(R.id.stageTextPrompt)
        val stageTextOutput = view.findViewById<TextView>(R.id.stageTextOutput)

        val textPunchline = view.findViewById<TextView>(R.id.textPunchline)
        val textDescription = view.findViewById<TextView>(R.id.textDescription)

        val btnPrevStep = view.findViewById<MaterialButton>(R.id.btnPrevStep)
        val textStepCounter = view.findViewById<TextView>(R.id.textStepCounter)
        val btnNextStep = view.findViewById<MaterialButton>(R.id.btnNextStep)

        val btnToggleMoreInfo = view.findViewById<MaterialButton>(R.id.btnToggleMoreInfo)
        val moreInfoContainer = view.findViewById<LinearLayout>(R.id.moreInfoContainer)
        val textTechnicalDetails = view.findViewById<TextView>(R.id.textTechnicalDetails)
        val btnOpenAppManual = view.findViewById<MaterialButton>(R.id.btnOpenAppManual)
        val btnOpenNavaManual = view.findViewById<MaterialButton>(R.id.btnOpenNavaManual)
        val btnCloseBottom = view.findViewById<MaterialButton>(R.id.btnCloseBottom)

        // Populate initial UI
        headerIcon.setImageResource(content.iconRes)
        headerBadge.setText(content.badgeRes)
        headerTitle.setText(content.titleRes)
        textTechnicalDetails.setText(content.technicalDetailsRes)

        var currentStep = 0
        var isPaused = false
        var isExpanded = startExpanded
        var userHasInteracted = false
        var isFrozenInManualMode = false
        var storyAnimator: ValueAnimator? = null

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        demoActiveDialog = dialog

        fun updateStep(stepIndex: Int, animate: Boolean, autoPlay: Boolean = true) {
            storyAnimator?.removeAllListeners()
            storyAnimator?.cancel()
            storyAnimator = null

            currentStep = stepIndex.coerceIn(0, totalSlides - 1)

            for (i in 0 until totalSlides) {
                storyBars[i].progress = when {
                    i < currentStep -> 1000
                    i == currentStep && !autoPlay -> 1000
                    else -> 0
                }
            }

            val slide = content.slides[currentStep]
            textPunchline.text = getString(slide.punchlineRes)
            textDescription.text = getString(slide.descRes)
            stageTextPrompt.text = slide.promptText
            stageTextOutput.text = slide.outputText
            textStepCounter.text = "${currentStep + 1} / $totalSlides"

            if (animate) {
                textPunchline.alpha = 0f
                textPunchline.scaleX = 0.78f
                textPunchline.scaleY = 0.78f
                textPunchline.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .start()

                stageTouchContainer.alpha = 0.6f
                stageTouchContainer.scaleX = 0.95f
                stageTouchContainer.scaleY = 0.95f
                stageTouchContainer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                textDescription.alpha = 0f
                textDescription.translationY = dp(8).toFloat()
                textDescription.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(350)
                    .setStartDelay(60)
                    .start()
            }

            if (!autoPlay || isFrozenInManualMode) {
                isPaused = true
                textHoldHint.text = getString(R.string.cinematic_paused_hint)
                return
            }

            isPaused = false
            textHoldHint.text = getString(R.string.cinematic_hold_hint)

            val totalTextLen = getString(slide.punchlineRes).length +
                    getString(slide.descRes).length +
                    slide.promptText.length +
                    slide.outputText.length

            val baseDurationMs = (7500L + (totalTextLen * 50L)).coerceIn(8500L, 16000L)
            val durationMs = if (userHasInteracted) {
                (baseDurationMs * 2.0f).toLong().coerceIn(16000L, 30000L)
            } else {
                baseDurationMs
            }

            storyAnimator = ValueAnimator.ofInt(0, 1000).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    if (currentStep in 0 until totalSlides) {
                        storyBars[currentStep].progress = anim.animatedValue as Int
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isPaused && !isFrozenInManualMode && dialog.isShowing) {
                            val next = if (currentStep < totalSlides - 1) currentStep + 1 else 0
                            updateStep(next, true, autoPlay = true)
                        }
                    }
                })
                if (!isPaused && !isExpanded) start()
            }
        }

        storyBars.forEachIndexed { idx, pb ->
            pb.setOnClickListener {
                userHasInteracted = true
                isFrozenInManualMode = true
                updateStep(idx, true, autoPlay = false)
            }
        }

        btnPrevStep.setOnClickListener {
            userHasInteracted = true
            isFrozenInManualMode = true
            val prev = if (currentStep > 0) currentStep - 1 else totalSlides - 1
            updateStep(prev, true, autoPlay = false)
        }

        btnNextStep.setOnClickListener {
            userHasInteracted = true
            isFrozenInManualMode = false
            val next = if (currentStep < totalSlides - 1) currentStep + 1 else 0
            updateStep(next, true, autoPlay = true)
        }

        stageTouchContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isPaused = true
                    storyAnimator?.pause()
                    textHoldHint.text = "⏸ Pausado"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isFrozenInManualMode) {
                        isPaused = false
                        if (!isExpanded) {
                            storyAnimator?.resume()
                            textHoldHint.text = getString(R.string.cinematic_hold_hint)
                        }
                    } else {
                        textHoldHint.text = getString(R.string.cinematic_paused_hint)
                    }
                    true
                }
                else -> false
            }
        }

        fun applyMoreInfoState() {
            moreInfoContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            btnToggleMoreInfo.text = if (isExpanded) getString(R.string.cinematic_hide_info) else getString(R.string.cinematic_more_info)
            if (isExpanded) {
                storyAnimator?.pause()
            } else if (!isPaused && !isFrozenInManualMode) {
                storyAnimator?.resume()
            }
        }

        btnToggleMoreInfo.setOnClickListener {
            isExpanded = !isExpanded
            applyMoreInfoState()
        }

        btnOpenAppManual.setOnClickListener {
            showManualActionDialog(getString(R.string.cmd_app_manual), "Manual_app_MeshNavarra.pdf")
        }

        btnOpenNavaManual.setOnClickListener {
            showManualActionDialog(getString(R.string.cmd_nava_manual), "Manual_NavaTastic.pdf")
        }

        btnCloseTop.setOnClickListener { dialog.dismiss() }
        btnCloseBottom.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            storyAnimator?.removeAllListeners()
            storyAnimator?.cancel()
            storyAnimator = null
        }

        applyMoreInfoState()
        updateStep(0, true, autoPlay = true)

        dialog.show()

        if (!forceShow && !demoMode) {
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        }
    }

    private fun showTabExplainer(position: Int) {
        showCinematicTabExplainer(position, forceShow = false)
    }

    private fun showGoodPracticesInfo() {
        showCinematicTabExplainer(0, forceShow = true, startExpanded = true)
    }

    private fun showNavaTasticIntro() {
        showCinematicTabExplainer(3, forceShow = true, startExpanded = false)
    }

    private fun showScrollableDialog(title: String, body: String) {
        val textView = TextView(this).apply {
            text = body
            textSize = 14f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
            isFillViewport = true
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
        demoActiveDialog = dialog
    }

    // ---------- Demo mode (screen-recording tour) ----------
    private var demoMode = false
    private var demoPointerOverlay: FrameLayout? = null
    private var demoPointer: ImageView? = null
    private var demoActiveDialog: androidx.appcompat.app.AlertDialog? = null
    private val demoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var demoPulseAnim: android.animation.ObjectAnimator? = null
    private var demoProgress: ProgressBar? = null
    private var demoBalloon: TextView? = null
    private var demoTotalMs = 65000L
    private var demoSuppressExplainers = false
    private val demoFakeNodes = mutableListOf<Int>()

    /** Thin bottom progress bar so the person recording can time the narration. */
    private val demoProgressTick = object : Runnable {
        override fun run() {
            val p = demoProgress ?: return
            val next = p.progress + ((250 * 100) / demoTotalMs).toInt()
            if (next >= 100) {
                p.progress = 100
                return
            }
            p.progress = next
            demoHandler.postDelayed(this, 250)
        }
    }

    /** True when connected for real or when the demo simulation is running. */
    private fun isReady(): Boolean =
        demoMode || usbConnectionManager.isConnected() || bleConnectionManager.isConnected()

    /**
     * Runs a scripted ~60 s tour of the app with an animated pointer, for screen
     * recording. Simulates a connection, walks every tab and shows the popups.
     */
    private fun startDemo() {
        if (usbConnectionManager.isConnected() || bleConnectionManager.isConnected()) {
            Toast.makeText(this, getString(R.string.demo_blocked_connected), Toast.LENGTH_LONG).show()
            return
        }
        stopDemo() // safe to press the button again: restarts cleanly
        demoMode = true
        demoSuppressExplainers = true
        demoTotalMs = 52000L
        resetForDemo()
        demoSetupOverlay()
        val overlay = demoPointerOverlay ?: return
        overlay.visibility = android.view.View.VISIBLE
        demoProgress?.progress = 0
        demoHandler.post(demoProgressTick)
        var t = 0L
        fun at(delay: Long, run: () -> Unit) {
            t += delay
            demoHandler.postDelayed(run, t)
        }

        // 1. Connect (fast simulation)
        at(300) { demoMoveTo(connectButton) }
        at(1200) { demoSimulateConnect() }
        at(2400) { populateDemoNodes() }

        // 2. Nodes (tab 5): the mesh, favorites first — the pain the app solves
        at(400) { demoGotoTab(5) }
        at(300) { }

        // 3. Administration (tab 2): mark a FAVORITE from the phone (no computer)
        at(400) { demoGotoTab(2) }
        at(300) { }
        at(400) { demoMoveTo(targetNodeInput) }
        at(900) { targetNodeInput.setText("!c0ffee") }
        at(600) { demoMoveTo(favoriteInput) }
        at(900) { favoriteInput.setText("5eed01") }
        at(700) { demoMoveTo(favoriteButton) }
        at(1000) { favoriteButton.performClick() }
        at(1200) { demoMoveTo(rebootButton) }
        at(1000) { rebootButton.performClick() }
        at(1200) { demoCloseDialog() }

        // 4. Good Practices (tab 0): duty-cycle explanation + apply
        at(400) { demoGotoTab(0) }
        at(300) { }
        at(400) { demoMoveTo(bpHelpButton) }
        at(1100) { bpHelpButton.performClick() }
        at(2000) { demoCloseDialog() }
        at(600) { demoMoveTo(bpApplyButton) }
        at(1100) { demoGoodPracticesApply() }
        at(2000) { demoCloseDialog() }

        // 5. Commands (tab 1): telemetry + position with decoded responses
        at(400) { demoGotoTab(1) }
        at(300) { }
        at(400) { demoMoveTo(cmdTargetInput) }
        at(900) { cmdTargetInput.setText("!c0ffee") }
        at(700) { demoMoveTo(cmdTelemetryButton) }
        at(1000) { cmdTelemetryButton.performClick() }
        at(1800) { demoFakeTelemetryResponse() }
        at(1500) { demoCloseDialog() }
        at(400) { demoMoveTo(cmdPositionButton) }
        at(1000) { cmdPositionButton.performClick() }
        at(1800) { demoFakePositionResponse() }
        at(1500) { demoCloseDialog() }

        // 6. NavaTastic CLI (tab 3): THE prize — favorites managed remotely over the fleet
        at(400) { demoGotoTab(3) }
        at(300) { }
        at(400) { demoMoveTo(navaTargetInput) }
        at(900) { navaTargetInput.setText("!c0ffee") }
        at(600) { demoMoveTo(navaCategorySpinner) }
        at(1000) { navaCategorySpinner.setSelection(2) }
        at(600) { demoMoveTo(navaCommandSpinner) }
        at(1000) { navaCommandSpinner.setSelection(1) }
        at(600) { demoMoveTo(navaArgInput) }
        at(900) { navaArgInput.setText("5eed01") }
        at(700) { demoMoveTo(navaSendButton) }
        at(1000) { navaSendButton.performClick() }
        at(1400) { demoCloseDialog() }

        // 7. Chat (tab 4): live messages + reply
        at(400) { demoGotoTab(4) }
        at(300) { }
        at(400) { demoSeedChat() }
        at(700) { demoMoveTo(chatReplyInput) }
        at(900) { chatReplyInput.setText("¡Favoritos desde el móvil!") }
        at(700) { demoMoveTo(chatSendButton) }
        at(1000) { chatSendButton.performClick() }
        at(1500) { demoCloseDialog() }

        // 8. Log (tab 6): everything recorded, newest first
        at(400) { demoGotoTab(6) }
        at(300) { }

        // 9. Finish
        at(400) { demoEnd() }
    }

    private fun stopDemo() {
        demoMode = false
        demoSuppressExplainers = false
        demoHandler.removeCallbacksAndMessages(null)
        demoPulseAnim?.cancel()
        demoPointerOverlay?.visibility = android.view.View.GONE
        synchronized(nodeEntries) {
            demoFakeNodes.forEach { nodeEntries.remove(it) }
        }
        demoFakeNodes.clear()
        refreshNodesList()
        statusProgress.visibility = android.view.View.GONE
        statusText.text = getString(R.string.status_disconnected)
    }

    /**
     * Brings the app back to a "just opened" state before the tour starts:
     * first tab, all panels scrolled to the top, dialogs closed and any fake
     * demo leftovers (nodes, chat, NavaTastic console) removed.
     */
    private fun resetForDemo() {
        demoActiveDialog?.dismiss()
        demoActiveDialog = null
        nodePopupDialog?.dismiss()
        bottomTabs.getTabAt(0)?.select()
        for (sv in listOf(
            bpPanel, commandsPanel, adminPanel, nodesPanel, logPanel, debugPanel,
            chatScroll,
            findViewById<ScrollView>(R.id.navaControlsScroll),
            findViewById<ScrollView>(R.id.navaConversationScroll)
        )) {
            sv?.scrollTo(0, 0)
        }
        synchronized(chatMessages) { chatMessages.clear() }
        refreshChat()
        synchronized(navaMessages) { navaMessages.clear() }
        refreshNava()
        synchronized(nodeEntries) {
            demoFakeNodes.forEach { nodeEntries.remove(it) }
        }
        demoFakeNodes.clear()
        refreshNodesList()
        statusProgress.visibility = android.view.View.GONE
        statusText.text = getString(R.string.status_disconnected)
    }

    private fun demoBalloonText(text: String) {
        val b = demoBalloon ?: return
        b.text = text
        b.visibility = android.view.View.VISIBLE
        b.alpha = 0f
        b.animate().alpha(1f).setDuration(160).start()
    }

    private fun demoBalloonHide() {
        val b = demoBalloon ?: return
        b.animate().alpha(0f).setDuration(120).withEndAction {
            b.visibility = android.view.View.GONE
            b.alpha = 1f
        }.start()
    }

    /**
     * Demo 2: guided tour with speech-balloon captions. No voice needed:
     * record the screen and add a music track. Walks every tab and explains
     * what each feature is for, tapping real controls with simulated responses.
     * Balloon hold time scales with the text length so it can be read.
     */
    private fun startDemo2() {
        if (usbConnectionManager.isConnected() || bleConnectionManager.isConnected()) {
            Toast.makeText(this, getString(R.string.demo_blocked_connected), Toast.LENGTH_LONG).show()
            return
        }
        stopDemo()
        demoMode = true
        demoSuppressExplainers = true
        demoTotalMs = 108000L
        resetForDemo()
        demoSetupOverlay()
        val overlay = demoPointerOverlay ?: return
        overlay.visibility = android.view.View.VISIBLE
        demoProgress?.progress = 0
        demoHandler.post(demoProgressTick)
        var t = 0L
        fun at(delay: Long, run: () -> Unit) {
            t += delay
            demoHandler.postDelayed(run, t)
        }
        fun hold(resId: Int): Long = (3500L + getString(resId).length * 60L).coerceAtMost(9000L)

        // 1. Connect (fast simulation)
        at(300) {
            demoMoveTo(connectButton)
            demoBalloonText(getString(R.string.d2_connect))
        }
        at(hold(R.string.d2_connect)) { demoBalloonHide() }
        at(200) { demoSimulateConnect() }
        at(2500) { populateDemoNodes() }

        // 2. Nodes (tab 5): the mesh as cards
        at(400) { demoGotoTab(5) }
        at(500) { demoBalloonText(getString(R.string.d2_nodes)) }
        at(hold(R.string.d2_nodes)) { demoBalloonHide() }

        // 2b. Node popup: eleven actions
        at(300) { showNodeInfoPopup(0xC0FFEE) }
        at(400) { demoBalloonText(getString(R.string.d2_popup)) }
        at(hold(R.string.d2_popup)) { demoBalloonHide() }
        at(200) { nodePopupDialog?.dismiss() }

        // 3. Administration (tab 2): mark a FAVORITE from the phone
        at(400) { demoGotoTab(2) }
        at(500) { demoBalloonText(getString(R.string.d2_admin)) }
        at(300) { demoMoveTo(targetNodeInput) }
        at(900) { targetNodeInput.setText("!c0ffee") }
        at(600) { demoMoveTo(favoriteInput) }
        at(900) { favoriteInput.setText("5eed01") }
        at(700) { demoMoveTo(favoriteButton) }
        at(1000) { favoriteButton.performClick() }
        at(500) { demoBalloonHide() }
        at(200) { demoCloseDialog() }

        // 4. Utilidades (tab 0): Good Practices + radio presets
        at(400) { demoGotoTab(0) }
        at(400) { demoMoveTo(bpHelpButton) }
        at(1100) { bpHelpButton.performClick() }
        at(400) { demoBalloonText(getString(R.string.d2_bp)) }
        at(hold(R.string.d2_bp)) { demoBalloonHide() }
        at(200) { demoCloseDialog() }
        at(500) { demoBalloonText(getString(R.string.d2_preset)) }
        at(300) { demoMoveTo(presetApplyButton) }
        at(3000) { demoBalloonHide() }

        // 5. Commands (tab 1): telemetry with decoded response
        at(400) { demoGotoTab(1) }
        at(400) { demoMoveTo(cmdTargetInput) }
        at(900) { cmdTargetInput.setText("!c0ffee") }
        at(700) { demoMoveTo(cmdTelemetryButton) }
        at(1000) { cmdTelemetryButton.performClick() }
        at(1800) { demoFakeTelemetryResponse() }
        at(400) { demoBalloonText(getString(R.string.d2_cmd)) }
        at(hold(R.string.d2_cmd)) { demoBalloonHide() }
        at(200) { demoCloseDialog() }

        // 6. NavaTastic CLI (tab 3): remote repeater control, the flagship
        at(400) { demoGotoTab(3) }
        at(400) { demoMoveTo(navaTargetInput) }
        at(900) { navaTargetInput.setText("!c0ffee") }
        at(400) { demoBalloonText(getString(R.string.d2_nava1)) }
        at(300) { demoMoveTo(navaCategorySpinner) }
        at(1000) { navaCategorySpinner.setSelection(2) }
        at(600) { demoMoveTo(navaCommandSpinner) }
        at(1000) { navaCommandSpinner.setSelection(1) }
        at(400) { demoBalloonHide() }
        at(200) { demoBalloonText(getString(R.string.d2_nava2)) }
        at(600) { demoMoveTo(navaArgInput) }
        at(900) { navaArgInput.setText("5eed01") }
        at(700) { demoMoveTo(navaSendButton) }
        at(1000) { navaSendButton.performClick() }
        at(400) { demoBalloonHide() }
        at(200) { demoCloseDialog() }
        at(600) {
            addNavaMsg(0xC0FFEE, "PONG: c0ffee | SNR: 12.3 dB | Bat: 4109 mV | RUIDO: -103 dBm", sent = false, route = "dm")
            demoBalloonText(getString(R.string.d2_pong))
        }
        at(hold(R.string.d2_pong)) { demoBalloonHide() }

        // 7. Chat (tab 4): live messages + reply with delivery status
        at(400) { demoGotoTab(4) }
        at(400) { demoSeedChat() }
        at(400) { demoBalloonText(getString(R.string.d2_chat)) }
        at(500) { demoMoveTo(chatReplyInput) }
        at(900) { chatReplyInput.setText("¡Favoritos desde el móvil!") }
        at(700) { demoMoveTo(chatSendButton) }
        at(1000) { chatSendButton.performClick() }
        at(400) { demoBalloonHide() }
        at(200) { demoCloseDialog() }

        // 8. Log (tab 6): everything recorded
        at(400) { demoGotoTab(6) }
        at(500) { demoBalloonText(getString(R.string.d2_log)) }
        at(3000) { demoBalloonHide() }

        // 9. Finish
        at(300) { demoBalloonText(getString(R.string.d2_end)) }
        at(hold(R.string.d2_end)) { demoBalloonHide() }
        at(200) { demoEnd() }
    }

    private fun demoEnd() {
        showScrollableDialog(getString(R.string.demo_end_title), getString(R.string.demo_end_body))
        demoHandler.postDelayed({
            stopDemo()
            statusProgress.visibility = android.view.View.GONE
            statusText.text = getString(R.string.status_disconnected)
            val keep = demoFakeNodes.toSet()
            synchronized(nodeEntries) { nodeEntries.keys.removeAll { it in keep } }
            refreshNodesList()
        }, 4200)
    }

    private fun demoSetupOverlay() {
        if (demoPointerOverlay != null) return
        val decor = window.decorView as? FrameLayout ?: return
        val overlay = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
            setWillNotDraw(true)
        }
        val pointer = ImageView(this).apply {
            setImageResource(R.drawable.ic_pointer)
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val stopBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.demo_stop)
            textSize = 11f
            alpha = 0.75f
            setOnClickListener { stopDemo() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            ).apply {
                bottomMargin = dp(14)
                rightMargin = dp(12)
            }
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                getColorAttr(com.google.android.material.R.attr.colorPrimary)
            )
            alpha = 0.85f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(6),
                android.view.Gravity.BOTTOM
            ).apply { bottomMargin = dp(2) }
        }
        val balloon = TextView(this).apply {
            visibility = android.view.View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 17f
            maxLines = 5
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(withAlpha(getColorAttr(com.google.android.material.R.attr.colorSurface), 0.94f))
                setStroke(dp(1), getColorAttr(com.google.android.material.R.attr.colorPrimary))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            ).apply {
                bottomMargin = dp(62)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        }
        overlay.addView(pointer)
        overlay.addView(stopBtn)
        overlay.addView(progress)
        overlay.addView(balloon)
        demoPointerOverlay = overlay
        demoPointer = pointer
        demoProgress = progress
        demoBalloon = balloon
        decor.addView(
            overlay,
            FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun demoMoveTo(view: android.view.View?) {
        val ov = demoPointerOverlay ?: return
        val ptr = demoPointer ?: return
        if (view == null) return
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val tx = loc[0] + view.width / 2 - ov.width / 2
        val ty = loc[1] + view.height / 2 - ov.height / 2
        ptr.animate().cancel()
        demoPulseAnim?.cancel()
        ptr.alpha = 1f
        ptr.translationX = tx.toFloat()
        ptr.translationY = ty.toFloat()
        ptr.scaleX = 1f
        ptr.scaleY = 1f
        val pvhX = android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.18f, 1f)
        val pvhY = android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.18f, 1f)
        val pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(ptr, pvhX, pvhY)
        pulse.duration = 900
        pulse.repeatCount = android.animation.ValueAnimator.INFINITE
        pulse.start()
        demoPulseAnim = pulse
    }

    private fun demoGotoTab(pos: Int) {
        val tab = bottomTabs.getTabAt(pos)
        demoMoveTo(tab?.view)
        demoHandler.postDelayed({ tab?.select() }, 700)
    }

    private fun demoCloseDialog() {
        val d = demoActiveDialog ?: return
        demoActiveDialog = null
        val btn = d.getButton(android.app.AlertDialog.BUTTON_POSITIVE) ?: return
        demoMoveTo(btn)
        demoHandler.postDelayed({ btn.performClick() }, 700)
    }

    private fun demoSimulateConnect() {
        statusProgress.visibility = android.view.View.VISIBLE
        statusText.text = getString(R.string.status_demo_connecting)
        demoHandler.postDelayed({
            statusProgress.visibility = android.view.View.GONE
            statusText.text = getString(R.string.status_demo_connected)
            localNodeNum = 0xC0FFEE
            appendLog("DEMO >> Conectado a Nodo DEMO (simulación)")
        }, 2500)
    }

    private fun populateDemoNodes() {
        val now = System.currentTimeMillis()
        val specs = listOf(
            Triple(0xC0FFEE, "REPETIDOR MIRADOR", true),
            Triple(0xC0FFAB, "REPETIDOR ROBLE", true),
            Triple(0x5EED01, "MANDO VALLE", false),
            Triple(0x5EED02, "MANDO MÓVIL", false)
        )
        synchronized(nodeEntries) {
            specs.forEachIndexed { i, (num, name, fav) ->
                nodeEntries[num] = NodeEntry(
                    num = num, name = name, isFavorite = fav,
                    battery = 92 - i * 7, voltage = 4.05f - i * 0.09f,
                    snr = 8.5f - i * 2.1f, lastHeard = now - i * 60000L, hops = if (fav) 0 else 2
                )
                demoFakeNodes.add(num)
            }
        }
        appendLog("DEMO >> NodeDB: ${nodeEntries.size} nodos cargados (simulación)")
        refreshNodesList()
    }

    private fun demoGoodPracticesApply() {
        bpAppendStatus(getString(R.string.bp_status_applying, 1, 3, getString(R.string.bp_step_hop, 5)))
        demoHandler.postDelayed({ bpAppendStatus(getString(R.string.bp_status_applying, 2, 3, getString(R.string.bp_step_nodeinfo, 259200))) }, 600)
        demoHandler.postDelayed({ bpAppendStatus(getString(R.string.bp_status_applying, 3, 3, getString(R.string.bp_step_position, 259200, getString(R.string.bp_smart_off), 120))) }, 1200)
        demoHandler.postDelayed({
            bpAppendStatus(getString(R.string.bp_status_done, "0xc0ffee"))
            showScrollableDialog(getString(R.string.bp_demo_title), getString(R.string.bp_demo_body))
        }, 1800)
    }

    private fun demoFakeTelemetryResponse() {
        val tel = "\n  Temp: 24,5 °C\n  Batería: 4,12 V (88%)\n  Heap: 96 kB / 122 kB"
        onResponseReceived(getString(R.string.popup_response_telemetry, tel))
    }

    private fun demoFakePositionResponse() {
        val pos = "\n  Lat: 42,8164\n  Lon: -1,6438\n  Alt: 431 m\n  Precisión ~12 m"
        onResponseReceived(getString(R.string.popup_response_position, pos))
    }

    private fun demoSeedChat() {
        val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        synchronized(chatMessages) {
            chatMessages.add(ChatMessage(0xC0FFAB, "Prueba de enlace, ¿me copias?", 0, now))
            chatMessages.add(ChatMessage(0x5EED01, "Copio fuerte y claro.", 0, now))
        }
        refreshChat()
    }

    /**
     * Lets the user swipe left/right on the content area to move between tabs,
     * which makes the off-screen tabs discoverable.
     */
    private fun setupTabSwipe() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = (e2.x - (e1?.x ?: e2.x))
                val dy = (e2.y - (e1?.y ?: e2.y))
                if (Math.abs(dx) > Math.abs(dy) * 1.2f && Math.abs(dx) > 120f && Math.abs(velocityX) > 400f) {
                    val target = (bottomTabs.selectedTabPosition + if (dx < 0) 1 else -1)
                        .coerceIn(0, bottomTabs.tabCount - 1)
                    bottomTabs.getTabAt(target)?.select()
                    return true
                }
                return false
            }
        })
        tabContent.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    /**
     * Arrows at both edges of the tab bar make it obvious the tabs can be
     * dragged/swiped. Each arrow pulses while there are more tabs in its
     * direction and dims at the end; tapping it moves to the next tab.
     */
    private fun setupTabEdgeHints() {
        val strip = bottomTabs.getChildAt(0) as? android.widget.HorizontalScrollView ?: return
        strip.viewTreeObserver.addOnScrollChangedListener {
            val inner = strip.getChildAt(0)
            val canScrollRight = inner != null && strip.scrollX < inner.width - strip.width
            val canScrollLeft = strip.scrollX > 0
            updateTabHint(tabHintRight, canScrollRight)
            updateTabHint(tabHintLeft, canScrollLeft)
        }
        strip.post {
            val inner = strip.getChildAt(0)
            val canScrollRight = inner != null && strip.scrollX < inner.width - strip.width
            updateTabHint(tabHintRight, canScrollRight)
            updateTabHint(tabHintLeft, strip.scrollX > 0)
        }
        tabHintRight.setOnClickListener {
            val pos = (bottomTabs.selectedTabPosition + 1).coerceAtMost(bottomTabs.tabCount - 1)
            bottomTabs.getTabAt(pos)?.select()
        }
        tabHintLeft.setOnClickListener {
            val pos = (bottomTabs.selectedTabPosition - 1).coerceAtLeast(0)
            bottomTabs.getTabAt(pos)?.select()
        }
    }

    private var tabHintAnimRight: android.animation.ObjectAnimator? = null
    private var tabHintAnimLeft: android.animation.ObjectAnimator? = null

    private fun updateTabHint(view: ImageView, more: Boolean) {
        val animator = if (view === tabHintRight) tabHintAnimRight else tabHintAnimLeft
        if (more) {
            if (animator == null || !animator.isRunning) {
                view.alpha = 1f
                val a = android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.45f, 1f)
                a.duration = 1400
                a.repeatCount = android.animation.ValueAnimator.INFINITE
                a.start()
                if (view === tabHintRight) tabHintAnimRight = a else tabHintAnimLeft = a
            }
        } else {
            animator?.cancel()
            view.alpha = 0.30f
        }
    }

    /**
     * Shows the persisted log file (last 200 lines) in the Log tab. The file
     * survives app restarts so past requests can be reviewed later.
     */
    private fun refreshLogTab() {
        runOnUiThread {
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val file = File(baseDir, MeshKachoUtilityApp.LOG_DIR + File.separator + "app_log.txt")
            val content = try {
                if (file.exists()) file.readText() else getString(R.string.log_empty)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read log file", e)
                getString(R.string.log_empty)
            }
            val lines = content.trimEnd('\n').lines().takeLast(200).reversed()
            logFileText.text = lines.joinToString("\n")
        }
    }

    /**
     * Clears the log file on disk and resets the in-memory log buffer and view.
     */
    private fun clearLogs() {
        runOnUiThread {
            try {
                val baseDir = getExternalFilesDir(null) ?: filesDir
                val file = File(baseDir, MeshKachoUtilityApp.LOG_DIR + File.separator + "app_log.txt")
                if (file.exists()) {
                    file.writeText("")
                }
                logText.text = ""
                logFileText.text = getString(R.string.log_empty)
                Toast.makeText(this, getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear log file", e)
                Toast.makeText(this, getString(R.string.log_error, e.localizedMessage), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Disconnects the active USB OTG session and cancels pending reconnection attempts.
     */
    private fun disconnectUsb() {
        if (demoMode) {
            demoEnd()
            return
        }
        userInitiatedDisconnect = true
        cancelReconnect()
        if (usbConnectionManager.isConnected()) {
            usbConnectionManager.disconnect()
            appendLog(getString(R.string.log_usb_disconnected_manual))
        } else {
            Toast.makeText(this, getString(R.string.usb_not_connected), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Disconnects the active Bluetooth Low Energy session and tears down the GATT link.
     */
    private fun disconnectBluetooth() {
        if (demoMode) {
            demoEnd()
            return
        }
        userInitiatedDisconnect = true
        cancelReconnect()
        if (bleConnectionManager.isConnected()) {
            bleConnectionManager.disconnect()
            bleTransportActive = false
            appendLog(getString(R.string.log_ble_disconnected_manual))
            onDisconnected()
        } else {
            Toast.makeText(this, getString(R.string.ble_not_connected), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Refreshes the full node list shown in the "Nodes" tab as visual cards
     * (favorites first), similar to the Meshtastic app.
     */
    private fun refreshNodesList() {
        val entries = synchronized(nodeEntries) { nodeEntries.values.toList() }
            .sortedWith(compareByDescending<NodeEntry> { it.isFavorite }.thenBy { it.name.lowercase(Locale.US) })
        appendLog("refreshNodesList: nodeInfoCount=$nodeInfoCount entries=${entries.size}")
        runOnUiThread {
            nodesListContainer.removeAllViews()

            val header = TextView(this).apply {
                text = getString(R.string.nodes_header, nodeInfoCount, entries.size)
                setPadding(dp(12), dp(4), dp(12), dp(8))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColorAttr(android.R.attr.colorAccent))
            }
            nodesListContainer.addView(header)

            var shown = 0
            for (entry in entries) {
                if (shown >= MAX_NODES_TAB_ROWS) break
                nodesListContainer.addView(buildNodeCard(entry))
                shown++
            }
            if (entries.size > shown) {
                nodesListContainer.addView(TextView(this).apply {
                    text = getString(R.string.nodes_more_cache, entries.size - shown)
                    setPadding(dp(12), dp(4), dp(12), dp(8))
                    textSize = 12f
                    setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
            }
        }
    }

    /**
     * Builds a visual node card (name, id, battery, voltage, SNR, last heard, hops).
     */
    private fun buildNodeCard(entry: NodeEntry): android.view.View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            setCardBackgroundColor(getColorAttr(com.google.android.material.R.attr.colorSurfaceVariant))
            cardElevation = dp(1).toFloat()
            setContentPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8).toInt() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // Status dot / favorite badge
        val badge = TextView(this).apply {
            text = if (entry.isFavorite) "★" else "•"
            textSize = 20f
            setTextColor(getColorAttr(androidx.appcompat.R.attr.colorAccent))
            setPadding(0, 0, dp(12).toInt(), 0)
        }
        row.addView(badge)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val name = TextView(this).apply {
            text = entry.name
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
            maxLines = 1
        }
        col.addView(name)

        val id = TextView(this).apply {
            text = "!${Integer.toHexString(entry.num)}"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        col.addView(id)

        val details = buildString {
            append("\uD83D\uDD0B ")
            append(if (entry.battery in 1..100) "${entry.battery}%" else "—")
            append("  ⚡")
            append(if (entry.voltage > 0) String.format(Locale.US, "%.2fV", entry.voltage) else "—")
            append("  📶 ")
            append(String.format(Locale.US, "%.1f", entry.snr))
            append("dB")
            if (entry.lastHeard > 0) {
                append("  🕐 ")
                append(formatSince(entry.lastHeard))
            }
            if (entry.hops >= 0) {
                append("  · ")
                append(entry.hops)
                append(" hops")
            }
        }
        val det = TextView(this).apply {
            text = details
            textSize = 12f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        col.addView(det)

        row.addView(col)
        card.addView(row)
        card.setOnClickListener { showNodeInfoPopup(entry.num) }
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
    private fun getColorAttr(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }

    /**
     * Adds a subtle press animation (scale down/up) to every action button for
     * clear tactile feedback.
     */
    private fun applyPressAnimations() {
        val buttons = listOf(
            helpButton, connectButton, disconnectUsbButton, connectBluetoothButton, disconnectBluetoothButton,
            queryNodeButton, rebootButton, wipeNodeDbButton, factoryResetButton, favoriteButton, unsetFavoriteButton,
            ignoredButton, unsetIgnoredButton, removeNodeButton, adminKeyAddButton, masterConvertButton, sendCmdButton,
            cmdTelemetryButton, cmdPositionButton, cmdTraceButton, cmdSetOwnerButton, bpApplyButton, bpBackupButton,
            bpRestoreButton, chatSendButton, nodesImportButton, hopApplyButton, freqApplyButton, navadminTestButton,
            navadminTestStopButton, presetApplyButton, chatPauseButton, navaSendButton, clearLogButton
        )
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        for (b in buttons) {
            var longPressFired = false
            var pressX = 0f
            var pressY = 0f
            val showBubble = Runnable {
                longPressFired = true
                showButtonHelpBubble(b, pressX.toInt(), pressY.toInt())
            }
            b.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressFired = false
                        pressX = event.x
                        pressY = event.y
                        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                        v.postDelayed(showBubble, longPressTimeout)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(showBubble)
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        if (longPressFired) {
                            longPressFired = false
                            dismissHelpBubble()
                            showButtonHelp(v)
                            return@setOnTouchListener true
                        }
                    }
                }
                false
            }
        }
    }

    private var helpBubble: android.widget.PopupWindow? = null

    /**
     * Reusable press animation + long-press help for dynamically-created
     * buttons: scale feedback on touch and, if the user holds, a bubble above
     * the finger; on release, the full help dialog with an explicit body.
     */
    private fun attachPressHelp(button: MaterialButton, helpBody: String) {
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        var longPressFired = false
        var pressX = 0f
        var pressY = 0f
        val showBubble = Runnable {
            longPressFired = true
            showHelpBubbleAt(button, helpBody, pressX.toInt(), pressY.toInt())
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    pressX = event.x
                    pressY = event.y
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    v.postDelayed(showBubble, longPressTimeout)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(showBubble)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (longPressFired) {
                        longPressFired = false
                        dismissHelpBubble()
                        showHelpDialogFor((button.text?.toString() ?: getString(R.string.help_generic_title)), helpBody)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun showHelpDialogFor(title: String, body: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showLicenseDialog() {
        val textView = TextView(this).apply {
            text = getString(R.string.license_body)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.license_title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showButtonHelpBubble(button: android.view.View, touchX: Int, touchY: Int) {
        dismissHelpBubble()
        val title = (button as? MaterialButton)?.text?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.help_generic_title)
        val body = buttonHelps[button.id]?.let { getString(it) } ?: getString(R.string.help_generic)
        val titleView = TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnPrimaryContainer))
        }
        val bodyView = TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnPrimaryContainer))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(titleView)
            addView(bodyView)
        }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            setCardBackgroundColor(getColorAttr(com.google.android.material.R.attr.colorPrimaryContainer))
            cardElevation = dp(4).toFloat()
            layoutParams = android.view.ViewGroup.LayoutParams(
                dp(280),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(column)
        val popup = android.widget.PopupWindow(card, dp(280), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false
            isFocusable = false
        }
        val screen = resources.displayMetrics
        val width = popup.width
        val height = popup.height
        val x = (touchX - width / 2).coerceIn(0, screen.widthPixels - width)
        val y = (touchY - height - dp(24)).coerceAtLeast(dp(8))
        popup.showAtLocation(button, android.view.Gravity.NO_GRAVITY, x, y)
        helpBubble = popup
    }

    /**
     * Bubble variant with an explicit help body (for dynamically-created buttons
     * that are not in the buttonHelps catalog).
     */
    private fun showHelpBubbleAt(button: android.view.View, body: String, touchX: Int, touchY: Int) {
        dismissHelpBubble()
        val title = (button as? MaterialButton)?.text?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.help_generic_title)
        val titleView = TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnPrimaryContainer))
        }
        val bodyView = TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnPrimaryContainer))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(titleView)
            addView(bodyView)
        }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            setCardBackgroundColor(getColorAttr(com.google.android.material.R.attr.colorPrimaryContainer))
            cardElevation = dp(4).toFloat()
            layoutParams = android.view.ViewGroup.LayoutParams(dp(280), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        card.addView(column)
        val popup = android.widget.PopupWindow(card, dp(280), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false
            isFocusable = false
        }
        val screen = resources.displayMetrics
        val width = popup.width
        val height = popup.height
        val x = (touchX - width / 2).coerceIn(0, screen.widthPixels - width)
        val y = (touchY - height - dp(24)).coerceAtLeast(dp(8))
        popup.showAtLocation(button, android.view.Gravity.NO_GRAVITY, x, y)
        helpBubble = popup
    }

    private fun dismissHelpBubble() {
        helpBubble?.dismiss()
        helpBubble = null
    }

    /**
     * Long-press help: every button shows a popup explaining what it does and
     * practical tips. Dedicated text per button when available, generic otherwise.
     */
    private val buttonHelps: Map<Int, Int> by lazy {
        mapOf(
            R.id.connectButton to R.string.help_connect_usb,
            R.id.disconnectUsbButton to R.string.help_disconnect_usb,
            R.id.connectBluetoothButton to R.string.help_connect_ble,
            R.id.disconnectBluetoothButton to R.string.help_disconnect_ble,
            R.id.queryNodeButton to R.string.help_query_node,
            R.id.rebootButton to R.string.help_reboot,
            R.id.wipeNodeDbButton to R.string.help_wipe,
            R.id.factoryResetButton to R.string.help_factory,
            R.id.favoriteButton to R.string.help_set_favorite,
            R.id.unsetFavoriteButton to R.string.help_unset_favorite,
            R.id.ignoredButton to R.string.help_set_ignored,
            R.id.unsetIgnoredButton to R.string.help_unset_ignored,
            R.id.removeNodeButton to R.string.help_remove_node,
            R.id.adminKeyAddButton to R.string.help_add_key,
            R.id.masterConvertButton to R.string.help_master,
            R.id.cmdTelemetryButton to R.string.help_telemetry,
            R.id.cmdPositionButton to R.string.help_position,
            R.id.cmdTraceButton to R.string.help_traceroute,
            R.id.cmdSetOwnerButton to R.string.help_set_owner,
            R.id.bpApplyButton to R.string.help_bp_apply,
            R.id.bpBackupButton to R.string.help_bp_backup,
            R.id.bpRestoreButton to R.string.help_bp_restore,
            R.id.chatSendButton to R.string.help_chat_send,
            R.id.nodesImportButton to R.string.help_nodes_import,
            R.id.presetApplyButton to R.string.help_preset_apply,
            R.id.chatPauseButton to R.string.help_chat_pause,
            R.id.sendCmdButton to R.string.help_send_cmd,
            R.id.navaSendButton to R.string.help_nava_send,
            R.id.hopApplyButton to R.string.help_hop_apply,
            R.id.freqApplyButton to R.string.help_freq_apply,
            R.id.navadminTestButton to R.string.help_audit_run,
            R.id.navadminTestStopButton to R.string.help_audit_stop,
            R.id.clearLogButton to R.string.help_clear_log
        )
    }

    private fun showButtonHelp(button: android.view.View) {
        val title = (button as? MaterialButton)?.let { mb ->
            mb.text?.toString()?.takeIf { it.isNotBlank() }
                ?: mb.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        } ?: getString(R.string.help_generic_title)
        val body = buttonHelps[button.id]?.let { getString(it) } ?: getString(R.string.help_generic)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /**
     * Attaches the node-picker button to every node input field (target,
     * favorite, ignored, commands target and good-practices target).
     * Favorites are listed first.
     */
    private fun setupNodePickers() {
        findViewById<TextInputLayout>(R.id.targetNodeInputLayout).setStartIconOnClickListener { showNodePicker(targetNodeInput) }
        findViewById<TextInputLayout>(R.id.favoriteInputLayout).setEndIconOnClickListener { showNodePicker(favoriteInput) }
        findViewById<TextInputLayout>(R.id.ignoredInputLayout).setEndIconOnClickListener { showNodePicker(ignoredInput) }
        findViewById<TextInputLayout>(R.id.removeNodeInputLayout).setEndIconOnClickListener { showNodePicker(removeNodeInput) }
        findViewById<TextInputLayout>(R.id.cmdTargetInputLayout).setStartIconOnClickListener { showNodePicker(cmdTargetInput) }
        findViewById<TextInputLayout>(R.id.bpTargetInputLayout).setStartIconOnClickListener { showNodePicker(bpTargetInput) }

        // Live name lookup: while typing a node ID manually, resolve it against
        // the NodeDB/cache and show the node name so the user cannot mistype.
        attachNodeNameLookup(targetNodeInput, findViewById(R.id.targetNodeInputLayout))
        attachNodeNameLookup(favoriteInput, findViewById(R.id.favoriteInputLayout))
        attachNodeNameLookup(ignoredInput, findViewById(R.id.ignoredInputLayout))
        attachNodeNameLookup(removeNodeInput, findViewById(R.id.removeNodeInputLayout))
        attachNodeNameLookup(cmdTargetInput, findViewById(R.id.cmdTargetInputLayout))
        attachNodeNameLookup(bpTargetInput, findViewById(R.id.bpTargetInputLayout))
    }

    private fun attachNodeNameLookup(input: TextInputEditText, layout: TextInputLayout) {
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    layout.helperText = null
                    return
                }
                val num = try { parseNodeId(raw) } catch (e: Exception) { -1 }
                if (num == -1) {
                    layout.helperText = null
                    return
                }
                val name = synchronized(nodeEntries) { nodeEntries[num]?.name }
                    ?: synchronized(nodeInfos) { nodeInfos[num] }?.takeIf { it.hasUser() }?.user?.longName
                layout.helperText = if (name != null) {
                    getString(R.string.node_name_lookup, name, "!${Integer.toHexString(num)}")
                } else {
                    getString(R.string.node_name_unknown)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    /**
     * Wires the Good Practices tab: builds the config steps (hop limit, GPS beacon
     * interval, disable smart position, GPS update) and sends them one by one,
     * waiting for the ACK of each before sending the next.
     */
    private fun setupGoodPractices() {
        bpApplyButton.setOnClickListener { if (demoMode) demoGoodPracticesApply() else runGoodPractices() }
        bpHelpButton.setOnClickListener { showGoodPracticesInfo() }
        bpBackupButton.setOnClickListener { runBackup() }
        bpRestoreButton.setOnClickListener { showBackupPicker() }
        setupPresets()
    }

    /**
     * One-tap radio presets: sets the primary channel (name + PSK AQ==) and the
     * LoRa preset. Stock presets use the firmware automation (channel_num 0 =
     * frequency derived from the channel name hash) and clear any frequency
     * override left by a custom preset; "SFN Spain" applies the SFNarrow values
     * (869.618 MHz, 62 kHz, SF7, CR5, slot 4).
     */
    private data class RadioPreset(val label: String, val channelName: String, val isStock: Boolean, val modemPreset: Int)
    private val radioPresets = listOf(
        RadioPreset("SFN Spain", "SFNarrow", false, 0),
        RadioPreset("LongFast", "LongFast", true, 0),
        RadioPreset("MediumFast", "MediumFast", true, 4),
        RadioPreset("ShortFast", "ShortFast", true, 1),
        RadioPreset("ShortTurbo", "ShortTurbo", true, 8),
        RadioPreset("LongTurbo", "LongTurbo", true, 9),
        RadioPreset("ShortSlow", "ShortSlow", true, 2),
        RadioPreset("MediumSlow", "MediumSlow", true, 3),
        RadioPreset("LongSlow", "LongSlow", true, 5),
        RadioPreset("VeryLongSlow", "VeryLongSlow", true, 6),
        RadioPreset("LongModerate", "LongModerate", true, 7)
    )

    private fun setupPresets() {
        val spinner = findViewById<Spinner>(R.id.presetSpinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, radioPresets.map { it.label })
        spinner.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.lora_settings_title), getString(R.string.help_preset_spinner))
            true
        }
        presetApplyButton.setOnClickListener {
            val preset = radioPresets.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
            applyPreset(preset)
        }
    }

    private fun applyPreset(preset: RadioPreset) {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            Toast.makeText(this, getString(R.string.log_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        if (warnIfAdminOverBle()) return
        val channel = org.meshtastic.proto.ChannelProtos.Channel.newBuilder()
            .setIndex(0)
            .setRole(org.meshtastic.proto.ChannelProtos.Channel.Role.PRIMARY)
            .setSettings(
                org.meshtastic.proto.ChannelProtos.ChannelSettings.newBuilder()
                    .setName(preset.channelName)
                    .setPsk(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x01)))
                    .build()
            )
            .build()
        sendToRadio(MeshPacketBuilder.buildSetChannelPacket(channel, localNodeNum ?: -1))
        applySingleLoraJob(getString(R.string.presets_apply) + ": " + preset.label) { b ->
            val lora = b.getLora().toBuilder()
                .setUsePreset(preset.isStock)
                .setModemPreset(
                    ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(preset.modemPreset)
                        ?: ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST
                )
                .setChannelNum(0)
                .setOverrideFrequency(0f)
            if (!preset.isStock) {
                lora.setBandwidth(62).setSpreadFactor(7).setCodingRate(5).setChannelNum(4)
                    .setOverrideFrequency(869.618f)
            }
            b.setLora(lora.build())
        }
    }

    /**
     * Shows a determinate progress dialog for multi-step operations (backup/restore).
     */
    private fun showProgress(title: String, total: Int) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = if (total > 0) total else 1
            progress = 0
            isIndeterminate = false
            setPadding(dp(24), dp(12), dp(24), dp(4))
        }
        val label = TextView(this).apply {
            setPadding(dp(24), 0, dp(24), dp(16))
            textSize = 13f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(label)
        }
        progressBar = bar
        progressLabel = label
        progressDialog?.dismiss()
        progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(layout)
            .setCancelable(false)
            .create()
        progressDialog?.show()
        updateProgress(0, total, "")
    }

    private fun updateProgress(current: Int, total: Int, note: String) {
        progressBar?.max = if (total > 0) total else 1
        progressBar?.progress = current
        val pct = if (total > 0) current * 100 / total else 0
        progressLabel?.text = getString(R.string.progress_section, current, total, pct, note)
    }

    private fun hideProgress() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        progressLabel = null
    }

    /** Caps a dialog ScrollView at ~70% of the screen so the dialog buttons stay reachable on small screens / large fonts. */
    private fun capDialogScroll(scroll: ScrollView, dialog: android.app.Dialog, ratio: Double = 0.7) {
        dialog.setOnShowListener {
            scroll.post {
                val maxH = (resources.displayMetrics.heightPixels * ratio).toInt()
                val lp = scroll.layoutParams
                if (lp != null && scroll.height > maxH) {
                    lp.height = maxH
                    scroll.layoutParams = lp
                }
            }
        }
    }

    /**
     * Shows the disclaimer on first launch. The Accept button is gated by a
     * checkbox the user must tick after reading the notice.
     */
    private fun showDisclaimerIfNeeded() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        val prefs = getSharedPreferences("meshkacho", MODE_PRIVATE)
        if (prefs.getString("disclaimer_accepted", null) == versionName) return

        val body = TextView(this).apply {
            text = getString(R.string.disclaimer_body)
            setPadding(dp(24), dp(8), dp(24), 0)
            textSize = 14f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
        }
        val agree = MaterialCheckBox(this).apply {
            text = getString(R.string.disclaimer_agree)
            setPadding(dp(24), dp(12), dp(24), dp(4))
        }
        val footer = TextView(this).apply {
            val email = getString(R.string.app_email)
            val githubUrl = getString(R.string.app_github_url)
            val ft = getString(R.string.app_author) + "\n" +
                    getString(R.string.app_version_fork, versionName, BUILD_DATE) + "\n" +
                    getString(R.string.app_github_hint)
            val sp = android.text.SpannableString(ft)
            val at = ft.indexOf(email)
            if (at >= 0) {
                sp.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            try {
                                startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("mailto:$email")
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.app_email_subject))
                                    }
                                )
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, getString(R.string.log_error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    at, at + email.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val urlAt = ft.indexOf(githubUrl)
            if (urlAt >= 0) {
                sp.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            try {
                                startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(githubUrl))
                                )
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, getString(R.string.log_error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    urlAt, urlAt + githubUrl.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            text = sp
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setLinkTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(24), dp(4), dp(24), dp(4))
            textSize = 12f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
            addView(footer)
            addView(agree)
        }
        // Scrollable body so the Accept button never hides on small screens / large fonts.
        val scroll = ScrollView(this).apply { addView(layout) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_title)
            .setView(scroll)
            .setPositiveButton(R.string.disclaimer_accept, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            scroll.post {
                val maxH = (resources.displayMetrics.heightPixels * 0.7).toInt()
                val lp = scroll.layoutParams
                if (lp != null && scroll.height > maxH) {
                    lp.height = maxH
                    scroll.layoutParams = lp
                }
            }
            val btn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            agree.setOnCheckedChangeListener { _, checked -> btn.isEnabled = checked }
            btn.setOnClickListener {
                prefs.edit().putString("disclaimer_accepted", versionName).apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Wires the Chat tab: replies on the current channel.
     */
    /**
     * Wires the NavaTastic tab: /nava commands over the Navadmin channel (read-only)
     * or as PKI DMs (destructive). Conversation shows Navadmin + DM responses.
     */
    /**
     * Opens a bundled PDF manual (assets/<assetName>) with the system PDF viewer,
     * copying it to cache and exposing it via FileProvider.
     */
    private fun openBundledPdf(assetName: String) {
        try {
            val cacheFile = File(cacheDir, assetName)
            assets.open(assetName).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", cacheFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.nava_manual_no_viewer), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "open bundled pdf $assetName failed", e)
            Toast.makeText(this, getString(R.string.nava_manual_open_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAssetToUri(assetName: String, uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                assets.open(assetName).use { input ->
                    input.copyTo(out)
                }
            }
            Toast.makeText(this, getString(R.string.pdf_saved_successfully), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save PDF to $uri", e)
            Toast.makeText(this, getString(R.string.pdf_save_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAssetToDownloads(assetName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, assetName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    saveAssetToUri(assetName, uri)
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), assetName)
                assets.open(assetName).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Toast.makeText(this, getString(R.string.pdf_saved_successfully), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save PDF to Downloads", e)
            Toast.makeText(this, getString(R.string.pdf_save_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun promptDownloadPdf(assetName: String) {
        pendingDownloadAssetName = assetName
        try {
            createDocumentLauncher.launch(assetName)
        } catch (e: Exception) {
            Log.w(TAG, "SAF create document failed, falling back to Downloads", e)
            saveAssetToDownloads(assetName)
        }
    }

    private fun sharePdf(assetName: String) {
        try {
            val cacheFile = File(cacheDir, assetName)
            assets.open(assetName).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", cacheFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.manual_action_share)))
        } catch (e: Exception) {
            Log.w(TAG, "Share PDF $assetName failed", e)
        }
    }

    private fun showManualActionDialog(title: String, assetName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(R.string.manual_action_prompt)
            .setPositiveButton(R.string.manual_action_open) { _, _ ->
                openBundledPdf(assetName)
            }
            .setNeutralButton(R.string.manual_action_download) { _, _ ->
                promptDownloadPdf(assetName)
            }
            .setNegativeButton(R.string.manual_action_share) { _, _ ->
                sharePdf(assetName)
            }
            .show()
    }

    private fun setupNavaTastic() {
        loadNavaHistory()
        findViewById<TextInputLayout>(R.id.navaTargetLayout).setStartIconOnClickListener { showNodePicker(navaTargetInput) }

        navaCategories = listOf(
            NavaCat(getString(R.string.nava_cat_diag), listOf(
                NavaCmd("ping", "ping", "none", "ch", getString(R.string.nava_desc_ping)),
                NavaCmd("status", "status", "none", "ch", getString(R.string.nava_desc_status)),
                NavaCmd("env", "env", "none", "ch", getString(R.string.nava_desc_env)),
                NavaCmd("channel", "channel", "none", "ch", getString(R.string.nava_desc_channel)),
                NavaCmd("peers", "peers", "none", "ch", getString(R.string.nava_desc_peers)),
                NavaCmd("rxlog", "rxlog", "none", "ch", getString(R.string.nava_desc_rxlog)),
                NavaCmd("afc", "afc", "none", "ch", getString(R.string.nava_desc_afc)),
                NavaCmd("reset_reason", "reset_reason", "none", "ch", getString(R.string.nava_desc_reset_reason)),
                NavaCmd("noise", "noise", "none", "ch", getString(R.string.nava_desc_noise)),
                NavaCmd("bat", "bat", "none", "ch", getString(R.string.nava_desc_bat)),
                NavaCmd("stats", "stats", "none", "ch", getString(R.string.nava_desc_stats)),
                NavaCmd("log", "log", "number", "ch", getString(R.string.nava_desc_log)),
                NavaCmd("help", "help", "textopt", "ch", getString(R.string.nava_desc_help)),
                NavaCmd("route", "route", "nodeid", "ch", getString(R.string.nava_desc_route)),
                NavaCmd("trace", "trace", "nodeid", "ch", getString(R.string.nava_desc_trace))
            )),
            NavaCat(getString(R.string.nava_cat_channels), listOf(
                NavaCmd("ch_ls", "ch_ls", "none", "dm", getString(R.string.nava_desc_ch_ls)),
                NavaCmd("ch_set", "ch_set", "text", "dm", getString(R.string.nava_desc_ch_set)),
                NavaCmd("ch_del", "ch_del", "number", "dm", getString(R.string.nava_desc_ch_del), warn = getString(R.string.nava_warn_ch_del)),
                NavaCmd("ch_url", "ch_url", "number", "dm", getString(R.string.nava_desc_ch_url)),
                NavaCmd("ch_reset", "ch_reset", "none", "dm", getString(R.string.nava_desc_ch_reset), warn = getString(R.string.nava_warn_ch_reset)),
                NavaCmd("set_cli_chan", "set_cli_chan", "number", "dm", getString(R.string.nava_desc_set_cli_chan)),
                NavaCmd("navadmin_mute", "navadmin_mute", "onoff", "dm", getString(R.string.nava_desc_navadmin_mute))
            )),
            NavaCat(getString(R.string.nava_cat_blocks), listOf(
                NavaCmd("ign ls", "ign ls", "none", "dm", getString(R.string.nava_desc_ign_ls)),
                NavaCmd("ign add", "ign add", "nodeid", "dm", getString(R.string.nava_desc_ign_add), warn = getString(R.string.nava_warn_ign_add)),
                NavaCmd("ign rm", "ign rm", "nodeid", "dm", getString(R.string.nava_desc_ign_rm)),
                NavaCmd("ign clear", "ign clear", "none", "dm", getString(R.string.nava_desc_ign_clear), warn = getString(R.string.nava_warn_ign_clear))
            )),
            NavaCat(getString(R.string.nava_cat_fav), listOf(
                NavaCmd("fav ls", "fav ls", "none", "dm", getString(R.string.nava_desc_fav_ls)),
                NavaCmd("fav add", "fav add", "nodeid", "dm", getString(R.string.nava_desc_fav_add)),
                NavaCmd("fav rm", "fav rm", "nodeid", "dm", getString(R.string.nava_desc_fav_rm)),
                NavaCmd("fav auto", "fav auto", "select", "dm", getString(R.string.nava_desc_fav_auto), listOf("on", "off"))
            )),
            NavaCat(getString(R.string.nava_cat_config), listOf(
                NavaCmd("set_name", "set_name", "text2", "dm", getString(R.string.nava_desc_set_name)),
                NavaCmd("set_role", "set_role", "select", "dm", getString(R.string.nava_desc_set_role), listOf("client", "mute", "router")),
                NavaCmd("set_mqtt", "set_mqtt", "onoff", "dm", getString(R.string.nava_desc_set_mqtt)),
                NavaCmd("ch_mqtt", "ch_mqtt", "text", "dm", getString(R.string.nava_desc_ch_mqtt)),
                NavaCmd("set_ok_to_mqtt", "set_ok_to_mqtt", "onoff", "dm", getString(R.string.nava_desc_set_ok_to_mqtt)),
                NavaCmd("set_pos", "set_pos", "text", "dm", getString(R.string.nava_desc_set_pos)),
                NavaCmd("pos_clear", "pos_clear", "none", "dm", getString(R.string.nava_desc_pos_clear)),
                NavaCmd("set_pos_tx", "set_pos_tx", "text", "dm", getString(R.string.nava_desc_set_pos_tx)),
                NavaCmd("set_nodeinfo_tx", "set_nodeinfo_tx", "text", "dm", getString(R.string.nava_desc_set_nodeinfo_tx)),
                NavaCmd("set_telem_tx", "set_telem_tx", "text", "dm", getString(R.string.nava_desc_set_telem_tx)),
                NavaCmd("set_beacon", "set_beacon", "number", "dm", getString(R.string.nava_desc_set_beacon)),
                NavaCmd("set_pin", "set_pin", "text", "dm", getString(R.string.nava_desc_set_pin)),
                NavaCmd("set_tz", "set_tz", "text", "dm", getString(R.string.nava_desc_set_tz)),
                NavaCmd("set_hops", "set_hops", "number", "dm", getString(R.string.nava_desc_set_hops)),
                NavaCmd("set_txpower", "set_txpower", "number", "dm", getString(R.string.nava_desc_set_txpower))
            )),
            NavaCat(getString(R.string.nava_cat_maint), listOf(
                NavaCmd("mute", "mute", "text", "dm", getString(R.string.nava_desc_mute)),
                NavaCmd("db_purge", "db_purge", "none", "dm", getString(R.string.nava_desc_db_purge), warn = getString(R.string.nava_warn_db_purge)),
                NavaCmd("db_clear", "db_clear", "none", "dm", getString(R.string.nava_desc_db_clear), warn = getString(R.string.nava_warn_db_clear)),
                NavaCmd("reboot", "reboot", "none", "dm", getString(R.string.nava_desc_reboot)),
                NavaCmd("factory_reset", "factory_reset", "none", "dm", getString(R.string.nava_desc_factory_reset), warn = getString(R.string.nava_warn_factory_reset)),
                NavaCmd("full_reset", "full_reset", "none", "dm", getString(R.string.nava_desc_full_reset), warn = getString(R.string.nava_warn_full_reset)),
                NavaCmd("wipe", "wipe", "none", "dm", getString(R.string.nava_desc_wipe), warn = getString(R.string.nava_warn_wipe))
            )),
            NavaCat(getString(R.string.nava_cat_power), listOf(
                NavaCmd("set_chem", "set_chem", "select", "dm", getString(R.string.nava_desc_set_chem), listOf("lipo", "nimh", "sodium", "lifepo4"), warn = getString(R.string.nava_warn_set_chem)),
                NavaCmd("set_vbat", "set_vbat", "number", "dm", getString(R.string.nava_desc_set_vbat), warn = getString(R.string.nava_warn_set_vbat)),
                NavaCmd("set_vwake", "set_vwake", "select", "dm", getString(R.string.nava_desc_set_vwake), listOf("1", "2", "3", "4", "5"), warn = getString(R.string.nava_warn_set_vwake)),
                NavaCmd("storm", "storm", "number", "dm", getString(R.string.nava_desc_storm), warn = getString(R.string.nava_warn_storm)),
                NavaCmd("storm test1", "storm test1", "none", "dm", getString(R.string.nava_desc_storm_test1), warn = getString(R.string.nava_warn_storm_test1)),
                NavaCmd("storm test2", "storm test2", "none", "dm", getString(R.string.nava_desc_storm_test2), warn = getString(R.string.nava_warn_storm_test2)),
                NavaCmd("txoff", "txoff", "none", "dm", getString(R.string.nava_desc_txoff), warn = getString(R.string.nava_warn_txoff)),
                NavaCmd("txon", "txon", "none", "dm", getString(R.string.nava_desc_txon)),
                NavaCmd("ble", "ble", "onoff", "dm", getString(R.string.nava_desc_ble), warn = getString(R.string.nava_warn_ble)),
                NavaCmd("sleepmsg", "sleepmsg", "onoff", "dm", getString(R.string.nava_desc_sleepmsg))
            )),
            NavaCat(getString(R.string.nava_cat_tx), listOf(
                NavaCmd("msg", "msg", "text", "dm", getString(R.string.nava_desc_msg)),
                NavaCmd("pos", "pos", "none", "dm", getString(R.string.nava_desc_pos)),
                NavaCmd("nodeinfo", "nodeinfo", "none", "dm", getString(R.string.nava_desc_nodeinfo)),
                NavaCmd("sendtel", "sendtel", "none", "dm", getString(R.string.nava_desc_sendtel)),
                NavaCmd("power", "power", "none", "dm", getString(R.string.nava_desc_power)),
                NavaCmd("test_tx", "test_tx", "number", "dm", getString(R.string.nava_desc_test_tx))
            )),
            NavaCat(getString(R.string.nava_cat_util), listOf(
                NavaCmd("bell", "bell", "none", "dm", getString(R.string.nava_desc_bell)),
                NavaCmd("admin_ls", "admin_ls", "none", "dm", getString(R.string.nava_desc_admin_ls)),
                NavaCmd("keys_ls", "keys_ls", "none", "dm", getString(R.string.nava_desc_keys_ls)),
                NavaCmd("keys_clear", "keys_clear", "none", "dm", getString(R.string.nava_desc_keys_clear), warn = getString(R.string.nava_warn_keys_clear))
            ))
        )

        // Default route is a private DM; Navadmin is opt-in.
        navaRouteDm.isChecked = true

        // Draggable divider between the command controls and the conversation
        // console: the user resizes both areas to taste; the split persists.
        val navaDivider = findViewById<android.view.View>(R.id.navaDivider)
        val navaControlsScrollV = findViewById<ScrollView>(R.id.navaControlsScroll)
        val navaConversationScrollV = findViewById<ScrollView>(R.id.navaConversationScroll)
        val prefs = getSharedPreferences("meshkacho", MODE_PRIVATE)
        fun applyNavaSplit(controlsWeight: Float) {
            val c = controlsWeight.coerceIn(0.3f, 2.4f)
            (navaControlsScrollV.layoutParams as LinearLayout.LayoutParams).weight = c
            (navaConversationScrollV.layoutParams as LinearLayout.LayoutParams).weight = 3f - c
            navaPanel.requestLayout()
            prefs.edit().putFloat("nava_split", c).apply()
        }
        val saved = prefs.getFloat("nava_split", -1f)
        if (saved > 0f) applyNavaSplit(saved)
        var lastDragY = 0f
        navaDivider.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastDragY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - lastDragY
                    lastDragY = event.rawY
                    val total = (navaPanel.height / resources.displayMetrics.density).coerceAtLeast(1f)
                    val lp = navaControlsScrollV.layoutParams as LinearLayout.LayoutParams
                    applyNavaSplit(lp.weight + dy / total * 3f)
                    true
                }
                else -> true
            }
        }

        navaCategorySpinner.adapter = ArrayAdapter(
            this, R.layout.nava_spinner_item,
            navaCategories.map { it.label }
        ).apply { setDropDownViewResource(R.layout.nava_spinner_item) }
        navaCategorySpinner.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.nava_category), getString(R.string.help_nava_category))
            true
        }
        navaCommandSpinner.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.nava_command), getString(R.string.help_nava_command))
            true
        }
        navaOptionSpinner.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.nava_option), getString(R.string.help_nava_option))
            true
        }
        navaCategorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                refreshNavaCommands()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        navaCommandSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (navaRevertingCommand) return
                val cmd = currentNavaCmd()
                if (currentNavaRoute() == "ch" && cmd.mode == "dm") {
                    showNavaNotAllowedOnChannel(cmd)
                    revertNavaSelection()
                    return
                }
                navaLastValidCommandPos = pos
                updateNavaArgUi()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        navaSendButton.setOnClickListener { sendNavaCommand() }
        navaRouteToggle.addOnButtonCheckedListener { _, _, _ ->
            navaCmdAdapter?.notifyDataSetChanged()
            if (currentNavaRoute() == "ch") {
                val cmd = currentNavaCmd()
                if (cmd.mode == "dm") {
                    revertNavaSelection()
                }
            }
            updateNavaPreview()
        }
        navaHelpButton.setOnClickListener {
            val cmd = currentNavaCmd()
            if (cmd.cmd.isEmpty()) return@setOnClickListener
            // New firmware (2026-08-12): any command answers its help+state when
            // queried with " ?". Ask the node live when a target/connection exists,
            // otherwise fall back to the bundled local description.
            val target = navaTargetId()
            val route = currentNavaRoute()
            if (isReady() && (route != "dm" || target != -1)) {
                val error = navaValidationError(cmd, route, target)
                if (error != null) {
                    showNavaError(error)
                } else {
                    doSendNava(cmd, "/nava ${cmd.cmd} ?", target, route)
                }
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("/nava " + cmd.cmd)
                    .setMessage(cmd.desc)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
        navaArgInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updateNavaPreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        navaOptionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) = updateNavaPreview()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        refreshNavaCommands()
    }

    private fun showNavaNotAllowedOnChannel(cmd: NavaCmd) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nava_invalid_route_title)
            .setMessage(getString(R.string.nava_cmd_not_allowed_route, cmd.cmd))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** Moves the command selection back to a command the current route allows. */
    private fun revertNavaSelection() {
        val cat = navaCategories.getOrNull(navaCategorySpinner.selectedItemPosition) ?: return
        val allowed = if (currentNavaRoute() == "ch") cat.cmds.indexOfFirst { it.mode != "dm" } else 0
        val target = if (allowed >= 0) allowed else 0
        navaLastValidCommandPos = target
        navaRevertingCommand = true
        navaCommandSpinner.setSelection(target)
        navaRevertingCommand = false
        updateNavaArgUi()
    }

    private fun updateNavaPreview() {
        val cmd = currentNavaCmd()
        if (cmd.cmd.isEmpty()) {
            navaPreviewText.text = getString(R.string.nava_preview_hint)
            navaPreviewText.setTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
            return
        }
        val route = currentNavaRoute()
        val invalid = route == "ch" && cmd.mode == "dm"
        navaPreviewText.setTextColor(
            if (invalid || cmd.warn.isNotEmpty())
                getColorAttr(com.google.android.material.R.attr.colorError)
            else
                getColorAttr(com.google.android.material.R.attr.colorPrimary)
        )
        val sb = StringBuilder("/nava")
        if (route == "ch" && navaTargetId() != -1) {
            sb.append(" !").append(Integer.toHexString(navaTargetNode))
        }
        sb.append(" ").append(cmd.cmd)
        when (cmd.argType) {
            "text", "text2", "textopt", "number", "nodeid" -> {
                val v = navaArgInput.text.toString().trim()
                if (v.isNotEmpty()) sb.append(" ").append(v)
            }
            "select", "onoff" -> {
                val opt = navaOptionSpinner.selectedItem?.toString() ?: ""
                if (opt.isNotEmpty() && opt != getString(R.string.nava_query_blank)) sb.append(" ").append(opt)
            }
        }
        navaPreviewText.text = sb.toString()
    }

    private fun currentNavaCmd(): NavaCmd {
        val cat = navaCategories.getOrNull(navaCategorySpinner.selectedItemPosition) ?: return NavaCmd("", "", "none", "ch", "")
        return cat.cmds.getOrNull(navaCommandSpinner.selectedItemPosition) ?: NavaCmd("", "", "none", "ch", "")
    }

    private fun refreshNavaCommands() {
        val cat = navaCategories.getOrNull(navaCategorySpinner.selectedItemPosition) ?: return
        navaCmdAdapter = NavaCommandAdapter(this, cat.cmds)
        navaCommandSpinner.adapter = navaCmdAdapter
        // Land on a command allowed by the current route (avoids an immediate
        // "not allowed" popup when opening a category on the Navadmin route).
        val preferred = if (currentNavaRoute() == "ch")
            cat.cmds.indexOfFirst { it.mode != "dm" }.let { if (it >= 0) it else 0 }
        else
            0
        navaLastValidCommandPos = preferred
        navaRevertingCommand = true
        navaCommandSpinner.setSelection(preferred)
        navaRevertingCommand = false
        updateNavaArgUi()
    }

    /**
     * Colors commands that cannot go through the Navadmin channel in red when
     * that route is active, so the user can tell at a glance what is allowed.
     */
    private inner class NavaCommandAdapter(
        ctx: android.content.Context,
        private val list: List<NavaCmd>
    ) : ArrayAdapter<NavaCmd>(ctx, R.layout.nava_spinner_item, list) {

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val v = super.getView(position, convertView, parent)
            (v as? TextView)?.text = label(position)
            return style(v, position)
        }

        override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val v = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.nava_spinner_dropdown, parent, false)
            val disallowed = currentNavaRoute() == "ch" && list[position].mode == "dm"
            val color = if (disallowed) getColorAttr(com.google.android.material.R.attr.colorError)
            else getColorAttr(com.google.android.material.R.attr.colorOnSurface)
            v.findViewById<TextView>(R.id.navaDropTitle).apply {
                text = label(position)
                setTextColor(color)
                alpha = if (disallowed) 0.6f else 1f
            }
            v.findViewById<TextView>(R.id.navaDropSub).apply {
                text = list[position].desc
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            return v
        }

        private fun label(position: Int): String {
            val c = list[position]
            return if (c.warn.isNotEmpty()) c.label + " \u26A0" else c.label
        }

        private fun style(v: android.view.View, position: Int): android.view.View {
            val tv = v as? TextView ?: return v
            val disallowed = currentNavaRoute() == "ch" && list[position].mode == "dm"
            tv.setTextColor(
                if (disallowed) getColorAttr(com.google.android.material.R.attr.colorError)
                else getColorAttr(com.google.android.material.R.attr.colorOnSurface)
            )
            tv.alpha = if (disallowed) 0.6f else 1f
            return v
        }
    }

    private fun updateNavaArgUi() {
        val cmd = currentNavaCmd()
        navaDescText.text = cmd.desc
        // Both routes are always selectable; the command list shows in red which
        // commands cannot go through the Navadmin channel.
        navaRouteCh.isEnabled = true
        navaRouteDm.isEnabled = true
        if (!navaRouteDm.isChecked && !navaRouteCh.isChecked) {
            navaRouteDm.isChecked = true
        }
        updateNavaPreview()
        val needsText = cmd.argType in listOf("text", "text2", "textopt", "number", "nodeid")
        navaArgLayout.visibility = if (needsText) android.view.View.VISIBLE else android.view.View.GONE
        navaArgInput.hint = when (cmd.cmd) {
            "ch_set" -> "2 Privada AQ=="
            "ch_del" -> "Slot [2-7]"
            "ch_url" -> "Slot [0-7]"
            "set_cli_chan" -> "Slot [1-7]"
            "ch_mqtt" -> "2 up (up/down/both/off)"
            "set_pos" -> "42.8168 -1.6432 450"
            "set_pos_tx" -> "on / off / 1-10080 min"
            "set_nodeinfo_tx" -> "on / off / 1-10080 min"
            "set_telem_tx" -> "on / off / 1-1440 min"
            "set_beacon" -> "1-1440 min"
            "set_pin" -> "PIN (6 dig)"
            "mute" -> "1-1440 min / off"
            "test_tx" -> "5-30 s"
            "log" -> "1-15 lines"
            else -> when (cmd.argType) {
                "text2" -> getString(R.string.nava_arg_hint) + " (\"Largo\" \"Corto\")"
                "number" -> getString(R.string.nava_arg_hint)
                "nodeid" -> getString(R.string.nava_arg_hint_nodeid)
                else -> getString(R.string.nava_arg_hint)
            }
        }
        if (cmd.cmd == "ch_set") {
            navaArgLayout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM
            navaArgLayout.setEndIconDrawable(android.R.drawable.ic_menu_edit)
            navaArgLayout.setEndIconContentDescription(getString(R.string.nava_keygen_title))
            navaArgLayout.setEndIconOnClickListener { showNavaKeygenDialog() }
        } else {
            navaArgLayout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_NONE
            navaArgLayout.setEndIconOnClickListener(null)
        }
        if (cmd.argType == "select" || cmd.argType == "onoff") {
            val options = if (cmd.argType == "onoff") listOf("on", "off") else cmd.options
            // First option = empty query (new firmware returns state when no arg).
            navaOptionSpinner.adapter = ArrayAdapter(
                this, R.layout.nava_spinner_item,
                listOf(getString(R.string.nava_query_blank)) + options
            ).apply { setDropDownViewResource(R.layout.nava_spinner_item) }
            navaOptionSpinner.visibility = android.view.View.VISIBLE
        } else {
            navaOptionSpinner.visibility = android.view.View.GONE
        }
    }

    private fun showNavaKeygenDialog() {
        val options = arrayOf(
            getString(R.string.nava_keygen_default) + " (AQ==)",
            getString(R.string.nava_keygen_aes128) + " (16B)",
            getString(R.string.nava_keygen_aes256) + " (32B)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nava_keygen_title)
            .setItems(options) { _, which ->
                val psk = when (which) {
                    1 -> {
                        val b = ByteArray(16)
                        java.security.SecureRandom().nextBytes(b)
                        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
                    }
                    2 -> {
                        val b = ByteArray(32)
                        java.security.SecureRandom().nextBytes(b)
                        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
                    }
                    else -> "AQ=="
                }
                val current = navaArgInput.text?.toString()?.trim() ?: ""
                val parts = current.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val slot = if (parts.isNotEmpty() && parts[0].toIntOrNull() in 2..7) parts[0] else "2"
                val name = if (parts.size >= 2) parts[1] else "Privado"
                navaArgInput.setText("$slot $name $psk")
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun navaTargetId(): Int {
        navaTargetNode = navaTargetInput.text?.toString()?.trim()?.let {
            try { parseNodeId(it) } catch (e: Exception) { -1 }
        } ?: -1
        return navaTargetNode
    }

    /** Current chosen route: "ch" (Navadmin channel) or "dm" (private PKI DM). */
    private fun currentNavaRoute(): String =
        if (navaRouteToggle.checkedButtonId == R.id.navaRouteDm) "dm" else "ch"

    /**
     * Returns the reason why the current /nava send cannot proceed, or null if
     * everything is ready. Covers: no command chosen, route/command mismatch,
     * missing target for DM and missing/invalid arguments.
     */
    private fun navaValidationError(cmd: NavaCmd, route: String, target: Int): String? {
        if (cmd.cmd.isEmpty()) return getString(R.string.nava_err_no_command)
        if (route == "ch" && cmd.mode == "dm") {
            return getString(R.string.nava_invalid_route_body, cmd.cmd)
        }
        if (route == "dm" && target == -1) return getString(R.string.nava_no_target)
        when (cmd.argType) {
            "text", "text2" -> {
                // New firmware: empty argument = query state (no ERR). Only "msg"
                // always needs its text (it is broadcast as-is).
                if (cmd.cmd == "msg" && navaArgInput.text.toString().trim().isEmpty()) {
                    return getString(R.string.nava_err_need_arg)
                }
                if (cmd.argType == "text2") {
                    val parts = navaArgInput.text.toString().trim().split(Regex("\\s+"))
                    val filled = navaArgInput.text.toString().trim().isNotEmpty()
                    if (filled && parts.size < 2) return getString(R.string.nava_err_need_two_args)
                }
            }
            "number" -> {
                val v = navaArgInput.text.toString().trim()
                if (v.isEmpty()) return null // query state
                val n = v.toIntOrNull() ?: return getString(R.string.nava_err_invalid_number)
                val range = when (cmd.cmd) {
                    "set_vbat" -> 2400 to 3600
                    "set_hops" -> 1 to 7
                    "set_txpower" -> 0 to 22
                    "storm" -> 1 to 720
                    "ch_del" -> 2 to 7
                    "ch_url" -> 0 to 7
                    "set_cli_chan" -> 1 to 7
                    "set_beacon" -> 1 to 1440
                    "test_tx" -> 5 to 30
                    "log" -> 1 to 15
                    else -> null
                }
                if (range != null && (n < range.first || n > range.second)) {
                    return getString(R.string.nava_err_range, range.first, range.second)
                }
            }
            "nodeid" -> {
                // empty = query state/help
            }
            "select", "onoff" -> {
                // empty = query state; handled by the "— Consultar —" option
            }
        }
        return null
    }

    private fun showNavaError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nava_err_title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun sendNavaCommand() {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            showNavaError(getString(R.string.log_not_connected))
            return
        }
        val cmd = currentNavaCmd()
        val target = navaTargetId()
        val route = currentNavaRoute()

        val error = navaValidationError(cmd, route, target)
        if (error != null) {
            appendLog("NAVA error: $error")
            showNavaError(error)
            return
        }

        val text = buildString {
            append("/nava")
            if (route == "ch" && target != -1) {
                append(" !").append(Integer.toHexString(target))
            }
            append(" ").append(cmd.cmd)
            when (cmd.argType) {
                "text" -> { val v = navaArgInput.text.toString().trim(); if (v.isNotEmpty()) append(" \"").append(v).append("\"") }
                "textopt" -> { val v = navaArgInput.text.toString().trim(); if (v.isNotEmpty()) append(" ").append(v) }
                "text2" -> {
                    val parts = navaArgInput.text.toString().trim().split(Regex("\\s+"))
                    if (parts.size >= 2) { append(" \"").append(parts[0]).append("\" \"").append(parts[1]).append("\"") }
                }
                "number" -> { val v = navaArgInput.text.toString().trim(); if (v.isNotEmpty()) append(" ").append(v) }
                "nodeid" -> { val v = navaArgInput.text.toString().trim(); if (v.isNotEmpty()) append(" ").append(v) }
                "select", "onoff" -> {
                    val opt = navaOptionSpinner.selectedItem?.toString() ?: ""
                    if (opt.isNotEmpty() && opt != getString(R.string.nava_query_blank)) append(" ").append(opt)
                }
            }
        }

        if (cmd.warn.isNotEmpty()) {
            confirmDangerousNava(cmd) { doSendNava(cmd, text, target, route) }
            return
        }
        doSendNava(cmd, text, target, route)
    }

    private fun doSendNava(cmd: NavaCmd, text: String, target: Int, route: String) {
        if (route == "ch" && navadminChannelIndex < 0) {
            offerCreateNavadmin()
            return
        }
        val packet = if (route == "ch") {
            MeshPacketBuilder.buildTextPacket(text, -1, navadminChannelIndex)
        } else {
            if (target == -1) {
                appendLog(getString(R.string.nava_no_target))
                Toast.makeText(this, getString(R.string.nava_no_target), Toast.LENGTH_SHORT).show()
                return
            }
            MeshPacketBuilder.buildTextPacket(text, target, 0, pkiEncrypted = true)
        }
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            appendLog("NAVA >> $text (${if (route == "ch") "ch1" else "DM"})")
            addNavaMsg(localNodeNum ?: 0, text, sent = true, route = route)
            // A new command starts a fresh response: reset the fragment window.
            navaFragmentKey = ""
            navaFragmentTime = 0L
        } else {
            appendLog(getString(R.string.log_cmd_write_failed))
        }
    }

    /**
     * Shows a safety popup before sending destructive/risky NavaTastic commands.
     * The Send button stays disabled until the user types CONFIRMAR.
     */
    private fun confirmMasterConversion() {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            Toast.makeText(this, getString(R.string.log_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingAdminKey != null || pendingMasterKey != null || awaitingAdminKeyRead != null || awaitingAdminKeyAck != null) {
            Toast.makeText(this, getString(R.string.admin_keys_busy), Toast.LENGTH_SHORT).show()
            return
        }
        val keyInput = TextInputEditText(this).apply {
            hint = getString(R.string.master_convert_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val confirmInput = TextInputEditText(this).apply {
            hint = getString(R.string.nava_confirm_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(0))
            addView(TextInputLayout(this@MainActivity).apply {
                hint = getString(R.string.master_convert_key_hint)
                addView(keyInput)
                setPadding(0, 0, 0, dp(12))
            })
            addView(TextInputLayout(this@MainActivity).apply {
                hint = getString(R.string.nava_confirm_hint)
                addView(confirmInput)
            })
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.master_convert_title)
            .setMessage(getString(R.string.master_convert_body))
            .setView(column)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.master_convert_ok, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            setOnClickListener {
                val key = keyInput.text.toString().trim()
                val keyBytes = try {
                    android.util.Base64.decode(key, android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    null
                }
                if (keyBytes != null && keyBytes.size == 32 &&
                    confirmInput.text.toString().trim().equals(getString(R.string.nava_confirm_word), ignoreCase = true)
                ) {
                    dialog.dismiss()
                    startMasterConversion(key)
                }
            }
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val key = keyInput.text.toString().trim()
                val keyOk = try {
                    android.util.Base64.decode(key, android.util.Base64.NO_WRAP).size == 32
                } catch (e: Exception) {
                    false
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled =
                    keyOk && confirmInput.text.toString().trim()
                        .equals(getString(R.string.nava_confirm_word), ignoreCase = true)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        }
        keyInput.addTextChangedListener(watcher)
        confirmInput.addTextChangedListener(watcher)
    }

    private fun startMasterConversion(masterKeyB64: String) {
        pendingMasterKey = masterKeyB64
        val adminTarget = localNodeNum ?: -1
        val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val packet = MeshPacketBuilder.buildGetConfigRequestPacket(AdminMessage.ConfigType.SECURITY_CONFIG, adminTarget, packetId)
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            awaitingAdminKeyRead = packetId
            appendLog(getString(R.string.admin_keys_reading))
            configHandler.removeCallbacksAndMessages(null)
            configHandler.postDelayed({ onAdminKeyTimeout() }, CONFIG_ACK_TIMEOUT_MS)
        } else {
            pendingMasterKey = null
            appendLog(getString(R.string.master_convert_failed))
        }
    }

    private fun confirmDangerousNava(cmd: NavaCmd, onConfirmed: () -> Unit) {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.nava_confirm_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val wrap = TextInputLayout(this).apply {
            hint = getString(R.string.nava_confirm_hint)
            addView(input)
            setPadding(dp(24), dp(12), dp(24), dp(0))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nava_danger_title)
            .setMessage(cmd.warn + "\n\n" + getString(R.string.nava_danger_body))
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.nava_danger_send, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            setOnClickListener {
                if (input.text.toString().trim().equals(getString(R.string.nava_confirm_word), ignoreCase = true)) {
                    dialog.dismiss()
                    onConfirmed()
                }
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled =
                    s?.toString()?.trim().equals(getString(R.string.nava_confirm_word), ignoreCase = true)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun addNavaMsg(from: Int, text: String, sent: Boolean, route: String = "ch") {
        synchronized(navaMessages) {
            navaMessages.add(NavaMsg(from, text, SimpleDateFormat("HH:mm", Locale.US).format(Date()), sent, route))
        }
        saveNavaHistory()
        if (navaPanel.visibility == android.view.View.VISIBLE) {
            refreshNava()
        }
    }

    private fun refreshNava() {
        runOnUiThread {
            navaMessagesContainer.removeAllViews()
            synchronized(navaMessages) {
                if (navaMessages.isEmpty()) {
                    navaMessagesContainer.addView(TextView(this).apply {
                        text = getString(R.string.nava_no_messages)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    })
                    return@runOnUiThread
                }
                // Newest first, so the latest activity is visible without scrolling.
                val nav = navaMessages.filter { it.route == "ch" }.reversed()
                val dm = navaMessages.filter { it.route != "ch" }.reversed()
                val sections = mutableListOf<Pair<Int, Triple<String, Int, List<NavaMsg>>>>()
                if (nav.isNotEmpty()) {
                    sections.add(
                        navaMessages.indexOfLast { it.route == "ch" } to
                            Triple(getString(R.string.nava_section_navadmin),
                                getColorAttr(com.google.android.material.R.attr.colorPrimary), nav)
                    )
                }
                if (dm.isNotEmpty()) {
                    sections.add(
                        navaMessages.indexOfLast { it.route != "ch" } to
                            Triple(getString(R.string.nava_section_dm),
                                getColorAttr(android.R.attr.colorAccent), dm)
                    )
                }
                sections.sortedByDescending { it.first }.forEach { (_, s) ->
                    renderNavaSection(s.first, s.second, s.third)
                }
            }
        }
    }

    /** Console-style section: coloured header + monospace lines per stream. */
    private fun renderNavaSection(title: String, accent: Int, msgs: List<NavaMsg>) {
        navaMessagesContainer.addView(TextView(this).apply {
            text = title
            setPadding(dp(12), dp(8), dp(12), dp(4))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(accent)
        })
        msgs.forEach { m ->
            val who = if (m.sent) getString(R.string.log_dest_local) else nodeLabel(m.from)
            val arrow = if (m.sent) "»" else "«"
            val (alertEmoji, alertColor) = if (m.sent) "" to 0 else navaAlertStyle(m.text)
            val line = "[${m.time}] $arrow $who: $alertEmoji${m.text}"
            navaMessagesContainer.addView(TextView(this).apply {
                text = line
                setPadding(dp(14), dp(2), dp(12), dp(2))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(
                    if (m.sent) accent
                    else if (alertColor != 0) alertColor
                    else getColorAttr(com.google.android.material.R.attr.colorOnSurface)
                )
            })
        }
        navaMessagesContainer.addView(TextView(this).apply { setPadding(0, dp(4), 0, 0) })
    }

    /** Sleep/wake announcements from the node ([Sueño]/[Vivo]/[Listo]/[Boot]): emoji + highlight color. */
    private fun navaAlertStyle(text: String): Pair<String, Int> = when {
        text.startsWith("[Sue\u00f1o]") -> "\uD83D\uDCA4 " to 0xFF42A5F5.toInt()
        text.startsWith("[Vivo]") -> "\u2600\uFE0F " to 0xFFFFB300.toInt()
        text.startsWith("[Listo]") -> "\u2705 " to 0xFF4CAF50.toInt()
        text.startsWith("[Boot]") -> "\uD83D\uDE80 " to 0xFF26C6DA.toInt()
        else -> "" to 0
    }

    private fun saveNavaHistory() {
        try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "navatastic")
            dir.mkdirs()
            val file = File(dir, "history.json")
            val sb = StringBuilder("[")
            synchronized(navaMessages) {
                for (i in navaMessages.indices) {
                    val m = navaMessages[i]
                    if (i > 0) sb.append(",")
                    sb.append("{\"from\":").append(m.from).append(",\"sent\":").append(m.sent)
                        .append(",\"time\":\"").append(m.time).append("\",\"route\":\"").append(m.route)
                        .append("\",\"text\":\"").append(escapeJson(m.text)).append("\"}")
                }
            }
            sb.append("]")
            saveJsonAtomic(file, sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save nava history failed", e)
        }
    }

    /** Writes a JSON file via tmp + rename so a crash mid-write never corrupts it. */
    private fun saveJsonAtomic(file: File, json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    /**
     * Own persistent node "cache": the node's NodeDB only holds 80 entries, so
     * the app accumulates every node it has ever seen across sessions and
     * connected nodes. Live NodeDB entries always win; cache-only entries keep
     * the name/ID known for pickers and labels.
     */
    @Volatile private var lastNodeCacheSave = 0L

    private fun loadNodeCache() {
        try {
            val file = File(getExternalFilesDir(null) ?: filesDir, "nodes/cache.json")
            if (!file.exists()) return
            val arr = org.json.JSONArray(file.readText())
            synchronized(nodeEntries) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val num = o.getInt("num")
                    if (nodeEntries.containsKey(num)) continue
                    nodeEntries[num] = NodeEntry(
                        num = num,
                        name = o.optString("name"),
                        isFavorite = o.optBoolean("isFavorite", false),
                        battery = o.optInt("battery", -1),
                        voltage = o.optDouble("voltage", 0.0).toFloat(),
                        snr = o.optDouble("snr", 0.0).toFloat(),
                        lastHeard = o.optLong("lastHeard", 0L),
                        hops = o.optInt("hops", -1),
                        cached = true,
                        pubKey = if (o.has("pubKey")) o.optString("pubKey") else null
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load node cache failed", e)
        }
    }

    private fun maybeSaveNodeCache() {
        val now = System.currentTimeMillis()
        if (now - lastNodeCacheSave < 5000) return
        lastNodeCacheSave = now
        try {
            val file = File(getExternalFilesDir(null) ?: filesDir, "nodes/cache.json")
            file.parentFile?.mkdirs()
            val sb = StringBuilder("[")
            synchronized(nodeEntries) {
                var first = true
                for (e in nodeEntries.values) {
                    if (!first) sb.append(",")
                    first = false
                    sb.append("{\"num\":").append(e.num)
                        .append(",\"name\":\"").append(escapeJson(e.name)).append("\"")
                        .append(",\"isFavorite\":").append(e.isFavorite)
                        .append(",\"battery\":").append(e.battery)
                        .append(",\"voltage\":").append(e.voltage)
                        .append(",\"snr\":").append(e.snr)
                        .append(",\"lastHeard\":").append(e.lastHeard)
                        .append(",\"hops\":").append(e.hops)
                        .append(if (e.pubKey != null) ",\"pubKey\":\"" + escapeJson(e.pubKey) + "\"" else "")
                        .append("}")
                }
            }
            sb.append("]")
            saveJsonAtomic(file, sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save node cache failed", e)
        }
    }

    private fun loadNavaHistory() {
        try {
            val file = File(getExternalFilesDir(null) ?: filesDir, "navatastic/history.json")
            if (!file.exists()) return
            val arr = org.json.JSONArray(file.readText())
            synchronized(navaMessages) {
                navaMessages.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    navaMessages.add(
                        NavaMsg(
                            o.getInt("from"),
                            o.getString("text"),
                            o.getString("time"),
                            o.getBoolean("sent"),
                            o.optString("route", "ch")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Keep the corrupt file for manual recovery instead of silently losing it.
            try {
                val file = File(getExternalFilesDir(null) ?: filesDir, "navatastic/history.json")
                if (file.exists()) file.renameTo(File(file.parentFile, "history.json.corrupt"))
            } catch (ignored: Exception) {
            }
            Log.w(TAG, "load nava history failed (backed up as .corrupt)", e)
        }
    }

    /**
     * Captures Navadmin-channel (index 1) text messages and PKI DMs from the
     * selected NavaTastic target into the conversation.
     */
    private fun maybeCaptureNavaMessage(packet: org.meshtastic.proto.MeshProtos.MeshPacket, text: String) {
        val onNavadmin = navadminChannelIndex >= 0 && packet.channel == navadminChannelIndex
        val fromTarget = navaTargetNode != -1 && packet.from == navaTargetNode && packet.to != -1
        if (packet.from == navaTargetNode || onNavadmin) {
            appendLog("NAVA rx: navadmin=$onNavadmin from=${packet.from} to=${packet.to} ch=${packet.channel} => ${text.take(48)}")
        }
        if (onNavadmin || fromTarget) {
            val route = if (onNavadmin) "ch" else "dm"
            val now = System.currentTimeMillis()
            val key = "${packet.from}|$route"
            synchronized(navaMessages) {
                val last = navaMessages.lastOrNull()
                if (last != null && !last.sent && last.from == packet.from && last.route == route &&
                    navaFragmentKey == key && now - navaFragmentTime <= NAVA_FRAGMENT_WINDOW_MS
                ) {
                    // Same node/route and within the fragment window: concatenate.
                    navaMessages[navaMessages.size - 1] = last.copy(text = last.text + text)
                } else {
                    navaMessages.add(NavaMsg(packet.from, text, SimpleDateFormat("HH:mm", Locale.US).format(Date()), sent = false, route = route))
                }
                navaFragmentKey = key
                navaFragmentTime = now
            }
            saveNavaHistory()
            if (navaPanel.visibility == android.view.View.VISIBLE) {
                refreshNava()
            }
        }
    }

    /**
     * If the Navadmin channel was not detected on the node, offers to create it
     * (first free slot, name "Navadmin", PSK AQ==) via AdminMessage.set_channel.
     * Slot 0 is the primary channel (untouchable); slots 1-7 are secondary.
     */
    private fun offerCreateNavadmin() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nava_navadmin_missing_title)
            .setMessage(R.string.nava_navadmin_missing_body)
            .setPositiveButton(R.string.nava_navadmin_create) { _, _ -> createNavadminChannel() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** First free secondary channel slot (1-7), or -1 if all 8 slots are used. */
    private fun firstFreeChannelSlot(): Int {
        val occupied = synchronized(channelNames) {
            channelNames.mapIndexedNotNull { i, n -> if (n.isNotEmpty()) i else null }.toSet()
        }
        return firstFreeChannelSlotFor(occupied)
    }

    /** Occupied slots (0-7) mapped to their channel name, for the limit dialog. */
    private fun occupiedChannelNames(): List<Pair<Int, String>> =
        synchronized(channelNames) {
            channelNames.mapIndexedNotNull { i, n -> if (n.isNotEmpty()) i to n else null }
        }

    private fun buildNavadminChannel(index: Int): org.meshtastic.proto.ChannelProtos.Channel =
        org.meshtastic.proto.ChannelProtos.Channel.newBuilder()
            .setIndex(index)
            .setRole(org.meshtastic.proto.ChannelProtos.Channel.Role.SECONDARY)
            .setSettings(
                org.meshtastic.proto.ChannelProtos.ChannelSettings.newBuilder()
                    .setName("Navadmin")
                    .setPsk(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x01)))
                    .build()
            )
            .build()

    private fun createNavadminChannel() {
        if (warnIfAdminOverBle()) return
        val slot = firstFreeChannelSlot()
        if (slot == -1) {
            // All 8 channel slots are used: offer to free one (disable it) so the
            // Navadmin channel can be added, or reuse (rename) an existing channel.
            showNavadminFullDialog()
            return
        }
        sendNavadminChannel(buildNavadminChannel(slot))
    }

    private fun sendNavadminChannel(channel: org.meshtastic.proto.ChannelProtos.Channel) {
        val packet = MeshPacketBuilder.buildSetChannelPacket(channel, parseTargetNodeId(targetNodeInput.text.toString().trim()))
        val bytes = sendToRadio(packet)
        appendLog(if (bytes != null) getString(R.string.nava_navadmin_create_sent, channel.index) else getString(R.string.log_cmd_write_failed))
    }

    /** All 8 slots used: let the user disable one (frees the slot) or reuse one. */
    private fun showNavadminFullDialog() {
        val occupied = occupiedChannelNames().filter { it.first != 0 }
        if (occupied.isEmpty()) {
            appendLog(getString(R.string.nava_navadmin_slot_primary_only))
            return
        }
        val labels = occupied.map { (i, n) -> "$i · $n" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nava_navadmin_full_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val (slot, _) = occupied[which]
                showNavadminFreeChoice(slot)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Chosen slot: disable it first then create Navadmin, or reuse it directly. */
    private fun showNavadminFreeChoice(slot: Int) {
        val name = synchronized(channelNames) { channelNames.getOrNull(slot) ?: "" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nava_navadmin_reuse_title)
            .setMessage(getString(R.string.nava_navadmin_reuse_body, slot, name))
            .setPositiveButton(R.string.nava_navadmin_reuse) { _, _ ->
                sendNavadminChannel(buildNavadminChannel(slot))
            }
            .setNegativeButton(R.string.nava_navadmin_disable_first) { _, _ ->
                val disabled = org.meshtastic.proto.ChannelProtos.Channel.newBuilder()
                    .setIndex(slot)
                    .setRole(org.meshtastic.proto.ChannelProtos.Channel.Role.DISABLED)
                    .setSettings(
                        org.meshtastic.proto.ChannelProtos.ChannelSettings.newBuilder()
                            .setName("")
                            .build()
                    )
                    .build()
                val packet = MeshPacketBuilder.buildSetChannelPacket(disabled, parseTargetNodeId(targetNodeInput.text.toString().trim()))
                val bytes = sendToRadio(packet)
                appendLog(if (bytes != null) getString(R.string.nava_navadmin_disable_sent, slot) else getString(R.string.log_cmd_write_failed))
                if (bytes != null) {
                    // Re-run the creation after a short pause so the node applies
                    // the disable before we write into the freshly freed slot.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        sendNavadminChannel(buildNavadminChannel(slot))
                    }, 1500)
                }
            }
            .show()
    }

    /**
     * Persists the chat history to a file so it survives app restarts.
     */
    private fun saveChatHistory() {
        try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "chat")
            dir.mkdirs()
            val file = File(dir, "history.json")
            val sb = StringBuilder("[")
            synchronized(chatMessages) {
                // Keep a bounded history per channel so the file stays small.
                val perChannel = linkedMapOf<Int, MutableList<ChatMessage>>()
                chatMessages.forEach { m -> perChannel.getOrPut(m.channel) { mutableListOf() }.add(m) }
                val trimmed = mutableListOf<ChatMessage>()
                perChannel.forEach { (_, list) ->
                    val from = (list.size - CHAT_HISTORY_PER_CHANNEL).coerceAtLeast(0)
                    trimmed.addAll(list.subList(from, list.size))
                }
                for (i in trimmed.indices) {
                    val m = trimmed[i]
                    if (i > 0) sb.append(",")
                    sb.append("{\"from\":").append(m.from)
                        .append(",\"channel\":").append(m.channel)
                        .append(",\"time\":\"").append(m.time)
                        .append("\",\"text\":\"").append(escapeJson(m.text)).append("\"")
                    if (m.packetId != -1) sb.append(",\"packetId\":").append(m.packetId)
                    if (m.status.isNotEmpty()) sb.append(",\"status\":\"").append(m.status).append("\"")
                    if (m.routingError.isNotEmpty()) sb.append(",\"routingError\":\"").append(m.routingError).append("\"")
                    if (m.relays > 0) sb.append(",\"relays\":").append(m.relays)
                    sb.append("}")
                }
            }
            sb.append("]")
            saveJsonAtomic(file, sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save chat history failed", e)
        }
    }

    private fun trimChatHistoryMemory() {
        // Bounded in-memory history per channel (mirrors the saved file policy).
        val byChannel = linkedMapOf<Int, MutableList<ChatMessage>>()
        chatMessages.forEach { m -> byChannel.getOrPut(m.channel) { mutableListOf() }.add(m) }
        chatMessages.clear()
        byChannel.forEach { (_, list) ->
            val from = (list.size - CHAT_HISTORY_PER_CHANNEL).coerceAtLeast(0)
            chatMessages.addAll(list.subList(from, list.size))
        }
    }

    private fun loadChatHistory() {
        try {
            val file = File(getExternalFilesDir(null) ?: filesDir, "chat/history.json")
            if (!file.exists()) return
            val arr = org.json.JSONArray(file.readText())
            synchronized(chatMessages) {
                chatMessages.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    chatMessages.add(
                        ChatMessage(
                            from = o.getInt("from"),
                            text = o.getString("text"),
                            channel = o.getInt("channel"),
                            time = o.getString("time"),
                            packetId = if (o.has("packetId")) o.getInt("packetId") else -1,
                            status = if (o.has("status")) o.getString("status") else "",
                            routingError = if (o.has("routingError")) o.getString("routingError") else "",
                            relays = if (o.has("relays")) o.getInt("relays") else 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            try {
                val file = File(getExternalFilesDir(null) ?: filesDir, "chat/history.json")
                if (file.exists()) file.renameTo(File(file.parentFile, "history.json.corrupt"))
            } catch (ignored: Exception) {
            }
            Log.w(TAG, "load chat history failed (backed up as .corrupt)", e)
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /**
     * Shows an error popup when a settings operation is attempted over Bluetooth
     * (admin read/write needs the PKC session key, not implemented).
     */
    private fun warnIfAdminOverBle(): Boolean {
        if (bleTransportActive) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ble_admin_warn_title)
                .setMessage(R.string.ble_admin_warn_body)
                .setPositiveButton(R.string.close, null)
                .show()
            return true
        }
        return false
    }

    private fun setupChat() {
        loadChatHistory()
        chatChannelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (chatSpinnerUpdating) return
                val indices = channelNames.indices
                    .filter { channelNames.getOrNull(it)?.isNotEmpty() == true || it == 0 }
                    .toList()
                val ch = indices.getOrNull(pos) ?: 0
                if (ch != currentChatChannel) {
                    currentChatChannel = ch
                    val name = channelNames.getOrNull(ch)?.takeIf { it.isNotEmpty() } ?: "Canal $ch"
                    chatChannelLabel.text = name
                    appendLog(getString(R.string.chat_channel_selected, name))
                    refreshChat()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        chatChannelSpinner.setOnLongClickListener {
            showHelpDialogFor(getString(R.string.chat_channel_hint), getString(R.string.help_chat_channel))
            true
        }
        chatSendButton.setOnClickListener {
            val text = chatReplyInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            chatReplyInput.text?.clear()
            sendChatReply(text)
        }
        chatPauseButton.setOnClickListener {
            chatPaused = !chatPaused
            chatPauseButton.setText(if (chatPaused) R.string.chat_resume else R.string.chat_pause)
            appendLog(if (chatPaused) "Chat paused" else "Chat resumed")
        }
        // Auto-scroll: manual scrolling pauses it; 10 s without touching resumes it.
        chatScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                chatAutoScrollPaused = true
                chatScrollHandler.removeCallbacksAndMessages(null)
                chatScrollHandler.postDelayed({
                    chatAutoScrollPaused = false
                    chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }, CHAT_AUTOSCROLL_RESUME_MS)
            }
            false
        }
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun sendChatReply(text: String) {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            return
        }
        val packetId = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)
        val packet = MeshPacketBuilder.buildTextPacket(text, -1, currentChatChannel, packetId = packetId, wantAck = true)
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            appendLog(getString(R.string.log_text_sent, bytes.size, getString(R.string.chat_channel_default)))
            synchronized(chatMessages) {
                chatMessages.add(
                    ChatMessage(
                        from = localNodeNum ?: 0,
                        text = text,
                        channel = currentChatChannel,
                        time = SimpleDateFormat("HH:mm", Locale.US).format(Date()),
                        packetId = packetId,
                        status = CHAT_STATUS_ENROUTE,
                        routingError = "",
                        relays = 0
                    )
                )
                trimChatHistoryMemory()
            }
            saveChatHistory()
            refreshChat()
        } else {
            appendLog(getString(R.string.log_cmd_write_failed))
        }
    }

    /**
     * Maps a routing ACK/NAK to the chat message that requested it (by request_id
     * == the MeshPacket.id we set when sending). Ported from the official app
     * (MeshDataHandlerImpl.kt handleAckNak): error NONE from any node = DELIVERED
     * (delivered to mesh); from the destination node = RECEIVED (DMs); NAK = ERROR.
     * The 3 transmit retries are done by the FIRMWARE (radio.max_retransmit), not here.
     */
    private fun handleChatRoutingAck(decoded: org.meshtastic.proto.MeshProtos.Data) {
        val requestId = decoded.requestId
        if (requestId == 0) return
        var isAck = false
        var errorName = "NONE"
        try {
            val routing = org.meshtastic.proto.MeshProtos.Routing.parseFrom(decoded.payload)
            isAck = routing.errorReason == org.meshtastic.proto.MeshProtos.Routing.Error.NONE
            errorName = routing.errorReason.name
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse chat routing ack", e)
            return
        }
        synchronized(chatMessages) {
            val idx = chatMessages.indexOfFirst { it.packetId == requestId && it.status == CHAT_STATUS_ENROUTE }
            if (idx < 0) return
            val m = chatMessages[idx]
            val newStatus = if (isAck) CHAT_STATUS_DELIVERED else CHAT_STATUS_ERROR
            chatMessages[idx] = m.copy(
                status = newStatus,
                routingError = errorName,
                relays = m.relays + if (isAck) 1 else 0
            )
        }
        saveChatHistory()
        refreshChat()
        appendLog("CHAT ACK: req=$requestId -> $errorName")
    }

    /**
     * Status dialog for a sent message: shows delivery state + routing error /
     * relay count, with a manual "Reenviar" for retryable errors (not
     * NO_CHANNEL/TOO_LARGE, which never succeed on retry).
     */
    private fun showChatStatusDialog(msg: ChatMessage) {
        val statusLabel = when (msg.status) {
            CHAT_STATUS_ENROUTE -> getString(R.string.chat_status_enroute)
            CHAT_STATUS_DELIVERED -> getString(R.string.chat_status_delivered)
            CHAT_STATUS_RECEIVED -> getString(R.string.chat_status_received)
            CHAT_STATUS_ERROR -> getString(R.string.chat_status_error)
            else -> getString(R.string.chat_status_unknown)
        }
        val detail = buildString {
            append(getString(R.string.chat_status_label, statusLabel))
            if (msg.status == CHAT_STATUS_ERROR && msg.routingError.isNotEmpty()) {
                append("\n")
                append(getString(R.string.chat_status_error_detail, msg.routingError))
            }
            if (msg.relays > 0) {
                append("\n")
                append(getString(R.string.chat_status_relays, msg.relays))
            }
        }
        val retryable = msg.status == CHAT_STATUS_ERROR &&
            msg.routingError != "NO_CHANNEL" && msg.routingError != "TOO_LARGE"
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.chat_status_title)
            .setMessage(detail)
            .setPositiveButton(android.R.string.ok, null)
        if (retryable) {
            builder.setNeutralButton(R.string.chat_resend) { _, _ -> sendChatReply(msg.text) }
        }
        builder.show()
    }

    /**
     * Renders the Chat tab grouped by channel. Each channel is a collapsible
     * section (header row + messages), so SFNarrow/Navadmin/others can be shown
     * or hidden independently. The send-channel spinner lets the user choose
     * which channel to reply on.
     */
    private fun refreshChat() {
        // NOTE: called from BOTH the UI thread (tab switch, send, ACK) and the BLE
        // reader thread (TEXT_MESSAGE_APP / hasChannel in handleFromRadio). Every
        // view touch MUST stay inside runOnUiThread or Android throws
        // CalledFromWrongThreadException and the chat stops refreshing.
        runOnUiThread {
            val channelName = channelNames.getOrNull(currentChatChannel)?.takeIf { it.isNotEmpty() }
                ?: "Canal $currentChatChannel"
            chatChannelLabel.text = channelName

            // Rebuild the send-channel spinner (channels discovered so far).
            val knownChannels = channelNames.indices
                .filter { channelNames.getOrNull(it)?.isNotEmpty() == true || it == 0 }
                .toList()
            if (knownChannels.isNotEmpty()) {
                val items = knownChannels.map { channelNames.getOrNull(it)?.takeIf { s -> s.isNotEmpty() } ?: "Canal $it" }
                val current = knownChannels.indexOf(currentChatChannel).coerceAtLeast(0)
                val oldPos = chatChannelSpinner.selectedItemPosition
                if (oldPos < 0 || chatChannelSpinner.adapter?.count != items.size || oldPos >= items.size) {
                    chatSpinnerUpdating = true
                    chatChannelSpinner.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item, items
                    )
                    chatChannelSpinner.setSelection(current)
                    chatSpinnerUpdating = false
                }
            }

            chatMessagesContainer.removeAllViews()
            synchronized(chatMessages) {
                if (chatMessages.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = getString(R.string.chat_no_messages)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    }
                    chatMessagesContainer.addView(empty)
                    return@runOnUiThread
                }
                // Show ONLY the selected channel's history (no mixing).
                val msgs = chatMessages.filter { it.channel == currentChatChannel }
                if (msgs.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = getString(R.string.chat_no_messages)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    }
                    chatMessagesContainer.addView(empty)
                    return@runOnUiThread
                }
                val name = channelNames.getOrNull(currentChatChannel)?.takeIf { it.isNotEmpty() } ?: "Canal $currentChatChannel"
                val collapsed = chatCollapsedChannels.contains(currentChatChannel)
                val header = TextView(this).apply {
                    text = (if (collapsed) "▶ " else "▼ ") + name + " (" + msgs.size + ")"
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
                    setOnClickListener {
                        if (!chatCollapsedChannels.add(currentChatChannel)) chatCollapsedChannels.remove(currentChatChannel)
                        refreshChat()
                    }
                }
                chatMessagesContainer.addView(header)
                if (!collapsed) {
                    msgs.forEach { msg ->
                        val fromLabel = nodeLabel(msg.from)
                        val base = getString(R.string.chat_msg, msg.time, fromLabel, msg.text)
                        val isMine = msg.from == localNodeNum
                        chatMessagesContainer.addView(
                            TextView(this).apply {
                                if (isMine && msg.status.isNotEmpty()) {
                                    val (glyph, color) = when (msg.status) {
                                        CHAT_STATUS_ENROUTE -> " ⟳" to 0xFF3D9BE9.toInt()
                                        CHAT_STATUS_DELIVERED, CHAT_STATUS_RECEIVED -> " ✓" to 0xFF32D77B.toInt()
                                        CHAT_STATUS_ERROR -> " ✗" to 0xFFE5484D.toInt()
                                        else -> "" to 0
                                    }
                                    text = android.text.SpannableString(base + glyph).apply {
                                        if (glyph.isNotEmpty()) {
                                            setSpan(
                                                android.text.style.ForegroundColorSpan(color),
                                                base.length, base.length + glyph.length,
                                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                            )
                                        }
                                    }
                                    setOnClickListener { showChatStatusDialog(msg) }
                                } else {
                                    text = base
                                }
                                setPadding(dp(12), dp(4), dp(12), dp(4))
                                textSize = 14f
                                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
                            }
                        )
                    }
                }
            }
            if (!chatAutoScrollPaused) {
                chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun runGoodPractices() {
        if (warnIfAdminOverBle()) return
        if (!isReady()) {
            bpAppendStatus(getString(R.string.bp_not_connected))
            return
        }

        // Fixed "good practices" presets: prioritise airtime for user messages.
        val hops = 5
        val beaconSecs = 259200
        val gpsSecs = 120
        val nodeInfoSecs = 259200
        val smartEnabled = false

        configTarget = bpTargetInput.text?.toString()?.trim()?.let { input ->
            try {
                parseNodeId(input)
            } catch (e: Exception) {
                localNodeNum ?: -1
            }
        } ?: (localNodeNum ?: -1)

        val destLabel = if (configTarget == -1) getString(R.string.log_dest_local) else "0x${Integer.toHexString(configTarget)}"

        configJobs.clear()
        configJobs.add(
            ConfigJob(getString(R.string.bp_step_hop, hops), AdminMessage.ConfigType.LORA_CONFIG) { b ->
                b.setLora(b.getLora().toBuilder().setHopLimit(hops).build())
            }
        )
        configJobs.add(
            ConfigJob(getString(R.string.bp_step_nodeinfo, nodeInfoSecs), AdminMessage.ConfigType.DEVICE_CONFIG) { b ->
                b.setDevice(b.getDevice().toBuilder().setNodeInfoBroadcastSecs(nodeInfoSecs).build())
            }
        )
        configJobs.add(
            ConfigJob(
                getString(R.string.bp_step_position, beaconSecs, if (smartEnabled) getString(R.string.bp_smart_on) else getString(R.string.bp_smart_off), gpsSecs),
                AdminMessage.ConfigType.POSITION_CONFIG
            ) { b ->
                b.setPosition(
                    b.getPosition().toBuilder()
                        .setPositionBroadcastSecs(beaconSecs)
                        .setPositionBroadcastSmartEnabled(smartEnabled)
                        .setGpsUpdateInterval(gpsSecs)
                        .setGpsMode(ConfigProtos.Config.PositionConfig.GpsMode.DISABLED)
                        .build()
                )
            }
        )

        configJobIndex = 0
        awaitingConfigAck = null
        awaitingConfigRead = null
        bpAppendStatus(getString(R.string.bp_status_applying, 1, configJobs.size, configJobs[0].label) + " -> " + destLabel)
        configReadNext()
    }

    /**
     * Sends the get_config_request for the current job's section. Once the
     * response arrives ([onConfigResponse]), the section is modified and written
     * back in full (read-modify-write), so no unrelated field is ever lost.
     */
    private fun configReadNext() {
        if (configJobIndex >= configJobs.size) {
            finishConfigSequence(true)
            return
        }
        val job = configJobs[configJobIndex]
        val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val packet = MeshPacketBuilder.buildGetConfigRequestPacket(job.section, configTarget, packetId)
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            awaitingConfigRead = packetId
            bpAppendStatus(getString(R.string.bp_status_reading, configJobIndex + 1, configJobs.size, job.label))
            configHandler.removeCallbacksAndMessages(null)
            configHandler.postDelayed({ onConfigReadTimeout() }, CONFIG_ACK_TIMEOUT_MS)
        } else {
            bpAppendStatus(getString(R.string.bp_status_failed, getString(R.string.log_cmd_write_failed)))
            finishConfigSequence(false)
        }
    }

    /**
     * Called from handleFromRadio when an AdminMessage response (get_config_response)
     * arrives matching our pending read request. Applies the job's modification and
     * writes the full section back.
     */
    private fun onConfigResponse(adminMessage: org.meshtastic.proto.AdminProtos.AdminMessage) {
        if (awaitingSensorRead != null && adminMessage.hasGetModuleConfigResponse()) {
            configHandler.removeCallbacksAndMessages(null)
            awaitingSensorRead = null
            try {
                val mc = adminMessage.getModuleConfigResponse.toBuilder()
                mc.setTelemetry(
                    mc.getTelemetry().toBuilder()
                        .setEnvironmentMeasurementEnabled(sensorPendingEnv)
                        .setAirQualityEnabled(sensorPendingEnv)
                        .setPowerMeasurementEnabled(sensorPendingPower)
                        .build()
                )
                val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
                val packet = MeshPacketBuilder.buildSetModuleConfigPacket(mc.build(), localNodeNum ?: -1, packetId)
                val bytes = sendToRadio(packet)
                if (bytes != null) {
                    awaitingSensorAck = packetId
                    appendLog("SENSOR: writing telemetry module (env=${sensorPendingEnv}, power=${sensorPendingPower})")
                    configHandler.removeCallbacksAndMessages(null)
                    configHandler.postDelayed({ onSensorTimeout() }, CONFIG_ACK_TIMEOUT_MS)
                } else {
                    sensorStatusText.text = getString(R.string.sensor_status_failed, getString(R.string.log_cmd_write_failed))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sensor config write failed", e)
                sensorStatusText.text = getString(R.string.sensor_status_failed, e.message)
            }
            return
        }
        if (backupRunning && waitingBackupSection) {
            val bytes = when {
                adminMessage.hasGetConfigResponse() -> adminMessage.getConfigResponse.toByteArray()
                adminMessage.hasGetModuleConfigResponse() -> adminMessage.getModuleConfigResponse.toByteArray()
                else -> null
            }
            if (bytes != null) {
                configHandler.removeCallbacksAndMessages(null)
                waitingBackupSection = false
                backupResults.add(bytes)
                backupIndex++
                updateProgress(backupIndex, backupPlan.size, sectionLabel(backupPlan[backupIndex - 1]))
                backupSendNext()
            }
            return
        }
        if ((pendingAdminKey != null || pendingMasterKey != null) && awaitingAdminKeyRead != null && adminMessage.hasGetConfigResponse()) {
            awaitingAdminKeyRead = null
            configHandler.removeCallbacksAndMessages(null)
            try {
                val security = adminMessage.getConfigResponse.security.toBuilder()
                if (pendingMasterKey != null) {
                    val keyBytes = android.util.Base64.decode(pendingMasterKey!!, android.util.Base64.NO_WRAP)
                    security.setPrivateKey(com.google.protobuf.ByteString.copyFrom(keyBytes))
                } else {
                    val keyBytes = android.util.Base64.decode(pendingAdminKey!!, android.util.Base64.NO_WRAP)
                    security.addAdminKey(com.google.protobuf.ByteString.copyFrom(keyBytes))
                }
                lastSecurityConfig = security.build()
                val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
                val packet = MeshPacketBuilder.buildConfigPacket(
                    ConfigProtos.Config.newBuilder().setSecurity(lastSecurityConfig).build(), localNodeNum ?: -1, packetId
                )
                val bytes = sendToRadio(packet)
                if (bytes != null) {
                    awaitingAdminKeyAck = packetId
                    appendLog(getString(R.string.admin_keys_writing))
                    configHandler.removeCallbacksAndMessages(null)
                    configHandler.postDelayed({ onAdminKeyTimeout() }, CONFIG_ACK_TIMEOUT_MS)
                } else {
                    pendingAdminKey = null
                    pendingMasterKey = null
                    appendLog(getString(R.string.master_convert_failed))
                }
            } catch (e: Exception) {
                pendingAdminKey = null
                pendingMasterKey = null
                appendLog(getString(R.string.admin_keys_failed) + ": " + e.message)
            }
            return
        }
        if (awaitingConfigRead == null || !adminMessage.hasGetConfigResponse()) return
        configHandler.removeCallbacksAndMessages(null)
        awaitingConfigRead = null

        val job = configJobs[configJobIndex]
        val builder = adminMessage.getConfigResponse.toBuilder()
        try {
            job.modify(builder)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply config modification", e)
            bpAppendStatus(getString(R.string.bp_status_failed, e.message))
            finishConfigSequence(false)
            return
        }
        val newConfig = builder.build()
        val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val packet = MeshPacketBuilder.buildConfigPacket(newConfig, configTarget, packetId)
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            awaitingConfigAck = packetId
            bpAppendStatus(getString(R.string.bp_status_writing, configJobIndex + 1, configJobs.size, job.label))
            configHandler.removeCallbacksAndMessages(null)
            configHandler.postDelayed({ onConfigAckTimeout() }, CONFIG_ACK_TIMEOUT_MS)
        } else {
            bpAppendStatus(getString(R.string.bp_status_failed, getString(R.string.log_cmd_write_failed)))
            finishConfigSequence(false)
        }
    }

    /**
     * Backup: reads every config section + module config and saves them to a file.
     */
    private fun runBackup() {
        if (warnIfAdminOverBle()) return
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            Toast.makeText(this, getString(R.string.log_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        backupPlan.clear()
        configSectionTypes.forEach { backupPlan.add(SectionItem(false, it.number)) }
        moduleSectionTypes.forEach { backupPlan.add(SectionItem(true, it.number)) }
        backupIndex = 0
        backupResults.clear()
        backupRunning = true
        waitingBackupSection = false
        appendLog("BACKUP: reading ${backupPlan.size} sections...")
        Toast.makeText(this, getString(R.string.bp_backup_start, backupPlan.size), Toast.LENGTH_SHORT).show()
        showProgress(getString(R.string.backup_title), backupPlan.size)
        backupSendNext()
    }

    private fun backupSendNext() {
        if (backupIndex >= backupPlan.size) {
            backupRunning = false
            waitingBackupSection = false
            saveBackupFile()
            return
        }
        val item = backupPlan[backupIndex]
        val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val toRadio = if (item.isModule) {
            MeshPacketBuilder.buildGetModuleConfigRequestPacket(
                AdminMessage.ModuleConfigType.forNumber(item.type) ?: return failBackup(),
                configTarget, packetId
            )
        } else {
            MeshPacketBuilder.buildGetConfigRequestPacket(
                AdminMessage.ConfigType.forNumber(item.type) ?: return failBackup(),
                configTarget, packetId
            )
        }
        val bytes = sendToRadio(toRadio)
        if (bytes != null) {
            waitingBackupSection = true
            appendLog("BACKUP: requesting section ${backupIndex + 1}/${backupPlan.size} (${sectionLabel(item)})")
            configHandler.removeCallbacksAndMessages(null)
            configHandler.postDelayed({
                // Skip unresponsive sections instead of aborting the whole backup.
                if (waitingBackupSection) {
                    waitingBackupSection = false
                    appendLog("BACKUP: section ${backupIndex + 1} (${sectionLabel(item)}) no response — skipping")
                    backupIndex++
                    updateProgress(backupIndex, backupPlan.size, getString(R.string.log_telemetry_na))
                    backupSendNext()
                }
            }, BACKUP_SECTION_TIMEOUT_MS)
        } else {
            failBackup()
        }
    }

    private fun sectionLabel(item: SectionItem): String {
        return if (item.isModule) {
            AdminMessage.ModuleConfigType.forNumber(item.type)?.name?.lowercase(Locale.US) ?: "module"
        } else {
            AdminMessage.ConfigType.forNumber(item.type)?.name?.lowercase(Locale.US) ?: "config"
        }
    }

    private fun failBackup() {
        backupRunning = false
        hideProgress()
        appendLog("BACKUP: failed")
    }

    private fun saveBackupFile() {
        if (backupResults.isEmpty()) {
            hideProgress()
            appendLog("BACKUP: no sections responded — no backup saved")
            Toast.makeText(this, getString(R.string.bp_backup_no_data), Toast.LENGTH_LONG).show()
            return
        }
        try {
            val nodeId = "!${Integer.toHexString(localNodeNum ?: 0)}"
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(getExternalFilesDir(null) ?: filesDir, "backups")
            dir.mkdirs()
            val file = File(dir, "backup_${nodeId}_$ts.json")
            val sb = StringBuilder("[")
            for (i in backupResults.indices) {
                val item = backupPlan[i]
                if (i > 0) sb.append(",")
                sb.append("{\"module\":").append(item.isModule)
                    .append(",\"type\":").append(item.type)
                    .append(",\"data\":\"")
                    .append(android.util.Base64.encodeToString(backupResults[i], android.util.Base64.NO_WRAP))
                    .append("\"}")
            }
            sb.append("]")
            file.writeText(sb.toString())
            hideProgress()
            appendLog("BACKUP: saved ${backupResults.size} sections -> $file")
            bpAppendStatus("Backup guardado: $file")
            Toast.makeText(this, getString(R.string.bp_backup_done, file.name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save backup", e)
            appendLog("BACKUP: save failed: ${e.message}")
            Toast.makeText(this, getString(R.string.bp_backup_fail, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Restore: writes each saved section back with an ACK between commands.
     */
    /**
     * Shows a picker of saved backups; on selection, restores it.
     */
    private fun confirmFactoryReset(targetNodeId: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.factory_reset_confirm_title)
            .setMessage(R.string.factory_reset_confirm_body)
            .setPositiveButton(R.string.factory_reset_yes) { _, _ ->
                val destLabel = if (targetNodeId == -1) getString(R.string.log_dest_local) else "0x${Integer.toHexString(targetNodeId)}"
                val packet = MeshPacketBuilder.buildFactoryResetPacket(targetNodeId)
                val bytes = sendToRadio(packet)
                if (bytes != null) {
                    appendLog(getString(R.string.log_factory_reset_sent, destLabel))
                    Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_LONG).show()
                } else {
                    appendLog(getString(R.string.log_cmd_write_failed))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBackupPicker() {
        if (warnIfAdminOverBle()) return
        val dir = File(getExternalFilesDir(null) ?: filesDir, "backups")
        val files = dir.listFiles { f -> f.name.startsWith("backup_") && f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (files.isEmpty()) {
            appendLog("RESTORE: no backup found")
            Toast.makeText(this, getString(R.string.bp_restore_none), Toast.LENGTH_SHORT).show()
            return
        }
        val names = files.map { it.name }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bp_restore_pick)
            .setItems(names.toTypedArray()) { _, which ->
                val file = files[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bp_restore_confirm_title)
                    .setMessage(getString(R.string.bp_restore_confirm_body, file.name))
                    .setPositiveButton(R.string.bp_restore_confirm_yes) { _, _ -> runRestore(file) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun runRestore(file: File) {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            return
        }
        try {
            val arr = org.json.JSONArray(file.readText())
            restorePlan.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val data = android.util.Base64.decode(o.getString("data"), android.util.Base64.DEFAULT)
                restorePlan.add(Triple(o.getBoolean("module"), o.getInt("type"), data))
            }
            restoreIndex = 0
            restoreRunning = true
            appendLog("RESTORE: restoring ${restorePlan.size} sections from ${file.name}")
            showProgress(getString(R.string.restore_title), restorePlan.size)
            restoreSendNext()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse backup", e)
            appendLog("RESTORE: parse failed: ${e.message}")
        }
    }

    private fun restoreSendNext() {
        if (restoreIndex >= restorePlan.size) {
            restoreRunning = false
            hideProgress()
            appendLog(getString(R.string.restore_done))
            Toast.makeText(this, getString(R.string.restore_done), Toast.LENGTH_SHORT).show()
            return
        }
        val (isModule, type, data) = restorePlan[restoreIndex]
        val packetId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val toRadio = if (isModule) {
            val mc = try {
                org.meshtastic.proto.ModuleConfigProtos.ModuleConfig.parseFrom(data)
            } catch (e: Exception) {
                appendLog("RESTORE: parse module fail")
                restoreRunning = false
                return
            }
            MeshPacketBuilder.buildSetModuleConfigPacket(mc, configTarget, packetId)
        } else {
            val cfg = try {
                org.meshtastic.proto.ConfigProtos.Config.parseFrom(data)
            } catch (e: Exception) {
                appendLog("RESTORE: parse config fail")
                restoreRunning = false
                return
            }
            MeshPacketBuilder.buildConfigPacket(cfg, configTarget, packetId)
        }
        val bytes = sendToRadio(toRadio)
        if (bytes != null) {
            awaitingConfigAck = packetId
            appendLog("RESTORE: writing section ${restoreIndex + 1}/${restorePlan.size}...")
            updateProgress(restoreIndex + 1, restorePlan.size, sectionLabel(SectionItem(isModule, type)))
            configHandler.removeCallbacksAndMessages(null)
            configHandler.postDelayed({ onConfigAckTimeout() }, CONFIG_ACK_TIMEOUT_MS)
        } else {
            appendLog("RESTORE: write failed")
            restoreRunning = false
            hideProgress()
        }
    }

    /**
     * Called from handleFromRadio when a routing (ACK/NAK) packet arrives.
     */
    private fun onConfigRoutingAck(decoded: org.meshtastic.proto.MeshProtos.Data) {
        val adminKeyAck = awaitingAdminKeyAck
        if (adminKeyAck != null) {
            if (decoded.requestId != adminKeyAck) return
            configHandler.removeCallbacksAndMessages(null)
            awaitingAdminKeyAck = null
            val key = pendingAdminKey
            pendingAdminKey = null
            var errorName = "NONE"
            try {
                errorName = org.meshtastic.proto.MeshProtos.Routing.parseFrom(decoded.payload).errorReason.name
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse admin-key routing ack", e)
            }
            if (errorName != "NONE") {
                appendLog("ADMIN KEY: error $errorName")
                runOnUiThread { Toast.makeText(this, getString(R.string.admin_keys_failed), Toast.LENGTH_SHORT).show() }
                return
            }
            val wasMaster = pendingMasterKey != null
            refreshAdminKeysDisplay(lastSecurityConfig)
            pendingMasterKey = null
            pendingAdminKey = null
            if (wasMaster) {
                appendLog(getString(R.string.master_convert_done))
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.master_convert_done), Toast.LENGTH_SHORT).show()
                    adminKeyInput.text?.clear()
                }
                val reboot = MeshPacketBuilder.buildRebootPacket(seconds = 3, targetNodeId = localNodeNum ?: -1)
                val rebootBytes = sendToRadio(reboot)
                if (rebootBytes != null) appendLog(getString(R.string.log_reboot_sent, rebootBytes.size))
            } else {
                appendLog(getString(R.string.admin_keys_added))
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.admin_keys_added), Toast.LENGTH_SHORT).show()
                    adminKeyInput.text?.clear()
                }
            }
            return
        }
        val sensorAck = awaitingSensorAck
        if (sensorAck != null) {
            if (decoded.requestId != sensorAck) return
            configHandler.removeCallbacksAndMessages(null)
            awaitingSensorAck = null
            var errorName = "NONE"
            try {
                val routing = org.meshtastic.proto.MeshProtos.Routing.parseFrom(decoded.payload)
                errorName = routing.errorReason.name
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse sensor routing ack", e)
            }
            if (errorName != "NONE") {
                appendLog("SENSOR: error $errorName")
                sensorStatusText.text = getString(R.string.sensor_status_failed, errorName)
            } else {
                appendLog("SENSOR: updated (env=${sensorPendingEnv}, power=${sensorPendingPower})")
                sensorStatusText.text = getString(R.string.sensor_status_ok, sensorPendingEnv, sensorPendingPower)
            }
            return
        }
        val expected = awaitingConfigAck ?: return
        if (decoded.requestId != expected) return
        configHandler.removeCallbacksAndMessages(null)
        awaitingConfigAck = null

        var errorName = "NONE"
        try {
            val routing = org.meshtastic.proto.MeshProtos.Routing.parseFrom(decoded.payload)
            errorName = routing.errorReason.name
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse routing ack", e)
        }

        if (errorName != "NONE") {
            appendLog("RESTORE/BP: error $errorName")
            if (restoreRunning) {
                restoreRunning = false
                hideProgress()
            } else {
                bpAppendStatus(getString(R.string.bp_status_failed, errorName))
                finishConfigSequence(false)
            }
            return
        }
        if (restoreRunning) {
            appendLog("RESTORE: section ${restoreIndex + 1} ack OK")
            restoreIndex++
            restoreSendNext()
        } else {
            val job = configJobs[configJobIndex]
            bpAppendStatus(getString(R.string.bp_status_step_ok, job.label))
            configJobIndex++
            configReadNext()
        }
    }

    private fun onConfigAckTimeout() {
        awaitingConfigAck = null
        bpAppendStatus(getString(R.string.bp_ack_timeout, configJobIndex + 1, configJobs.size))
        finishConfigSequence(false)
    }

    private fun onAdminKeyTimeout() {
        pendingAdminKey = null
        pendingMasterKey = null
        awaitingAdminKeyRead = null
        awaitingAdminKeyAck = null
        appendLog(getString(R.string.admin_keys_timeout))
    }

    private fun refreshAdminKeysDisplay(security: ConfigProtos.Config.SecurityConfig?) {
        runOnUiThread {
            val sb = StringBuilder()
            if (security != null && security.publicKey.size() > 0) {
                val b64 = android.util.Base64.encodeToString(security.publicKey.toByteArray(), android.util.Base64.NO_WRAP)
                sb.append(getString(R.string.admin_keys_own)).append(": ").append(b64).append('\n')
            }
            if (security != null && security.privateKey.size() > 0) {
                val b64 = android.util.Base64.encodeToString(security.privateKey.toByteArray(), android.util.Base64.NO_WRAP)
                sb.append(getString(R.string.admin_keys_priv)).append(": ").append(b64).append('\n')
            }
            if (security == null || security.adminKeyCount == 0) {
                if (sb.isEmpty()) sb.append(getString(R.string.admin_keys_empty))
            } else {
                for (i in 0 until security.adminKeyCount) {
                    val b64 = android.util.Base64.encodeToString(security.getAdminKey(i).toByteArray(), android.util.Base64.NO_WRAP)
                    sb.append("K").append(i).append(": ").append(b64).append('\n')
                }
            }
            adminKeysText.text = sb.toString().trimEnd()
        }
    }

    private fun onConfigReadTimeout() {
        awaitingConfigRead = null
        bpAppendStatus(getString(R.string.bp_ack_timeout, configJobIndex + 1, configJobs.size))
        finishConfigSequence(false)
    }

    private fun finishConfigSequence(success: Boolean) {
        configHandler.removeCallbacksAndMessages(null)
        awaitingConfigAck = null
        if (success) {
            val destLabel = if (configTarget == -1) getString(R.string.log_dest_local) else "0x${Integer.toHexString(configTarget)}"
            bpAppendStatus(getString(R.string.bp_status_done, destLabel))
        }
    }

    private fun bpAppendStatus(line: String) {
        appendLog(line)
        runOnUiThread {
            bpStatusText.append("$line\n")
        }
    }

    /**
     * Opens a dialog listing the nodes known in the local NodeDB (favorites
     * first) so the user can pick one by name instead of typing the ID.
     *
     * @param target field to fill with the chosen node's ID.
     * @param onPicked optional callback invoked with the chosen node.
     */
    private fun showNodePicker(target: TextInputEditText, onPicked: ((NodeEntry) -> Unit)? = null) {
        maybeShowFavoriteHint()

        val entries = sortedNodeEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.picker_empty), Toast.LENGTH_LONG).show()
            return
        }

        val search = TextInputEditText(this).apply {
            hint = getString(R.string.picker_search_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(6)
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(rows) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(search)
            addView(scroll)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.picker_title)
            .setNegativeButton(R.string.close, null)
            .create()

        fun render(query: String) {
            rows.removeAllViews()
            val q = query.trim().lowercase(Locale.US)
            fun matches(entry: NodeEntry): Boolean {
                if (q.isEmpty()) return true
                val name = entry.name.lowercase(Locale.US)
                val id = "!${Integer.toHexString(entry.num)}"
                val aka = synchronized(nodeInfos) { nodeInfos[entry.num] }
                    ?.takeIf { it.hasUser() }?.user?.shortName?.lowercase(Locale.US) ?: ""
                return name.contains(q) || id.contains(q) || (aka.isNotEmpty() && aka.contains(q))
            }
            var totalMatches = 0
            var shown = 0
            for (entry in entries) if (matches(entry)) totalMatches++
            for (entry in entries) {
                if (matches(entry)) {
                    if (q.isEmpty() && shown >= MAX_PICKER_ROWS) continue
                    rows.addView(buildPickerRow(entry) {
                        target.setText("!${Integer.toHexString(entry.num)}")
                        onPicked?.invoke(entry)
                        dialog.dismiss()
                    })
                    shown++
                }
            }
            if (totalMatches == 0) {
                rows.addView(TextView(this).apply {
                    text = getString(R.string.picker_no_match)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
            } else if (shown < totalMatches) {
                rows.addView(TextView(this).apply {
                    text = getString(R.string.picker_more, totalMatches - shown)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    textSize = 12f
                    setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = render(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        dialog.setView(container)
        render("")
        dialog.show()
        search.requestFocus()
    }

    /**
     * Builds a tappable card row for the node picker (favorites first).
     */
    private fun buildPickerRow(entry: NodeEntry, onClick: () -> Unit): android.view.View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            setCardBackgroundColor(getColorAttr(com.google.android.material.R.attr.colorSurfaceVariant))
            cardElevation = 0f
            setContentPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6).toInt() }
            setOnClickListener { onClick() }
            isClickable = true
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val star = TextView(this).apply {
            text = if (entry.isFavorite) "★" else "○"
            textSize = 18f
            setTextColor(getColorAttr(android.R.attr.colorAccent))
            setPadding(0, 0, dp(10).toInt(), 0)
        }
        row.addView(star)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val name = TextView(this).apply {
            text = entry.name + if (entry.cached) " (cache)" else ""
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
        }
        col.addView(name)
        val id = TextView(this).apply {
            text = "!${Integer.toHexString(entry.num)}"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        col.addView(id)

        row.addView(col)
        card.addView(row)
        return card
    }

    /**
     * Shows the favourites tip the first 5 times the picker is opened.
     */
    private fun maybeShowFavoriteHint() {
        val prefs = getSharedPreferences("meshkacho", MODE_PRIVATE)
        val count = prefs.getInt("node_picker_hints", 0)
        if (count < 5) {
            prefs.edit().putInt("node_picker_hints", count + 1).apply()
            Toast.makeText(this, getString(R.string.picker_hint), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Returns the NodeDB entries sorted with favourites first, then by name.
     */
    private fun sortedNodeEntries(): List<NodeEntry> {
        val list = synchronized(nodeEntries) { nodeEntries.values.toList() }
        return list.sortedWith(
            compareByDescending<NodeEntry> { it.isFavorite }
                .thenBy { it.name.lowercase(Locale.US) }
        )
    }

    /**
     * Wires up the Commands tab: free-text sender plus standard protobuf actions
     * (telemetry, position, traceroute, owner) that work on any firmware.
     */
    private fun setupCommands() {
        sendCmdButton.setOnClickListener {
            val text = cmdTextInput.text?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                cmdTextInput.error = getString(R.string.please_enter_node)
                return@setOnClickListener
            }
            cmdTextInput.error = null
            sendTextPacket(text)
        }

        cmdTelemetryButton.setOnClickListener {
            sendAction({ t -> MeshPacketBuilder.buildRequestTelemetryPacket(t) }, R.string.cmd_telemetry)
        }
        cmdPositionButton.setOnClickListener {
            sendAction({ t -> MeshPacketBuilder.buildRequestPositionPacket(t) }, R.string.cmd_position)
        }
        cmdTraceButton.setOnClickListener {
            sendAction({ t -> MeshPacketBuilder.buildTraceRoutePacket(t) }, R.string.cmd_traceroute)
        }

        cmdSetOwnerButton.setOnClickListener {
            showSetOwnerDialog()
        }
    }

    /**
     * Command self-test: sends a block of commands in sequence and logs the
     * write result of each. The responses appear in the console log so the
     * behaviour of every command can be audited from the log file.
     */
    private fun runAudit() {
        if (auditBatteryRunning) {
            navadminTestStatus.text = getString(R.string.audit_already)
            return
        }
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            return
        }
        val local = localNodeNum ?: -1
        val steps = listOf(
            AuditStep("request-telemetry(local)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildRequestTelemetryPacket(local)) else null }),
            AuditStep("request-position(local)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildRequestPositionPacket(local)) else null }),
            AuditStep("traceroute(local)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildTraceRoutePacket(local)) else null }),
            AuditStep("set-favorite(local)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildSetFavoritePacket(local, -1)) else null }),
            AuditStep("set-ignored(local)", { if (local != -1) sendToRadio(MeshPacketBuilder.buildSetIgnoredPacket(local, -1)) else null }),
        )
        auditBatteryRunning = true
        auditName = getString(R.string.audit_title)
        auditIndex = 0
        auditSteps = steps
        auditIntervalMs = 6000L
        appendLog(getString(R.string.audit_started, auditName, steps.size, 6))
        showAuditPopup(auditName)
        openAuditFile(auditName)
        runNextAuditStep()
    }

    /**
     * Sends a text packet to the commands-panel target on the selected channel.
     */
    private fun sendTextPacket(text: String) {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            return
        }
        val target = commandTargetId()
        val destLabel = if (target == -1) getString(R.string.log_dest_local) else "0x${Integer.toHexString(target)}"
        val packet = MeshPacketBuilder.buildTextPacket(text, target, 0)
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            appendLog(getString(R.string.log_text_sent, bytes.size, destLabel))
        } else {
            appendLog(getString(R.string.log_cmd_write_failed))
        }
    }

    /**
     * Sends a standard protobuf action packet (telemetry/position/traceroute).
     * If no target is typed, opens the node picker so the user chooses who the
     * request is sent to, then shows a popup with the request status and the
     * decoded response.
     */
    private fun sendAction(packetBuilder: (Int) -> org.meshtastic.proto.MeshProtos.ToRadio, actionRes: Int) {
        if (!isReady()) {
            appendLog(getString(R.string.log_not_connected))
            return
        }

        fun sendTo(target: Int) {
            val destLabel = if (target == -1) getString(R.string.log_dest_local) else nodeLabel(target)
            val packet = packetBuilder(target)
            val bytes = sendToRadio(packet)
            if (bytes != null) {
                appendLog(getString(R.string.log_command_sent, getString(actionRes), destLabel, bytes.size))
                beginResponsePopup(actionRes, destLabel)
            } else {
                appendLog(getString(R.string.log_cmd_write_failed))
            }
        }

        if (cmdTargetInput.text.isNullOrBlank()) {
            // No target chosen yet: let the user pick from the NodeDB (favorites first).
            showNodePicker(cmdTargetInput) { sendTo(it.num) }
        } else {
            sendTo(commandTargetId())
        }
    }

    /**
     * Shows a popup stating the request was sent and that we are waiting for the
     * response. When a matching FromRadio packet arrives, the popup is updated
     * with the decoded response (see [updateResponsePopup]).
     */
    private fun beginResponsePopup(actionRes: Int, destLabel: String) {
        pendingResponseAction = when (actionRes) {
            R.string.cmd_telemetry -> "telemetry"
            R.string.cmd_position -> "position"
            R.string.cmd_traceroute -> "traceroute"
            else -> null
        }
        showResponsePopup(
            getString(actionRes),
            getString(R.string.popup_waiting, destLabel),
            cancellable = pendingResponseAction == "traceroute"
        )
    }

    private fun showResponsePopup(title: String, initial: String, cancellable: Boolean = false) {
        val textView = TextView(this).apply {
            text = initial
            setPadding(dp(24), dp(12), dp(24), dp(12))
            textSize = 14f
            movementMethod = ScrollingMovementMethod()
        }
        responseTextRef = textView
        responseDialog?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(textView)
            .setPositiveButton(R.string.close, null)
        if (cancellable) {
            builder.setNegativeButton(R.string.cancel) { _, _ ->
                pendingResponseAction = null
            }
        }
        responseDialog = builder.show()
        demoActiveDialog = responseDialog
    }

    /**
     * Appends a decoded response to the open response popup and stops tracking
     * the pending request. The dialog stays open until the user taps Accept.
     */
    private fun onResponseReceived(extra: String) {
        pendingResponseAction = null
        responseTextRef?.post {
            val current = responseTextRef?.text?.toString().orEmpty()
            responseTextRef?.text = "$current\n\n$extra"
        }
    }

    /**
     * Parses the commands-panel target node; empty means local/broadcast (-1).
     */
    private fun commandTargetId(): Int {
        val input = cmdTargetInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            // Default to the locally connected node so requests like telemetry
            // are answered directly instead of being broadcast to the mesh.
            return localNodeNum ?: -1
        }
        return try {
            parseNodeId(input)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse command target '$input'", e)
            localNodeNum ?: -1
        }
    }

    /**
     * Dialog to set the owner (long/short name) of a node, like `--set-owner`.
     */
    private fun showSetOwnerDialog() {
        val longInput = TextInputEditText(this)
        val shortInput = TextInputEditText(this)
        longInput.hint = getString(R.string.cmd_dialog_long_name)
        shortInput.hint = getString(R.string.cmd_dialog_short_name)
        longInput.setText(localLongName)
        shortInput.setText(localShortName)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
            addView(TextInputLayout(this@MainActivity).apply {
                addView(longInput)
            })
            addView(TextInputLayout(this@MainActivity).apply {
                addView(shortInput)
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cmd_dialog_title)
            .setView(layout)
            .setPositiveButton(R.string.cmd_dialog_ok) { _, _ ->
                if (warnIfAdminOverBle()) return@setPositiveButton
                val longName = longInput.text?.toString()?.trim().orEmpty()
                val shortName = shortInput.text?.toString()?.trim().orEmpty()
                if (longName.isEmpty() || shortName.isEmpty()) return@setPositiveButton
                val target = commandTargetId()
                val destLabel = if (target == -1) getString(R.string.log_dest_local) else "0x${Integer.toHexString(target)}"
                val packet = MeshPacketBuilder.buildSetOwnerPacket(longName, shortName, target)
                val bytes = sendToRadio(packet)
                if (bytes != null) {
                    appendLog(getString(R.string.log_owner_sent, destLabel))
                    Toast.makeText(this, getString(R.string.command_sent_toast, destLabel), Toast.LENGTH_SHORT).show()
                } else {
                    appendLog(getString(R.string.log_cmd_write_failed))
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun connectToDevice(device: UsbDevice) {
        val success = usbConnectionManager.connect(device)
        if (success) {
            appendLog(getString(R.string.log_connecting, device.deviceName))
            streamApiUnframer.reset()
            // Auto-download the NodeDB shortly after connecting so the node
            // pickers and node list are populated without a manual query.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (usbConnectionManager.isConnected()) {
                    appendLog(getString(R.string.log_query_auto))
                    sendWantConfig()
                }
            }, 1500)
        } else {
            appendLog(getString(R.string.log_connect_failed))
        }
    }

    /**
     * Sends a ToRadio packet over the active transport: raw protobuf over BLE,
     * or framed (0x94 0xC3) over USB. Returns the bytes actually written, or
     * null on failure.
     */
    private fun sendToRadio(toRadio: org.meshtastic.proto.MeshProtos.ToRadio): ByteArray? {
        if (demoMode) return byteArrayOf(0x01, 0x02)
        val bytes = if (bleTransportActive) {
            toRadio.toByteArray()
        } else {
            StreamApiFramer.frameToRadio(toRadio)
        }
        val ok = if (bleTransportActive) {
            bleConnectionManager.write(bytes)
        } else {
            usbConnectionManager.write(bytes)
        }
        return if (ok) bytes else null
    }

    /**
     * Connects to a node over Bluetooth: checks/requests BLE permissions, lists bonded
     * devices and connects to the chosen one.
     */
    private fun connectViaBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasScan && hasConnect) {
                showBleDevicePicker()
            } else {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    REQ_BLE_PERMISSIONS
                )
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasLocation) {
                showBleDevicePicker()
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQ_BLE_PERMISSIONS)
            }
            return
        }
        showBleDevicePicker()
    }

    private var bleScanCallback: android.bluetooth.le.ScanCallback? = null
    private var bleBondReceiver: android.content.BroadcastReceiver? = null
    private var pendingBondDevice: android.bluetooth.BluetoothDevice? = null
    private var bleScanning = false
    private var bleDialog: android.app.Dialog? = null
    private val bleFoundDevices = LinkedHashMap<String, android.bluetooth.BluetoothDevice>()
    private var bleFoundAdapter: ArrayAdapter<String>? = null
    private var bleBondedAdapter: ArrayAdapter<String>? = null
    private var bleBondedList: android.widget.ListView? = null
    private var bleFoundList: android.widget.ListView? = null
    private var bleFoundContainer: LinearLayout? = null
    private var bleFoundHeader: TextView? = null

    /**
     * Bluetooth picker: paired devices (refreshable) + a continuous scanner for
     * unpaired devices. Tapping an unpaired device requests bonding (the system
     * UI asks for confirmation/PIN, shown on the node's OLED); on success the
     * paired list refreshes and the app connects automatically. On failure the
     * user can retry or refresh the paired list.
     */
    private fun showBleDevicePicker() {
        val refreshBtn = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = getString(R.string.ble_picker_refresh)
            setOnClickListener {
                pressFeedback(this)
                renderBondedList()
            }
        }
        val bondedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(4), dp(8), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.ble_bonded_section)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(refreshBtn)
        }
        val bondedList = android.widget.ListView(this).apply {
            dividerHeight = 0
            setOnItemClickListener { _, view, pos, _ ->
                pressFeedback(view)
                val dev = bleConnectionManager.scan().getOrNull(pos) ?: return@setOnItemClickListener
                connectToBleDevice(dev)
            }
        }
        bleBondedList = bondedList
        renderBondedList()

        val scanProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = android.view.View.GONE
        }
        val scanBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.ble_scan_button)
        }
        scanBtn.setOnClickListener {
            pressFeedback(scanBtn)
            if (bleScanning) {
                stopBleScan()
                scanBtn.text = getString(R.string.ble_scan_button)
                scanProgress.visibility = android.view.View.GONE
                bleFoundHeader?.visibility = android.view.View.GONE
                bleFoundContainer?.visibility = android.view.View.GONE
            } else {
                startBleScan(scanBtn, scanProgress)
            }
        }
        val scanRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(scanBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(scanProgress)
        }
        val foundHeader = TextView(this).apply {
            text = getString(R.string.ble_found_section)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(20), dp(12), dp(20), dp(4))
            visibility = android.view.View.GONE
        }
        bleFoundHeader = foundHeader
        val foundList = android.widget.ListView(this).apply {
            dividerHeight = 0
            setOnItemClickListener { _, view, pos, _ ->
                pressFeedback(view)
                // Stop the scan first: while results keep pouring in the list
                // re-layouts constantly and the tap may never register / the row
                // gets recycled. With the scan stopped the pairing order goes out
                // immediately and the press animation stays visible.
                if (bleScanning) {
                    stopBleScan()
                    scanBtn.text = getString(R.string.ble_scan_button)
                    scanProgress.visibility = android.view.View.GONE
                }
                val dev = bleFoundDevices.values.toList().getOrNull(pos) ?: return@setOnItemClickListener
                requestBond(dev)
            }
        }
        bleFoundList = foundList
        val foundContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            addView(foundList)
        }
        bleFoundContainer = foundContainer

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bondedHeader)
            addView(bondedList)
            addView(scanRow)
            addView(foundHeader)
            addView(foundContainer)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ble_picker_title)
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnDismissListener {
            stopBleScan()
            stopBondPolling()
            unregisterBleBondReceiver()
            bleDialog = null
            pendingBondDevice = null
        }
        dialog.setOnShowListener {
            resizeBleLists()
        }
        bleDialog = dialog
        dialog.show()
        resizeBleLists()
    }

    /** Capped list heights so the dialog never overflows small screens. */
    private fun resizeBleLists() {
        fun cap(lv: android.widget.ListView?, count: Int) {
            if (lv == null) return
            val h = (Math.min(count, 4) * dp(52)).coerceAtLeast(dp(52))
            val lp = lv.layoutParams
            if (lp != null && lp.height != h) {
                lp.height = h
                lv.layoutParams = lp
            }
        }
        cap(bleBondedList, bleBondedAdapter?.count ?: 0)
        cap(bleFoundList, bleFoundAdapter?.count ?: 0)
    }

    private fun renderBondedList() {
        val devices = bleConnectionManager.scan()
        bleBondedAdapter = ArrayAdapter(this, R.layout.nava_spinner_item, devices.map { it.name ?: it.address }.toMutableList())
        bleBondedList?.adapter = bleBondedAdapter
        resizeBleLists()
    }

    private fun connectToBleDevice(device: android.bluetooth.BluetoothDevice) {
        bleDialog?.dismiss() // close the picker: the user already chose / just paired
        bleTransportActive = true
        appendLog(getString(R.string.ble_connecting, device.name ?: device.address))
        if (bleConnectionManager.connect(device)) {
            appendLog(getString(R.string.ble_connect_started))
        } else {
            appendLog(getString(R.string.ble_connect_failed))
        }
    }

    /** Quick press feedback (scale) for list rows and new picker buttons. */
    private fun pressFeedback(v: android.view.View) {
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private var bleFoundPending = arrayListOf<String>()
    private var bleFoundBatchScheduled = false
    private val bleFoundBatchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun scheduleBleFoundBatch() {
        if (bleFoundBatchScheduled) return
        bleFoundBatchScheduled = true
        bleFoundBatchHandler.postDelayed({ flushBleFoundBatch() }, 400)
    }

    private fun flushBleFoundBatch() {
        bleFoundBatchScheduled = false
        if (bleFoundPending.isEmpty()) return
        bleFoundPending.forEach { bleFoundAdapter?.add(it) }
        bleFoundPending.clear()
        resizeBleLists()
        if (bleScanning) scheduleBleFoundBatch()
    }

    private fun startBleScan(btn: com.google.android.material.button.MaterialButton, progress: ProgressBar) {
        bleFoundDevices.clear()
        bleFoundPending.clear()
        bleFoundAdapter = ArrayAdapter(this, R.layout.nava_spinner_item, arrayListOf())
        bleFoundList?.adapter = bleFoundAdapter
        bleFoundHeader?.visibility = android.view.View.VISIBLE
        bleFoundContainer?.visibility = android.view.View.VISIBLE
        progress.visibility = android.view.View.VISIBLE
        btn.text = getString(R.string.ble_stop_button)
        val callback = bleConnectionManager.startScan { dev ->
            runOnUiThread {
                val name = try { dev.name } catch (e: Exception) { null }
                if (name.isNullOrBlank()) return@runOnUiThread
                if (bleConnectionManager.scan().any { it.address == dev.address }) return@runOnUiThread
                if (!bleFoundDevices.containsKey(dev.address)) {
                    bleFoundDevices[dev.address] = dev
                    bleFoundPending.add(name)
                    scheduleBleFoundBatch()
                }
            }
        }
        if (callback == null) {
            bleScanning = false
            progress.visibility = android.view.View.GONE
            btn.text = getString(R.string.ble_scan_button)
            appendLog(getString(R.string.ble_scan_unavailable))
        } else {
            bleScanning = true
            bleScanCallback = callback
            appendLog(getString(R.string.ble_scan_started))
        }
    }

    private fun stopBleScan() {
        if (!bleScanning && bleScanCallback == null) return
        bleScanning = false
        bleConnectionManager.stopScan(bleScanCallback)
        bleScanCallback = null
        bleFoundBatchHandler.removeCallbacksAndMessages(null)
        bleFoundBatchScheduled = false
        flushBleFoundBatch()
        appendLog(getString(R.string.ble_scan_stopped))
    }

    private fun requestBond(device: android.bluetooth.BluetoothDevice) {
        pendingBondDevice = device
        registerBleBondReceiver()
        val state = try {
            device.bondState
        } catch (e: Exception) {
            android.bluetooth.BluetoothDevice.BOND_NONE
        }
        when {
            // Already paired (e.g. the user paired it moments ago): connect directly.
            state == android.bluetooth.BluetoothDevice.BOND_BONDED -> onBleBonded(device)
            // A previous attempt got stuck in BONDING (some stacks never show the
            // PIN prompt again): retry shortly — this re-triggers the system dialog.
            state == android.bluetooth.BluetoothDevice.BOND_BONDING -> {
                appendLog(getString(R.string.ble_bond_retry))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (pendingBondDevice?.address == device.address) {
                        appendLog(getString(R.string.ble_bond_start, device.name ?: device.address))
                        startBondPolling(device)
                        if (!bleConnectionManager.createBond(device)) {
                            appendLog(getString(R.string.ble_bond_failed))
                            pendingBondDevice = null
                            stopBondPolling()
                        }
                    }
                }, 400)
            }
            else -> {
                appendLog(getString(R.string.ble_bond_start, device.name ?: device.address))
                startBondPolling(device)
                if (!bleConnectionManager.createBond(device)) {
                    appendLog(getString(R.string.ble_bond_failed))
                    pendingBondDevice = null
                    stopBondPolling()
                }
            }
        }
    }

    /**
     * Bond detection is belt-and-braces: the ACTION_BOND_STATE_CHANGED broadcast
     * plus a poll of bondState (every 500 ms, 60 s deadline). Some vendors
     * (Samsung/MIUI) deliver the broadcast late or never, so the poller is the
     * reliable path; it also covers the case where the system dialog is still
     * showing while the user enters the PIN.
     */
    private var bondPoller: Runnable? = null
    private val bondPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bondPollDeadline = 0L

    private fun startBondPolling(device: android.bluetooth.BluetoothDevice) {
        stopBondPolling()
        bondPollDeadline = System.currentTimeMillis() + 60000L
        val runnable = object : Runnable {
            override fun run() {
                val pending = pendingBondDevice ?: return
                val state = try {
                    pending.bondState
                } catch (e: Exception) {
                    android.bluetooth.BluetoothDevice.BOND_NONE
                }
                when {
                    state == android.bluetooth.BluetoothDevice.BOND_BONDED -> onBleBonded(pending)
                    System.currentTimeMillis() > bondPollDeadline -> onBleBondFailed(pending)
                    else -> bondPollHandler.postDelayed(this, 500)
                }
            }
        }
        bondPoller = runnable
        bondPollHandler.postDelayed(runnable, 500)
    }

    private fun stopBondPolling() {
        bondPoller?.let { bondPollHandler.removeCallbacks(it) }
        bondPoller = null
    }

    private fun onBleBonded(device: android.bluetooth.BluetoothDevice) {
        appendLog(getString(R.string.ble_bond_ok, device.name ?: device.address))
        stopBondPolling()
        pendingBondDevice = null
        renderBondedList()
        connectToBleDevice(device)
    }

    private fun onBleBondFailed(device: android.bluetooth.BluetoothDevice) {
        appendLog(getString(R.string.ble_bond_failed))
        stopBondPolling()
        pendingBondDevice = null
    }

    private fun registerBleBondReceiver() {
        if (bleBondReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val dev: android.bluetooth.BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                val device = dev ?: return
                val state = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, android.bluetooth.BluetoothDevice.BOND_NONE)
                val pending = pendingBondDevice
                if (pending == null || device.address != pending.address) return
                when (state) {
                    android.bluetooth.BluetoothDevice.BOND_BONDED -> onBleBonded(device)
                    android.bluetooth.BluetoothDevice.BOND_NONE -> onBleBondFailed(device)
                }
            }
        }
        val filter = android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        bleBondReceiver = receiver
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterBleBondReceiver() {
        bleBondReceiver?.let { runCatching { unregisterReceiver(it) } }
        bleBondReceiver = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERMISSIONS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (granted) {
                showBleDevicePicker()
            } else {
                appendLog(getString(R.string.ble_permission_denied))
            }
        }
    }

    /**
     * Sends the two-phase config handshake (equivalent to `meshtastic --info`):
     * phase 1 (nonce 69420) streams config + channels + own NodeInfo, phase 2
     * (nonce 69421) streams the full node DB. Chaining both is the official
     * client pattern and avoids the single-shot handshake that stalls on BLE
     * reconnects (the firmware only understands these two special nonces).
     */
    private fun sendWantConfig() {
        sendConfigPhase(1)
    }

    private fun sendConfigPhase(phase: Int) {
        val requestId = if (phase == 1) CONFIG_PHASE1_NONCE else CONFIG_PHASE2_NONCE
        pendingQueryRequestId = requestId
        pendingConfigPhase = phase
        if (phase == 1) {
            nodeInfoCount = 0
            navadminChannelSeen = false
            navadminChannelIndex = -1
            synchronized(channelNames) { channelNames.clear() }
            synchronized(nodeInfoLines) { nodeInfoLines.clear() }
        }
        val packet = MeshPacketBuilder.buildWantConfigPacket(requestId)
        val bytes = sendToRadio(packet)
        if (bytes != null) {
            appendLog(getString(R.string.log_query_sent, requestId))
            bleConnectionManager.setConfigDraining(true)
            bleConnectionManager.armStallWatchdog()
            bleConnectionManager.setHighConnectionPriority(true)
            statusProgress.visibility = android.view.View.VISIBLE
            statusProgress.isIndeterminate = totalNodeInfos <= 0
            if (totalNodeInfos > 0) {
                statusProgress.max = totalNodeInfos
                statusProgress.progress = 0
            }
            runOnUiThread {
                statusText.text = getString(R.string.status_downloading, 0, totalNodeInfos)
            }
            // Safety stop: if ConfigComplete never matches, stop the spinner anyway.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (pendingQueryRequestId != null) {
                    pendingQueryRequestId = null
                    bleConnectionManager.setConfigDraining(false)
                    bleConnectionManager.disarmStallWatchdog()
                    bleConnectionManager.setHighConnectionPriority(false)
                    statusProgress.visibility = android.view.View.GONE
                    runOnUiThread {
                        statusText.text = getString(R.string.status_connected_transport, transportLabel())
                        statusText.setTextColor(0xFF32D77B.toInt())
                    }
                }
            }, DOWNLOAD_TIMEOUT_MS)
        } else {
            appendLog(getString(R.string.log_query_failed))
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            Log.d(TAG, message)
            logText.append("$message\n")
            appendToLogFile(message)
            if (auditBatteryRunning) {
                auditConsoleAppend(message)
                try {
                    auditFile?.appendText(message + "\n")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write audit file", e)
                }
            }
        }
    }

    /**
     * Persists console output to a rolling file inside the app's external files
     * directory so it can be shared even without adb/logcat access.
     */
    private fun appendToLogFile(message: String) {
        try {
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val dir = File(baseDir, MeshKachoUtilityApp.LOG_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "app_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            FileWriter(file, true).use { it.append("$timestamp $message\n") }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write log file", e)
        }
    }

    // ---------- Remote control (adb am broadcast com.meshkachoutility.REMOTE) ----------

    /**
     * Dispatches a remote control command coming from the PC
     * (`adb shell am broadcast -a com.meshkachoutility.REMOTE --es cmd <action>
     * [--ei num N] [--es arg ".."] [--es arg2 ".."]`). All commands run on the
     * main thread (invoked via runOnUiThread by RemoteControlReceiver) and reuse
     * the existing send/audit/nodes code paths. The agent reads results from
     * `app_log.txt` / `remote_state.json`.
     */
    private fun handleRemoteCommand(cmd: String, num: Int, arg: String, arg2: String) {
        when (cmd) {
            "tab" -> remoteSwitchTab(num)
            "state" -> writeRemoteState()
            "send_nava" -> remoteSendNava(arg, arg2)
            "request" -> remoteRequest(arg, arg2)
            "nodes" -> dumpNodesToFile()
            "fav" -> remoteAdminAction("fav", arg)
            "ign" -> remoteAdminAction("ign", arg)
            "remove" -> remoteAdminAction("remove", arg)
            "chat" -> sendChatReply(arg)
            "import_url" -> importContactFromUrl(arg)
            "audit" -> remoteStartAudit(num)
            "audit_stop" -> {
                stopAuditBattery()
                appendLog("REMOTE: audit_stop")
            }
            "debug_tab" -> setDebugTabEnabled(arg.equals("on", true) || arg == "1")
            else -> appendLog("REMOTE: comando desconocido '$cmd'")
        }
    }

    private fun remoteSwitchTab(n: Int) {
        if (n < 0 || n >= bottomTabs.tabCount) {
            appendLog("REMOTE: tab fuera de rango ($n)")
            return
        }
        remoteTabSwitch = true
        bottomTabs.getTabAt(n)?.select()
        remoteTabSwitch = false
        appendLog("REMOTE: tab -> $n")
    }

    /**
     * Writes `remote_state.json` (inside getExternalFilesDir) with the current
     * tab, connection/transport, node count and the last log lines, so the agent
     * can verify state without screen dumps.
     */
    private fun writeRemoteState() {
        val connected = isReady()
        val transport = when {
            !connected -> "none"
            bleTransportActive -> "bt"
            else -> "usb"
        }
        val logLines = try {
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val file = File(baseDir, MeshKachoUtilityApp.LOG_DIR + File.separator + "app_log.txt")
            if (file.exists()) file.readText().trimEnd('\n').lines().takeLast(20) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read log for remote state", e)
            emptyList()
        }
        val json = org.json.JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("tab", bottomTabs.selectedTabPosition)
            put("connected", connected)
            put("transport", transport)
            put("nodeInfoCount", nodeInfoCount)
            put("localNodeNum", localNodeNum?.let { "!${Integer.toHexString(it)}" } ?: "")
            put("logLines", org.json.JSONArray(logLines))
        }
        try {
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val file = File(baseDir, "remote_state.json")
            FileWriter(file).use { it.write(json.toString(2)) }
            appendLog("REMOTE: state escrita (tab=${bottomTabs.selectedTabPosition}, conectado=$connected/$transport)")
        } catch (e: Exception) {
            appendLog("REMOTE: error escribiendo state: ${e.message}")
        }
    }

    /**
     * Remote /nava command dispatcher for automation test harnesses (supports
     * both DM and Navadmin channel routes directly from ADB intents).
     */
    private fun remoteSendNava(text: String, target: String) {
        if (!isReady()) {
            appendLog("REMOTE: send_nava rechazado (no conectado)")
            return
        }
        val trimmed = text.trim()
        val navaText = if (trimmed.startsWith("/nava")) trimmed else "/nava $trimmed"
        val isChannel = target.isEmpty() || target.equals("ch", true) || target.equals("navadmin", true) || target == "-1"
        val targetNum = if (isChannel) -1 else try { parseNodeId(target) } catch (e: Exception) { -1 }

        if (!isChannel && targetNum == -1) {
            appendLog("REMOTE: send_nava rechazado (target inválido '$target')")
            return
        }

        val packet = if (isChannel) {
            val chIndex = if (navadminChannelIndex >= 0) navadminChannelIndex else 1
            MeshPacketBuilder.buildTextPacket(navaText, -1, chIndex)
        } else {
            MeshPacketBuilder.buildTextPacket(navaText, targetNum, 0, pkiEncrypted = true)
        }

        val bytes = sendToRadio(packet)
        if (bytes != null) {
            val route = if (isChannel) "ch" else "dm"
            appendLog("REMOTE: send_nava >> $navaText (${if (isChannel) "ch" else "DM $target"})")
            addNavaMsg(localNodeNum ?: 0, navaText, sent = true, route = route)
        } else {
            appendLog("REMOTE: send_nava fallo de envío")
        }
    }

    /**
     * Remote telemetry/position/traceroute request, mirroring the node-popup
     * request buttons (standard protobuf, not NavaCLI).
     */
    private fun remoteRequest(which: String, target: String) {
        if (!isReady()) {
            appendLog("REMOTE: request rechazado (no conectado)")
            return
        }
        val targetNum = try { parseNodeId(target) } catch (e: Exception) { -1 }
        if (targetNum == -1) {
            appendLog("REMOTE: request rechazado (target inválido '$target')")
            return
        }
        val packet = when (which) {
            "tlm" -> MeshPacketBuilder.buildRequestTelemetryPacket(targetNum)
            "env" -> MeshPacketBuilder.buildRequestTelemetryTypePacket(targetNum, "env")
            "power" -> MeshPacketBuilder.buildRequestTelemetryTypePacket(targetNum, "power")
            "pos" -> MeshPacketBuilder.buildRequestPositionPacket(targetNum)
            "trace" -> MeshPacketBuilder.buildTraceRoutePacket(targetNum)
            else -> null
        }
        if (packet == null) {
            appendLog("REMOTE: request tipo desconocido '$which' (tlm|pos|trace|env|power)")
            return
        }
        val bytes = sendToRadio(packet)
        appendLog(if (bytes != null) "REMOTE: request $which -> $target" else "REMOTE: request $which fallo de envío")
    }

    /**
     * Remote local-admin ops: favorite/ignored/remove on the connected node.
     */
    private fun remoteAdminAction(op: String, id: String) {
        if (!isReady()) {
            appendLog("REMOTE: $op rechazado (no conectado)")
            return
        }
        val nodeNum = try { parseNodeId(id) } catch (e: Exception) { -1 }
        if (nodeNum == -1) {
            appendLog("REMOTE: $op rechazado (id inválido '$id')")
            return
        }
        val packet = when (op) {
            "fav" -> MeshPacketBuilder.buildSetFavoritePacket(nodeNum, -1)
            "ign" -> MeshPacketBuilder.buildSetIgnoredPacket(nodeNum, -1)
            "remove" -> MeshPacketBuilder.buildRemoveNodePacket(nodeNum, -1)
            else -> null
        }
        if (packet == null) {
            appendLog("REMOTE: op desconocida '$op'")
            return
        }
        val bytes = sendToRadio(packet)
        appendLog(if (bytes != null) "REMOTE: $op $id (local) enviado" else "REMOTE: $op $id fallo de envío")
    }

    private fun remoteStartAudit(n: Int) {
        if (n < 0 || n > 6) {
            appendLog("REMOTE: audit índice fuera de rango ($n)")
            return
        }
        batterySpinner.setSelection(n)
        startAuditBattery()
        appendLog("REMOTE: audit iniciada (batería $n)")
    }

    /**
     * Dumps the in-memory node list to `nodes_dump.json` (inside
     * getExternalFilesDir) for remote inspection.
     */
    private fun dumpNodesToFile() {
        val json = org.json.JSONArray()
        synchronized(nodeEntries) {
            nodeEntries.values.forEach { e ->
                json.put(org.json.JSONObject().apply {
                    put("num", "!${Integer.toHexString(e.num)}")
                    put("name", e.name)
                    put("isFavorite", e.isFavorite)
                    put("battery", e.battery)
                    put("voltage", e.voltage)
                    put("snr", e.snr)
                    put("lastHeard", e.lastHeard)
                    put("hops", e.hops)
                    put("cached", e.cached)
                })
            }
        }
        try {
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val file = File(baseDir, "nodes_dump.json")
            FileWriter(file).use { it.write(json.toString(2)) }
            appendLog("REMOTE: nodes dump ${json.length()} nodos")
        } catch (e: Exception) {
            appendLog("REMOTE: error escribiendo nodes_dump.json: ${e.message}")
        }
    }

    // ---------- Shared contact (business card) URL import ----------

    /**
     * Parses a Meshtastic shared-contact URL (`https://meshtastic.org/v/#<base64>`)
     * into its [org.meshtastic.proto.AdminProtos.SharedContact]. Accepts
     * meshtastic.org / www.meshtastic.org and any path containing a "v" segment
     * (same lenient rules as the official app). Returns null when the URL is not
     * a valid shared contact.
     */
    private fun parseSharedContactUrl(raw: String): org.meshtastic.proto.AdminProtos.SharedContact? {
        return try {
            val uri = android.net.Uri.parse(raw.trim())
            val host = uri.host?.lowercase(Locale.US) ?: ""
            if (host != "meshtastic.org" && host != "www.meshtastic.org") return null
            if (uri.pathSegments.none { it.equals("v", ignoreCase = true) }) return null
            val fragment = uri.fragment ?: return null
            val data = fragment.substringBefore('?')
            if (data.isBlank()) return null
            val b64 = data.replace('-', '+').replace('_', '/')
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            org.meshtastic.proto.AdminProtos.SharedContact.parseFrom(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "parse shared contact URL failed: $raw", e)
            null
        }
    }

    /**
     * Imports a shared-contact URL into the app's own node cache (nodes/cache.json),
     * NOT into the node's NodeDB (nRF52 keeps only 80 entries). If the contact has
     * no public key, forces a NodeInfo/user-info exchange by DM-ing the node.
     */
    private fun importContactFromUrl(raw: String) {
        val contact = parseSharedContactUrl(raw)
        if (contact == null) {
            appendLog("URL: formato inválido (no es una tarjeta de contacto Meshtastic)")
            return
        }
        val user = contact.user ?: run {
            appendLog("URL: falta user en la tarjeta")
            return
        }
        val num = contact.nodeNum
        if (num == 0) {
            appendLog("URL: falta node_num en la tarjeta")
            return
        }
        val hasKey = user.publicKey.size() > 0
        val pkB64 = if (hasKey) android.util.Base64.encodeToString(user.publicKey.toByteArray(), android.util.Base64.NO_WRAP) else null
        synchronized(nodeEntries) {
            val existing = nodeEntries[num]
            nodeEntries[num] = NodeEntry(
                num = num,
                name = user.longName,
                isFavorite = existing?.isFavorite ?: false,
                battery = existing?.battery ?: -1,
                voltage = existing?.voltage ?: 0f,
                snr = existing?.snr ?: 0f,
                lastHeard = existing?.lastHeard ?: 0L,
                hops = existing?.hops ?: -1,
                cached = true,
                pubKey = pkB64
            )
        }
        lastNodeCacheSave = 0L
        maybeSaveNodeCache()
        refreshNodesList()
        appendLog("URL: tarjeta de ${user.longName} (!${Integer.toHexString(num)}) importada" + if (hasKey) " (con clave PKI)" else " (sin clave)")
        if (!hasKey) {
            // No PKI key in the card: force the NodeInfo exchange so the real
            // key can be learned from the node's broadcast.
            val packet = MeshPacketBuilder.buildTextPacket("info", num, 0)
            val bytes = sendToRadio(packet)
            appendLog(if (bytes != null) "URL: intercambio de info forzado → !${Integer.toHexString(num)}" else "URL: fallo forzando intercambio de info")
        }
    }

    /**
     * Parses the destination node input string. Defaulting to -1 (local/broadcast) if empty or invalid.
     */
    private fun parseTargetNodeId(input: String): Int {
        if (input.isEmpty()) return -1
        return try {
            parseNodeId(input)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse target node ID '$input', defaulting to local node (-1)", e)
            -1
        }
    }

    /**
     * Parses Node ID inputs supporting:
     * - Hex representation with '0x' prefix (e.g. 0x2c3f)
     * - Hex representation with '!' prefix (e.g. !2c3f)
     * - Bare hexadecimal string (e.g. 2c3f)
     * - Standard decimal base (e.g. 11234)
     */
    private fun parseNodeId(input: String): Int {
        val clean = input.trim()
        return when {
            clean.startsWith("0x", ignoreCase = true) -> {
                clean.substring(2).toLong(16).toInt()
            }
            clean.startsWith("!") -> {
                clean.substring(1).toLong(16).toInt()
            }
            clean.any { it in 'a'..'f' || it in 'A'..'F' } -> {
                clean.toLong(16).toInt()
            }
            else -> {
                try {
                    clean.toLong().toInt()
                } catch (e: NumberFormatException) {
                    clean.toLong(16).toInt()
                }
            }
        }
    }

    /**
     * Formats the FromRadio message content for console display.
     */
    private fun handleFromRadio(fromRadio: FromRadio) {
        val message = StringBuilder(getString(R.string.log_from_radio, fromRadio.id))
        when {
            fromRadio.hasPacket() -> {
                val packet = fromRadio.packet
                message.append(
                    getString(
                        R.string.log_mesh_packet,
                        Integer.toHexString(packet.from),
                        Integer.toHexString(packet.to),
                        packet.id
                    )
                )
                if (packet.hasDecoded()) {
                    val decoded = packet.decoded
                    when (decoded.portnum) {
                        PortNum.TELEMETRY_APP -> {
                            val tel = formatTelemetry(decoded.payload)
                            message.append(tel)
                            if (pendingResponseAction == "telemetry") {
                                onResponseReceived(getString(R.string.popup_response_telemetry, tel))
                            }
                        }
                        PortNum.POSITION_APP -> {
                            val pos = formatPosition(decoded.payload)
                            message.append(pos)
                            if (pendingResponseAction == "position") {
                                onResponseReceived(getString(R.string.popup_response_position, pos))
                            }
                        }
                        PortNum.NEIGHBORINFO_APP -> {
                            val nb = formatNeighbors(decoded.payload)
                            message.append(nb)
                            if (pendingResponseAction == "neighbors") {
                                onResponseReceived(getString(R.string.popup_response_neighbors, nb))
                            }
                        }
                        PortNum.TEXT_MESSAGE_APP -> {
                            val text = try {
                                decoded.payload.toStringUtf8()
                            } catch (e: Exception) {
                                getString(R.string.log_unknown_name)
                            }
                            message.append(getString(R.string.log_text_received, text))
                            maybeCaptureNavaMessage(packet, text)
                            if (!chatPaused) {
                                synchronized(chatMessages) {
                                    chatMessages.add(
                                        ChatMessage(
                                            from = packet.from,
                                            text = text,
                                            channel = packet.channel,
                                            time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                                        )
                                    )
                                    trimChatHistoryMemory()
                                }
                                saveChatHistory()
                                if (chatPanel.visibility == android.view.View.VISIBLE) {
                                    refreshChat()
                                }
                            }
                        }
                        PortNum.ADMIN_APP -> {
                            message.append(getString(R.string.log_packet_decoded, decoded.portnum.toString(), decoded.payload.size()))
                            try {
                                onConfigResponse(AdminMessage.parseFrom(decoded.payload))
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse admin response", e)
                            }
                        }
                        PortNum.ROUTING_APP -> {
                            if (pendingResponseAction == "traceroute") {
                                val route = formatTraceRoute(decoded.payload, packet.from, packet.to)
                                message.append(route)
                                onResponseReceived(route)
                            } else {
                                message.append(getString(R.string.log_packet_decoded, decoded.portnum.toString(), decoded.payload.size()))
                            }
                            onConfigRoutingAck(decoded)
                            handleChatRoutingAck(decoded)
                        }
                        PortNum.TRACEROUTE_APP -> {
                            if (pendingResponseAction == "traceroute") {
                                val route = formatTraceRoute(decoded.payload, packet.from, packet.to)
                                message.append(route)
                                onResponseReceived(route)                            } else {
                                message.append(getString(R.string.log_packet_decoded, decoded.portnum.toString(), decoded.payload.size()))
                            }
                        }
                        else -> message.append(getString(R.string.log_packet_decoded, decoded.portnum.toString(), decoded.payload.size()))
                    }
                    if (packet.wantAck) {
                        message.append(getString(R.string.log_ack_requested))
                    }
                } else if (packet.hasEncrypted()) {
                    message.append(getString(R.string.log_encrypted))
                }
            }
            fromRadio.hasMyInfo() -> {
                val myInfo = fromRadio.myInfo
                localNodeNum = myInfo.myNodeNum
                totalNodeInfos = myInfo.nodedbCount
                if (pendingQueryRequestId != null && totalNodeInfos > 0) {
                    runOnUiThread {
                        statusProgress.isIndeterminate = false
                        statusProgress.max = totalNodeInfos
                    }
                }
                message.append(
                    getString(
                        R.string.log_my_info,
                        Integer.toHexString(myInfo.myNodeNum),
                        myInfo.rebootCount
                    )
                )
            }
            fromRadio.hasNodeInfo() -> {
                val nodeInfo = fromRadio.nodeInfo
                nodeInfoCount++
                if (pendingQueryRequestId != null) {
                    runOnUiThread {
                        statusText.text = getString(R.string.status_downloading, nodeInfoCount, totalNodeInfos)
                        statusText.setTextColor(0xFF32D77B.toInt())
                        if (totalNodeInfos > 0) {
                            statusProgress.progress = nodeInfoCount
                        }
                    }
                }
                if (localNodeNum != null && nodeInfo.num == localNodeNum && nodeInfo.hasUser()) {
                    localLongName = nodeInfo.user.longName
                    localShortName = nodeInfo.user.shortName
                }
                synchronized(nodeEntries) {
                    val name = if (nodeInfo.hasUser()) nodeInfo.user.longName else getString(R.string.log_unknown_name)
                    val dm = nodeInfo.deviceMetrics
                    val existing = nodeEntries[nodeInfo.num]
                    val pk = nodeInfo.user.publicKey.takeIf { it.size() > 0 }?.let {
                        android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP)
                    } ?: existing?.pubKey
                    nodeEntries[nodeInfo.num] = NodeEntry(
                        num = nodeInfo.num,
                        name = name,
                        isFavorite = nodeInfo.isFavorite,
                        battery = if (dm.hasBatteryLevel()) dm.batteryLevel else -1,
                        voltage = if (dm.hasVoltage()) dm.voltage else 0f,
                        snr = nodeInfo.snr,
                        lastHeard = nodeInfo.lastHeard.toLong(),
                        hops = if (nodeInfo.hasHopsAway()) nodeInfo.hopsAway else -1,
                        cached = existing?.cached ?: false,
                        pubKey = pk
                    )
                }
                synchronized(nodeInfos) { nodeInfos[nodeInfo.num] = nodeInfo }
                if (nodePopupNum == nodeInfo.num) {
                    runOnUiThread { refreshNodePopup() }
                }
                maybeSaveNodeCache()
                if (pendingQueryRequestId == null) {
                    val line = formatNodeInfo(nodeInfo)
                    synchronized(nodeInfoLines) { nodeInfoLines.add(line) }
                    message.append(line)
                } else {
                    // During the NodeDB download we only fill the node list; the
                    // console flood would slow things down.
                    message.setLength(0)
                }
            }
            fromRadio.hasConfig() -> {
                val config = fromRadio.config
                if (config.hasSecurity()) {
                    lastSecurityConfig = config.security
                    refreshAdminKeysDisplay(lastSecurityConfig)
                }
                message.append(getString(R.string.log_config, config.payloadVariantCase.toString()))
            }
            fromRadio.hasModuleConfig() -> {
                val moduleConfig = fromRadio.moduleConfig
                message.append(getString(R.string.log_module_config, moduleConfig.payloadVariantCase.toString()))
            }
            fromRadio.hasChannel() -> {
                val channel = fromRadio.channel
                synchronized(channelNames) {
                    while (channelNames.size <= channel.index) channelNames.add("")
                    val chName = if (channel.hasSettings() && channel.settings.name.isNotBlank()) {
                        channel.settings.name
                    } else {
                        "Canal ${channel.index}"
                    }
                    channelNames[channel.index] = chName
                }
                // Navadmin channel = named "Navadmin" (fallback: the special 0x01 PSK)
                val pskIsNav = channel.hasSettings() && channel.settings.psk.size() == 1 &&
                        channel.settings.psk.byteAt(0) == 0x01.toByte()
                if (channel.hasSettings() && channel.settings.name.equals("Navadmin", ignoreCase = true) || pskIsNav) {
                    navadminChannelIndex = channel.index
                    navadminChannelSeen = true
                }
                message.append(getString(R.string.log_channel, channel.index, channel.role.toString()))
                if (chatPanel.visibility == android.view.View.VISIBLE) {
                    refreshChat()
                }
            }
            fromRadio.hasFileInfo() -> {
                // Proto-definition manifest files (config.proto, nodes.proto, ...):
                // not needed by the app, skip to speed up the download.
                return
            }
            fromRadio.hasMetadata() -> {
                val meta = fromRadio.metadata
                message.append(
                    getString(
                        R.string.log_metadata,
                        meta.firmwareVersion,
                        meta.hasWifi,
                        meta.hasBluetooth
                    )
                )
            }
            fromRadio.hasLogRecord() -> {
                val log = fromRadio.logRecord
                message.append(getString(R.string.log_console_log, log.source, log.message))
            }
            fromRadio.configCompleteId != 0 -> {
                message.append(getString(R.string.log_config_complete, fromRadio.configCompleteId))
                if (pendingQueryRequestId != null && fromRadio.configCompleteId == pendingQueryRequestId) {
                    message.append("\n")
                    message.append(getString(R.string.log_config_complete_summary, nodeInfoCount))
                    pendingQueryRequestId = null
                    if (pendingConfigPhase == 1) {
                        // Phase 1 (config + channels) done — now stream the node DB.
                        pendingConfigPhase = 0
                        sendConfigPhase(2)
                    } else {
                        pendingConfigPhase = 0
                        bleConnectionManager.setConfigDraining(false)
                        bleConnectionManager.disarmStallWatchdog()
                        bleConnectionManager.setHighConnectionPriority(false)
                        runOnUiThread {
                            statusProgress.visibility = android.view.View.GONE
                            statusText.text = getString(R.string.status_ready_transport, transportLabel(), nodeInfoCount)
                            statusText.setTextColor(0xFF32D77B.toInt())
                            Toast.makeText(this, getString(R.string.status_ready_transport, transportLabel(), nodeInfoCount), Toast.LENGTH_SHORT).show()
                            refreshNodesList()
                        }
                        if (lowImpactSwitch.isChecked && !lowImpactApplied) {
                            lowImpactApplied = true
                            applySingleLoraJob(getString(R.string.low_impact_label)) { b ->
                                savedLowImpactLora = b.getLora().toBuilder().build()
                                b.setLora(b.getLora().toBuilder().setHopLimit(1).build())
                            }
                        }
                    }
                }
            }
            fromRadio.rebooted -> {
                message.append(getString(R.string.log_rebooted))
            }
            else -> {
                message.append(getString(R.string.log_unformatted, fromRadio.payloadVariantCase.toString()))
            }
        }
        if (message.isNotEmpty()) {
            appendLog(message.toString())
            val pkt = fromRadio.packet
            if (nodePopupNum != -1 && pkt != null && (pkt.from == nodePopupNum || pkt.to == nodePopupNum)) {
                nodePopupAppend(message.toString())
            }
        }
    }

    /**
     * Formats a NodeInfo entry with the same detail level the Meshtastic CLI
     * `--nodes` table shows: position, battery/voltage, SNR, last heard, hops and
     * favourite/ignored markers.
     */
    private fun formatNodeInfo(nodeInfo: org.meshtastic.proto.MeshProtos.NodeInfo): String {
        val name = if (nodeInfo.hasUser()) nodeInfo.user.longName else getString(R.string.log_unknown_name)
        val pos = nodeInfo.position
        val lat = if (pos.hasLatitudeI()) String.format(Locale.US, "%.5f", pos.latitudeI / 1e7) else getString(R.string.log_telemetry_na)
        val lon = if (pos.hasLongitudeI()) String.format(Locale.US, "%.5f", pos.longitudeI / 1e7) else getString(R.string.log_telemetry_na)
        val alt = if (pos.hasAltitude()) "${pos.altitude} m" else getString(R.string.log_telemetry_na)

        val dm = nodeInfo.deviceMetrics
        val bat = when {
            dm.hasBatteryLevel() && (dm.batteryLevel == 0 || dm.batteryLevel > 100) -> getString(R.string.log_telemetry_powered)
            dm.hasBatteryLevel() -> "${dm.batteryLevel}%"
            else -> getString(R.string.log_telemetry_na)
        }
        val volt = if (dm.hasVoltage()) String.format(Locale.US, "%.2f", dm.voltage) else getString(R.string.log_telemetry_na)
        val snr = String.format(Locale.US, "%.1f", nodeInfo.snr)
        val since = formatSince(nodeInfo.lastHeard.toLong())
        val hops = if (nodeInfo.hasHopsAway()) nodeInfo.hopsAway.toString() else getString(R.string.log_telemetry_na)
        val markers = buildString {
            if (nodeInfo.isFavorite) append("★")
            if (nodeInfo.isIgnored) append("✖")
            if (nodeInfo.viaMqtt) append("☁")
        }
        return getString(
            R.string.log_node_info_full,
            Integer.toHexString(nodeInfo.num), name, lat, lon, alt, bat, volt, snr, since, hops, markers
        )
    }

    /**
     * Formats a unix timestamp as a short relative age (e.g. "5m", "2h", "3d").
     */
    private fun formatSince(lastHeard: Long): String {
        if (lastHeard == 0L) return getString(R.string.log_telemetry_na)
        val diff = ((System.currentTimeMillis() / 1000) - lastHeard).coerceAtLeast(0)
        return when {
            diff < 60 -> "${diff}s"
            diff < 3600 -> "${diff / 60}m"
            diff < 86400 -> "${diff / 3600}h"
            else -> "${diff / 86400}d"
        }
    }

    /**
     * Formats a traceroute response (RouteDiscovery) showing the path out and
     * back, each hop with its name (when known) and ID.
     */
    private fun formatTraceRoute(payload: com.google.protobuf.ByteString, from: Int, to: Int): String {
        return try {
            val rd = org.meshtastic.proto.MeshProtos.RouteDiscovery.parseFrom(payload)
            val towards = rd.routeList.map { nodeLabel(it) }
            val back = rd.routeBackList.map { nodeLabel(it) }
            val src = nodeLabel(from)
            val dst = nodeLabel(to)
            buildString {
                append(getString(R.string.log_traceroute_out, src, towards.joinToString(" -> "), dst))
                if (back.isNotEmpty()) {
                    append("\n")
                    append(getString(R.string.log_traceroute_back, dst, back.joinToString(" -> "), src))
                }
            }
        } catch (e: Exception) {
            getString(R.string.log_traceroute_decode_error)
        }
    }

    /**
     * Returns a node label with name (when known) and ID.
     */
    private fun nodeLabel(num: Int): String {
        val name = synchronized(nodeEntries) { nodeEntries[num]?.name }
        val id = "!${Integer.toHexString(num)}"
        return if (name != null) "$name ($id)" else id
    }

    /**
     * Opens the node info popup: all cached info about the node (emojis, rich
     * text), live-updating while the session receives new NodeInfo/telemetry,
     * plus quick request buttons (user info / telemetry / env / power /
     * position / traceroute). With "Use NavaCLI" checked the requests are sent
     * as /nava DM commands and the replies land in the inline console.
     */
    private fun showNodeInfoPopup(num: Int) {
        nodePopupNum = num
        nodePopupUseNava = false
        nodePopupConsole = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        nodePopupBody = TextView(this).apply {
            textSize = 14f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        refreshNodePopup()

        val consoleScroll = ScrollView(this).apply {
            addView(nodePopupConsole)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
            )
            visibility = if (nodePopupConsole?.text?.isEmpty() == false) android.view.View.VISIBLE else android.view.View.GONE
        }

        val navaCheck = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.node_popup_use_nava)
            textSize = 13f
            setOnCheckedChangeListener { _, checked -> nodePopupUseNava = checked }
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val buttons = mutableListOf<Pair<String, String>>(
            "info" to getString(R.string.node_popup_info),
            "tlm" to getString(R.string.node_popup_tlm),
            "env" to getString(R.string.node_popup_env),
            "power" to getString(R.string.node_popup_power),
            "pos" to getString(R.string.node_popup_pos),
            "trace" to getString(R.string.node_popup_trace),
            "neighbors" to getString(R.string.node_popup_neighbors),
            "signal" to getString(R.string.node_popup_signal),
            "air" to getString(R.string.node_popup_air),
            "host" to getString(R.string.node_popup_host),
            "share" to getString(R.string.node_popup_share)
        )
        // If the node's public key is not known yet (e.g. a card imported without
        // a key, or the key never broadcast), offer a handy request button so the
        // user can trigger the NodeInfo exchange and fill the missing key.
        val entryPubKey = synchronized(nodeEntries) { nodeEntries[num]?.pubKey }
        val hasKey = entryPubKey != null ||
            (synchronized(nodeInfos) { nodeInfos[num] }?.user?.publicKey?.size() ?: 0) > 0
        if (!hasKey) {
            buttons.add("key" to getString(R.string.node_popup_request_key))
        }
        buttons.forEachIndexed { i, (action, label) ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setOnClickListener { nodePopupRequest(action) }
            }
            val helpBody = when (action) {
                "info" -> getString(R.string.node_popup_help_info)
                "tlm" -> getString(R.string.node_popup_help_tlm)
                "env" -> getString(R.string.node_popup_help_env)
                "power" -> getString(R.string.node_popup_help_power)
                "pos" -> getString(R.string.node_popup_help_pos)
                "trace" -> getString(R.string.node_popup_help_trace)
                "neighbors" -> getString(R.string.node_popup_help_neighbors)
                "signal" -> getString(R.string.node_popup_help_signal)
                "air" -> getString(R.string.node_popup_help_air)
                "host" -> getString(R.string.node_popup_help_host)
                "share" -> getString(R.string.node_popup_help_share)
                "key" -> getString(R.string.node_popup_help_request_key)
                else -> getString(R.string.help_generic)
            }
            attachPressHelp(b, helpBody)
            val row = when (i) {
                in 0..3 -> row1
                in 4..7 -> row2
                else -> row3
            }
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(nodePopupBody)
            addView(consoleScroll)
            addView(navaCheck)
            addView(row1)
            addView(row2)
            addView(row3)
        }

        nodePopupDialog = MaterialAlertDialogBuilder(this)
            .setTitle(nodeLabel(num))
            .setView(content)
            .setNegativeButton(R.string.close) { _, _ ->
                nodePopupNum = -1
                nodePopupDialog = null
                nodePopupBody = null
                nodePopupConsole = null
            }
            .setOnDismissListener {
                nodePopupNum = -1
                nodePopupDialog = null
                nodePopupBody = null
                nodePopupConsole = null
            }
            .show()
    }

    private fun refreshNodePopup() {
        val body = nodePopupBody ?: return
        val num = nodePopupNum
        if (num == -1) return
        body.text = buildNodePopupBody(num)
    }

    private fun buildNodePopupBody(num: Int): String {
        val ni = synchronized(nodeInfos) { nodeInfos[num] }
        val entry = synchronized(nodeEntries) { nodeEntries[num] }
        val id = "!${Integer.toHexString(num)}"
        if (ni == null && entry == null) return getString(R.string.node_popup_unknown, id)
        val name = ni?.let { if (it.hasUser()) it.user.longName else null } ?: entry?.name ?: getString(R.string.log_unknown_name)
        val aka = ni?.takeIf { it.hasUser() }?.user?.shortName ?: ""
        val role = ni?.user?.role?.name ?: "UNKNOWN"
        val roleEmoji = nodeRoleEmoji(role)
        val hw = ni?.user?.hwModel?.name ?: ""
        val hwEmoji = nodeHwEmoji(hw)
        val star = if (entry?.isFavorite == true) " ⭐" else ""
        val pos = ni?.position
        val lat = pos?.takeIf { it.hasLatitudeI() }?.let { String.format(Locale.US, "%.5f", it.latitudeI / 1e7) } ?: getString(R.string.log_telemetry_na)
        val lon = pos?.takeIf { it.hasLongitudeI() }?.let { String.format(Locale.US, "%.5f", it.longitudeI / 1e7) } ?: getString(R.string.log_telemetry_na)
        val alt = pos?.takeIf { it.hasAltitude() }?.let { "${it.altitude} m" } ?: getString(R.string.log_telemetry_na)
        val dm = ni?.deviceMetrics
        val bat = when {
            dm == null || !dm.hasBatteryLevel() -> getString(R.string.log_telemetry_na)
            dm.batteryLevel == 0 || dm.batteryLevel > 100 -> getString(R.string.node_popup_powered)
            else -> "\uD83D\uDD0B ${dm.batteryLevel}%"
        }
        val volt = dm?.takeIf { it.hasVoltage() }?.let { String.format(Locale.US, "%.2f V", it.voltage) } ?: getString(R.string.log_telemetry_na)
        val snr = entry?.let { String.format(Locale.US, "%.1f dB", it.snr) } ?: getString(R.string.log_telemetry_na)
        val since = ni?.lastHeard?.toLong()?.let { formatSince(it) } ?: getString(R.string.log_telemetry_na)
        val hops = entry?.hops?.takeIf { it >= 0 }?.toString() ?: getString(R.string.log_telemetry_na)
        val hasPubKey = (ni?.user?.publicKey?.size() ?: 0) > 0
        val pubkey = when {
            hasPubKey -> android.util.Base64.encodeToString(ni!!.user.publicKey.toByteArray(), android.util.Base64.NO_WRAP)
            entry?.pubKey != null -> entry.pubKey!!
            else -> getString(R.string.node_popup_no_key)
        }
        val chUtil = dm?.takeIf { it.hasChannelUtilization() }?.let { String.format(Locale.US, "%.1f%%", it.channelUtilization) } ?: ""
        val airUtil = dm?.takeIf { it.hasAirUtilTx() }?.let { String.format(Locale.US, "%.2f%%", it.airUtilTx) } ?: ""
        return buildString {
            append("$roleEmoji $role$star\n")
            append("\uD83D\uDCDB $name\n")
            if (aka.isNotEmpty()) append("🔔 $aka · $id\n")
            if (hw.isNotEmpty()) append("\uD83D\uDD27 $hw $hwEmoji\n")
            append("📍 $lat, $lon · $alt\n")
            append("$bat · $volt\n")
            append("📶 SNR $snr · 🎯 $hops saltos\n")
            append("🕐 último: $since\n")
            append("\uD83D\uDDDD $pubkey\n")
            if (chUtil.isNotEmpty()) append("📡 canal $chUtil · TX $airUtil\n")
        }
    }

    private fun nodeRoleEmoji(role: String): String = when {
        role.contains("ROUTER") -> "📡"
        role.contains("REPEATER") -> "\uD83D\uDD01"
        role.contains("CLIENT_MUTE") -> "\uD83D\uDD07"
        role.contains("CLIENT") -> "\uD83D\uDCF1"
        role.contains("SENSOR") -> "\uD83C\uDF21"
        else -> "\uD83D\uDD18"
    }

    private fun nodeHwEmoji(hw: String): String = when {
        hw.contains("NRF52") -> "\uD83E\uDDE0"
        hw.contains("ESP32") -> "🖥"
        hw.contains("HELTEC") -> "\uD83D\uDEF0"
        hw.contains("TBEAM") || hw.contains("RAK") -> "\uD83C\uDF10"
        hw.contains("SOLAR") -> "☀"
        else -> ""
    }

    /**
     * Inline console for the node popup: mirrors decoded traffic involving the
     * popup node (its broadcasts + replies to our requests).
     */
    private fun nodePopupAppend(line: String) {
        val tv = nodePopupConsole ?: return
        runOnUiThread {
            tv.append(line + "\n")
            tv.visibility = android.view.View.VISIBLE
            (tv.parent as? android.view.View)?.visibility = android.view.View.VISIBLE
            (tv.parent as? ScrollView)?.post { (tv.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * Sends a request to the popup node: standard protobuf when "Use NavaCLI"
     * is off, or the matching /nava DM command when it is on.
     */
    private fun nodePopupRequest(action: String) {
        val num = nodePopupNum
        if (num == -1 || !isReady()) return
        if (action == "share") {
            shareNodeCard(num)
            return
        }
        if (nodePopupUseNava) {
            val cmdText = when (action) {
                "info" -> "/nava nodeinfo"
                "key" -> "/nava nodeinfo"
                "tlm" -> "/nava sendtel"
                "env" -> "/nava env"
                "power" -> "/nava power"
                "pos" -> "/nava pos"
                "trace" -> "/nava trace !${Integer.toHexString(num)}"
                else -> return
            }
            val packet = MeshPacketBuilder.buildTextPacket(cmdText, num, 0, pkiEncrypted = true)
            val bytes = sendToRadio(packet)
            nodePopupAppend(if (bytes != null) "▶ $cmdText (DM)" else "✖ fallo envío DM")
        } else {
            val packet = when (action) {
                "tlm" -> MeshPacketBuilder.buildRequestTelemetryPacket(num)
                "env" -> MeshPacketBuilder.buildRequestTelemetryTypePacket(num, "env")
                "power" -> MeshPacketBuilder.buildRequestTelemetryTypePacket(num, "power")
                "signal" -> MeshPacketBuilder.buildRequestTelemetryTypePacket(num, "signal")
                "air" -> MeshPacketBuilder.buildRequestTelemetryTypePacket(num, "air")
                "host" -> MeshPacketBuilder.buildRequestTelemetryTypePacket(num, "host")
                "pos" -> MeshPacketBuilder.buildRequestPositionPacket(num)
                "trace" -> MeshPacketBuilder.buildTraceRoutePacket(num)
                "neighbors" -> MeshPacketBuilder.buildRequestNeighborInfoPacket(num)
                else -> null
            }
            if (packet != null) {
                val bytes = sendToRadio(packet)
                nodePopupAppend(if (bytes != null) "▶ petición $action → ${Integer.toHexString(num)}" else "✖ fallo envío $action")
            } else if (action == "info" || action == "key") {
                // A DM to an unknown node triggers the NodeInfo exchange, which
                // also brings the public key if it was missing.
                val p = MeshPacketBuilder.buildTextPacket("info", num, 0)
                val bytes = sendToRadio(p)
                nodePopupAppend(if (bytes != null) "▶ intercambio de info (DM) → ${Integer.toHexString(num)}" else "✖ fallo envío info")
            }
        }
    }

    /**
     * Generates the node's business-card URL (SharedContact proto, base64 URL-safe
     * in the fragment, same format the official app uses) and copies it to the
     * clipboard.
     */
    private fun shareNodeCard(num: Int) {
        val user = synchronized(nodeInfos) { nodeInfos[num] }?.user
        val contact = org.meshtastic.proto.AdminProtos.SharedContact.newBuilder()
            .setNodeNum(num)
            .apply { if (user != null) setUser(user) }
            .build()
        val b64 = android.util.Base64.encodeToString(contact.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            .replace("=", "")
        val url = "https://meshtastic.org/v/#$b64"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MeshNavarra node card", url))
        nodePopupAppend("▶ tarjeta copiada: $url")
        Toast.makeText(this, getString(R.string.nodes_share_copied), Toast.LENGTH_SHORT).show()
    }

    /**
     * Decodes a POSITION_APP payload into lat/lon/altitude.
     */
    private fun formatPosition(payload: com.google.protobuf.ByteString): String {
        return try {
            val pos = org.meshtastic.proto.MeshProtos.Position.parseFrom(payload)
            val lat = if (pos.hasLatitudeI()) String.format(Locale.US, "%.5f", pos.latitudeI / 1e7) else getString(R.string.log_telemetry_na)
            val lon = if (pos.hasLongitudeI()) String.format(Locale.US, "%.5f", pos.longitudeI / 1e7) else getString(R.string.log_telemetry_na)
            val alt = if (pos.hasAltitude()) "${pos.altitude} m" else getString(R.string.log_telemetry_na)
            getString(R.string.log_position, lat, lon, alt)
        } catch (e: Exception) {
            getString(R.string.log_position_decode_error)
        }
    }

    /**
     * Decodes a TELEMETRY_APP payload and formats the device metrics like the
     * Meshtastic CLI does (battery, voltage, channel utilization, air-util, uptime).
     */
    private fun formatTelemetry(payload: com.google.protobuf.ByteString): String {
        return try {
            val telemetry = TelemetryProtos.Telemetry.parseFrom(payload)
            when {
                telemetry.hasDeviceMetrics() -> {
                    val dm = telemetry.deviceMetrics
                    val battery = when {
                        dm.hasBatteryLevel() && (dm.batteryLevel == 0 || dm.batteryLevel > 100) -> getString(R.string.log_telemetry_powered)
                        dm.hasBatteryLevel() -> "${dm.batteryLevel}%"
                        else -> getString(R.string.log_telemetry_na)
                    }
                    val voltage = if (dm.hasVoltage()) String.format(Locale.US, "%.2f", dm.voltage) else getString(R.string.log_telemetry_na)
                    val chUtil = if (dm.hasChannelUtilization()) String.format(Locale.US, "%.2f%%", dm.channelUtilization) else getString(R.string.log_telemetry_na)
                    val airUtil = if (dm.hasAirUtilTx()) String.format(Locale.US, "%.2f%%", dm.airUtilTx) else getString(R.string.log_telemetry_na)
                    val uptime = dm.uptimeSeconds
                    getString(R.string.log_telemetry, battery, voltage, chUtil, airUtil, uptime)
                }
                telemetry.hasEnvironmentMetrics() -> {
                    val em = telemetry.environmentMetrics
                    getString(
                        R.string.log_telemetry_env,
                        if (em.hasTemperature()) String.format(Locale.US, "%.1f", em.temperature) else getString(R.string.log_telemetry_na),
                        if (em.hasRelativeHumidity()) String.format(Locale.US, "%.1f", em.relativeHumidity) else getString(R.string.log_telemetry_na),
                        if (em.hasBarometricPressure()) String.format(Locale.US, "%.1f", em.barometricPressure) else getString(R.string.log_telemetry_na)
                    )
                }
                telemetry.hasPowerMetrics() -> {
                    val pm = telemetry.powerMetrics
                    getString(
                        R.string.log_telemetry_power,
                        if (pm.hasCh1Voltage()) String.format(Locale.US, "%.3f", pm.ch1Voltage) else getString(R.string.log_telemetry_na),
                        if (pm.hasCh2Voltage()) String.format(Locale.US, "%.3f", pm.ch2Voltage) else getString(R.string.log_telemetry_na),
                        if (pm.hasCh3Voltage()) String.format(Locale.US, "%.3f", pm.ch3Voltage) else getString(R.string.log_telemetry_na)
                    )
                }
                telemetry.hasAirQualityMetrics() -> {
                    val aq = telemetry.airQualityMetrics
                    getString(
                        R.string.log_telemetry_air,
                        if (aq.hasPm10Standard()) aq.pm10Standard.toString() else getString(R.string.log_telemetry_na),
                        if (aq.hasPm25Standard()) aq.pm25Standard.toString() else getString(R.string.log_telemetry_na),
                        if (aq.hasPm100Standard()) aq.pm100Standard.toString() else getString(R.string.log_telemetry_na)
                    )
                }
                telemetry.hasLocalStats() -> {
                    val ls = telemetry.localStats
                    getString(
                        R.string.log_telemetry_signal,
                        ls.numPacketsRx.toString(),
                        ls.numPacketsTx.toString(),
                        String.format(Locale.US, "%.2f", ls.airUtilTx),
                        String.format(Locale.US, "%.2f", ls.channelUtilization)
                    )
                }
                telemetry.hasHostMetrics() -> {
                    val hm = telemetry.hostMetrics
                    val freeMb = String.format(Locale.US, "%.1f", hm.freememBytes / 1048576.0) + " MB"
                    getString(R.string.log_telemetry_host, freeMb, "${hm.uptimeSeconds}s")
                }
                else -> getString(R.string.log_telemetry_raw, telemetry.variantCase.toString())
            }
        } catch (e: Exception) {
            getString(R.string.log_telemetry_decode_error)
        }
    }

    /**
     * Decodes a NEIGHBORINFO_APP payload into a readable neighbor list.
     */
    private fun formatNeighbors(payload: com.google.protobuf.ByteString): String {
        return try {
            val ni = org.meshtastic.proto.MeshProtos.NeighborInfo.parseFrom(payload)
            val names = ni.neighborsList.joinToString(", ") { n ->
                nodeLabel(n.nodeId)
            }
            getString(R.string.log_telemetry_neighbors, ni.neighborsList.size, names)
        } catch (e: Exception) {
            getString(R.string.log_telemetry_decode_error)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        RemoteControlReceiver.handler = null
        appInBackground = true
        cancelReconnect()
        usbConnectionManager.destroy()
        bleConnectionManager.destroy()
    }

    override fun onStart() {
        super.onStart()
        appInBackground = false
        // Returning from background: resume the node link we released on onStop.
        if (everConnected && !userInitiatedDisconnect && !demoMode) {
            if (bleTransportActive) {
                if (bleConnectionManager.reconnect()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (bleConnectionManager.isConnected()) sendWantConfig()
                    }, 1500)
                }
            } else {
                reconnectUsbDiscover()
            }
        } else if (!everConnected && !userInitiatedDisconnect && !demoMode) {
            // App launch: if an OTG node is already present, connect automatically.
            maybeAutoConnectUsb()
        }
    }

    override fun onStop() {
        // Release the node so other apps (e.g. the official Meshtastic app in
        // the background) can grab it over USB/BLE while we are not in front.
        appInBackground = true
        cancelReconnect()
        stopAuditBattery()
        auditDialog?.dismiss()
        if (usbConnectionManager.isConnected()) {
            usbConnectionManager.disconnect()
        } else if (bleConnectionManager.isConnected()) {
            bleConnectionManager.disconnect()
        }
        dismissHelpBubble()
        lastNodeCacheSave = 0L
        maybeSaveNodeCache()
        super.onStop()
    }

    // --- UsbConnectionManager.ConnectionListener Callback Implementation ---

    override fun onDeviceAttached(device: UsbDevice) {
        appendLog(getString(R.string.log_usb_attached, device.deviceName))
        maybeAutoConnectUsb(device)
    }

    override fun onDeviceDetached(device: UsbDevice) {
        appendLog(getString(R.string.log_usb_detached, device.deviceName))
    }

    override fun onPermissionGranted(device: UsbDevice) {
        usbPermPending = false
        appendLog(getString(R.string.log_permission_granted, device.deviceName))
        connectToDevice(device)
    }

    override fun onPermissionDenied(device: UsbDevice) {
        usbPermPending = false
        appendLog(getString(R.string.log_permission_denied, device.deviceName))
        val errorMsg = getString(R.string.usb_permission_denied)
        Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
    }

    override fun onConnected() {
        runOnUiThread {
            everConnected = true
            userInitiatedDisconnect = false
            usbPermRequestedThisCycle = false
            reconnectAttempts = 0
            cancelReconnect()
            statusText.text = getString(R.string.status_connected_transport, transportLabel())
            statusText.setTextColor(0xFF32D77B.toInt())
            appendLog(getString(R.string.log_status_connected))
            if (bleTransportActive) {
                // BLE has no auto-download in the connect flow: request the NodeDB.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (bleConnectionManager.isConnected()) {
                        appendLog(getString(R.string.log_query_auto))
                        sendWantConfig()
                    }
                }, 1500)
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            statusText.text = getString(R.string.status_disconnected)
            statusText.setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            statusProgress.visibility = android.view.View.GONE
            appendLog(getString(R.string.log_status_disconnected))
            streamApiUnframer.reset()
            scheduleReconnect()
        }
    }

    private fun transportLabel(): String =
        getString(if (bleTransportActive) R.string.conn_transport_bt else R.string.conn_transport_usb)

    /**
     * Moves the top area (app header + status card) into the currently selected
     * panel's scrollable content, so it scrolls away with the rest of the tab.
     */
    private fun applyTabVisibility(pos: Int) {
        bpPanel.visibility = if (pos == 0) android.view.View.VISIBLE else android.view.View.GONE
        commandsPanel.visibility = if (pos == 1) android.view.View.VISIBLE else android.view.View.GONE
        adminPanel.visibility = if (pos == 2) android.view.View.VISIBLE else android.view.View.GONE
        navaPanel.visibility = if (pos == 3) android.view.View.VISIBLE else android.view.View.GONE
        chatPanel.visibility = if (pos == 4) android.view.View.VISIBLE else android.view.View.GONE
        nodesPanel.visibility = if (pos == 5) android.view.View.VISIBLE else android.view.View.GONE
        logPanel.visibility = if (pos == 6) android.view.View.VISIBLE else android.view.View.GONE
        debugPanel.visibility = if (pos == 7) android.view.View.VISIBLE else android.view.View.GONE
        attachHeaderToCurrentPanel()
    }

    private fun attachHeaderToCurrentPanel() {
        val pos = bottomTabs.selectedTabPosition
        if (pos < 0) {
            appendLog("HEADER: pos<0 -> select(0)")
            bottomTabs.getTabAt(0)?.select()
            return
        }
        val navaControlsContent =
            findViewById<ScrollView>(R.id.navaControlsScroll)?.getChildAt(0) as? ViewGroup
        val chatContent = chatScroll.getChildAt(0) as? ViewGroup
        val containers = mapOf(
            0 to (bpPanel.getChildAt(0) as? ViewGroup),
            1 to (commandsPanel.getChildAt(0) as? ViewGroup),
            2 to (adminPanel.getChildAt(0) as? ViewGroup),
            3 to navaControlsContent,
            4 to chatContent,
            5 to (nodesPanel.getChildAt(0) as? ViewGroup),
            6 to (logPanel.getChildAt(0) as? ViewGroup),
            7 to (debugPanel.getChildAt(0) as? ViewGroup)
        )
        val target = containers[pos] ?: return
        if (topArea.parent === target) return
        (topArea.parent as? ViewGroup)?.removeView(topArea)
        target.addView(topArea, 0)
        appendLog("HEADER: pos=$pos -> ${target.javaClass.simpleName} (panelVisible=${(target.parent as? android.view.View)?.visibility})")
    }

    /** One target node for the whole app: every target field mirrors the others. */
    private fun syncTargetInputs(value: String) {
        if (syncingTarget) return
        syncingTarget = true
        if (targetNodeInput.text?.toString() != value) targetNodeInput.setText(value)
        if (cmdTargetInput.text?.toString() != value) cmdTargetInput.setText(value)
        if (bpTargetInput.text?.toString() != value) bpTargetInput.setText(value)
        if (navaTargetInput.text?.toString() != value) navaTargetInput.setText(value)
        syncingTarget = false
    }

    /**
     * Schedules the next auto-reconnect attempt. Only fires when a real
     * connection existed, the user did not initiate the disconnect and we are
     * not in demo mode. Stops after RECONNECT_MAX_ATTEMPTS.
     */
    private fun scheduleReconnect() {
        if (!everConnected || userInitiatedDisconnect || demoMode || appInBackground) return
        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            statusText.text = getString(R.string.status_reconnect_exhausted, transportLabel())
            appendLog(getString(R.string.log_reconnect_exhausted, transportLabel()))
            reconnectAttempts = 0
            return
        }
        reconnectAttempts++
        statusText.text = getString(R.string.status_reconnecting, transportLabel(), reconnectAttempts, RECONNECT_MAX_ATTEMPTS)
        appendLog(getString(R.string.log_reconnecting, transportLabel(), reconnectAttempts, RECONNECT_MAX_ATTEMPTS))
        reconnectHandler.postDelayed({ doReconnect() }, RECONNECT_DELAY_MS)
    }

    private fun doReconnect() {
        if (!everConnected || userInitiatedDisconnect || demoMode || appInBackground) {
            cancelReconnect()
            return
        }
        val ok = if (bleTransportActive) {
            bleConnectionManager.reconnect()
        } else {
            usbConnectionManager.reconnect() || reconnectUsbDiscover()
        }
        appendLog(if (ok) getString(R.string.log_reconnect_attempted, transportLabel()) else getString(R.string.log_reconnect_failed, transportLabel()))
    }

    /**
     * USB fallback for auto-reconnect: a node reboot re-enumerates the bus, so
     * the remembered device path can go stale. If no permitted device matches,
     * rediscover the bus and try the first available node; request USB
     * permission at most once per reconnect cycle to avoid dialog spam.
     */
    private fun reconnectUsbDiscover(): Boolean {
        val devices = usbConnectionManager.discoverDevices()
        val permitted = devices.firstOrNull { usbConnectionManager.hasPermission(it) }
        if (permitted != null) {
            connectToDevice(permitted)
            return true
        }
        val candidate = devices.firstOrNull()
        if (candidate != null && !usbConnectionManager.hasPermission(candidate) && !usbPermRequestedThisCycle) {
            usbPermRequestedThisCycle = true
            usbConnectionManager.requestPermission(candidate)
        }
        return false
    }

    /**
     * Auto-connects to a USB-serial node when one is present: on app launch
     * (no preferred device) and on USB attach while the app is in the foreground.
     * Connects directly if permission is already granted, otherwise requests it
     * once (the onPermissionGranted flow completes the connect).
     */
    private fun maybeAutoConnectUsb(preferred: UsbDevice? = null) {
        if (demoMode || appInBackground || bleTransportActive || usbConnectionManager.isConnected() ||
            userInitiatedDisconnect || usbPermPending || usbPermRequestedThisCycle
        ) return
        val candidates = preferred?.let { listOf(it) } ?: usbConnectionManager.discoverDevices()
        if (candidates.isEmpty()) return
        appendLog(getString(R.string.log_auto_connect))
        val permitted = candidates.firstOrNull { usbConnectionManager.hasPermission(it) }
        if (permitted != null) {
            connectToDevice(permitted)
            return
        }
        val candidate = candidates.firstOrNull()
        if (candidate != null) {
            usbPermPending = true
            usbConnectionManager.requestPermission(candidate)
        }
    }

    private fun cancelReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
    }

    override fun onDataReceived(data: ByteArray) {
        if (bleTransportActive) {
            // BLE delivers one raw FromRadio protobuf per read (no framing).
            try {
                handleFromRadio(FromRadio.parseFrom(data))
            } catch (e: Exception) {
                appendLog(getString(R.string.log_decoding_error, e.message))
            }
        } else {
            // Feed raw fragmented serial data chunks straight into the state-machine
            streamApiUnframer.addBytes(data)
        }
    }

    override fun onError(exception: Exception) {
        appendLog(getString(R.string.log_error, exception.localizedMessage))
        runOnUiThread {
            Toast.makeText(
                this,
                getString(R.string.log_error, exception.localizedMessage),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/**
 * First free secondary channel slot (1-7) for the given occupied set, or -1 if
 * all 8 slots (0-7) are used. Slot 0 is the primary channel and is never offered.
 */
internal fun firstFreeChannelSlotFor(occupied: Set<Int>): Int {
    for (i in 1..7) if (i !in occupied) return i
    return -1
}
