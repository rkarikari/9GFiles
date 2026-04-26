package com.radiozport.ninegfiles.ui.network

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class NetworkProtocol { FTP, FTPS, SFTP, WEBDAV, WEBDAVS, SMB }

@Parcelize
data class NetworkConnectionItem(
    val id: Long = System.currentTimeMillis(),
    val displayName: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",          // stored only in encrypted prefs
    val remotePath: String = "/",
    val isAnonymous: Boolean = false
) : Parcelable {

    val defaultPort get() = when (protocol) {
        NetworkProtocol.FTP     -> 21
        NetworkProtocol.FTPS    -> 990
        NetworkProtocol.SFTP    -> 22
        NetworkProtocol.WEBDAV  -> 80
        NetworkProtocol.WEBDAVS -> 443
        NetworkProtocol.SMB     -> 445
    }

    val scheme get() = when (protocol) {
        NetworkProtocol.FTP     -> "ftp"
        NetworkProtocol.FTPS    -> "ftps"
        NetworkProtocol.SFTP    -> "sftp"
        NetworkProtocol.WEBDAV  -> "http"
        NetworkProtocol.WEBDAVS -> "https"
        NetworkProtocol.SMB     -> "smb"
    }

    val protocolLabel get() = when (protocol) {
        NetworkProtocol.FTP     -> "FTP"
        NetworkProtocol.FTPS    -> "FTP (TLS)"
        NetworkProtocol.SFTP    -> "SFTP"
        NetworkProtocol.WEBDAV  -> "WebDAV"
        NetworkProtocol.WEBDAVS -> "WebDAV (HTTPS)"
        NetworkProtocol.SMB     -> "SMB / Windows Share"
    }
}

/** Lightweight representation of a remote directory entry */
data class RemoteFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = -1L,
    val lastModified: Long = 0L
)
