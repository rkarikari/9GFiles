package com.radiozport.ninegfiles.ui.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Wi-Fi Direct send/receive screen — fully bidirectional, no fixed roles.
 *
 * Architecture:
 *  - GO opens a ServerSocket and accepts the client connection.
 *  - Client dials the GO's known IP.
 *  - Both devices get a live Socket and start an always-on receive loop.
 *  - EITHER device can send files at any time via sendFile().
 *    The peer's receive loop picks it up automatically.
 *
 * Status messages:
 *  "Connected as GO.  Ready to send/receive..."
 *  "Connected as Client.  Ready to send/receive..."
 *
 * Protocol (binary framing over TCP port SERVER_PORT):
 *   [4-byte big-endian filename length] [filename UTF-8 bytes]
 *   [8-byte big-endian file size]       [file bytes]
 *   ... repeated per file ...
 *
 * Bug fixes retained from original audit:
 *  Bug 1 - Channel leak            -> channel.close() in onDestroyView() (API 27+)
 *  Bug 2 - Stale group / BUSY      -> removeGroup() in onDestroyView() + cycle start
 *  Bug 3 - Concurrent discovery    -> stopPeerDiscovery() -> discoverPeers() chain
 *  Bug 4 - Random GO role          -> groupOwnerIntent left at default (-1); role-free
 *  Bug 5 - Missed broadcast        -> requestConnectionInfo() replay in onResume()
 *  Bug 6 - No state sync on resume -> same requestConnectionInfo() call
 *  Bug 7 - Discovery expires       -> 30-s postDelayed retry
 *  Bug 8 - THIS_DEVICE silent drop -> explicit handler registered
 *  Bug 9 - BUSY not retried        -> 3-s postDelayed retry on BUSY/ERROR
 */
class WifiDirectFragment : Fragment() {

    companion object {
        private const val TAG                   = "WifiDirect"
        private const val SERVER_PORT           = 8988
        private const val BUFFER_SIZE           = 65_536
        private const val CONNECT_TIMEOUT_MS    = 10_000
        private const val DISCOVERY_RETRY_MS    = 30_000L
        private const val CONNECT_BUSY_RETRY_MS = 3_000L
    }

    // Wi-Fi P2P
    private lateinit var wifiP2pManager: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null  // Bug 1: nullable, closed in onDestroyView

    // Connection state
    private var isConnected       = false
    private var isGroupOwner      = false
    private var groupOwnerAddress: String? = null
    private var pendingConnectConfig: WifiP2pConfig? = null  // Bug 9: retained for retry

    // Bidirectional TCP — single persistent socket shared by send and receive
    @Volatile private var activeSocket:   Socket?      = null
    private            var serverSocket:  ServerSocket? = null
    private            var tcpThread:     Thread?       = null
    private            var receiveThread: Thread?       = null

    // Cancel flag for in-progress send (Thread-based — volatile ensures visibility)
    @Volatile private var cancelTransfer: Boolean = false

    // File selection
    private var selectedFileUri: Uri? = null

    // Handlers
    private val handler                = Handler(Looper.getMainLooper())
    private val discoveryRetryRunnable = Runnable { startDiscoveryCycle() }             // Bug 7
    private val connectRetryRunnable   = Runnable { pendingConnectConfig?.let { connectToPeer(it) } }  // Bug 9

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)  // Bug 8
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                  WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "Wi-Fi Direct enabled=$enabled")
                    if (!enabled) updateStatus("Wi-Fi Direct is off — enable it in Settings")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                        else @Suppress("DEPRECATION")
                            intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                    if (networkInfo?.isConnected == true) {
                        channel?.let { ch ->
                            wifiP2pManager.requestConnectionInfo(ch) { info -> onConnectionInfoAvailable(info) }
                        }
                    } else {
                        isConnected       = false
                        groupOwnerAddress = null
                        closeConnection()
                        updateStatus("Disconnected")
                        updateSendButton()
                    }
                }

                // Bug 8: was silently dropped before
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        else @Suppress("DEPRECATION")
                            intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Log.d(TAG, "Own device ready: name=${device?.deviceName} status=${device?.status}")
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedFileUri = uri
            updateStatus("File selected — connect to a peer and tap Send")
            updateSendButton()
        }
    }

    private val discoveredPeers = mutableListOf<WifiP2pDevice>()
    private val peerListAdapter by lazy {
        ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_activated_1)
    }

    // UI refs (programmatic layout — no separate XML)
    private var tvStatus:        TextView?     = null
    private var lvPeers:         ListView?     = null
    private var btnDiscover:     Button?       = null
    private var btnPickFile:     Button?       = null
    private var btnSend:         Button?       = null
    private var progressTransfer: android.widget.ProgressBar? = null
    private var tvTransferFileName: TextView?  = null
    private var tvTransferPercent:  TextView?  = null
    private var tvTransferBytes:    TextView?  = null
    private var tvTransferSpeed:    TextView?  = null
    private var btnCancelTransfer:  Button?    = null
    private var cardTransferProgress: View?    = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        buildUi(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wifiP2pManager = requireContext().getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager.initialize(requireContext(), Looper.getMainLooper(), null)  // Bug 1

        btnDiscover?.setOnClickListener { startDiscoveryCycle() }
        btnPickFile?.setOnClickListener { filePickerLauncher.launch("*/*") }
        btnSend?.setOnClickListener     { sendFile() }

        lvPeers?.setOnItemClickListener { _, _, position, _ ->
            discoveredPeers.getOrNull(position)?.let { connectToDevice(it) }
        }

        updateSendButton()
        updateStatus("Tap 'Discover' to scan for nearby devices")
    }

    // Bug 5 & 6: register receiver + replay any missed connection broadcast
    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(p2pReceiver, intentFilter)
        channel?.let { ch ->
            wifiP2pManager.requestConnectionInfo(ch) { info -> onConnectionInfoAvailable(info) }
        }
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(p2pReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(discoveryRetryRunnable)
        handler.removeCallbacks(connectRetryRunnable)
        closeConnection()

        channel?.let { ch ->
            // Bug 2: removeGroup so no stale group causes BUSY next session
            wifiP2pManager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "removeGroup on destroy: ok") }
                override fun onFailure(r: Int) { Log.d(TAG, "removeGroup on destroy: reason=$r") }
            })
            // Bug 1: close channel (API 27+, minSdk 26)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) ch.close()
        }
        channel = null

        tvStatus = null; lvPeers = null; btnDiscover = null; btnPickFile = null; btnSend = null
        progressTransfer = null; tvTransferFileName = null; tvTransferPercent = null
        tvTransferBytes = null; tvTransferSpeed = null; btnCancelTransfer = null
        cardTransferProgress = null
    }

    // =========================================================================
    // Discovery — atomic 3-step chain (Bugs 2, 3, 7)
    // =========================================================================

    private fun startDiscoveryCycle() {
        if (isConnected) return
        val ch = channel ?: return
        updateStatus("Preparing discovery…")
        discoveredPeers.clear(); peerListAdapter.clear()
        handler.removeCallbacks(discoveryRetryRunnable)

        // Step 1 — Bug 2: clear any stale group first
        wifiP2pManager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { stopThenDiscover(ch) }
            override fun onFailure(r: Int) { Log.d(TAG, "removeGroup pre-discovery: $r"); stopThenDiscover(ch) }
        })
    }

    // Step 2 — Bug 3: stop any running scan before starting a new one
    private fun stopThenDiscover(ch: WifiP2pManager.Channel) {
        wifiP2pManager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { doDiscoverPeers(ch) }
            override fun onFailure(r: Int) { Log.d(TAG, "stopPeerDiscovery: $r"); doDiscoverPeers(ch) }
        })
    }

    // Step 3 — start scan + arm 30-s retry (Bug 7)
    private fun doDiscoverPeers(ch: WifiP2pManager.Channel) {
        if (!hasRequiredPermission()) { updateStatus("Location / Nearby-Wi-Fi permission required"); return }
        wifiP2pManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateStatus("Scanning for peers…")
                handler.postDelayed(discoveryRetryRunnable, DISCOVERY_RETRY_MS)
            }
            override fun onFailure(r: Int) {
                Log.w(TAG, "discoverPeers failed: $r")
                updateStatus("Scan failed (reason=$r) — tap Discover to retry")
            }
        })
    }

    private fun requestPeers() {
        val ch = channel ?: return
        if (!hasRequiredPermission()) return
        wifiP2pManager.requestPeers(ch) { peerList ->
            discoveredPeers.clear(); discoveredPeers.addAll(peerList.deviceList)
            peerListAdapter.clear()
            if (discoveredPeers.isEmpty()) peerListAdapter.add("No peers found – searching…")
            else discoveredPeers.forEach { peerListAdapter.add(it.deviceName) }
            peerListAdapter.notifyDataSetChanged()
        }
    }

    // =========================================================================
    // Connection (Bugs 4, 9)
    // =========================================================================

    private fun connectToDevice(device: WifiP2pDevice) {
        // groupOwnerIntent left at default (-1 = no preference).
        // Transfer direction is NOT tied to GO role — both sides receive automatically.
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        pendingConnectConfig = config
        connectToPeer(config)
    }

    private fun connectToPeer(config: WifiP2pConfig) {
        val ch = channel ?: return
        updateStatus("Connecting to ${config.deviceAddress}…")
        wifiP2pManager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "connect() issued — awaiting broadcast") }
            override fun onFailure(reason: Int) {
                // Bug 9: BUSY/ERROR are transient — retry after 3 s
                val rs = when (reason) {
                    WifiP2pManager.BUSY            -> "BUSY"
                    WifiP2pManager.ERROR           -> "ERROR"
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    else                           -> "reason=$reason"
                }
                Log.w(TAG, "connect() failed: $rs — retrying")
                updateStatus("Connect failed ($rs) — retrying…")
                handler.removeCallbacks(connectRetryRunnable)
                handler.postDelayed(connectRetryRunnable, CONNECT_BUSY_RETRY_MS)
            }
        })
    }

    /**
     * Called from CONNECTION_CHANGED broadcast AND onResume() (Bugs 5 & 6).
     * Both GO and Client establish a socket and start an always-on receive loop.
     * Both can also call sendFile() at any time — direction is not predetermined.
     */
    private fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            isConnected = false; groupOwnerAddress = null
            updateSendButton(); return
        }
        isConnected       = true
        isGroupOwner      = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress

        handler.removeCallbacks(discoveryRetryRunnable)
        handler.removeCallbacks(connectRetryRunnable)
        updateSendButton()

        if (activeSocket != null) return  // TCP already open — idempotent

        if (isGroupOwner) openTcpServer()
        else              dialTcpServer(groupOwnerAddress ?: return)
    }

    // =========================================================================
    // TCP — bidirectional, role-free
    // =========================================================================

    /** GO: accept the client then start the always-on receive loop. */
    private fun openTcpServer() {
        if (tcpThread?.isAlive == true) return
        tcpThread = Thread {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(SERVER_PORT)) }
            serverSocket = ss
            try {
                activity?.runOnUiThread { updateStatus("Connected as GO.  Ready to send/receive...") }
                val sock = ss.accept()
                sock.soTimeout = 0
                activeSocket = sock
                activity?.runOnUiThread { updateSendButton() }
                startReceiveLoop(sock)
            } catch (_: SocketException) {
            } catch (e: Exception) {
                Log.e(TAG, "TCP server error", e)
                activity?.runOnUiThread { updateStatus("Server error: ${e.message}") }
            } finally {
                ss.closeQuietly(); serverSocket = null
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Client: dial GO then start the always-on receive loop. */
    private fun dialTcpServer(ip: String) {
        if (tcpThread?.isAlive == true) return
        tcpThread = Thread {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, SERVER_PORT), CONNECT_TIMEOUT_MS)
                sock.soTimeout = 0
                activeSocket = sock
                activity?.runOnUiThread {
                    updateStatus("Connected as Client.  Ready to send/receive...")
                    updateSendButton()
                }
                startReceiveLoop(sock)
            } catch (e: Exception) {
                Log.e(TAG, "Dial error", e)
                activity?.runOnUiThread { updateStatus("Connect error: ${e.message}") }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * Always-on background reader — runs on BOTH devices for the lifetime of the connection.
     * Protocol: [4B nameLen][name bytes][8B fileLen][file bytes]
     */
    private fun startReceiveLoop(socket: Socket) {
        receiveThread = Thread {
            val din     = DataInputStream(socket.getInputStream())
            val destDir = File(Environment.getExternalStorageDirectory(), "received")
                .also { if (!it.exists()) it.mkdirs() }
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val nameLen = din.readInt()
                    if (nameLen <= 0) continue
                    val name    = String(ByteArray(nameLen).also { din.readFully(it) }, Charsets.UTF_8)
                    val fileLen = din.readLong()

                    cancelTransfer = false
                    showTransferCard(true)
                    updateTransferProgress(name, "Receiving", 0L, fileLen, 0, "Starting…")

                    val dest    = File(destDir, name)
                    val startMs = System.currentTimeMillis()
                    dest.outputStream().use { out ->
                        val buf         = ByteArray(BUFFER_SIZE)
                        var remaining   = fileLen
                        var received    = 0L
                        var lastPercent = -1
                        while (remaining > 0 && !cancelTransfer) {
                            val n = din.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) return@Thread
                            out.write(buf, 0, n)
                            remaining -= n
                            received  += n
                            val percent    = if (fileLen > 0) (received * 100 / fileLen).toInt().coerceIn(0, 100) else 0
                            if (percent != lastPercent) {
                                lastPercent    = percent
                                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                                val speed      = if (elapsedSec > 0.5) (received / elapsedSec).toLong() else 0L
                                val etaSec     = if (speed > 0) (fileLen - received) / speed else -1L
                                val speedStr   = if (speed > 0)
                                    "${formatSpeed(speed)}  ·  ETA ${formatEta(etaSec)}" else "Calculating…"
                                updateTransferProgress(name, "Receiving", received, fileLen, percent, speedStr)
                            }
                        }
                    }
                    if (cancelTransfer) {
                        dest.delete()          // remove partial file
                        cancelTransfer = false
                        continue
                    }
                    val kb = fileLen / 1024
                    activity?.runOnUiThread {
                        showTransferCard(false)
                        updateStatus("Received: $name ($kb KB)")
                        showSnack("File received -> $name")
                    }
                }
            } catch (_: SocketException) {
            } catch (e: Exception) {
                Log.d(TAG, "Receive loop ended: ${e.message}")
            } finally {
                socket.closeQuietly(); activeSocket = null
                activity?.runOnUiThread {
                    showTransferCard(false)
                    isConnected = false
                    updateStatus("Disconnected")
                    updateSendButton()
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun closeConnection() {
        tcpThread?.interrupt();     tcpThread     = null
        receiveThread?.interrupt(); receiveThread = null
        activeSocket?.closeQuietly();  activeSocket  = null
        serverSocket?.closeQuietly();  serverSocket  = null
    }

    // =========================================================================
    // Send — available from both GO and Client
    // =========================================================================

    private fun sendFile() {
        val uri  = selectedFileUri ?: run { showSnack("Pick a file first");       return }
        val sock = activeSocket    ?: run { showSnack("Connect to a peer first"); return }

        cancelTransfer = false
        updateStatus("Sending…")
        Thread {
            try {
                val cr   = requireContext().contentResolver
                val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val size: Long = cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

                showTransferCard(true)
                updateTransferProgress(name, "Sending", 0L, size, 0, "Starting…")

                val dout = DataOutputStream(sock.getOutputStream())
                cr.openInputStream(uri)?.use { stream ->
                    val nb = name.toByteArray(Charsets.UTF_8)
                    dout.writeInt(nb.size); dout.write(nb)
                    if (size >= 0) {
                        dout.writeLong(size)
                        val buf         = ByteArray(BUFFER_SIZE)
                        var bytesSent   = 0L
                        var lastPercent = -1
                        val startMs     = System.currentTimeMillis()
                        var n: Int = -1
                        while (!cancelTransfer && stream.read(buf).also { n = it } != -1) {
                            dout.write(buf, 0, n)
                            bytesSent += n
                            val percent    = (bytesSent * 100 / size).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent    = percent
                                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                                val speed      = if (elapsedSec > 0.5) (bytesSent / elapsedSec).toLong() else 0L
                                val etaSec     = if (speed > 0) (size - bytesSent) / speed else -1L
                                val speedStr   = if (speed > 0)
                                    "${formatSpeed(speed)}  ·  ETA ${formatEta(etaSec)}" else "Calculating…"
                                updateTransferProgress(name, "Sending", bytesSent, size, percent, speedStr)
                            }
                        }
                        if (cancelTransfer) { showTransferCard(false); return@Thread }
                    } else {
                        val bytes = stream.readBytes()
                        dout.writeLong(bytes.size.toLong()); dout.write(bytes)
                        updateTransferProgress(name, "Sending", bytes.size.toLong(), bytes.size.toLong(), 100)
                    }
                    dout.flush()
                }
                activity?.runOnUiThread {
                    showTransferCard(false)
                    updateStatus("Sent: $name")
                    showSnack("File sent ✓")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                activity?.runOnUiThread {
                    showTransferCard(false)
                    updateStatus("Send failed: ${e.message}")
                }
            } finally {
                cancelTransfer = false
            }
        }.also { it.isDaemon = true }.start()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun formatSize(bytes: Long): String = when {
        bytes < 0         -> "? B"
        bytes < 1_024     -> "$bytes B"
        bytes < 1_048_576 -> "${bytes / 1_024} KB"
        else              -> String.format("%.1f MB", bytes / 1_048_576.0)
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0        -> "…"
        bytesPerSec < 1_024     -> "$bytesPerSec B/s"
        bytesPerSec < 1_048_576 -> "${bytesPerSec / 1_024} KB/s"
        else                    -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
    }

    private fun formatEta(seconds: Long): String = when {
        seconds < 0    -> "--:--"
        seconds < 60   -> "0:%02d".format(seconds)
        seconds < 3600 -> "%d:%02d".format(seconds / 60, seconds % 60)
        else           -> "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    private fun showTransferCard(visible: Boolean) {
        activity?.runOnUiThread { cardTransferProgress?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    private fun updateTransferProgress(
        fileName: String, direction: String,
        received: Long, total: Long, percent: Int,
        speedStr: String = ""
    ) {
        activity?.runOnUiThread {
            tvTransferFileName?.text = "$direction: $fileName"
            progressTransfer?.progress = percent
            tvTransferPercent?.text = "$percent%"
            tvTransferBytes?.text = "${formatSize(received)} / ${formatSize(total)}"
            tvTransferSpeed?.text = speedStr
        }
    }

    private fun updateStatus(msg: String) { Log.d(TAG, msg); tvStatus?.text = msg }

    // Send enabled when socket is open AND a file is selected — no GO/client restriction
    private fun updateSendButton() {
        btnSend?.isEnabled = isConnected && activeSocket != null && selectedFileUri != null
    }

    private fun showSnack(msg: String) = view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show() }

    private fun hasRequiredPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun java.io.Closeable.closeQuietly() = try { close() } catch (_: Exception) {}

    // =========================================================================
    // Programmatic UI
    // =========================================================================

    private fun buildUi(ctx: Context): View {
        val dp = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16(dp), dp16(dp), dp16(dp), dp16(dp))
        }

        tvStatus = TextView(ctx).apply { textSize = 14f; setPadding(0, 0, 0, dp12(dp)) }
        root.addView(tvStatus, matchWrap())

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btnDiscover = Button(ctx).apply { text = "Discover" }
        btnPickFile = Button(ctx).apply { text = "Pick File" }
        btnSend     = Button(ctx).apply { text = "Send"; isEnabled = false }

        fun rowParam(end: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.marginEnd = end }

        btnRow.addView(btnDiscover, rowParam(dp4(dp)))
        btnRow.addView(btnPickFile, rowParam(dp4(dp)))
        btnRow.addView(btnSend,     rowParam())
        root.addView(btnRow, matchWrap())

        // ── Transfer progress card ─────────────────────────────────────────
        val card = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            setPadding(0, dp12(dp), 0, 0)
            visibility   = View.GONE
            background   = android.graphics.drawable.GradientDrawable().also { gd ->
                gd.cornerRadius = 12 * dp
                gd.setColor(0x0F000000)         // subtle tinted surface
            }
        }
        cardTransferProgress = card

        tvTransferFileName = TextView(ctx).apply {
            textSize   = 13f
            maxLines   = 1
            ellipsize  = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp12(dp), dp12(dp), dp12(dp), dp4(dp))
        }
        card.addView(tvTransferFileName, matchWrap())

        val progressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp12(dp), 0, dp12(dp), 0)
        }
        progressTransfer = android.widget.ProgressBar(ctx, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; isIndeterminate = false
        }
        progressRow.addView(progressTransfer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = dp4(dp) })

        tvTransferPercent = TextView(ctx).apply {
            textSize  = 12f
            text      = "0%"
            minWidth  = (40 * dp).toInt()
            gravity   = android.view.Gravity.END
        }
        progressRow.addView(tvTransferPercent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(progressRow, matchWrap())

        tvTransferBytes = TextView(ctx).apply {
            textSize = 11f
            setPadding(dp12(dp), dp4(dp), dp12(dp), 0)
        }
        card.addView(tvTransferBytes, matchWrap())

        // Speed · ETA + Cancel row
        val speedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp12(dp), dp4(dp), dp4(dp), dp12(dp))
        }
        tvTransferSpeed = TextView(ctx).apply {
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        speedRow.addView(tvTransferSpeed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        btnCancelTransfer = Button(ctx).apply {
            text      = "Cancel"
            textSize  = 12f
            setPadding(dp12(dp), 0, dp12(dp), 0)
            setOnClickListener {
                cancelTransfer = true
                showTransferCard(false)
                showSnack("Transfer cancelled")
                activity?.runOnUiThread { updateStatus("Transfer cancelled by user") }
            }
        }
        speedRow.addView(btnCancelTransfer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(speedRow, matchWrap())
        root.addView(card, matchWrap())
        // ──────────────────────────────────────────────────────────────────

        lvPeers = ListView(ctx).apply { adapter = peerListAdapter; setPadding(0, dp12(dp), 0, 0) }
        root.addView(lvPeers, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp4(dp: Float)  = (4  * dp).toInt()
    private fun dp12(dp: Float) = (12 * dp).toInt()
    private fun dp16(dp: Float) = (16 * dp).toInt()
}
