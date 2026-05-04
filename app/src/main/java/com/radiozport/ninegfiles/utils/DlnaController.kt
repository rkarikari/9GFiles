package com.radiozport.ninegfiles.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal UPnP AV/DLNA control point for [DlnaDevice] MediaRenderers.
 *
 * Implements only the AVTransport:1 actions needed for basic playback control:
 *   • [setAndPlay]  — load a URI and immediately start playback
 *   • [play]        — resume after pause
 *   • [pause]       — pause playback
 *   • [stop]        — stop and release the AVTransport instance
 *   • [seekTo]      — seek to an absolute position (seconds)
 *   • [getPositionSeconds] — poll current playback position
 *
 * All public functions are `suspend` and perform I/O on [Dispatchers.IO].
 * They return `true` / a non-negative integer on success, `false` / -1 on failure.
 *
 * Samsung TV compatibility notes
 * ────────────────────────────────
 * • Samsung TVs require the `SOAPAction` header value to be double-quoted.
 * • The `Content-Type` charset value must also be double-quoted (`charset="utf-8"`).
 * • `CurrentURIMetaData` must be valid XML-escaped DIDL-Lite; an empty string causes
 *   some Samsung models to refuse the SetAVTransportURI call entirely.
 * • The media URL must be served with `transferMode.dlna.org: Streaming` and
 *   `contentFeatures.dlna.org` headers (handled by [CastMediaServer]).
 */
object DlnaController {

    private const val TAG          = "DlnaController"
    private const val SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val INSTANCE     = "0"

    // ── Playback control ───────────────────────────────────────────────────────

    /**
     * Load [uri] onto [device] and immediately begin playback.
     *
     * [metadata] must be a valid DIDL-Lite XML string; use [buildDidlMetadata] to
     * generate one. Samsung TVs in particular reject an empty metadata argument.
     */
    suspend fun setAndPlay(
        device: DlnaDevice,
        uri: String,
        metadata: String
    ): Boolean {
        val setOk = soapCall(
            device, "SetAVTransportURI",
            "<InstanceID>$INSTANCE</InstanceID>" +
                "<CurrentURI>${escapeXml(uri)}</CurrentURI>" +
                "<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>"
        )
        if (!setOk) return false
        return soapCall(
            device, "Play",
            "<InstanceID>$INSTANCE</InstanceID><Speed>1</Speed>"
        )
    }

    /** Resume playback after [pause]. */
    suspend fun play(device: DlnaDevice): Boolean =
        soapCall(device, "Play", "<InstanceID>$INSTANCE</InstanceID><Speed>1</Speed>")

    /** Pause playback (idempotent; safe to call when already paused). */
    suspend fun pause(device: DlnaDevice): Boolean =
        soapCall(device, "Pause", "<InstanceID>$INSTANCE</InstanceID>")

    /**
     * Stop playback and release the AVTransport instance.
     * Called on disconnect and when the fragment is destroyed.
     */
    suspend fun stop(device: DlnaDevice): Boolean =
        soapCall(device, "Stop", "<InstanceID>$INSTANCE</InstanceID>")

    /**
     * Seek to an absolute position.
     *
     * @param seconds  Target position in seconds from the start.
     */
    suspend fun seekTo(device: DlnaDevice, seconds: Int): Boolean {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return soapCall(
            device, "Seek",
            "<InstanceID>$INSTANCE</InstanceID>" +
                "<Unit>REL_TIME</Unit>" +
                "<Target>%02d:%02d:%02d</Target>".format(h, m, s)
        )
    }

    /**
     * Query the renderer's current playback position via GetPositionInfo.
     *
     * @return Position in seconds, or -1 if the call fails or no media is loaded.
     */
    suspend fun getPositionSeconds(device: DlnaDevice): Int =
        withContext(Dispatchers.IO) {
            val response = soapCallRaw(
                device, "GetPositionInfo",
                "<InstanceID>$INSTANCE</InstanceID>"
            ) ?: return@withContext -1
            val relTime = extractXmlTag(response, "RelTime") ?: return@withContext -1
            parseUpnpTime(relTime)
        }

    // ── DIDL-Lite metadata ─────────────────────────────────────────────────────

    /**
     * Build the DIDL-Lite XML string required as `CurrentURIMetaData` when calling
     * [setAndPlay].
     *
     * Samsung TVs use the metadata to select the correct hardware decoder and to
     * display the media title in their Now Playing overlay.
     *
     * @param title    File/track name shown on the TV's OSD.
     * @param uri      The HTTP URL that will be loaded (same as passed to [setAndPlay]).
     * @param mime     MIME type (e.g. "video/mp4", "audio/mpeg").
     * @param isVideo  `true` for video content, `false` for audio.
     */
    fun buildDidlMetadata(
        title: String,
        uri: String,
        mime: String,
        isVideo: Boolean
    ): String {
        val upnpClass = if (isVideo) "object.item.videoItem" else "object.item.audioItem.musicTrack"
        // DLNA.ORG_OP=01 → supports byte-seek (Range) and time-seek.
        // DLNA.ORG_FLAGS=01700000000000000000000000000000 → indicates a streaming,
        // connection-stall-based transfer with full seek support.
        val dlnaInfo = "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="0">""" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            """<res protocolInfo="http-get:*:$mime:$dlnaInfo">${escapeXml(uri)}</res>""" +
            "</item></DIDL-Lite>"
    }

    // ── SOAP transport ─────────────────────────────────────────────────────────

    private suspend fun soapCall(
        device: DlnaDevice,
        action: String,
        bodyContent: String
    ): Boolean = withContext(Dispatchers.IO) {
        soapCallRaw(device, action, bodyContent) != null
    }

    /**
     * Send a SOAP action to the device's AVTransport control URL.
     *
     * Returns the raw HTTP response body on success (HTTP 2xx), or null on failure.
     * Errors are logged but never thrown.
     */
    private fun soapCallRaw(
        device: DlnaDevice,
        action: String,
        bodyContent: String
    ): String? = try {
        val envelope =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<s:Envelope """ +
                """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" """ +
                """xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
                "<s:Body>" +
                """<u:$action xmlns:u="$SERVICE_TYPE">""" +
                bodyContent +
                "</u:$action>" +
                "</s:Body></s:Envelope>"

        val conn = (URL(device.controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout    = 6_000
            doOutput       = true
            // Both header values must be double-quoted for Samsung TV compatibility
            setRequestProperty("Content-Type",  """text/xml; charset="utf-8"""")
            setRequestProperty("SOAPAction",    """"$SERVICE_TYPE#$action"""")
            setRequestProperty("Connection",    "close")
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(envelope) }

        val code = conn.responseCode
        if (code in 200..299) {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            // Read error stream for diagnostics without crashing
            val err = try {
                conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Exception) { null }
            Log.w(TAG, "SOAP $action → HTTP $code from '${device.friendlyName}': $err")
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "SOAP $action to '${device.friendlyName}' failed: ${e.message}")
        null
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    /** XML-escape a string for embedding inside element content or attribute values. */
    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Extract text content of the first occurrence of [tag] in [xml]. */
    private fun extractXmlTag(xml: String, tag: String): String? {
        val open  = "<$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf("</$tag>", start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end).trim()
    }

    /**
     * Parse a UPnP time string (HH:MM:SS or HH:MM:SS.mmm) to whole seconds.
     * Returns 0 for malformed input instead of throwing.
     */
    private fun parseUpnpTime(time: String): Int = try {
        val clean = time.substringBefore('.') // drop sub-second fraction
        val parts = clean.split(":").map { it.toLongOrNull() ?: 0L }
        when (parts.size) {
            3    -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toInt()
            2    -> (parts[0] * 60  + parts[1]).toInt()
            else -> 0
        }
    } catch (_: Exception) { 0 }
}
