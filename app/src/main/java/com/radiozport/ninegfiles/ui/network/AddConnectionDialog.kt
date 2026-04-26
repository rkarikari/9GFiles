package com.radiozport.ninegfiles.ui.network

import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.DialogAddConnectionBinding

class AddConnectionDialog : BottomSheetDialogFragment() {

    private var _binding: DialogAddConnectionBinding? = null
    private val binding get() = _binding!!
    private var onAdd: ((NetworkConnectionItem) -> Unit)? = null

    companion object {
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            onAdd: (NetworkConnectionItem) -> Unit
        ) = AddConnectionDialog().apply {
            this.onAdd = onAdd
        }.show(fm, "AddConnection")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val protocols = NetworkProtocol.values().map { proto ->
            when (proto) {
                NetworkProtocol.FTP     -> "FTP"
                NetworkProtocol.FTPS    -> "FTP over TLS"
                NetworkProtocol.SFTP    -> "SFTP (SSH)"
                NetworkProtocol.WEBDAV  -> "WebDAV (HTTP)"
                NetworkProtocol.WEBDAVS -> "WebDAV (HTTPS)"
                NetworkProtocol.SMB     -> "SMB / Windows Share"
            }
        }

        val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_choice, protocols)
        (binding.spinnerProtocol as? AutoCompleteTextView)?.setAdapter(adapter)
        (binding.spinnerProtocol as? AutoCompleteTextView)?.setText(protocols[0], false)

        var selectedProtocol = NetworkProtocol.FTP

        (binding.spinnerProtocol as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            selectedProtocol = NetworkProtocol.values()[position]
            // Auto-fill default port
            val port = when (selectedProtocol) {
                NetworkProtocol.FTP     -> "21"
                NetworkProtocol.FTPS    -> "990"
                NetworkProtocol.SFTP    -> "22"
                NetworkProtocol.WEBDAV  -> "80"
                NetworkProtocol.WEBDAVS -> "443"
                NetworkProtocol.SMB     -> "445"
            }
            if (binding.etPort.text.isNullOrEmpty()) binding.etPort.setText(port)
            binding.layoutCredentials.isVisible = selectedProtocol != NetworkProtocol.SMB ||
                    !(binding.switchAnonymous.isChecked)
        }

        binding.switchAnonymous.setOnCheckedChangeListener { _, isAnonymous ->
            binding.layoutCredentials.isVisible = !isAnonymous
        }

        binding.btnConnect.setOnClickListener {
            val name = binding.etDisplayName.text?.toString()?.trim()
            val host = binding.etHost.text?.toString()?.trim()
            val portStr = binding.etPort.text?.toString()?.trim()

            if (name.isNullOrEmpty()) { binding.tilDisplayName.error = "Required"; return@setOnClickListener }
            if (host.isNullOrEmpty()) { binding.tilHost.error = "Required"; return@setOnClickListener }

            val port = portStr?.toIntOrNull() ?: selectedProtocol.let {
                when (it) {
                    NetworkProtocol.FTP     -> 21
                    NetworkProtocol.FTPS    -> 990
                    NetworkProtocol.SFTP    -> 22
                    NetworkProtocol.WEBDAV  -> 80
                    NetworkProtocol.WEBDAVS -> 443
                    NetworkProtocol.SMB     -> 445
                }
            }

            val conn = NetworkConnectionItem(
                displayName  = name!!,
                protocol     = selectedProtocol,
                host         = host!!,
                port         = port,
                username     = binding.etUsername.text?.toString() ?: "",
                password     = binding.etPassword.text?.toString() ?: "",
                remotePath   = binding.etRemotePath.text?.toString()?.ifEmpty { "/" } ?: "/",
                isAnonymous  = binding.switchAnonymous.isChecked
            )
            onAdd?.invoke(conn)
            dismiss()
        }

        binding.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
