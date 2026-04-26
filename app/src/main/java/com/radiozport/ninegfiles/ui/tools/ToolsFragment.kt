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

        // Show live trash summary in the recycle bin card
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.trashCount.collectLatest { count ->
                        binding.tvTrashSummary?.text =
                            if (count == 0) "Trash is empty"
                            else "$count item${if (count == 1) "" else "s"} in trash"
                    }
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
