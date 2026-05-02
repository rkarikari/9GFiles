package com.radiozport.ninegfiles.ui.explorer

import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentDualPaneBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dual-pane file explorer.
 *
 * Portrait (phone): SlidingPaneLayout shows one pane at a time.
 *   - A clearly labelled segmented toggle ("◀ Left Pane" / "Right Pane ▶") lets the user
 *     switch between panes. The active pane's button is filled; the inactive is outlined.
 *   - A "Swap Pane Paths" button exchanges the two panes' directories.
 *
 * Landscape / tablet: both panes are always visible side-by-side; the toggle still works
 *   as an indicator but both panes are simultaneously accessible.
 *
 * Each pane hosts its own FileExplorerFragment with isDualPane=true, giving each an
 * independent fragment-scoped ViewModel (independent path, sort, selection, history).
 */
class DualPaneFragment : Fragment() {

    private var _binding: FragmentDualPaneBinding? = null
    private val binding get() = _binding!!

    private fun leftPane()  = childFragmentManager.findFragmentByTag("pane_left")  as? FileExplorerFragment
    private fun rightPane() = childFragmentManager.findFragmentByTag("pane_right") as? FileExplorerFragment

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDualPaneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = "Dual-Pane"
            subtitle = null
        }

        // Inflate child fragments once — savedInstanceState restores them automatically
        if (savedInstanceState == null) {
            childFragmentManager.commit {
                replace(R.id.paneLeft,
                    FileExplorerFragment().apply {
                        arguments = Bundle().apply {
                            putBoolean("isDualPane", true)
                            putBoolean("isLeftPane", true)
                        }
                    }, "pane_left")
                replace(R.id.paneRight,
                    FileExplorerFragment().apply {
                        arguments = Bundle().apply {
                            putBoolean("isDualPane", true)
                            putBoolean("isLeftPane", false)
                        }
                    }, "pane_right")
            }
        }

        // ── Segmented toggle drives SlidingPaneLayout on phones ──────────────
        binding.togglePaneGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnPaneLeft  -> if (binding.slidingPane.isSlideable) binding.slidingPane.closePane()
                R.id.btnPaneRight -> if (binding.slidingPane.isSlideable) binding.slidingPane.openPane()
            }
        }

        // Select "Left" initially
        binding.togglePaneGroup.check(R.id.btnPaneLeft)

        // ── SlidingPaneLayout keeps toggle in sync when user swipes ──────────
        binding.slidingPane.addPanelSlideListener(object : SlidingPaneLayout.PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) {}
            override fun onPanelOpened(panel: View) {
                // Right pane slid into view
                binding.togglePaneGroup.check(R.id.btnPaneRight)
            }
            override fun onPanelClosed(panel: View) {
                // Left pane returned to view
                binding.togglePaneGroup.check(R.id.btnPaneLeft)
            }
        })

        // ── Swap: exchange the two panes' directories ────────────────────────
        binding.btnSwapPanes.setOnClickListener {
            val left  = leftPane()  ?: return@setOnClickListener
            val right = rightPane() ?: return@setOnClickListener
            val lp = left.currentPath()
            val rp = right.currentPath()
            if (lp.isNotEmpty() && rp.isNotEmpty()) {
                left.navigateTo(rp)
                right.navigateTo(lp)
            }
        }

        // Copy the selected files in the active pane into the other pane's current directory.
        binding.btnCopyToOther.setOnClickListener {
            val left  = leftPane()  ?: return@setOnClickListener
            val right = rightPane() ?: return@setOnClickListener
            // Determine which pane is "active" (open in a slideable layout, or always both visible).
            val (source, dest) = if (!binding.slidingPane.isSlideable || !binding.slidingPane.isOpen)
                Pair(left, right) else Pair(right, left)

            val selected = source.getSelectedItems()
            val destPath = dest.currentPath()
            if (selected.isEmpty()) {
                Snackbar.make(
                    binding.root, "Select files in the source pane first", Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (destPath.isEmpty()) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                val repo = (requireActivity().application as NineGFilesApp).fileRepository
                withContext(Dispatchers.IO) {
                    repo.copyFiles(selected, destPath) {}
                }
                dest.refresh()
                Snackbar.make(
                    binding.root,
                    "Copied ${selected.size} item(s) to ${java.io.File(destPath).name}",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }

        // Navigate the other pane to the same directory the active pane is showing.
        binding.btnOpenInOther.setOnClickListener {
            val left  = leftPane()  ?: return@setOnClickListener
            val right = rightPane() ?: return@setOnClickListener
            val (source, dest) = if (!binding.slidingPane.isSlideable || !binding.slidingPane.isOpen)
                Pair(left, right) else Pair(right, left)
            val path = source.currentPath()
            if (path.isNotEmpty()) dest.navigateTo(path)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
