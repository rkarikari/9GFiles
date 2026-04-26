package com.radiozport.ninegfiles.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal multi-file HTTP server for streaming local media to a Chromecast receiver.
 *
 * Single-file usage (backward-compatible):
 *   CastMediaServer.serveFile(file, "video/mp4")
 *   val url = CastMediaServer.getUrl(context)          // → http://x.x.x.x:PORT/media
 *
 * Queue usage (multi-file):
 *   CastMediaServer.serveQueue(listOf(file1 to "audio/mpeg", file2 to "audio/mpeg", …))
 *   val url0 = CastMediaServer.getUrlForIndex(context, 0)   // → http://…/media/0
 *   val url1 = CastMediaServer.getUrlForIndex(context, 1)   // → http://…/media/1
 *
 * Supports HTTP Range requests so the receiver can seek inside large files.
 */
object CastMediaServer {

    private const val TAG = "CastMediaServer"

    private val serverSocketRef = AtomicReference<ServerSocket?>(null)
    private val executor = Executors.newCachedThreadPool()

    // ── Queue ──────────────────────────────────────────────────────────────────

    @Volatile private var queue: List<Pair<File, String>> = emptyList()

    /** Replace the entire queue. Each entry is a (File, mimeType) pair. */
    fun serveQueue(files: List<Pair<File, String>>) {
        queue = files.toList()
    }

    /** Convenience: register a single file (sets a 1-entry queue). */
    fun serveFile(file: File, mimeType: String) {
        queue = listOf(file to mimeType)
    }

    @Volatile var port: Int = 0
        private set

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        if (serverSocketRef.get() != null) return           // already running
        try {
            val ss = ServerSocket(0)                        // bind to any free port
            port = ss.localPort
            serverSocketRef.set(ss)
            Log.d(TAG, "Cast HTTP server listening on port $port")
            executor.submit {
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        executor.submit { handleRequest(client) }
                    } catch (_: Exception) { /* normal shutdown */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cast server", e)
        }
    }

    fun stop() {
        serverSocketRef.getAndSet(null)?.close()
        queue = emptyList()
    }

    // ── URL helpers ────────────────────────────────────────────────────────────

    private fun getBaseUrl(context: Context): String? {
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val rawIp = wm.connectionInfo.ipAddress
        if (rawIp == 0) return null
        val ip = "%d.%d.%d.%d".format(
            rawIp and 0xFF, rawIp shr 8 and 0xFF,
            rawIp shr 16 and 0xFF, rawIp shr 24 and 0xFF
        )
        return "http://$ip:$port"
    }

    /** URL for the first (or only) queued file — backward-compatible. */
    fun getUrl(context: Context): String? = getBaseUrl(context)?.let { "$it/media" }

    /** URL for the file at [index] in the queue. */
    fun getUrlForIndex(context: Context, index: Int): String? =
        getBaseUrl(context)?.let { "$it/media/$index" }

    // ── Request handling ───────────────────────────────────────────────────────

    private fun handleRequest(socket: Socket) {
        try {
            socket.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                if (!requestLine.startsWith("GET") && !requestLine.startsWith("HEAD")) {
                    sendError(sock, 405, "Method Not Allowed"); return
                }

                // Parse headers
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                    line = reader.readLine()
                }

                // Resolve path → queue index
                // Accepts:  GET /media        → index 0 (compat)
                //           GET /media/N      → index N
                val path = requestLine.split(" ").getOrNull(1) ?: "/media"
                val index: Int = when {
                    path == "/media" || path == "/media/" -> 0
                    path.startsWith("/media/") -> path.removePrefix("/media/").trimEnd('/').toIntOrNull() ?: 0
                    else -> { sendError(sock, 404, "Not Found"); return }
                }

                val entry = queue.getOrNull(index)
                val file = entry?.first
                val mime = entry?.second ?: "*/*"

                if (file == null || !file.exists()) { sendError(sock, 404, "Not Found"); return }

                val isHead = requestLine.startsWith("HEAD")
                val fileLen = file.length()
                val out = sock.getOutputStream()
                val rangeHeader = headers["range"]

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    // Partial content (Range request)
                    val rangePart = rangeHeader.removePrefix("bytes=")
                    val dashIdx = rangePart.indexOf('-')
                    val start = if (dashIdx > 0) rangePart.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
                    val end = if (dashIdx < rangePart.length - 1)
                        rangePart.substring(dashIdx + 1).toLongOrNull() ?: (fileLen - 1)
                    else (fileLen - 1)
                    val length = (end - start + 1).coerceAtLeast(0)

                    out.write(buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Type: $mime\r\n")
                        append("Content-Length: $length\r\n")
                        append("Content-Range: bytes $start-$end/$fileLen\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray())
                    if (!isHead) {
                        FileInputStream(file).use { fis ->
                            fis.skip(start)
                            val buf = ByteArray(65_536)
                            var remaining = length
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val read = fis.read(buf, 0, toRead)
                                if (read < 0) break
                                out.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                    }
                } else {
                    // Full content
                    out.write(buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: $mime\r\n")
                        append("Content-Length: $fileLen\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray())
                    if (!isHead) FileInputStream(file).use { it.copyTo(out, bufferSize = 65_536) }
                }
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error serving cast request", e)
        }
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        try {
            socket.getOutputStream()
                .write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } catch (_: Exception) {}
    }
}
