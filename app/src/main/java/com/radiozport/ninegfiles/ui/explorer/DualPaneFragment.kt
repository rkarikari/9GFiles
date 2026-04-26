package com.radiozport.ninegfiles.ui.explorer

import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.google.android.material.button.MaterialButtonToggleGroup
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentDualPaneBinding

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
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
