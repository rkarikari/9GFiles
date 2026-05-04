package com.radiozport.ninegfiles.utils

/**
 * A UPnP/DLNA MediaRenderer discovered on the local network via SSDP.
 *
 * @param friendlyName  Human-readable device name (e.g. "Samsung TV [55]")
 * @param location      Absolute LOCATION URL returned by the SSDP response
 * @param controlUrl    Absolute AVTransport:1 service control URL
 * @param udn           Unique Device Name (UUID) from the device description XML
 */
data class DlnaDevice(
    val friendlyName: String,
    val location: String,
    val controlUrl: String,
    val udn: String
)
