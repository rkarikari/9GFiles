package com.radiozport.ninegfiles.ui.tools

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentToolsBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardDuplicates.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_duplicates)
        }
        binding.cardLargeFiles.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_large_files)
        }
        binding.cardStorageAnalysis.setOnClickListener {
            findNavController().navigate(R.id.storageAnalysisFragment)
        }
        binding.cardEmptyFolders.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_empty_folders)
        }
        binding.cardRecentFiles.setOnClickListener {
            findNavController().navigate(R.id.searchFragment)
        }
        binding.cardRecycleBin?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_recycle_bin)
        }
        binding.cardDiskMap?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_treemap)
        }
        binding.cardSecureVault?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_vault)
        }
        binding.cardNetwork?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_network)
        }
        binding.cardDualPane?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_dual_pane)
        }
        binding.cardFtpServer?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_ftp_server)
        }
        binding.cardAppManager?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_app_manager)
        }
        binding.cardPublisherTool?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_publisher)
        }
        binding.cardEbookBuilder?.setOnClickListener {
            findNavController().navigate(R.id.action_tools_to_epub_builder)
        }

        // Show live trash summary (count + total size) in the recycle bin card
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    kotlinx.coroutines.flow.combine(
                        viewModel.trashCount,
                        viewModel.trashSize
                    ) { count, bytes -> Pair(count, bytes ?: 0L) }
                        .collectLatest { (count, bytes) ->
                            binding.tvTrashSummary?.text = when {
                                count == 0 -> "Trash is empty"
                                else -> {
                                    val items = "$count item${if (count == 1) "" else "s"}"
                                    val size  = com.radiozport.ninegfiles.utils.FileUtils.formatSize(bytes)
                                    "$items · $size"
                                }
                            }
                        }
                }

                // ── Security & Privacy section visibility ─────────────────────
                // Hidden by default; shown only when the device has been unlocked
                // with the device-specific unlock code.
                launch {
                    val prefs = (requireActivity().application as NineGFilesApp).preferences
                    prefs.securitySectionUnlocked.collectLatest { unlocked ->
                        setSecuritySectionVisible(unlocked)
                    }
                }
            }
        }
    }

    /**
     * Shows or hides every view belonging to the "Security & Privacy" section:
     * the divider, the section header, and all three feature cards.
     */
    private fun setSecuritySectionVisible(visible: Boolean) {
        // Divider and header (given IDs in the layout)
        binding.root.findViewById<View>(R.id.dividerSecurityPrivacy)?.isVisible = visible
        binding.root.findViewById<View>(R.id.tvSecurityPrivacyHeader)?.isVisible = visible
        // Feature cards
        binding.cardSecureVault?.isVisible   = visible
        binding.cardEbookBuilder?.isVisible  = visible
        binding.cardPublisherTool?.isVisible = visible
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
