package com.radiozport.ninegfiles.ui.viewer

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentMediaPlayerBinding
import com.radiozport.ninegfiles.utils.CastMediaServer
import java.io.File

/**
 * Inline audio/video player with Prev / Rewind / Play / FF / Next queue support,
 * full-screen video playback, and Chromecast integration.
 *
 * Full-screen behaviour
 * ─────────────────────
 * • Tapping the ⛶ button (visible on the video surface) opens a full-screen
 *   [Dialog] that covers status bar + navigation bar.
 * • The activity orientation is switched to landscape when the video is wider
 *   than it is tall, and portrait otherwise (using [ActivityInfo]).
 * • A transparent controls overlay (top title bar + bottom seek/transport bar)
 *   auto-hides after [FS_HIDE_DELAY_MS] ms of inactivity.  Tapping anywhere on
 *   the video surface re-shows them and resets the timer.
 * • The [MediaPlayer] display is transferred to the Dialog's [SurfaceView] on
 *   entry and back to the in-fragment [SurfaceView] on exit — no re-prepare.
 * • configChanges is declared on MainActivity so rotation does not recreate
 *   the activity (and therefore does not destroy the player).
 *
 * Casting:
 *  - Uses remoteMediaClient.load() for the current item.
 *  - Prev/Next while casting re-calls load() with the new item's URL.
 *  - On session end, local playback resumes from the Cast position.
 *
 * Error 38 fix:
 *  - All MediaPlayer listeners are nulled before reset()/release() so no callbacks
 *    fire after the player is torn down.
 */
class MediaPlayerFragment : Fragment() {

    private var _binding: FragmentMediaPlayerBinding? = null
    private val binding get() = _binding!!

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isVideo = false

    // ── Video dimensions (filled once the player is prepared) ─────────────────
    private var videoWidth  = 0
    private var videoHeight = 0

    // ── Full-screen state ─────────────────────────────────────────────────────
    private var fullscreenDialog: Dialog? = null
    private var isFullscreen = false
    /** SurfaceHolder that is currently driving the in-fragment SurfaceView. */
    private var activeSurfaceHolder: SurfaceHolder? = null
    private val fsHideHandler = Handler(Looper.getMainLooper())
    private var fsHideRunnable: Runnable? = null

    // ── Queue ──────────────────────────────────────────────────────────────────
    private var queue: List<String> = emptyList()
    private var currentIndex = 0

    private val currentPath get() = queue.getOrNull(currentIndex) ?: ""

    // ── Video surface — store the single callback so we can remove it on track change ──
    private var surfaceCallback: SurfaceHolder.Callback? = null

    // ── Cast ──────────────────────────────────────────────────────────────────
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var remoteMediaClientCallback: RemoteMediaClient.Callback? = null
    private var remoteClientRef: RemoteMediaClient? = null
    private var castProgressListener: RemoteMediaClient.ProgressListener? = null
    private var castAdvanceScheduled = false

    // ── Playback extras ───────────────────────────────────────────────────────
    private var currentSpeedIndex = 2  // index into SPEEDS (1.0× default)
    private var repeatMode = "OFF"     // OFF | ALL | ONE
    private var shuffleEnabled = false
    private var sleepTimerRunnable: Runnable? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var shuffledQueue: List<String> = emptyList()
    private var shuffledIndex = 0

    companion object {
        private val SPEEDS      = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private val SPEED_LABELS = arrayOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×")
        private const val TAG          = "MediaPlayerFragment"
        private const val ARG_PATHS    = "paths"
        private const val ARG_INDEX    = "startIndex"
        private const val ARG_IS_VIDEO = "isVideo"
        /** Milliseconds of inactivity before fullscreen controls auto-hide. */
        private const val FS_HIDE_DELAY_MS = 3_000L

        fun newInstance(path: String, isVideo: Boolean) = MediaPlayerFragment().apply {
            arguments = bundleOf(
                ARG_PATHS    to arrayListOf(path),
                ARG_INDEX    to 0,
                ARG_IS_VIDEO to isVideo
            )
        }

        fun newInstance(paths: List<String>, startIndex: Int, isVideo: Boolean) =
            MediaPlayerFragment().apply {
                arguments = bundleOf(
                    ARG_PATHS    to ArrayList(paths),
                    ARG_INDEX    to startIndex,
                    ARG_IS_VIDEO to isVideo
                )
            }
    }

    // ── Seek-bar updater ──────────────────────────────────────────────────────

    private val seekUpdater = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.isPlaying) {
                    val pos = p.currentPosition
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = formatMs(pos.toLong())
                    // Mirror into fullscreen controls if open
                    fullscreenDialog?.let { dlg ->
                        if (dlg.isShowing) {
                            dlg.findViewById<SeekBar>(R.id.fsSeekBar)?.progress = pos
                            dlg.findViewById<android.widget.TextView>(R.id.fsTvCurrentTime)
                                ?.text = formatMs(pos.toLong())
                        }
                    }
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    // ── Cast session listener ─────────────────────────────────────────────────

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castSession = session
            loadCurrentItemOnCast()
            registerRemoteCallback(session)
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
            registerRemoteCallback(session)
        }
        override fun onSessionEnding(session: CastSession) { unregisterRemoteCallback() }
        override fun onSessionEnded(session: CastSession, error: Int) {
            val remotePos = try {
                session.remoteMediaClient?.approximateStreamPosition?.toInt() ?: 0
            } catch (_: Exception) { 0 }
            castSession = null
            player?.seekTo(remotePos)
            player?.start()
            handler.post(seekUpdater)
            _binding?.btnPlay?.setImageResource(android.R.drawable.ic_media_pause)
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("UNCHECKED_CAST")
        queue = (arguments?.getStringArrayList(ARG_PATHS) as? List<String>) ?: emptyList()
        currentIndex = arguments?.getInt(ARG_INDEX, 0) ?: 0
        isVideo = arguments?.getBoolean(ARG_IS_VIDEO) ?: false
        binding.surfaceContainer.isVisible = isVideo

        castContext = try {
            CastContext.getSharedInstance(requireContext())
        } catch (e: Exception) {
            Log.w(TAG, "Cast not available: ${e.message}"); null
        }
        castContext?.let { ctx ->
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.btnCast)
            castSession = ctx.sessionManager.currentCastSession
        }

        CastMediaServer.start()
        setupControls()

        CastMediaServer.serveQueue(queue.map { path -> File(path) to deriveMime(path) })

        if (isVideo) {
            attachSurfaceCallback()
        } else {
            loadTrackMetadata()
            preparePlayer(null, autoPlay = false)
        }
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager
            ?.addSessionManagerListener(sessionListener, CastSession::class.java)
        castSession = castContext?.sessionManager?.currentCastSession
    }

    override fun onPause() {
        super.onPause()
        castContext?.sessionManager
            ?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        // Don't interrupt playback if the fullscreen dialog is active
        if (!isFullscreen) {
            player?.pause()
            handler.removeCallbacks(seekUpdater)
            _binding?.btnPlay?.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(seekUpdater)
        sleepTimerRunnable?.let { sleepHandler.removeCallbacks(it) }
        cancelFsHideTimer()
        unregisterRemoteCallback()
        fullscreenDialog?.setOnDismissListener(null)
        fullscreenDialog?.dismiss()
        fullscreenDialog = null
        releasePlayer()
        surfaceCallback?.let { binding.surfaceView.holder.removeCallback(it) }
        surfaceCallback = null
        CastMediaServer.stop()
        _binding = null
    }

    // ── Surface management ────────────────────────────────────────────────────

    private fun attachSurfaceCallback() {
        surfaceCallback?.let { binding.surfaceView.holder.removeCallback(it) }
        val cb = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (_binding == null) return
                activeSurfaceHolder = holder
                // Only prepare/display here when fullscreen is NOT active;
                // in fullscreen the Dialog's SurfaceView owns the display.
                if (!isFullscreen) {
                    preparePlayer(holder, autoPlay = false)
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (!isFullscreen) player?.setDisplay(null)
            }
        }
        surfaceCallback = cb
        binding.surfaceView.holder.addCallback(cb)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Full-screen
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Open a full-screen [Dialog] that shows the video surface and a thin
     * auto-hiding controls overlay.
     *
     * Orientation is chosen from [videoWidth] / [videoHeight]:
     *  • SENSOR_LANDSCAPE when width > height (landscape video)
     *  • SENSOR_PORTRAIT  otherwise            (portrait / square video)
     */
    private fun enterFullscreen() {
        if (isFullscreen || fullscreenDialog != null) return
        val p = player ?: return
        isFullscreen = true

        // 1. Switch to the correct orientation for this video's aspect ratio
        val targetOrientation =
            if (videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        requireActivity().requestedOrientation = targetOrientation

        // 2. Build a true full-screen Dialog (covers status + nav bars)
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }

        val fsView = LayoutInflater.from(requireContext())
            .inflate(R.layout.overlay_fullscreen_player, null)
        dialog.setContentView(fsView)

        // 3. Attach to the Dialog's SurfaceView
        val fsSurface = fsView.findViewById<SurfaceView>(R.id.fsSurfaceView)
        fsSurface.setOnClickListener { toggleFsControls(dialog) }
        fsSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                player?.setDisplay(holder)
            }
            override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (isFullscreen) player?.setDisplay(null)
            }
        })

        // 4. Wire controls
        setupFullscreenControls(dialog, fsView, p)

        // 5. Show
        dialog.setOnDismissListener { exitFullscreen() }
        dialog.show()
        fullscreenDialog = dialog
        scheduleFsHide(dialog)
    }

    /**
     * Tear down the full-screen Dialog and return the player display to the
     * in-fragment SurfaceView.
     */
    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        cancelFsHideTimer()

        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Return display to the fragment SurfaceView
        activeSurfaceHolder?.let { player?.setDisplay(it) }
            ?: run { player?.setDisplay(null) }

        val dlg = fullscreenDialog
        fullscreenDialog = null
        dlg?.setOnDismissListener(null)
        if (dlg?.isShowing == true) dlg.dismiss()

        _binding?.btnPlay?.setImageResource(
            if (player?.isPlaying == true) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    // ── Fullscreen controls wiring ────────────────────────────────────────────

    private fun setupFullscreenControls(dialog: Dialog, fsView: View, p: MediaPlayer) {
        val fsBtnPlay   = fsView.findViewById<android.widget.ImageButton>(R.id.fsBtnPlay)
        val fsBtnRewind = fsView.findViewById<android.widget.ImageButton>(R.id.fsBtnRewind)
        val fsBtnFf     = fsView.findViewById<android.widget.ImageButton>(R.id.fsBtnFastForward)
        val fsBtnPrev   = fsView.findViewById<android.widget.ImageButton>(R.id.fsBtnPrevious)
        val fsBtnNext   = fsView.findViewById<android.widget.ImageButton>(R.id.fsBtnNext)
        val fsBtnExit   = fsView.findViewById<android.widget.ImageButton>(R.id.fsBtnExitFullscreen)
        val fsSeek      = fsView.findViewById<SeekBar>(R.id.fsSeekBar)
        val fsTvCurrent = fsView.findViewById<android.widget.TextView>(R.id.fsTvCurrentTime)
        val fsTvTotal   = fsView.findViewById<android.widget.TextView>(R.id.fsTvTotalTime)
        val fsTvTitle   = fsView.findViewById<android.widget.TextView>(R.id.fsTvTitle)
        val overlay     = fsView.findViewById<FrameLayout>(R.id.fsControlsOverlay)

        fsTvTitle.text      = File(currentPath).name
        fsSeek.max          = p.duration
        fsTvTotal.text      = formatMs(p.duration.toLong())
        fsSeek.progress     = p.currentPosition
        fsTvCurrent.text    = formatMs(p.currentPosition.toLong())
        fsBtnPlay.setImageResource(
            if (p.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        fsBtnPrev.alpha = if (currentIndex > 0) 1f else 0.35f
        fsBtnNext.alpha = if (currentIndex < queue.size - 1) 1f else 0.35f

        // Tap controls overlay to reset hide timer
        overlay.setOnClickListener { resetFsHideTimer(dialog) }

        // Exit fullscreen
        fsBtnExit.setOnClickListener { dialog.dismiss() }

        // Play / Pause
        fsBtnPlay.setOnClickListener {
            resetFsHideTimer(dialog)
            val session = castSession
            if (session != null && session.isConnected) {
                val client = session.remoteMediaClient ?: return@setOnClickListener
                if (client.isPlaying) client.pause() else client.play()
            } else {
                if (p.isPlaying) {
                    p.pause()
                    handler.removeCallbacks(seekUpdater)
                    fsBtnPlay.setImageResource(android.R.drawable.ic_media_play)
                    _binding?.btnPlay?.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    p.start()
                    handler.post(seekUpdater)
                    fsBtnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    _binding?.btnPlay?.setImageResource(android.R.drawable.ic_media_pause)
                }
            }
        }

        // Rewind
        fsBtnRewind.setOnClickListener {
            resetFsHideTimer(dialog)
            val session = castSession
            if (session != null && session.isConnected) {
                val client = session.remoteMediaClient ?: return@setOnClickListener
                val pos = (client.approximateStreamPosition - 10_000).coerceAtLeast(0)
                client.seek(MediaSeekOptions.Builder().setPosition(pos).build())
            } else {
                p.seekTo(maxOf(0, p.currentPosition - 10_000))
            }
        }

        // Fast-forward
        fsBtnFf.setOnClickListener {
            resetFsHideTimer(dialog)
            val session = castSession
            if (session != null && session.isConnected) {
                val client = session.remoteMediaClient ?: return@setOnClickListener
                val dur = client.mediaInfo?.streamDuration ?: Long.MAX_VALUE
                val pos = (client.approximateStreamPosition + 10_000).coerceAtMost(dur)
                client.seek(MediaSeekOptions.Builder().setPosition(pos).build())
            } else {
                p.seekTo(minOf(p.duration, p.currentPosition + 10_000))
            }
        }

        // Previous
        fsBtnPrev.setOnClickListener {
            resetFsHideTimer(dialog)
            val session = castSession
            if (session != null && session.isConnected) {
                if (currentIndex > 0) { currentIndex--; loadCurrentItemOnCast(); updateQueueUi() }
            } else {
                advanceLocal(-1)
            }
            fsTvTitle.text  = File(currentPath).name
            fsBtnPrev.alpha = if (currentIndex > 0) 1f else 0.35f
            fsBtnNext.alpha = if (currentIndex < queue.size - 1) 1f else 0.35f
        }

        // Next
        fsBtnNext.setOnClickListener {
            resetFsHideTimer(dialog)
            val session = castSession
            if (session != null && session.isConnected) {
                if (currentIndex < queue.size - 1) { currentIndex++; loadCurrentItemOnCast(); updateQueueUi() }
            } else {
                advanceLocal(+1)
            }
            fsTvTitle.text  = File(currentPath).name
            fsBtnPrev.alpha = if (currentIndex > 0) 1f else 0.35f
            fsBtnNext.alpha = if (currentIndex < queue.size - 1) 1f else 0.35f
        }

        // Seek bar
        fsSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val session = castSession
                    if (session != null && session.isConnected) {
                        session.remoteMediaClient?.seek(
                            MediaSeekOptions.Builder().setPosition(progress.toLong()).build()
                        )
                    } else {
                        p.seekTo(progress)
                    }
                    fsTvCurrent.text = formatMs(progress.toLong())
                    _binding?.seekBar?.progress = progress
                    _binding?.tvCurrentTime?.text = formatMs(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { cancelFsHideTimer() }
            override fun onStopTrackingTouch(sb: SeekBar?)  { scheduleFsHide(dialog) }
        })
    }

    // ── Fullscreen auto-hide helpers ──────────────────────────────────────────

    private fun scheduleFsHide(dialog: Dialog) {
        cancelFsHideTimer()
        val r = Runnable { hideFsControls(dialog) }
        fsHideRunnable = r
        fsHideHandler.postDelayed(r, FS_HIDE_DELAY_MS)
    }

    private fun resetFsHideTimer(dialog: Dialog) {
        showFsControls(dialog)
        scheduleFsHide(dialog)
    }

    private fun cancelFsHideTimer() {
        fsHideRunnable?.let { fsHideHandler.removeCallbacks(it) }
        fsHideRunnable = null
    }

    private fun showFsControls(dialog: Dialog) {
        if (!dialog.isShowing) return
        dialog.findViewById<View>(R.id.fsControlsOverlay)
            ?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun hideFsControls(dialog: Dialog) {
        if (!dialog.isShowing) return
        dialog.findViewById<View>(R.id.fsControlsOverlay)
            ?.animate()?.alpha(0f)?.setDuration(400)?.start()
    }

    private fun toggleFsControls(dialog: Dialog) {
        val overlay = dialog.findViewById<View>(R.id.fsControlsOverlay) ?: return
        if (overlay.alpha > 0.5f) {
            cancelFsHideTimer()
            hideFsControls(dialog)
        } else {
            resetFsHideTimer(dialog)
        }
    }

    // ── Queue navigation ──────────────────────────────────────────────────────

    private fun advanceLocal(direction: Int) {
        val next = currentIndex + direction
        if (next < 0 || next >= queue.size) return
        currentIndex = next
        handler.removeCallbacks(seekUpdater)
        switchTrack(autoPlay = true)
    }

    private fun switchTrack(autoPlay: Boolean) {
        releasePlayer()
        videoWidth  = 0
        videoHeight = 0
        updateQueueUi()
        loadTrackMetadata()
        if (currentPath.isEmpty()) return
        if (isVideo) {
            attachSurfaceCallback()
        } else {
            preparePlayer(null, autoPlay)
        }
    }

    private fun updateQueueUi() {
        if (_binding == null) return
        val multi = queue.size > 1
        binding.tvNowPlaying.isVisible = multi
        binding.tvQueuePosition.isVisible = multi
        if (multi) {
            binding.tvNowPlaying.text    = File(currentPath).name
            binding.tvQueuePosition.text = "${currentIndex + 1} / ${queue.size}"
        }
        binding.btnPrevious.isVisible = multi
        binding.btnNext.isVisible     = multi
        binding.btnPrevious.alpha = if (currentIndex > 0) 1f else 0.35f
        binding.btnNext.alpha     = if (currentIndex < queue.size - 1) 1f else 0.35f
    }

    // ── Metadata / album art ──────────────────────────────────────────────────

    private fun loadTrackMetadata() {
        if (_binding == null) return
        if (isVideo) { binding.ivAlbumArt.isVisible = false; return }

        val capturedPath = currentPath
        if (capturedPath.isEmpty()) { binding.ivAlbumArt.isVisible = false; return }

        binding.ivAlbumArt.setImageResource(R.drawable.ic_file_audio)
        binding.ivAlbumArt.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val artBytes: ByteArray? = withContext(Dispatchers.IO) {
                try {
                    MediaMetadataRetriever().run {
                        setDataSource(capturedPath)
                        val bytes = embeddedPicture
                        release()
                        bytes
                    }
                } catch (_: Exception) { null }
            }
            if (_binding == null || currentPath != capturedPath) return@launch
            if (artBytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                if (bitmap != null) { binding.ivAlbumArt.setImageBitmap(bitmap); return@launch }
            }
        }
    }

    // ── Local player ──────────────────────────────────────────────────────────

    private fun releasePlayer() {
        player?.let { p ->
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.setOnVideoSizeChangedListener(null)
            try { p.reset() } catch (_: Exception) {}
            p.release()
        }
        player = null
        activeSurfaceHolder = null
    }

    private fun preparePlayer(videoHolder: SurfaceHolder?, autoPlay: Boolean) {
        if (currentPath.isEmpty()) return
        binding.progressPrepare.isVisible = true
        binding.playerControls.isVisible  = false

        try {
            player = MediaPlayer().apply {
                setDataSource(currentPath)
                if (isVideo && videoHolder != null) {
                    setDisplay(videoHolder)
                    activeSurfaceHolder = videoHolder
                }

                CastMediaServer.serveQueue(queue.map { path -> File(path) to deriveMime(path) })

                // Capture dimensions for fullscreen orientation decision
                setOnVideoSizeChangedListener { _, width, height ->
                    this@MediaPlayerFragment.videoWidth  = width
                    this@MediaPlayerFragment.videoHeight = height
                }

                setOnPreparedListener { mp ->
                    if (_binding == null) return@setOnPreparedListener
                    binding.progressPrepare.isVisible = false
                    binding.playerControls.isVisible  = true
                    binding.seekBar.max       = mp.duration
                    binding.tvTotalTime.text  = formatMs(mp.duration.toLong())
                    updateQueueUi()
                    if (autoPlay) {
                        mp.start()
                        handler.post(seekUpdater)
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    } else {
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
                setOnCompletionListener {
                    if (_binding == null) return@setOnCompletionListener
                    handler.removeCallbacks(seekUpdater)
                    if (currentIndex < queue.size - 1) {
                        advanceLocal(+1)
                    } else {
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                        binding.seekBar.progress = 0
                        binding.tvCurrentTime.text = formatMs(0)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    _binding?.tvError?.let { tv ->
                        tv.text      = "Playback error ($what/$extra)"
                        tv.isVisible = true
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            _binding?.tvError?.let { tv ->
                tv.text      = "Cannot open: ${e.message}"
                tv.isVisible = true
            }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun setupControls() {
        // ── Play / Pause ──────────────────────────────────────────────────────
        binding.btnPlay.setOnClickListener {
            val session = castSession
            if (session != null && session.isConnected) {
                val client = session.remoteMediaClient ?: return@setOnClickListener
                if (client.isPlaying) client.pause() else client.play()
                return@setOnClickListener
            }
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                handler.removeCallbacks(seekUpdater)
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
            } else {
                p.start()
                handler.post(seekUpdater)
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        // ── Rewind 10s ────────────────────────────────────────────────────────
        binding.btnRewind.setOnClickListener {
            val session = castSession
            if (session != null && session.isConnected) {
                val client = session.remoteMediaClient ?: return@setOnClickListener
                val pos = (client.approximateStreamPosition - 10_000).coerceAtLeast(0)
                client.seek(MediaSeekOptions.Builder().setPosition(pos).build())
            } else {
                player?.let { it.seekTo(maxOf(0, it.currentPosition - 10_000)) }
            }
        }

        // ── Fast-forward 10s ──────────────────────────────────────────────────
        binding.btnFastForward.setOnClickListener {
            val session = castSession
            if (session != null && session.isConnected) {
                val client = session.remoteMediaClient ?: return@setOnClickListener
                val dur = client.mediaInfo?.streamDuration ?: Long.MAX_VALUE
                val pos = (client.approximateStreamPosition + 10_000).coerceAtMost(dur)
                client.seek(MediaSeekOptions.Builder().setPosition(pos).build())
            } else {
                player?.let { it.seekTo(minOf(it.duration, it.currentPosition + 10_000)) }
            }
        }

        // ── Previous track ────────────────────────────────────────────────────
        binding.btnPrevious.setOnClickListener {
            val session = castSession
            if (session != null && session.isConnected) {
                if (currentIndex > 0) { currentIndex--; loadCurrentItemOnCast(); updateQueueUi() }
            } else {
                advanceLocal(-1)
            }
        }

        // ── Next track ────────────────────────────────────────────────────────
        binding.btnNext.setOnClickListener {
            val session = castSession
            if (session != null && session.isConnected) {
                if (currentIndex < queue.size - 1) { currentIndex++; loadCurrentItemOnCast(); updateQueueUi() }
            } else {
                advanceLocal(+1)
            }
        }

        // ── Full-screen toggle ────────────────────────────────────────────────
        binding.btnFullscreen.isVisible = isVideo
        binding.btnFullscreen.setOnClickListener {
            if (isFullscreen) exitFullscreen() else enterFullscreen()
        }

        // ── Seek bar ──────────────────────────────────────────────────────────
        binding.seekBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                ) {
                    if (fromUser) {
                        val session = castSession
                        if (session != null && session.isConnected) {
                            session.remoteMediaClient?.seek(
                                MediaSeekOptions.Builder().setPosition(progress.toLong()).build()
                            )
                        } else {
                            player?.seekTo(progress)
                        }
                        binding.tvCurrentTime.text = formatMs(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )

        // ── Playback speed ────────────────────────────────────────────────────
        binding.btnSpeed.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Playback Speed")
                .setItems(SPEED_LABELS) { _, idx ->
                    currentSpeedIndex = idx
                    binding.btnSpeed.text = SPEED_LABELS[idx]
                    applyPlaybackSpeed(SPEEDS[idx])
                }
                .show()
        }

        // ── Repeat ────────────────────────────────────────────────────────────
        updateRepeatIcon()
        binding.btnRepeat.setOnClickListener {
            repeatMode = when (repeatMode) { "OFF" -> "ALL"; "ALL" -> "ONE"; else -> "OFF" }
            updateRepeatIcon()
            Toast.makeText(requireContext(), "Repeat: $repeatMode", Toast.LENGTH_SHORT).show()
        }

        // ── Shuffle ───────────────────────────────────────────────────────────
        updateShuffleIcon()
        binding.btnShuffle.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            if (shuffleEnabled) {
                shuffledQueue = queue.toMutableList().also { it.shuffle() }
                shuffledIndex = shuffledQueue.indexOf(currentPath).coerceAtLeast(0)
            }
            updateShuffleIcon()
            Toast.makeText(requireContext(),
                if (shuffleEnabled) "Shuffle on" else "Shuffle off", Toast.LENGTH_SHORT).show()
        }

        // ── Sleep timer ───────────────────────────────────────────────────────
        binding.btnSleepTimer.setOnClickListener {
            val options = arrayOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "Cancel timer")
            val minutes = intArrayOf(15, 30, 45, 60, -1)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Sleep Timer")
                .setItems(options) { _, idx ->
                    sleepTimerRunnable?.let { sleepHandler.removeCallbacks(it) }
                    if (minutes[idx] == -1) {
                        binding.btnSleepTimer.alpha = 1.0f
                        Toast.makeText(requireContext(), "Sleep timer cancelled", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    val ms = minutes[idx] * 60_000L
                    binding.btnSleepTimer.alpha = 0.6f
                    val r = Runnable {
                        player?.pause()
                        handler.removeCallbacks(seekUpdater)
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                        binding.btnSleepTimer.alpha = 1.0f
                        Toast.makeText(requireContext(), "Sleep timer: stopped playback", Toast.LENGTH_LONG).show()
                    }
                    sleepTimerRunnable = r
                    sleepHandler.postDelayed(r, ms)
                    Toast.makeText(requireContext(), "Sleep in ${options[idx]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val p = player ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                p.playbackParams = p.playbackParams.setSpeed(speed)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Speed change not supported for this media", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateRepeatIcon() {
        binding.btnRepeat.alpha = if (repeatMode == "OFF") 0.4f else 1.0f
        binding.btnRepeat.contentDescription = "Repeat: $repeatMode"
    }

    private fun updateShuffleIcon() {
        binding.btnShuffle.alpha = if (shuffleEnabled) 1.0f else 0.4f
    }

    @Suppress("unused")
    private fun advanceWithRepeatShuffle(direction: Int) {
        when {
            shuffleEnabled -> {
                shuffledIndex = (shuffledIndex + direction).coerceIn(0, shuffledQueue.size - 1)
                currentIndex  = queue.indexOf(shuffledQueue.getOrNull(shuffledIndex)).coerceAtLeast(0)
            }
            repeatMode == "ONE" -> { /* stay on same track */ }
            else -> {
                val next = currentIndex + direction
                currentIndex = when {
                    next < 0          -> if (repeatMode == "ALL") queue.size - 1 else 0
                    next >= queue.size -> if (repeatMode == "ALL") 0 else queue.size - 1
                    else              -> next
                }
            }
        }
    }

    // ── Cast helpers ──────────────────────────────────────────────────────────

    private fun registerRemoteCallback(session: CastSession) {
        val client = session.remoteMediaClient ?: return
        remoteMediaClientCallback?.let { remoteClientRef?.unregisterCallback(it) }
        castProgressListener?.let { remoteClientRef?.removeProgressListener(it) }
        remoteClientRef = client

        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                if (_binding == null) return
                val status = client.mediaStatus ?: return
                when (status.playerState) {
                    MediaStatus.PLAYER_STATE_PLAYING -> castAdvanceScheduled = false
                    MediaStatus.PLAYER_STATE_IDLE    ->
                        if (status.idleReason == MediaStatus.IDLE_REASON_FINISHED) advanceCastQueue()
                }
            }
        }
        remoteMediaClientCallback = cb
        client.registerCallback(cb)

        val pl = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
            if (_binding == null) return@ProgressListener
            if (durationMs > 0 && progressMs >= durationMs - 800) advanceCastQueue()
        }
        castProgressListener = pl
        client.addProgressListener(pl, 500)
    }

    private fun unregisterRemoteCallback() {
        remoteMediaClientCallback?.let { cb -> remoteClientRef?.unregisterCallback(cb) }
        castProgressListener?.let { pl -> remoteClientRef?.removeProgressListener(pl) }
        remoteMediaClientCallback = null
        castProgressListener      = null
        remoteClientRef           = null
    }

    private fun advanceCastQueue() {
        if (castAdvanceScheduled) return
        castAdvanceScheduled = true
        if (currentIndex < queue.size - 1) {
            currentIndex++
            loadCurrentItemOnCast()
            updateQueueUi()
        }
    }

    private fun loadCurrentItemOnCast() {
        val session = castSession ?: return
        if (!session.isConnected) return

        val url = CastMediaServer.getUrlForIndex(requireContext(), currentIndex)
        if (url == null) {
            Toast.makeText(requireContext(), "Wi-Fi required for casting", Toast.LENGTH_SHORT).show()
            return
        }

        val localPos = player?.currentPosition ?: 0
        player?.pause()
        handler.removeCallbacks(seekUpdater)
        _binding?.btnPlay?.setImageResource(android.R.drawable.ic_media_play)

        val mime       = deriveMime()
        val metaType   = if (isVideo) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        val metadata   = MediaMetadata(metaType).apply {
            putString(MediaMetadata.KEY_TITLE, File(currentPath).name)
        }
        val mediaInfo  = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()
        val request    = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(localPos.toLong())
            .build()

        session.remoteMediaClient?.load(request)
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun deriveMime(path: String = currentPath): String =
        when (File(path).extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv"        -> "video/x-matroska"
            "avi"        -> "video/x-msvideo"
            "mov"        -> "video/quicktime"
            "webm"       -> "video/webm"
            "mp3"        -> "audio/mpeg"
            "flac"       -> "audio/flac"
            "aac"        -> "audio/aac"
            "wav"        -> "audio/wav"
            "ogg"        -> "audio/ogg"
            "m4a"        -> "audio/mp4"
            "opus"       -> "audio/opus"
            else         -> if (isVideo) "video/*" else "audio/*"
        }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
