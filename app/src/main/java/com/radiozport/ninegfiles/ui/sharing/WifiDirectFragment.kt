package com.radiozport.ninegfiles.ui.sharing

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.*
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentWifiDirectBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Wi-Fi Direct Peer-to-Peer file sharing.
 *
 * Roles:
 *  • Sender   — selects files in FileExplorer, opens this screen, discovers peers,
 *               connects to chosen peer, then sends files over a raw TCP socket.
 *  • Receiver — opens this screen, appears in peer list of other devices.
 *               A background ServerSocket accepts incoming transfers.
 *
 * Protocol (binary framing over TCP port 49152):
 *   [4-byte big-endian filename length] [filename UTF-8 bytes]
 *   [8-byte big-endian file length]     [file bytes]
 *   … repeated for each file …
 *   [4-byte big-endian] = 0  → end of transfer marker
 */
class WifiDirectFragment : Fragment() {

    private var _binding: FragmentWifiDirectBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TRANSFER_PORT    = 49152
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS  = 30_000
        private const val BUFFER_SIZE        = 65_536
    }

    // ─── Wi-Fi P2P ────────────────────────────────────────────────────────
    private lateinit var wifiP2pManager: WifiP2pManager

    // Bug 1 fix: nullable so it can be explicitly closed in onDestroyView(),
    // preventing ghost channel accumulation on OEMs across re-entry.
    private var channel: WifiP2pManager.Channel? = null

    // Bug 7 + 9 fix: handler drives the 30-s discovery retry and the 3-s BUSY retry.
    private val handler = Handler(Looper.getMainLooper())
    private val discoveryRetryRunnable = Runnable { startDiscoveryCycle() }
    private val connectRetryRunnable   = Runnable { pendingConnectConfig?.let { connectToPeer(it) } }
    private var pendingConnectConfig: WifiP2pConfig? = null  // Bug 9: retained for retry

    private var isConnected      = false
    private var isGroupOwner     = false

    // The single live TCP socket shared by both send and receive.
    // Once set, either side can write files to it at any time; the
    // always-on read loop on the other side picks them up automatically.
    @Volatile private var activeSocket: Socket? = null
    private var serverSocket: ServerSocket?  = null
    private var acceptJob:    Job? = null   // GO's ServerSocket accept loop
    private var receiveJob:   Job? = null   // always-on background reader
    private var sendJob:      Job? = null   // active outbound transfer (cancellable)

    private var groupOwnerAddress: String? = null

    /** Paths pre-selected in the explorer; sent automatically once the TCP socket is ready. */
    private var pendingFilePaths: ArrayList<String>? = null

    private val peerAdapter = PeerAdapter { peer -> connectToDevice(peer) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) initWifiDirect()
        else showSnack("Location/nearby permission required for Wi-Fi Direct")
    }

    // ─── Broadcast receiver ───────────────────────────────────────────────
    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val ch = channel ?: return
                    wifiP2pManager.requestPeers(ch) { peers ->
                        val list = peers.deviceList.toList()
                        peerAdapter.submitList(list)
                        binding.tvPeerCount.text =
                            if (list.isEmpty()) "No peers found – searching…"
                            else "${list.size} peer(s) nearby"

                        // No auto-connect: user taps peer to connect.
                        // Either device can initiate — GO role doesn't matter
                        // anymore since transfer direction is app-level.
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            android.net.NetworkInfo::class.java
                        )
                    else @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)

                    if (networkInfo?.isConnected == true) {
                        val ch = channel ?: return
                        wifiP2pManager.requestConnectionInfo(ch) { info ->
                            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                            isConnected = true
                            isGroupOwner = info.isGroupOwner
                            handler.removeCallbacks(discoveryRetryRunnable)
                            handler.removeCallbacks(connectRetryRunnable)
                            binding.tvStatus.text = if (info.isGroupOwner) "Connected as GO.  Ready to send/receive..." else "Connected as Client.  Ready to send/receive..."
                            updateSendButton()
                            // TCP is bidirectional. We don't pre-assign roles.
                            // GO opens a ServerSocket (it has the known IP).
                            // Client dials GO. Once the socket is open, EITHER side
                            // can send files at any time — a background read loop on
                            // both devices catches whatever arrives automatically.
                            if (info.isGroupOwner) openTcpServer()
                            else info.groupOwnerAddress?.hostAddress?.let { dialTcpServer(it) }
                        }
                    } else {
                        binding.tvStatus.text = "Disconnected"
                        groupOwnerAddress = null
                        isConnected = false
                        isGroupOwner = false
                        closeConnection()
                    }
                }

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    binding.btnDiscover.isEnabled = enabled
                    if (!enabled) closeConnection()
                }

                // Bug 8 fix: was silently dropped — handler confirms own device readiness
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    else @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as? WifiP2pDevice
                    android.util.Log.d("WifiDirect", "Own device: name=${device?.deviceName} status=${device?.status}")
                }
            }
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWifiDirectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wifiP2pManager = requireContext()
            .getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        // Bug 1 fix: single initialization here; closed in onDestroyView()
        channel = wifiP2pManager.initialize(
            requireContext(), requireActivity().mainLooper, null
        )

        binding.rvPeers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPeers.adapter = peerAdapter

        binding.btnDiscover.setOnClickListener { discoverPeers() }
        // btnReceive removed — receive is now automatic (always-on loop)
        // binding.btnReceive can be hidden in the layout or repurposed
        binding.btnSendFiles.setOnClickListener { pickAndSendFiles() }
        binding.btnCancelTransfer.setOnClickListener { cancelActiveTransfer() }

        // Collect any files pre-selected in the explorer so they are sent automatically
        // once the peer connection and TCP socket are established.
        pendingFilePaths = arguments?.getStringArrayList("pendingFilePaths")
        if (!pendingFilePaths.isNullOrEmpty()) {
            binding.tvStatus.text =
                "${pendingFilePaths!!.size} file(s) queued — discover a peer and connect to send automatically"
        }

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(p2pReceiver, intentFilter)
        // Bug 5 & 6 fix: replay any WIFI_P2P_CONNECTION_CHANGED_ACTION missed while
        // the file picker (or any other foreground activity) had us in the background.
        channel?.let { ch ->
            wifiP2pManager.requestConnectionInfo(ch) { info ->
                if (info?.groupFormed == true) {
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                    isConnected = true
                    val role = if (info.isGroupOwner) "GO.  Ready to send/receive..." else "Client.  Ready to send/receive..."
                    binding.tvStatus.text = "Connected as $role"
                    updateSendButton()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(p2pReceiver)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(discoveryRetryRunnable)
        handler.removeCallbacks(connectRetryRunnable)
        closeConnection()
        channel?.let { ch ->
            wifiP2pManager.cancelConnect(ch, null)
            // Bug 2 fix: removeGroup so a stale group cannot return BUSY on next entry
            wifiP2pManager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { android.util.Log.d("WifiDirect", "removeGroup on destroy: ok") }
                override fun onFailure(r: Int) { android.util.Log.d("WifiDirect", "removeGroup on destroy: reason=$r") }
            })
            // Bug 1 fix: close channel to avoid ghost channel on OEMs (API 27+; minSdk=26)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                ch.close()
            }
        }
        channel = null
        super.onDestroyView()
        _binding = null
    }

    // ─── Permissions ──────────────────────────────────────────────────────

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) initWifiDirect()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun initWifiDirect() {
        binding.btnDiscover.isEnabled = true
        binding.tvStatus.text = "Ready — tap Discover to find nearby devices"
    }

    // ─── Discover ─────────────────────────────────────────────────────────

    /**
     * Public entry point for the Discover button and the 30-s auto-retry (Bug 7).
     * Delegates to the atomic 3-step chain so it always starts clean.
     */
    private fun discoverPeers() = startDiscoveryCycle()

    /**
     * Full discovery cycle — atomic 3-step chain (Bugs 2, 3, 7):
     *   Step 1: removeGroup()         — clear any stale group (Bug 2)
     *   Step 2: stopPeerDiscovery()   — stop any running scan  (Bug 3)
     *   Step 3: discoverPeers()       — start fresh
     * Each step proceeds regardless of failure so a missing group or idle radio
     * never blocks the chain.
     */
    private fun startDiscoveryCycle() {
        if (isConnected) return
        val ch = channel ?: return
        binding.progressWifi.isVisible = true
        binding.tvPeerCount.text = "Scanning…"
        handler.removeCallbacks(discoveryRetryRunnable)   // reset the 30-s timer

        // Step 1 — Bug 2: remove stale group before scanning
        wifiP2pManager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { stopThenDiscover(ch) }
            override fun onFailure(r: Int) {
                android.util.Log.d("WifiDirect", "removeGroup pre-discovery: reason=$r — continuing")
                stopThenDiscover(ch)
            }
        })
    }

    /** Step 2 — Bug 3: stop any running scan before starting a new one. */
    private fun stopThenDiscover(ch: WifiP2pManager.Channel) {
        wifiP2pManager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { doDiscoverPeers(ch) }
            override fun onFailure(r: Int) {
                android.util.Log.d("WifiDirect", "stopPeerDiscovery: reason=$r — continuing")
                doDiscoverPeers(ch)
            }
        })
    }

    /** Step 3 — start the actual scan and arm the 30-s retry (Bug 7). */
    private fun doDiscoverPeers(ch: WifiP2pManager.Channel) {
        wifiP2pManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                binding.progressWifi.isVisible = false
                binding.tvStatus.text = "Discovery started"
                // Bug 7 fix: re-fire the full cycle after 30 s if discovery expires
                handler.postDelayed(discoveryRetryRunnable, 30_000L)
            }
            override fun onFailure(reason: Int) {
                binding.progressWifi.isVisible = false
                showSnack("Discovery failed (error $reason). Enable Wi-Fi.")
            }
        })
    }

    // ─── Connect ──────────────────────────────────────────────────────────

    private fun connectToDevice(device: WifiP2pDevice) {
        // The protocol REQUIRES the receiver to be Group Owner: the sender always
        // connects to groupOwnerAddress, where the receiver's ServerSocket listens.
        // If both devices use intent=0 the framework resolves the tie via hardware
        // (MAC address priority), which is deterministic for any fixed pair — always
        // making the same device the GO. That is why one device was permanently the
        // sender and the other permanently the receiver.
        //
        // Fix: receiver declares intent=15 (strongly wants GO) so it always wins the
        // negotiation. Sender declares intent=0 so it always defers to the receiver.
        // groupOwnerIntent deliberately left at default (-1 = no preference).
        // Transfer direction is now determined by the application-level role
        // (isReceiverMode), not by who wins GO. Forcing intent caused the
        // opposite device to always lose — which broke role reversal.
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        pendingConnectConfig = config
        binding.tvStatus.text = "Connecting to ${device.deviceName}…"
        connectToPeer(config)
    }

    private fun connectToPeer(config: WifiP2pConfig) {
        val ch = channel ?: return
        wifiP2pManager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { binding.tvStatus.text = "Connection initiated…" }
            override fun onFailure(reason: Int) {
                // Bug 9 fix: BUSY and ERROR are transient — schedule a retry instead of
                // surfacing a permanent failure snack to the user.
                val reasonStr = when (reason) {
                    WifiP2pManager.BUSY            -> "BUSY"
                    WifiP2pManager.ERROR           -> "ERROR"
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    else                           -> "reason=$reason"
                }
                android.util.Log.w("WifiDirect", "connect() failed: $reasonStr — retrying in 3 s")
                binding.tvStatus.text = "Connect failed ($reasonStr) — retrying…"
                handler.removeCallbacks(connectRetryRunnable)
                handler.postDelayed(connectRetryRunnable, 3_000L)
            }
        })
    }

    // ─── TCP connection — role-free ───────────────────────────────────────
    //
    // GO opens a ServerSocket (it has the known IP) and accepts.
    // Client dials the GO's IP.
    // Both sides then start an always-on background READ loop.
    // EITHER device sends files at any time by writing to activeSocket.
    // No "receiver mode" or "sender mode" pre-declaration is needed.

    /** GO side: bind ServerSocket, accept peer, then start bidirectional IO. */
    private fun openTcpServer() {
        if (acceptJob?.isActive == true) return
        acceptJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(TRANSFER_PORT)) }
            serverSocket = ss
            try {
                withContext(Dispatchers.Main) { binding.tvStatus.text = "Connected as GO.  Ready to send/receive..." }
                val sock = ss.accept()
                sock.soTimeout = 0          // no timeout — connection stays open indefinitely
                activeSocket = sock
                autoSendIfPending(sock)     // send pre-selected files immediately if launched from explorer
                startReceiveLoop(sock)      // background reader; send path is independent
            } catch (_: SocketException) {
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("Server error: ${e.message}") }
            } finally {
                ss.closeQuietly(); serverSocket = null
            }
        }
    }

    /** Client side: dial GO, then start bidirectional IO. */
    private fun dialTcpServer(ip: String) {
        if (acceptJob?.isActive == true) return
        acceptJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, TRANSFER_PORT), CONNECT_TIMEOUT_MS)
                sock.soTimeout = 0
                activeSocket = sock
                withContext(Dispatchers.Main) { binding.tvStatus.text = "Connected as Client.  Ready to send/receive..." }
                autoSendIfPending(sock)     // send pre-selected files immediately if launched from explorer
                startReceiveLoop(sock)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("Dial error: ${e.message}") }
            }
        }
    }

    /**
     * Always-on background reader — runs for the lifetime of the connection.
     * Saves every batch of files that arrives from the peer, regardless of
     * whether this device "asked to receive". Either peer can send at any time.
     */
    private fun startReceiveLoop(socket: Socket) {
        receiveJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val din     = DataInputStream(socket.getInputStream())
            val destDir = File(android.os.Environment.getExternalStorageDirectory(), "received")
                .also { if (!it.exists()) it.mkdirs() }
            try {
                while (isActive) {
                    val nameLen = din.readInt()
                    if (nameLen == 0) continue          // keepalive / end-of-batch marker
                    val name    = String(ByteArray(nameLen).also { din.readFully(it) })
                    val fileLen = din.readLong()

                    withContext(Dispatchers.Main) {
                        _binding?.apply {
                            cardTransferProgress.isVisible = true
                            tvTransferFileName.text = "Receiving: $name"
                            progressTransfer.progress = 0
                            tvTransferPercent.text = "0%"
                            tvTransferBytes.text = "0 / ${formatSize(fileLen)}"
                            tvTransferSpeed.text = "Starting…"
                        }
                    }

                    val dest    = File(destDir, name)
                    val startMs = System.currentTimeMillis()
                    dest.outputStream().use { out ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var remaining   = fileLen
                        var received    = 0L
                        var lastPercent = -1
                        while (remaining > 0) {
                            val n = din.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) return@launch
                            out.write(buf, 0, n)
                            remaining -= n
                            received  += n
                            val percent   = if (fileLen > 0) (received * 100 / fileLen).toInt().coerceIn(0, 100) else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                                val speed      = if (elapsedSec > 0.5) (received / elapsedSec).toLong() else 0L
                                val etaSec     = if (speed > 0) (fileLen - received) / speed else -1L
                                withContext(Dispatchers.Main) {
                                    _binding?.apply {
                                        progressTransfer.progress = percent
                                        tvTransferPercent.text = "$percent%"
                                        tvTransferBytes.text = "${formatSize(received)} / ${formatSize(fileLen)}"
                                        tvTransferSpeed.text = if (speed > 0)
                                            "${formatSpeed(speed)}  ·  ETA ${formatEta(etaSec)}"
                                        else "Calculating…"
                                    }
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        _binding?.cardTransferProgress?.isVisible = false
                        appendLog("Received: ${dest.absolutePath} (${fileLen / 1024} KB)")
                        showSnack("File received → ${dest.name}")
                    }
                }
            } catch (_: SocketException) {
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _binding?.cardTransferProgress?.isVisible = false
                    appendLog("Receive loop ended: ${e.message}")
                }
            } finally {
                socket.closeQuietly(); activeSocket = null
                withContext(Dispatchers.Main) {
                    isConnected = false
                    _binding?.apply {
                        tvStatus.text = "Disconnected"
                        cardTransferProgress.isVisible = false
                    }
                    updateSendButton()
                }
            }
        }
    }

    private fun cancelActiveTransfer() {
        sendJob?.cancel()
        sendJob = null
        _binding?.apply {
            cardTransferProgress.isVisible = false
            appendLog("Transfer cancelled by user")
        }
        showSnack("Transfer cancelled")
    }

    private fun closeConnection() {
        sendJob?.cancel();    sendJob    = null
        receiveJob?.cancel(); receiveJob = null
        acceptJob?.cancel();  acceptJob  = null
        activeSocket?.closeQuietly(); activeSocket = null
        serverSocket?.closeQuietly(); serverSocket = null
    }

    // ─── Send ─────────────────────────────────────────────────────────────

    private fun formatSize(bytes: Long): String = when {
        bytes < 0            -> "? B"
        bytes < 1_024        -> "$bytes B"
        bytes < 1_048_576    -> "${bytes / 1_024} KB"
        else                 -> String.format("%.1f MB", bytes / 1_048_576.0)
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0       -> "…"
        bytesPerSec < 1_024    -> "$bytesPerSec B/s"
        bytesPerSec < 1_048_576 -> "${bytesPerSec / 1_024} KB/s"
        else                   -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
    }

    private fun formatEta(seconds: Long): String = when {
        seconds < 0    -> "--:--"
        seconds < 60   -> "0:%02d".format(seconds)
        seconds < 3600 -> "%d:%02d".format(seconds / 60, seconds % 60)
        else           -> "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val sock = activeSocket
        if (sock == null || !isConnected) { showSnack("Connect to a peer first"); return@registerForActivityResult }
        sendJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { sendFiles(sock, uris) }
    }

    private fun pickAndSendFiles() {
        if (!isConnected || activeSocket == null) { showSnack("Connect to a peer first"); return }
        filePicker.launch(arrayOf("*/*"))
    }

    private suspend fun sendFiles(socket: Socket, uris: List<android.net.Uri>) {
        val dout = DataOutputStream(socket.getOutputStream())
        val cr   = requireContext().contentResolver
        var sent = 0
        withContext(Dispatchers.Main) {
            _binding?.apply {
                cardTransferProgress.isVisible = true
                tvTransferSpeed.text = ""
            }
        }
        try {
            uris.forEach { uri ->
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val size: Long = cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

                withContext(Dispatchers.Main) {
                    _binding?.apply {
                        tvTransferFileName.text = "Sending: $name"
                        progressTransfer.progress = 0
                        tvTransferPercent.text = "0%"
                        tvTransferBytes.text = "0 / ${formatSize(size)}"
                        tvTransferSpeed.text = "Starting…"
                    }
                }

                cr.openInputStream(uri)?.use { stream ->
                    val nb = name.toByteArray()
                    dout.writeInt(nb.size); dout.write(nb)
                    if (size >= 0) {
                        dout.writeLong(size)
                        val buf = ByteArray(BUFFER_SIZE)
                        var bytesSent   = 0L
                        var lastPercent = -1
                        val startMs     = System.currentTimeMillis()
                        var n: Int
                        while (stream.read(buf).also { n = it } != -1) {
                            dout.write(buf, 0, n)
                            bytesSent += n
                            val percent  = (bytesSent * 100 / size).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                                val speed      = if (elapsedSec > 0.5) (bytesSent / elapsedSec).toLong() else 0L
                                val etaSec     = if (speed > 0) (size - bytesSent) / speed else -1L
                                withContext(Dispatchers.Main) {
                                    _binding?.apply {
                                        progressTransfer.progress = percent
                                        tvTransferPercent.text = "$percent%"
                                        tvTransferBytes.text = "${formatSize(bytesSent)} / ${formatSize(size)}"
                                        tvTransferSpeed.text = if (speed > 0)
                                            "${formatSpeed(speed)}  ·  ETA ${formatEta(etaSec)}"
                                        else "Calculating…"
                                    }
                                }
                            }
                        }
                    } else {
                        val bytes = stream.readBytes()
                        dout.writeLong(bytes.size.toLong()); dout.write(bytes)
                        withContext(Dispatchers.Main) {
                            _binding?.apply {
                                progressTransfer.progress = 100
                                tvTransferPercent.text = "100%"
                                tvTransferBytes.text = formatSize(bytes.size.toLong())
                                tvTransferSpeed.text = ""
                            }
                        }
                    }
                    sent++
                    withContext(Dispatchers.Main) { appendLog("Sent: $name") }
                }
            }
            dout.flush()
            val count = sent
            withContext(Dispatchers.Main) {
                showSnack("Sent $count file(s)")
                _binding?.cardTransferProgress?.isVisible = false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e   // let coroutine cancel cleanly
            withContext(Dispatchers.Main) {
                showSnack("Send failed: ${e.message}")
                _binding?.cardTransferProgress?.isVisible = false
            }
        } finally {
            sendJob = null
        }
    }

    // ─── Pre-selected file auto-send ──────────────────────────────────────

    /**
     * Called as soon as the TCP socket is ready (both GO and client paths).
     * Consumes [pendingFilePaths] exactly once and kicks off the transfer so
     * the user never has to re-select files that already initiated the session.
     */
    private fun autoSendIfPending(socket: Socket) {
        val paths = pendingFilePaths?.takeIf { it.isNotEmpty() } ?: return
        pendingFilePaths = null   // consume once — manual sends via btnSendFiles still work afterward
        sendJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            sendFilesFromPaths(socket, paths)
        }
    }

    /**
     * Sends a list of local file paths over [socket].
     * Mirrors [sendFiles] but reads directly from [File] instead of going through
     * the content resolver — avoids the file-picker round-trip for explorer-sourced files.
     */
    private suspend fun sendFilesFromPaths(socket: Socket, paths: List<String>) {
        val dout = DataOutputStream(socket.getOutputStream())
        var sent = 0
        withContext(Dispatchers.Main) {
            _binding?.apply { cardTransferProgress.isVisible = true; tvTransferSpeed.text = "" }
        }
        try {
            paths.forEach { path ->
                val file = File(path)
                val name = file.name
                val size = file.length()
                withContext(Dispatchers.Main) {
                    _binding?.apply {
                        tvTransferFileName.text = "Sending: $name"
                        progressTransfer.progress = 0
                        tvTransferPercent.text = "0%"
                        tvTransferBytes.text = "0 / ${formatSize(size)}"
                        tvTransferSpeed.text = "Starting…"
                    }
                }
                file.inputStream().use { stream ->
                    val nb = name.toByteArray()
                    dout.writeInt(nb.size); dout.write(nb)
                    dout.writeLong(size)
                    val buf = ByteArray(BUFFER_SIZE)
                    var bytesSent = 0L
                    var lastPercent = -1
                    val startMs = System.currentTimeMillis()
                    var n: Int
                    while (stream.read(buf).also { n = it } != -1) {
                        dout.write(buf, 0, n)
                        bytesSent += n
                        val percent = (bytesSent * 100 / size).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                            val speed = if (elapsedSec > 0.5) (bytesSent / elapsedSec).toLong() else 0L
                            val etaSec = if (speed > 0) (size - bytesSent) / speed else -1L
                            withContext(Dispatchers.Main) {
                                _binding?.apply {
                                    progressTransfer.progress = percent
                                    tvTransferPercent.text = "$percent%"
                                    tvTransferBytes.text = "${formatSize(bytesSent)} / ${formatSize(size)}"
                                    tvTransferSpeed.text = if (speed > 0)
                                        "${formatSpeed(speed)}  ·  ETA ${formatEta(etaSec)}"
                                    else "Calculating…"
                                }
                            }
                        }
                    }
                }
                sent++
                withContext(Dispatchers.Main) { appendLog("Sent: $name") }
            }
            dout.flush()
            val count = sent
            withContext(Dispatchers.Main) {
                showSnack("Sent $count file(s)")
                _binding?.cardTransferProgress?.isVisible = false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            withContext(Dispatchers.Main) {
                showSnack("Send failed: ${e.message}")
                _binding?.cardTransferProgress?.isVisible = false
            }
        } finally {
            sendJob = null
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun updateSendButton() {
        _binding?.btnSendFiles?.isEnabled = isConnected
    }

    private fun appendLog(msg: String) {
        val prev = binding.tvLog.text?.toString() ?: ""
        binding.tvLog.text = "$prev\n$msg".trimStart('\n')
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    /** Swallows any secondary exception during cleanup so it never masks a real error. */
    private fun java.io.Closeable.closeQuietly() = try { close() } catch (_: Exception) {}
}

// ─── Peer Adapter ─────────────────────────────────────────────────────────────

class PeerAdapter(
    private val onClick: (WifiP2pDevice) -> Unit
) : ListAdapter<WifiP2pDevice, PeerAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<WifiP2pDevice>() {
            override fun areItemsTheSame(a: WifiP2pDevice, b: WifiP2pDevice) =
                a.deviceAddress == b.deviceAddress
            override fun areContentsTheSame(a: WifiP2pDevice, b: WifiP2pDevice) =
                a.deviceName == b.deviceName && a.status == b.status
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:   TextView = view.findViewById(R.id.tvName)
        val tvStatus: TextView = view.findViewById(R.id.tvSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_list, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = getItem(position)
        holder.tvName.text   = d.deviceName.ifBlank { d.deviceAddress }
        holder.tvStatus.text = when (d.status) {
            WifiP2pDevice.CONNECTED   -> "Connected"
            WifiP2pDevice.INVITED     -> "Invited…"
            WifiP2pDevice.FAILED      -> "Failed"
            WifiP2pDevice.AVAILABLE   -> "Available"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else                      -> "Unknown"
        }
        holder.itemView.setOnClickListener { onClick(d) }
    }
}
