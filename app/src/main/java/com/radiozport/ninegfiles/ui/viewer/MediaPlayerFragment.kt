package com.radiozport.ninegfiles.ui.viewer

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
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
import com.radiozport.ninegfiles.databinding.FragmentMediaPlayerBinding
import com.radiozport.ninegfiles.utils.CastMediaServer
import java.io.File

/**
 * Inline audio/video player with Prev / Rewind / Play / FF / Next queue support.
 *
 * Accepts either a single path (backward-compatible) or a list of paths + startIndex.
 *
 * Casting:
 *  - Uses remoteMediaClient.load() for the current item (same proven path as the
 *    original single-file implementation — avoids queueLoad() compatibility issues).
 *  - Prev/Next while casting re-calls load() with the new item's URL.
 *  - On session end, local playback resumes from the Cast position.
 *
 * Error 38 fix:
 *  - All MediaPlayer listeners are nulled before reset()/release() so no callbacks
 *    fire after the player is torn down (avoids the race between prepareAsync() and
 *    release() that caused ENOSYS/38).
 */
class MediaPlayerFragment : Fragment() {

    private var _binding: FragmentMediaPlayerBinding? = null
    private val binding get() = _binding!!

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isVideo = false

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
    private var remoteClientRef: RemoteMediaClient? = null   // which client owns the callback
    private var castProgressListener: RemoteMediaClient.ProgressListener? = null
    // Guards against double-advancing when both the progress listener and
    // the status callback fire close together at end-of-track.
    private var castAdvanceScheduled = false

    // ── Playback extras ───────────────────────────────────────────────────────
    private var currentSpeedIndex = 2  // index into SPEEDS array (1.0× default)
    private var repeatMode = "OFF"     // OFF | ALL | ONE
    private var shuffleEnabled = false
    private var sleepTimerRunnable: Runnable? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var shuffledQueue: List<String> = emptyList()
    private var shuffledIndex = 0

    companion object {
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private val SPEED_LABELS = arrayOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×")
        private const val TAG = "MediaPlayerFragment"
        private const val ARG_PATHS    = "paths"
        private const val ARG_INDEX    = "startIndex"
        private const val ARG_IS_VIDEO = "isVideo"

        /** Single-file (backward-compatible). */
        fun newInstance(path: String, isVideo: Boolean) = MediaPlayerFragment().apply {
            arguments = bundleOf(
                ARG_PATHS    to arrayListOf(path),
                ARG_INDEX    to 0,
                ARG_IS_VIDEO to isVideo
            )
        }

        /** Multi-file queue. */
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
                    binding.seekBar.progress = p.currentPosition
                    binding.tvCurrentTime.text = formatMs(p.currentPosition.toLong())
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
        override fun onSessionEnding(session: CastSession) {
            unregisterRemoteCallback()
        }
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

        // Register the whole queue with the HTTP server upfront so Cast can
        // reach any item immediately when a session starts.
        CastMediaServer.serveQueue(queue.map { path -> File(path) to deriveMime(path) })

        if (isVideo) {
            // Set up a single persistent surface callback — replaced on track change.
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
        player?.pause()
        handler.removeCallbacks(seekUpdater)
        _binding?.btnPlay?.setImageResource(android.R.drawable.ic_media_play)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(seekUpdater)
        sleepTimerRunnable?.let { sleepHandler.removeCallbacks(it) }
        unregisterRemoteCallback()
        releasePlayer()
        surfaceCallback?.let { binding.surfaceView.holder.removeCallback(it) }
        surfaceCallback = null
        CastMediaServer.stop()
        _binding = null
    }

    // ── Surface management ────────────────────────────────────────────────────

    private fun attachSurfaceCallback() {
        // Remove any previous callback before adding a new one
        surfaceCallback?.let { binding.surfaceView.holder.removeCallback(it) }
        val cb = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (_binding == null) return
                preparePlayer(holder, autoPlay = false)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player?.setDisplay(null)
            }
        }
        surfaceCallback = cb
        binding.surfaceView.holder.addCallback(cb)
    }

    // ── Queue navigation ──────────────────────────────────────────────────────

    private fun advanceLocal(direction: Int) {
        val next = currentIndex + direction
        if (next < 0 || next >= queue.size) return
        currentIndex = next
        handler.removeCallbacks(seekUpdater)
        switchTrack(autoPlay = true)
    }

    /**
     * Release the current player and load the file at [currentIndex].
     * Called for both initial load and track changes.
     */
    private fun switchTrack(autoPlay: Boolean) {
        releasePlayer()
        updateQueueUi()
        loadTrackMetadata()
        if (currentPath.isEmpty()) return
        if (isVideo) {
            attachSurfaceCallback()   // surface may already exist; callback fires immediately
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
            binding.tvNowPlaying.text = File(currentPath).name
            binding.tvQueuePosition.text = "${currentIndex + 1} / ${queue.size}"
        }
        binding.btnPrevious.isVisible = multi
        binding.btnNext.isVisible = multi
        binding.btnPrevious.alpha = if (currentIndex > 0) 1f else 0.35f
        binding.btnNext.alpha = if (currentIndex < queue.size - 1) 1f else 0.35f
    }

    // ── Metadata / album art ──────────────────────────────────────────────────

    /**
     * Asynchronously extract embedded album art (and fall back to a generic
     * audio icon) for the track at [currentIndex].  Must be called after
     * [currentIndex] has been updated.  Safe to call on the main thread.
     *
     * The stale-path guard (`capturedPath != currentPath`) ensures that a slow
     * IO result from a previously-playing track never overwrites art that was
     * already loaded for a newer track.
     */
    private fun loadTrackMetadata() {
        if (_binding == null) return
        // Album art is only meaningful for audio; video shows its own surface.
        if (isVideo) {
            binding.ivAlbumArt.isVisible = false
            return
        }

        val capturedPath = currentPath
        if (capturedPath.isEmpty()) {
            binding.ivAlbumArt.isVisible = false
            return
        }

        // Show placeholder immediately so there is never stale art visible
        // while the IO is in-flight.
        binding.ivAlbumArt.setImageResource(com.radiozport.ninegfiles.R.drawable.ic_file_audio)
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

            // Discard result if the view was destroyed or the user already
            // skipped to a different track while we were loading.
            if (_binding == null || currentPath != capturedPath) return@launch

            if (artBytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                if (bitmap != null) {
                    binding.ivAlbumArt.setImageBitmap(bitmap)
                    return@launch
                }
            }
            // No embedded art — placeholder is already set; nothing more to do.
        }
    }

    // ── Local player ──────────────────────────────────────────────────────────

    /**
     * Null out all callbacks before reset/release so no error or completion
     * callbacks fire on a dead player (prevents the error-38/ENOSYS race when
     * release() is called while prepareAsync() is still in-flight).
     */
    private fun releasePlayer() {
        player?.let { p ->
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            try { p.reset() } catch (_: Exception) {}
            p.release()
        }
        player = null
    }

    private fun preparePlayer(videoHolder: SurfaceHolder?, autoPlay: Boolean) {
        if (currentPath.isEmpty()) return
        binding.progressPrepare.isVisible = true
        binding.playerControls.isVisible = false

        try {
            player = MediaPlayer().apply {
                setDataSource(currentPath)
                if (isVideo && videoHolder != null) setDisplay(videoHolder)

                // Register the current file so Cast can serve it immediately
                CastMediaServer.serveQueue(queue.map { path -> File(path) to deriveMime(path) })

                setOnPreparedListener { mp ->
                    if (_binding == null) return@setOnPreparedListener
                    binding.progressPrepare.isVisible = false
                    binding.playerControls.isVisible = true
                    binding.seekBar.max = mp.duration
                    binding.tvTotalTime.text = formatMs(mp.duration.toLong())
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
                    val hasNext = currentIndex < queue.size - 1
                    if (hasNext) {
                        advanceLocal(+1)
                    } else {
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                        binding.seekBar.progress = 0
                        binding.tvCurrentTime.text = formatMs(0)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    _binding?.tvError?.let { tv ->
                        tv.text = "Playback error ($what/$extra)"
                        tv.isVisible = true
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            _binding?.tvError?.let { tv ->
                tv.text = "Cannot open: ${e.message}"
                tv.isVisible = true
            }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun setupControls() {
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

        binding.btnPrevious.setOnClickListener {
            val session = castSession
            if (session != null && session.isConnected) {
                if (currentIndex > 0) {
                    currentIndex--
                    loadCurrentItemOnCast()
                    updateQueueUi()
                }
            } else {
                advanceLocal(-1)
            }
        }

        binding.btnNext.setOnClickListener {
            val session = castSession
            if (session != null && session.isConnected) {
                if (currentIndex < queue.size - 1) {
                    currentIndex++
                    loadCurrentItemOnCast()
                    updateQueueUi()
                }
            } else {
                advanceLocal(+1)
            }
        }

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
                    val speed = SPEEDS[idx]
                    binding.btnSpeed.text = SPEED_LABELS[idx]
                    applyPlaybackSpeed(speed)
                }
                .show()
        }

        // ── Repeat mode ───────────────────────────────────────────────────────
        updateRepeatIcon()
        binding.btnRepeat.setOnClickListener {
            repeatMode = when (repeatMode) {
                "OFF" -> "ALL"
                "ALL" -> "ONE"
                else  -> "OFF"
            }
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
            Toast.makeText(requireContext(), if (shuffleEnabled) "Shuffle on" else "Shuffle off", Toast.LENGTH_SHORT).show()
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
        val alpha = if (repeatMode == "OFF") 0.4f else 1.0f
        binding.btnRepeat.alpha = alpha
        binding.btnRepeat.contentDescription = "Repeat: $repeatMode"
    }

    private fun updateShuffleIcon() {
        binding.btnShuffle.alpha = if (shuffleEnabled) 1.0f else 0.4f
    }

    private fun advanceWithRepeatShuffle(direction: Int) {
        when {
            shuffleEnabled -> {
                shuffledIndex = (shuffledIndex + direction).coerceIn(0, shuffledQueue.size - 1)
                currentIndex = queue.indexOf(shuffledQueue.getOrNull(shuffledIndex))
                    .coerceAtLeast(0)
            }
            repeatMode == "ONE" -> { /* stay on same track — just restart */ }
            else -> {
                val next = currentIndex + direction
                currentIndex = when {
                    next < 0 -> if (repeatMode == "ALL") queue.size - 1 else 0
                    next >= queue.size -> if (repeatMode == "ALL") 0 else queue.size - 1
                    else -> next
                }
            }
        }
    }

    // ── Cast helpers ──────────────────────────────────────────────────────────

    /**
     * Register a [RemoteMediaClient.Callback] + [RemoteMediaClient.ProgressListener] pair
     * that advances the queue when the current Cast item finishes.
     *
     * Why two mechanisms?
     *  - Some receivers default to REPEAT_SINGLE, so PLAYER_STATE_IDLE /
     *    IDLE_REASON_FINISHED is never emitted when the track loops.
     *  - The ProgressListener fires every 500 ms and triggers the advance when
     *    ≤ 800 ms remain — early enough to pre-empt the loop restart.
     *  - The status-callback is kept as a fallback for receivers that do emit
     *    IDLE_REASON_FINISHED instead of looping.
     *
     * Why this doesn't double-advance (the original bug):
     *  - Previously, loadCurrentItemOnCast() reset castAdvanceScheduled = false
     *    immediately, so a second progress tick or a stale IDLE callback could
     *    re-enter advanceCastQueue() before the new track started playing.
     *  - Now castAdvanceScheduled is reset ONLY when PLAYER_STATE_PLAYING fires,
     *    i.e. when the new track is actually running on the receiver.  The guard
     *    therefore stays true for the entire gap between load() and playback start,
     *    making both paths safe to coexist.
     */
    private fun registerRemoteCallback(session: CastSession) {
        val client = session.remoteMediaClient ?: return
        // Always unregister from the CLIENT the callback was registered on,
        // not the new session's client (they may differ on session resume).
        remoteMediaClientCallback?.let { remoteClientRef?.unregisterCallback(it) }
        castProgressListener?.let { remoteClientRef?.removeProgressListener(it) }
        remoteClientRef = client

        // ── Status callback ───────────────────────────────────────────────────
        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                if (_binding == null) return
                val status = client.mediaStatus ?: return
                when (status.playerState) {
                    MediaStatus.PLAYER_STATE_PLAYING -> {
                        // New track is actually playing on the receiver — safe to reset
                        // the guard so this track can advance when it finishes.
                        castAdvanceScheduled = false
                    }
                    MediaStatus.PLAYER_STATE_IDLE -> {
                        if (status.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                            advanceCastQueue()
                        }
                    }
                }
            }
        }
        remoteMediaClientCallback = cb
        client.registerCallback(cb)

        // ── Progress listener (primary path for looping receivers) ────────────
        // Fires every 500 ms; triggers the advance when ≤ 800 ms remain.
        // castAdvanceScheduled guards against re-entry until PLAYER_STATE_PLAYING
        // resets it, so no double-advance can occur even if this fires multiple
        // times before the new track starts.
        val pl = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
            if (_binding == null) return@ProgressListener
            if (durationMs > 0 && progressMs >= durationMs - 800) {
                advanceCastQueue()
            }
        }
        castProgressListener = pl
        client.addProgressListener(pl, 500)
    }

    private fun unregisterRemoteCallback() {
        remoteMediaClientCallback?.let { cb -> remoteClientRef?.unregisterCallback(cb) }
        castProgressListener?.let { pl -> remoteClientRef?.removeProgressListener(pl) }
        remoteMediaClientCallback = null
        castProgressListener = null
        remoteClientRef = null
    }

    /**
     * Advance to the next item in the Cast queue, guarded against double-calls
     * from the status callback and the progress listener firing simultaneously.
     * Must be called on the main thread.
     */
    private fun advanceCastQueue() {
        if (castAdvanceScheduled) return
        castAdvanceScheduled = true
        val hasNext = currentIndex < queue.size - 1
        if (hasNext) {
            currentIndex++
            loadCurrentItemOnCast()
            updateQueueUi()
        }
    }

    /**
     * Load the current queue item onto the Cast receiver using the same
     * remoteMediaClient.load(MediaLoadRequestData) path as the original
     * single-file implementation — avoids queueLoad() compatibility issues.
     *
     * The LAN URL for the current item is /media/<currentIndex>, which
     * CastMediaServer routes to the correct file.
     */
    private fun loadCurrentItemOnCast() {
        val session = castSession ?: return
        if (!session.isConnected) return

        // NOTE: castAdvanceScheduled is intentionally NOT reset here.
        // It is reset in the RemoteMediaClient.Callback when PLAYER_STATE_PLAYING fires,
        // which guarantees the guard stays active until the new track is actually playing.
        // Resetting it here (before client.load() completes) caused a race where a stale
        // IDLE_REASON_FINISHED or a late progress tick could double-advance the queue.

        val url = CastMediaServer.getUrlForIndex(requireContext(), currentIndex)
        if (url == null) {
            Toast.makeText(requireContext(), "Wi-Fi required for casting", Toast.LENGTH_SHORT).show()
            return
        }

        val localPos = player?.currentPosition ?: 0
        player?.pause()
        handler.removeCallbacks(seekUpdater)
        _binding?.btnPlay?.setImageResource(android.R.drawable.ic_media_play)

        val mime = deriveMime()
        val streamType = MediaInfo.STREAM_TYPE_BUFFERED
        val metaType = if (isVideo) MediaMetadata.MEDIA_TYPE_MOVIE
                       else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK

        val metadata = MediaMetadata(metaType).apply {
            putString(MediaMetadata.KEY_TITLE, File(currentPath).name)
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(streamType)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(localPos.toLong())
            .build()

        val client = session.remoteMediaClient ?: return

        // Fire-and-forget load; any state/error changes are surfaced by the
        // RemoteMediaClient.Callback already registered in registerRemoteCallback().
        // setRepeatMode is not available on this SDK version; the Callback already
        // handles auto-advance on IDLE_REASON_FINISHED so looping is not an issue.
        client.load(request)
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
