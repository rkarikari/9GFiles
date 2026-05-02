package com.radiozport.ninegfiles.ui.server

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentFtpServerBinding
import com.radiozport.ninegfiles.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPCmd
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal anonymous read-only FTP server so any PC on the same Wi-Fi can
 * browse the device's public storage via any FTP client (FileZilla, WinSCP,
 * Nautilus, Finder, etc.).
 *
 * Protocol coverage: PASV data connections, LIST, RETR, CWD, PWD, TYPE, SYST,
 * FEAT, QUIT. Anonymous login only (no credentials sent over the air).
 */
class FtpServerFragment : Fragment() {

    private var _binding: FragmentFtpServerBinding? = null
    private val binding get() = _binding!!

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val logBuffer = StringBuilder()

    companion object {
        private const val DEFAULT_PORT = 2121   // >1024 so no root needed
        private const val NOTIFICATION_FTP_ID = 2001
        private val ROOT = Environment.getExternalStorageDirectory()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFtpServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvServerAddress.text = "Tap Start to launch the FTP server"
        binding.btnToggleServer.text = "Start Server"

        binding.btnToggleServer.setOnClickListener {
            if (serverJob?.isActive == true) stopServer() else startServer()
        }
        binding.btnClearLog.setOnClickListener {
            logBuffer.clear()
            binding.tvLog.text = ""
        }
    }

    // ─── Start / Stop ─────────────────────────────────────────────────────

    private fun startServer() {
        val port = binding.etPort.text?.toString()?.toIntOrNull() ?: DEFAULT_PORT
        val ip = getDeviceIp() ?: "127.0.0.1"
        val serverUrl = "ftp://$ip:$port"

        binding.btnToggleServer.text = "Stop Server"
        binding.tvServerAddress.text = "$serverUrl   (anonymous, read-only)"
        binding.cardStatus.setCardBackgroundColor(
            requireContext().getColor(com.google.android.material.R.color.design_default_color_secondary))

        // Enable QR button and wire it to show the server URL as a QR code
        binding.btnShowQr.isEnabled = true
        binding.btnShowQr.setOnClickListener {
            com.radiozport.ninegfiles.ui.dialogs.QrShareDialog.show(
                childFragmentManager,
                content = serverUrl,
                label = "Scan to connect — $serverUrl"
            )
        }

        serverJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                log("Server started on port $port")
                log("Root: ${ROOT.absolutePath}")
                postFtpNotification(serverUrl)
                while (isActive) {
                    val client = serverSocket!!.accept()
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) log("Server error: ${e.message}")
            }
        }
    }

    private fun stopServer() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        cancelFtpNotification()
        binding.btnToggleServer.text = "Start Server"
        binding.btnShowQr.isEnabled = false
        binding.tvServerAddress.text = "Server stopped"
        binding.cardStatus.setCardBackgroundColor(
            requireContext().getColor(android.R.color.transparent))
        log("Server stopped")
    }

    // ─── Persistent notification ──────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun postFtpNotification(serverUrl: String) {
        val tapIntent = PendingIntent.getActivity(
            requireContext(), 0,
            Intent(requireContext(), MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(requireContext(), NineGFilesApp.CHANNEL_FTP_SERVER)
            .setSmallIcon(R.drawable.ic_ftp_server)
            .setContentTitle("FTP Server is running")
            .setContentText(serverUrl)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
        NotificationManagerCompat.from(requireContext()).notify(NOTIFICATION_FTP_ID, notification)
    }

    private fun cancelFtpNotification() {
        NotificationManagerCompat.from(requireContext()).cancel(NOTIFICATION_FTP_ID)
    }

    // ─── FTP session handler ─────────────────────────────────────────────

    private suspend fun handleClient(socket: Socket) {
        val remoteAddr = socket.inetAddress.hostAddress ?: "unknown"
        log("Client connected: $remoteAddr")
        var cwd = ROOT

        val reader = socket.getInputStream().bufferedReader()
        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)

        fun send(msg: String) { writer.println(msg) }

        // PASV state: server opens this socket on PASV command, accepts on LIST/RETR
        var pasvServer: ServerSocket? = null

        /** Open a passive data socket and return the 227 address string. Call on PASV. */
        fun openPasv(): String {
            runCatching { pasvServer?.close() }
            val ss = ServerSocket(0).also { it.soTimeout = 15_000 }
            pasvServer = ss
            val port = ss.localPort
            val ip = getDeviceIp() ?: socket.localAddress.hostAddress!!
            val p1 = port / 256
            val p2 = port % 256
            val ipStr = ip.replace('.', ',')
            return "227 Entering Passive Mode ($ipStr,$p1,$p2)"
        }

        /** Accept the pre-opened PASV data connection. Call on LIST/RETR, after sending 150. */
        fun acceptData(): Socket? {
            val ss = pasvServer ?: return null
            pasvServer = null
            return try { ss.accept() } catch (e: Exception) { null } finally { ss.close() }
        }

        send("220 9GFiles FTP Server ready")

        try {
            while (true) {
                val raw = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                val line = raw.trim()
                val cmd  = line.substringBefore(' ').uppercase()
                val arg  = if (line.contains(' ')) line.substringAfter(' ').trim() else ""
                log("$remoteAddr < $line")

                when (cmd) {
                    "USER" -> send("331 Please specify password")
                    "PASS" -> send("230 Login successful (anonymous)")
                    "SYST" -> send("215 UNIX Type: L8")
                    "FEAT" -> { send("211-Features:"); send(" PASV"); send(" SIZE"); send("211 End") }
                    "TYPE" -> send("200 Switching to ${if (arg == "I") "Binary" else "ASCII"} mode")
                    "NOOP" -> send("200 OK")

                    // ── Directory commands ─────────────────────────────
                    "PWD", "XPWD" -> send("257 \"${cwd.relativePath()}\" is current directory")

                    "CWD" -> {
                        val target = when {
                            arg == "/"          -> ROOT
                            arg.startsWith("/") -> File(ROOT, arg)
                            else                -> File(cwd, arg)
                        }
                        val canonical = target.canonicalFile
                        if (canonical.exists() && canonical.isDirectory &&
                            canonical.absolutePath.startsWith(ROOT.absolutePath)) {
                            cwd = canonical
                            send("250 Directory changed to \"${cwd.relativePath()}\"")
                        } else {
                            send("550 Failed to change directory")
                        }
                    }

                    "CDUP" -> {
                        val parent = cwd.parentFile
                        if (parent != null && parent.absolutePath.startsWith(ROOT.absolutePath)) {
                            cwd = parent
                            send("200 Directory changed")
                        } else {
                            send("550 Already at root")
                        }
                    }

                    // ── Passive mode ─────────────────────────────────────
                    "PASV" -> send(openPasv())

                    // ── Directory listing ─────────────────────────────────
                    "LIST", "NLST" -> {
                        send("150 Here comes the directory listing")
                        val dc = withContext(Dispatchers.IO) { acceptData() }
                        if (dc == null) { send("425 Can't open data connection"); continue }
                        withContext(Dispatchers.IO) {
                            try {
                                val out = PrintWriter(dc.getOutputStream().bufferedWriter(), true)
                                val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                                cwd.listFiles()
                                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                    ?.forEach { f ->
                                        val perm = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                                        val size = if (f.isDirectory) 4096L else f.length()
                                        val date = sdf.format(Date(f.lastModified()))
                                        out.println("$perm 1 ftp ftp ${size.toString().padStart(13)} $date ${f.name}")
                                    }
                                out.flush()
                            } finally { runCatching { dc.close() } }
                        }
                        send("226 Directory send OK")
                    }

                    // ── File download ─────────────────────────────────────
                    "RETR" -> {
                        val file = if (arg.startsWith("/")) File(ROOT, arg) else File(cwd, arg)
                        when {
                            !file.exists() || file.isDirectory ->
                                send("550 No such file")
                            !file.canonicalPath.startsWith(ROOT.absolutePath) ->
                                send("550 Permission denied")
                            else -> {
                                send("150 Opening BINARY mode data connection for ${file.name} (${file.length()} bytes)")
                                val dc = withContext(Dispatchers.IO) { acceptData() }
                                if (dc == null) { send("425 Can't open data connection"); continue }
                                withContext(Dispatchers.IO) {
                                    try {
                                        val out = java.io.BufferedOutputStream(dc.getOutputStream())
                                        file.inputStream().use { it.copyTo(out) }
                                        out.flush()
                                    } finally { runCatching { dc.close() } }
                                }
                                send("226 Transfer complete")
                            }
                        }
                    }

                    // ── File info ─────────────────────────────────────────
                    "SIZE" -> {
                        val f = if (arg.startsWith("/")) File(ROOT, arg) else File(cwd, arg)
                        if (f.exists() && !f.isDirectory) send("213 ${f.length()}")
                        else send("550 Not found")
                    }

                    "MDTM" -> {
                        val f = if (arg.startsWith("/")) File(ROOT, arg) else File(cwd, arg)
                        if (f.exists()) {
                            val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(f.lastModified()))
                            send("213 $ts")
                        } else send("550 Not found")
                    }

                    // ── Read-only guards ──────────────────────────────────
                    "STOR", "APPE", "DELE", "MKD", "RMD", "RNFR", "RNTO" ->
                        send("550 Permission denied — read-only server")

                    "QUIT" -> { send("221 Goodbye"); break }

                    else -> send("502 Command not implemented: $cmd")
                }
            }
        } catch (e: Exception) {
            log("Session error [$remoteAddr]: ${e.message}")
        } finally {
            runCatching { pasvServer?.close() }
            runCatching { socket.close() }
            log("Client disconnected: $remoteAddr")
        }
    }

    private fun File.relativePath(): String {
        val rel = absolutePath.removePrefix(ROOT.absolutePath)
        return if (rel.isEmpty()) "/" else rel
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun getDeviceIp(): String? {
        val wm = requireContext().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $msg\n"
        logBuffer.append(line)
        // Trim to last 200 lines
        val lines = logBuffer.lines()
        if (lines.size > 200) logBuffer.replace(0, logBuffer.length, lines.takeLast(200).joinToString("\n"))
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            _binding?.tvLog?.text = logBuffer.toString()
            _binding?.scrollLog?.post { _binding?.scrollLog?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroyView() {
        stopServer()
        super.onDestroyView()
        _binding = null
    }
}
