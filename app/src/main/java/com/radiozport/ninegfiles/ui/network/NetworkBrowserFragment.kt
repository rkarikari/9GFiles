package com.radiozport.ninegfiles.ui.network

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentNetworkBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class NetworkBrowserFragment : Fragment() {

    private var _binding: FragmentNetworkBrowserBinding? = null
    private val binding get() = _binding!!

    private var activeConnection: NetworkConnectionItem? = null
    private var currentRemotePath = "/"
    private val savedConnections = mutableListOf<NetworkConnectionItem>()

    private val cloudLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Snackbar.make(binding.root, "Opened: $uri", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworkBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = "Network"
            subtitle = "Connections"
        }

        binding.rvConnections.layoutManager = LinearLayoutManager(requireContext())
        showConnectionList()

        binding.fabAddConnection.setOnClickListener { showAddConnectionDialog() }
        binding.btnOpenCloud.setOnClickListener { launchCloudPicker() }
    }

    // ─── Connection list ──────────────────────────────────────────────────

    private fun showConnectionList() {
        binding.layoutRemoteBrowser.isVisible = false
        binding.layoutConnections.isVisible = true
        binding.rvConnections.adapter = ConnectionListAdapter(
            savedConnections,
            onConnect = { connect(it) },
            onDelete  = { removeConnection(it) }
        )
        binding.tvConnectionsEmpty.isVisible = savedConnections.isEmpty()
    }

    private fun showAddConnectionDialog() {
        AddConnectionDialog.show(childFragmentManager) { conn ->
            savedConnections.add(conn)
            showConnectionList()
        }
    }

    private fun removeConnection(conn: NetworkConnectionItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove connection?")
            .setMessage("Remove \"${conn.displayName}\"?")
            .setPositiveButton("Remove") { _, _ ->
                savedConnections.remove(conn)
                showConnectionList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── FTP connection ───────────────────────────────────────────────────

    private fun connect(conn: NetworkConnectionItem) {
        activeConnection = conn
        currentRemotePath = conn.remotePath
        binding.layoutConnections.isVisible = false
        binding.layoutRemoteBrowser.isVisible = true
        binding.tvRemoteHost.text = "${conn.protocolLabel}: ${conn.host}"
        loadRemoteDirectory(conn, currentRemotePath)
    }

    private fun loadRemoteDirectory(conn: NetworkConnectionItem, path: String) {
        binding.progressNetwork.isVisible = true
        binding.tvRemotePath.text = path

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (conn.protocol) {
                    NetworkProtocol.FTP, NetworkProtocol.FTPS -> listFtp(conn, path)
                    NetworkProtocol.WEBDAV, NetworkProtocol.WEBDAVS -> listWebDav(conn, path)
                    NetworkProtocol.SMB -> listSmb(conn, path)
                    NetworkProtocol.SFTP -> listSftp(conn, path)
                }
            }
            binding.progressNetwork.isVisible = false
            result.fold(
                onSuccess = { entries ->
                    binding.rvRemoteFiles.layoutManager = LinearLayoutManager(requireContext())
                    binding.rvRemoteFiles.adapter = RemoteFileAdapter(entries) { entry ->
                        if (entry.isDirectory) {
                            currentRemotePath = "$path/${entry.name}".replace("//", "/")
                            loadRemoteDirectory(conn, currentRemotePath)
                        } else {
                            downloadFile(conn, "$path/${entry.name}", entry.name)
                        }
                    }
                },
                onFailure = { e ->
                    Snackbar.make(binding.root, "Connection failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                    showConnectionList()
                }
            )
        }
    }

    // ─── FTP ─────────────────────────────────────────────────────────────

    private fun listFtp(conn: NetworkConnectionItem, path: String): Result<List<RemoteFileEntry>> {
        val ftp = FTPClient()
        return try {
            ftp.connect(conn.host, conn.port)
            if (conn.isAnonymous) ftp.login("anonymous", "")
            else ftp.login(conn.username, conn.password)
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)

            val files: Array<FTPFile> = ftp.listFiles(path)
            val entries = files.mapNotNull { f ->
                if (f.name == "." || f.name == "..") null
                else RemoteFileEntry(
                    name = f.name,
                    isDirectory = f.isDirectory,
                    size = f.size,
                    lastModified = f.timestamp?.timeInMillis ?: 0L
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ftp.logout()
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (ftp.isConnected) ftp.disconnect()
        }
    }

    // ─── WebDAV ───────────────────────────────────────────────────────────

    private fun listWebDav(conn: NetworkConnectionItem, path: String): Result<List<RemoteFileEntry>> {
        return try {
            val url = "${conn.scheme}://${conn.host}:${conn.port}$path"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "PROPFIND"
            connection.setRequestProperty("Depth", "1")
            connection.setRequestProperty("Content-Type", "application/xml")
            if (conn.username.isNotEmpty()) {
                val creds = "${conn.username}:${conn.password}"
                val encoded = android.util.Base64.encodeToString(
                    creds.toByteArray(), android.util.Base64.NO_WRAP
                )
                connection.setRequestProperty("Authorization", "Basic $encoded")
            }
            connection.connect()

            if (connection.responseCode == 207) {
                val xml = connection.inputStream.bufferedReader().readText()
                val entries = parseWebDavPropfind(xml, path)
                Result.success(entries)
            } else {
                Result.failure(Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseWebDavPropfind(xml: String, basePath: String): List<RemoteFileEntry> {
        // Simple regex-based parsing — covers all major WebDAV servers
        val entries = mutableListOf<RemoteFileEntry>()
        val hrefPattern = Regex("<D:href>([^<]+)</D:href>", RegexOption.IGNORE_CASE)
        val collectionPattern = Regex("<D:collection/>", RegexOption.IGNORE_CASE)
        val sizePattern = Regex("<D:getcontentlength>([^<]+)</D:getcontentlength>", RegexOption.IGNORE_CASE)

        // Split into individual response blocks
        val responses = xml.split("<D:response>", ignoreCase = true).drop(1)
        for (block in responses) {
            val href = hrefPattern.find(block)?.groupValues?.get(1) ?: continue
            val name = href.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty() || href == basePath || href == "$basePath/") continue
            val isDir = collectionPattern.containsMatchIn(block)
            val size = sizePattern.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            entries += RemoteFileEntry(name = name, isDirectory = isDir, size = size)
        }
        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    // ─── SMB (jcifs-ng) ──────────────────────────────────────────────────

    private fun listSmb(conn: NetworkConnectionItem, path: String): Result<List<RemoteFileEntry>> {
        return try {
            val props = java.util.Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
            }
            val config = PropertyConfiguration(props)
            val ctx = BaseContext(config)
            val auth = if (conn.isAnonymous)
                NtlmPasswordAuthenticator("", "", "")
            else
                NtlmPasswordAuthenticator(null, conn.username, conn.password)
            val smbCtx = ctx.withCredentials(auth)
            val url = "smb://${conn.host}${if (path.startsWith("/")) path else "/$path"}"
                .let { if (!it.endsWith("/")) "$it/" else it }
            val dir = SmbFile(url, smbCtx)
            val entries = dir.listFiles()?.mapNotNull { f ->
                if (f.name == "." || f.name == "..") null
                else RemoteFileEntry(
                    name         = f.name.trimEnd('/'),
                    isDirectory  = f.isDirectory,
                    size         = if (f.isDirectory) -1L else f.length(),
                    lastModified = f.lastModified()
                )
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── SFTP (JSch mwiede fork) ──────────────────────────────────────────

    private fun listSftp(conn: NetworkConnectionItem, path: String): Result<List<RemoteFileEntry>> {
        val jsch = JSch()
        var session: com.jcraft.jsch.Session? = null
        var channel: ChannelSftp? = null
        return try {
            session = jsch.getSession(conn.username, conn.host, conn.port).apply {
                setPassword(conn.password)
                setConfig("StrictHostKeyChecking", "no")
                timeout = 15_000
                connect()
            }
            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()
            @Suppress("UNCHECKED_CAST")
            val entries = (channel.ls(path.ifEmpty { "/" }) as java.util.Vector<ChannelSftp.LsEntry>)
                .mapNotNull { entry ->
                    if (entry.filename == "." || entry.filename == "..") null
                    else RemoteFileEntry(
                        name         = entry.filename,
                        isDirectory  = entry.attrs.isDir,
                        size         = entry.attrs.size,
                        lastModified = entry.attrs.mTime.toLong() * 1000L
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }

    // ─── Download ─────────────────────────────────────────────────────────

    private fun downloadFile(conn: NetworkConnectionItem, remotePath: String, fileName: String) {
        val destDir = requireContext().getExternalFilesDir("Downloads") ?: return
        val dest = File(destDir, fileName)
        binding.progressNetwork.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    when (conn.protocol) {
                        NetworkProtocol.FTP, NetworkProtocol.FTPS -> {
                            val ftp = FTPClient()
                            ftp.connect(conn.host, conn.port)
                            if (conn.isAnonymous) ftp.login("anonymous", "")
                            else ftp.login(conn.username, conn.password)
                            ftp.enterLocalPassiveMode()
                            ftp.setFileType(FTP.BINARY_FILE_TYPE)
                            dest.outputStream().use { out -> ftp.retrieveFile(remotePath, out) }
                            ftp.logout()
                            true
                        }
                        NetworkProtocol.SFTP -> {
                            val jsch = JSch()
                            val session = jsch.getSession(conn.username, conn.host, conn.port).apply {
                                setPassword(conn.password)
                                setConfig("StrictHostKeyChecking", "no")
                                timeout = 15_000
                                connect()
                            }
                            val channel = session.openChannel("sftp") as ChannelSftp
                            channel.connect()
                            dest.outputStream().use { out -> channel.get(remotePath, out) }
                            channel.disconnect(); session.disconnect()
                            true
                        }
                        NetworkProtocol.SMB -> {
                            val props = java.util.Properties().apply {
                                setProperty("jcifs.smb.client.minVersion", "SMB202")
                            }
                            val ctx = BaseContext(PropertyConfiguration(props))
                            val auth = if (conn.isAnonymous) NtlmPasswordAuthenticator("", "", "")
                                       else NtlmPasswordAuthenticator(null, conn.username, conn.password)
                            val smbCtx = ctx.withCredentials(auth)
                            SmbFile("smb://${conn.host}$remotePath", smbCtx).inputStream.use { inn ->
                                dest.outputStream().use { out -> inn.copyTo(out) }
                            }
                            true
                        }
                        else -> false
                    }
                } catch (e: Exception) { false }
            }
            binding.progressNetwork.isVisible = false
            Snackbar.make(
                binding.root,
                if (ok) "Downloaded to ${dest.absolutePath}" else "Download failed",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ─── Cloud (SAF) ─────────────────────────────────────────────────────

    private fun launchCloudPicker() {
        cloudLauncher.launch(arrayOf("*/*"))
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    // ─── Adapters ─────────────────────────────────────────────────────────

    inner class ConnectionListAdapter(
        private val list: List<NetworkConnectionItem>,
        private val onConnect: (NetworkConnectionItem) -> Unit,
        private val onDelete:  (NetworkConnectionItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = list.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_network_connection, parent, false)
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val conn = list[position]
            holder.itemView.apply {
                findViewById<TextView>(R.id.tvConnName).text = conn.displayName
                findViewById<TextView>(R.id.tvConnDetail).text =
                    "${conn.protocolLabel} · ${conn.host}:${conn.port}"
                setOnClickListener { onConnect(conn) }
                setOnLongClickListener { onDelete(conn); true }
            }
        }
    }

    inner class RemoteFileAdapter(
        private val list: List<RemoteFileEntry>,
        private val onClick: (RemoteFileEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = list.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_file_list, parent, false)
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = list[position]
            holder.itemView.apply {
                findViewById<TextView>(R.id.tvFileName).text = entry.name
                findViewById<TextView>(R.id.tvFileInfo)?.text =
                    if (entry.isDirectory) "Folder"
                    else if (entry.size >= 0)
                        com.radiozport.ninegfiles.utils.FileUtils.formatSize(entry.size)
                    else ""
                val iconRes = if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file_generic
                findViewById<ImageView>(R.id.ivFileIcon)?.setImageResource(iconRes)
                setOnClickListener { onClick(entry) }
            }
        }
    }
}
