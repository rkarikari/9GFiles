package com.radiozport.ninegfiles.ui.cloud

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.radiozport.ninegfiles.R

/**
 * DriveFragment — Google Drive REST API removed.
 *
 * This fragment previously hosted a Google Drive REST API browser.
 * That API integration has been removed. If you arrived here via a
 * stale deep-link or shortcut, we redirect immediately to
 * CloudStorageFragment which provides full SAF-based cloud access
 * (works with Google Drive, Dropbox, OneDrive, Box, etc.).
 */
class DriveFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = View(requireContext())  // invisible placeholder

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Redirect to the SAF-based cloud browser immediately.
        findNavController().navigate(R.id.cloudStorageFragment)
    }
}
