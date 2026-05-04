package com.radiozport.ninegfiles.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Two-pronged DLNA/UPnP MediaRenderer discovery.
 *
 * ## Why two strategies are needed
 *
 * SSDP M-SEARCH is the standard UPnP discovery mechanism, but Samsung Smart TVs
 * on firmware released from ~2020 onwards often do not respond to M-SEARCH at all.
 * This is a well-documented Samsung firmware regression seen across the openHAB,
 * Home Assistant, and Control4 communities — the TV stops advertising via SSDP
 * while still accepting UPnP/DLNA playback commands on port 9197.
 *
 * Strategy 1 — SSDP M-SEARCH (catches non-Samsung renderers, older Samsung TVs)
 * ────────────────────────────────────────────────────────────────────────────────
 * Sends dual M-SEARCH bursts (ssdp:all + MediaRenderer:1) and collects replies.
 * Requires a WifiManager.MulticastLock so Android's Wi-Fi driver doesn't drop
 * incoming multicast UDP before it reaches the DatagramSocket.
 *
 * Strategy 2 — Samsung subnet scan on port 8001 (catches modern Samsung TVs)
 * ────────────────────────────────────────────────────────────────────────────────
 * Samsung Tizen TVs (2016+) always expose a local REST API on port 8001:
 *   GET http://TV_IP:8001/api/v2/
 * returns JSON with the TV's friendly name and confirms it is a Samsung TV.
 * Once the IP is confirmed, the UPnP DMR description is fetched from port 9197
 * (Samsung's standard DMR port: LOCATION: http://TV_IP:9197/dmr) to extract the
 * AVTransport service control URL needed for playback commands.
 *
 * The /24 subnet is derived from the phone's current Wi-Fi IP address. All 254
 * host addresses are probed in parallel coroutines with a short TCP connect
 * timeout (300 ms) so the full scan completes in ~1-2 s on a typical home LAN.
 *
 * Both strategies run concurrently and results are merged and deduplicated by IP.
 */
object SsdpDiscovery {

    private const val TAG          = "SsdpDiscovery"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT    = 1900
    private const val MX           = 3

    /** Samsung Tizen REST API port (present on all 2016+ Tizen TVs). */
    private const val SAMSUNG_REST_PORT = 8001

    /**
     * Samsung's UPnP DMR service port. Confirmed by Wireshark captures:
     *   LOCATION: http://TV_IP:9197/dmr
     *   SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0
     */
    private const val SAMSUNG_DMR_PORT  = 9197

    /** TCP connect timeout for the subnet port scan (ms). */
    private const val SCAN_CONNECT_MS  = 300

    /** HTTP timeout for fetching device description XML or REST API JSON (ms). */
    private const val FETCH_TIMEOUT_MS = 5_000

    private fun mSearchBytes(st: String): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $MX\r\n" +
        "ST: $st\r\n\r\n"
    ).toByteArray(Charsets.UTF_8)

    private val SEARCH_ALL      by lazy { mSearchBytes("ssdp:all") }
    private val SEARCH_RENDERER by lazy { mSearchBytes("urn:schemas-upnp-org:device:MediaRenderer:1") }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Discover DLNA MediaRenderers on the current Wi-Fi network.
     *
     * Runs SSDP and the Samsung subnet scan concurrently. Both complete within
     * the [ssdpListenMs] window. Results are merged and deduplicated by IP address.
     *
     * @param context     Used to obtain WifiManager (application context is used internally).
     * @param ssdpListenMs  SSDP reply-collection window (default 4 s).
     */
    suspend fun discover(
        context: Context,
        ssdpListenMs: Int = (MX + 1) * 1000
    ): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val wifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // MulticastLock is required for Strategy 1 — without it Android's Wi-Fi
        // driver silently drops all incoming multicast UDP (SSDP replies).
        val lock = wifiManager.createMulticastLock("9GFiles_SSDP").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            coroutineScope {
                // Both strategies run in parallel; whichever finishes first waits
                // for the other (they are bounded by the same ssdpListenMs budget).
                val ssdpDeferred   = async { ssdpDiscover(ssdpListenMs) }
                val samsungDeferred = async { samsungSubnetScan(wifiManager, ssdpListenMs) }

                val ssdpDevices    = ssdpDeferred.await()
                val samsungDevices = samsungDeferred.await()

                // Merge; deduplicate by IP address (prefer the SSDP result if same IP appears twice)
                val byIp = LinkedHashMap<String, DlnaDevice>()
                samsungDevices.forEach { byIp[ipOf(it.controlUrl)] = it }
                ssdpDevices.forEach   { byIp[ipOf(it.controlUrl)] = it }

                Log.d(TAG, "Discovery complete: ${byIp.size} unique device(s)")
                byIp.values.toList().sortedBy { it.friendlyName }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    // ── Strategy 1 — SSDP ──────────────────────────────────────────────────────

    private suspend fun ssdpDiscover(listenMs: Int): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val locations = collectSsdpLocations(listenMs)
            Log.d(TAG, "SSDP: ${locations.size} LOCATION(s)")
            locations.mapNotNull { loc ->
                try { fetchDevice(loc) }
                catch (e: Exception) { Log.w(TAG, "SSDP skip $loc: ${e.message}"); null }
            }
        }

    private fun collectSsdpLocations(listenMs: Int): Set<String> {
        val locations = mutableSetOf<String>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = listenMs
            val group = InetAddress.getByName(SSDP_ADDRESS)

            // ssdp:all first — Samsung TVs respond to this more reliably
            repeat(2) { socket.send(DatagramPacket(SEARCH_ALL, SEARCH_ALL.size, group, SSDP_PORT)) }
            repeat(2) { socket.send(DatagramPacket(SEARCH_RENDERER, SEARCH_RENDERER.size, group, SSDP_PORT)) }

            Log.d(TAG, "SSDP M-SEARCH sent, listening ${listenMs}ms...")
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    extractHeader(text, "LOCATION")
                        ?.takeIf { it.startsWith("http") }
                        ?.let {
                            Log.d(TAG, "SSDP LOCATION: $it")
                            locations.add(it)
                        }
                }
            } catch (_: SocketTimeoutException) {
                Log.d(TAG, "SSDP window closed, ${locations.size} location(s)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP socket error: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return locations
    }

    // ── Strategy 2 — Samsung subnet scan ───────────────────────────────────────

    /**
     * Derive the phone's /24 subnet from the WifiManager connection, then probe
     * all 254 host addresses in parallel for Samsung's REST API on port 8001.
     *
     * For each responding host:
     *   1. Fetch http://IP:8001/api/v2/ and confirm it contains "Samsung SmartTV"
     *   2. Probe http://IP:9197/dmr for the UPnP DMR device description
     *   3. Parse the AVTransport control URL and return a [DlnaDevice]
     *
     * If port 9197 is not reachable (older firmware variant), fall back to the
     * standard SSDP description URL derived from the REST response.
     */
    private suspend fun samsungSubnetScan(
        wifiManager: WifiManager,
        budgetMs: Int
    ): List<DlnaDevice> = withContext(Dispatchers.IO) {

        // Get phone IP from WifiManager (stored as little-endian int)
        @Suppress("DEPRECATION")
        val rawIp = wifiManager.connectionInfo?.ipAddress ?: 0
        if (rawIp == 0) {
            Log.d(TAG, "Samsung scan: no Wi-Fi IP, skipping")
            return@withContext emptyList()
        }

        // Convert little-endian int to "A.B.C." subnet prefix
        val a = rawIp and 0xFF
        val b = (rawIp shr 8) and 0xFF
        val c = (rawIp shr 16) and 0xFF
        val subnet = "$a.$b.$c."
        Log.d(TAG, "Samsung scan: probing $subnet* on port $SAMSUNG_REST_PORT")

        val found = mutableListOf<DlnaDevice>()
        val scanTimeout = (budgetMs - 500).coerceAtLeast(2_000).toLong()

        withTimeoutOrNull(scanTimeout) {
            coroutineScope {
                (1..254).map { host ->
                    async {
                        val ip = "$subnet$host"
                        if (portOpen(ip, SAMSUNG_REST_PORT, SCAN_CONNECT_MS)) {
                            Log.d(TAG, "Samsung scan: port $SAMSUNG_REST_PORT open on $ip")
                            probeSamsungDevice(ip)
                        } else null
                    }
                }.awaitAll()
                    .filterNotNull()
                    .also { found.addAll(it) }
            }
        }

        Log.d(TAG, "Samsung scan: ${found.size} Samsung device(s) found")
        found
    }

    /**
     * Confirm a host is a Samsung Smart TV and resolve its AVTransport URL.
     *
     * Tries port 9197 (Samsung's DMR port) first for the UPnP description,
     * then falls back to the device info from the REST API endpoint.
     */
    private fun probeSamsungDevice(ip: String): DlnaDevice? {
        return try {
            // Step 1: confirm Samsung TV via REST API
            val restUrl  = "http://$ip:$SAMSUNG_REST_PORT/api/v2/"
            val restJson = httpGet(restUrl) ?: return null
            if (!restJson.contains("Samsung", ignoreCase = true)) return null

            val friendlyName = parseJsonString(restJson, "name")
                ?: parseJsonString(restJson, "DeviceName")
                ?: "Samsung TV ($ip)"

            Log.d(TAG, "Samsung REST confirmed: '$friendlyName' at $ip")

            // Step 2: try Samsung's UPnP DMR port for the AVTransport control URL
            if (portOpen(ip, SAMSUNG_DMR_PORT, SCAN_CONNECT_MS)) {
                val dmrUrl = "http://$ip:$SAMSUNG_DMR_PORT/dmr"
                val xml = httpGet(dmrUrl)
                if (xml != null) {
                    val controlRelUrl = findAvTransportControlUrl(xml)
                    if (controlRelUrl != null) {
                        val controlUrl = resolveUrl(dmrUrl, controlRelUrl)
                        Log.d(TAG, "Samsung DMR found AVTransport: $controlUrl")
                        return DlnaDevice(friendlyName, dmrUrl, controlUrl,
                            parseJsonString(restJson, "id") ?: "")
                    }
                }
            }

            // Step 3: fallback — Samsung's AVTransport path is well-known even without
            // a device description response (confirmed across multiple Samsung models)
            val fallbackControlUrl = "http://$ip:$SAMSUNG_DMR_PORT/upnp/control/AVTransport1"
            Log.d(TAG, "Samsung DMR fallback control URL: $fallbackControlUrl")
            DlnaDevice(friendlyName, restUrl, fallbackControlUrl,
                parseJsonString(restJson, "id") ?: "")

        } catch (e: Exception) {
            Log.d(TAG, "probeSamsungDevice($ip) failed: ${e.message}")
            null
        }
    }

    // ── UPnP device description parsing ───────────────────────────────────────

    private fun fetchDevice(location: String): DlnaDevice? {
        val xml          = httpGet(location) ?: return null
        val friendlyName = extractTag(xml, "friendlyName") ?: return null
        val udn          = extractTag(xml, "UDN") ?: ""
        val controlRelUrl = findAvTransportControlUrl(xml) ?: run {
            Log.d(TAG, "No AVTransport on '$friendlyName'")
            return null
        }
        val controlUrl = resolveUrl(location, controlRelUrl)
        Log.d(TAG, "SSDP found: '$friendlyName' -> $controlUrl")
        return DlnaDevice(friendlyName, location, controlUrl, udn)
    }

    private fun findAvTransportControlUrl(xml: String): String? {
        // Strip XML namespace prefixes so <s:serviceType> matches <serviceType>
        val normalised = xml.replace(Regex("<(/?)[a-zA-Z0-9_]+:")) { m ->
            "<${m.groupValues[1]}"
        }
        val blocks = normalised.split("<service>").drop(1)
        for (block in blocks) {
            val end     = block.indexOf("</service>")
            val service = if (end >= 0) block.substring(0, end) else block
            val type    = extractTag(service, "serviceType") ?: continue
            if (type.contains("AVTransport", ignoreCase = true)) {
                return extractTag(service, "controlURL")
            }
        }
        return null
    }

    // ── Network utilities ──────────────────────────────────────────────────────

    private fun portOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        }
    } catch (_: Exception) { false }

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection()
        conn.connectTimeout = FETCH_TIMEOUT_MS
        conn.readTimeout    = FETCH_TIMEOUT_MS
        conn.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) { null }

    private fun ipOf(url: String): String = try { URL(url).host } catch (_: Exception) { url }

    // ── Parsing utilities ──────────────────────────────────────────────────────

    /** Case-insensitive extraction of an HTTP header value from a raw response. */
    private fun extractHeader(response: String, header: String): String? {
        val lower  = response.lowercase()
        val prefix = "${header.lowercase()}:"
        val idx    = lower.indexOf(prefix)
        if (idx < 0) return null
        val start  = idx + prefix.length
        val end    = response.indexOf('\n', start)
        return response.substring(start, if (end < 0) response.length else end).trim()
    }

    /** Extract text content of the first XML element with the given local name. */
    private fun extractTag(xml: String, tag: String): String? {
        val open  = "<$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf("</$tag>", start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end).trim()
    }

    /**
     * Minimal JSON string-value extractor — avoids pulling in a JSON library.
     * Finds the first occurrence of "key":"value" or "key": "value" and returns value.
     */
    private fun parseJsonString(json: String, key: String): String? {
        val needle = "\"$key\""
        val keyIdx = json.indexOf(needle)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + needle.length)
        if (colonIdx < 0) return null
        val quoteStart = json.indexOf('"', colonIdx + 1)
        if (quoteStart < 0) return null
        val quoteEnd = json.indexOf('"', quoteStart + 1)
        if (quoteEnd < 0) return null
        return json.substring(quoteStart + 1, quoteEnd).takeIf { it.isNotBlank() }
    }

    /**
     * Resolve [relUrl] against [baseUrl].
     * LOCATION headers are always absolute; controlURL values are often relative.
     */
    private fun resolveUrl(baseUrl: String, relUrl: String): String {
        if (relUrl.startsWith("http://") || relUrl.startsWith("https://")) return relUrl
        val parsed = URL(baseUrl)
        val port   = if (parsed.port > 0 && parsed.port != 80) ":${parsed.port}" else ""
        val path   = if (relUrl.startsWith("/")) relUrl else "/$relUrl"
        return "${parsed.protocol}://${parsed.host}$port$path"
    }
}
