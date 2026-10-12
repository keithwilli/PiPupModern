package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

// Note: media dimensions are in pixels, matching the original PiPup API.

@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    open fun create() {
        inflate(context, R.layout.popup, this)

        orientation = VERTICAL
        minimumWidth = 240
        setPadding(20, 20, 20, 20)

        val title = findViewById<TextView>(R.id.popup_title)
        val message = findViewById<TextView>(R.id.popup_message)
        val frame = findViewById<FrameLayout>(R.id.popup_frame)

        if (popup.media == null) {
            removeView(frame)
        }

        if (popup.title.isNullOrEmpty()) {
            removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(parseColor(popup.titleColor, Color.WHITE))
        }

        if (popup.message.isNullOrEmpty()) {
            removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(parseColor(popup.messageColor, Color.WHITE))
        }

        setBackgroundColor(parseColor(popup.backgroundColor, Color.parseColor(PopupProps.DEFAULT_BACKGROUND_COLOR)))
    }

    open fun destroy() {}

    private class Default(context: Context, popup: PopupProps) : PopupView(context, popup) {
        init {
            create()
        }
    }

    /**
     * Plays a video or live camera stream using Media3 ExoPlayer.
     * Supports rtsp:// (camera streams), HLS (.m3u8) and regular http(s) video files.
     * Shows a status line inside the popup while connecting or when playback fails,
     * and records each step in [Diagnostics] for the status page.
     */
    @OptIn(UnstableApi::class)
    private class Video(context: Context, popup: PopupProps, val media: PopupProps.Media.Video) :
        PopupView(context, popup) {

        private var mPlayer: ExoPlayer? = null
        private var mStatus: TextView? = null
        private val mHandler = Handler(Looper.getMainLooper())
        private var mRetries = 0
        private var mFirstFrameShown = false

        init {
            create()
        }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val safeUri = Diagnostics.redact(media.uri)

            // Small buffers so live camera streams start quickly and stay close to real time
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 250, 500)
                .build()

            val player = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    volume = if (media.muted) 0f else 1f
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            Diagnostics.record("Player state: ${stateName(playbackState)}")
                        }

                        override fun onRenderedFirstFrame() {
                            mFirstFrameShown = true
                            Diagnostics.record("Video showing")
                            mStatus?.visibility = View.GONE
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val reason = describe(error)
                            Log.e(LOG_TAG, "Playback error for $safeUri: $reason", error)
                            Diagnostics.record("ERROR: $reason")

                            if (mRetries < MAX_RETRIES) {
                                mRetries++
                                showStatus("$reason\nRetrying ($mRetries/$MAX_RETRIES)…")
                                mHandler.postDelayed({ startPlayback() }, RETRY_DELAY_MS)
                            } else {
                                showStatus(reason)
                            }
                        }
                    })
                }
            mPlayer = player

            val playerView = PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.BLACK)
                this.player = player
            }

            // Reserve a 16:9 area straight away so the popup appears immediately,
            // even while the camera stream is still connecting.
            val height = media.width * 9 / 16
            frame.addView(playerView, FrameLayout.LayoutParams(media.width, height).apply {
                gravity = Gravity.CENTER
            })

            // Status text shown on top of the video area until the first frame arrives
            mStatus = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
                text = "Connecting…"
            }
            frame.addView(mStatus, FrameLayout.LayoutParams(media.width, height).apply {
                gravity = Gravity.CENTER
            })

            Diagnostics.record("Opening $safeUri")
            startPlayback()

            // If nothing has appeared after a while and no error was reported, say so
            mHandler.postDelayed({
                if (!mFirstFrameShown) {
                    val state = mPlayer?.playbackState?.let { stateName(it) } ?: "released"
                    Diagnostics.record("No video after ${WATCHDOG_MS / 1000}s (player state: $state)")
                    if (mStatus?.text?.toString() == "Connecting…") {
                        showStatus("No video received yet\n(player state: $state)")
                    }
                }
            }, WATCHDOG_MS)
        }

        private fun showStatus(text: String) {
            mStatus?.apply {
                this.text = text
                visibility = View.VISIBLE
            }
        }

        private fun startPlayback() {
            val player = mPlayer ?: return
            val uri = Uri.parse(media.uri)
            val mediaItem = MediaItem.fromUri(uri)

            if (uri.scheme.equals("rtsp", ignoreCase = true) || uri.scheme.equals("rtsps", ignoreCase = true)) {
                // TCP transport is far more reliable than UDP for most IP cameras
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(RTSP_TIMEOUT_MS)
                    .createMediaSource(mediaItem)
                player.setMediaSource(source)
            } else {
                player.setMediaItem(mediaItem)
            }
            player.prepare()
        }

        override fun destroy() {
            mHandler.removeCallbacksAndMessages(null)
            try {
                mPlayer?.release()
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "Failed to release player", e)
            }
            mPlayer = null
        }

        companion object {
            const val MAX_RETRIES = 3
            const val RETRY_DELAY_MS = 1000L
            const val WATCHDOG_MS = 12000L
            const val RTSP_TIMEOUT_MS = 8000L

            fun stateName(state: Int): String = when (state) {
                Player.STATE_IDLE -> "idle"
                Player.STATE_BUFFERING -> "connecting/buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "unknown ($state)"
            }

            /** Turns a playback error into a short, human-readable reason. */
            fun describe(error: PlaybackException): String {
                val hint = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "Can't connect to the camera. Check its IP address and that RTSP is turned on."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "The camera didn't answer in time. Check its IP address and that RTSP is turned on."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "The camera refused the request. Check the username, password and stream name."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        "This TV can't decode the camera's video format."
                    else -> "Playback failed."
                }

                // Find the most specific message in the cause chain (e.g. an RTSP 401)
                var detail: String? = null
                var cause: Throwable? = error
                while (cause != null) {
                    cause.message?.takeIf { it.isNotBlank() }?.let { detail = it }
                    cause = cause.cause
                }
                val shortDetail = detail?.let { Diagnostics.redact(it).take(140) }

                return buildString {
                    append(hint)
                    append(" [")
                    append(error.errorCodeName)
                    if (shortDetail != null) append(": ").append(shortDetail)
                    append("]")
                }
            }
        }
    }

    private class Image(context: Context, popup: PopupProps, val media: PopupProps.Media.Image) :
        PopupView(context, popup) {
        init {
            create()
        }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            try {
                val imageView = ImageView(context).apply {
                    adjustViewBounds = true
                }

                frame.addView(
                    imageView,
                    FrameLayout.LayoutParams(media.width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                )

                Glide.with(context.applicationContext)
                    .load(Uri.parse(media.uri))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .into(imageView)

            } catch (e: Throwable) {
                Log.e(LOG_TAG, "Failed to load image", e)
                removeView(frame)
            }
        }
    }

    private class Bitmap(context: Context, popup: PopupProps, val media: PopupProps.Media.Bitmap) :
        PopupView(context, popup) {
        private var mImageView: ImageView? = null

        init {
            create()
        }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            mImageView = ImageView(context).apply {
                setImageBitmap(media.image)
            }

            val scaledHeight = ((media.width.toFloat() / media.image.width) * media.image.height).toInt()
            frame.addView(mImageView, FrameLayout.LayoutParams(media.width, scaledHeight).apply {
                gravity = Gravity.CENTER
            })
        }

        override fun destroy() {
            try {
                mImageView?.setImageDrawable(null)
                media.image.recycle()
            } catch (e: Throwable) {
            }
        }
    }

    private class Web(context: Context, popup: PopupProps, val media: PopupProps.Media.Web) :
        PopupView(context, popup) {
        private var mWebView: WebView? = null

        init {
            create()
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val webView = WebView(context).apply {
                with(settings) {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                }
                loadUrl(media.uri)
            }
            mWebView = webView

            frame.addView(webView, FrameLayout.LayoutParams(media.width, media.height).apply {
                gravity = Gravity.CENTER
            })
        }

        override fun destroy() {
            try {
                mWebView?.apply {
                    stopLoading()
                    destroy()
                }
            } catch (e: Throwable) {
            }
            mWebView = null
        }
    }

    companion object {
        const val LOG_TAG = "PopupView"

        private fun parseColor(value: String, fallback: Int): Int =
            try {
                Color.parseColor(value)
            } catch (e: IllegalArgumentException) {
                fallback
            }

        fun build(context: Context, popup: PopupProps): PopupView {
            return when (val media = popup.media) {
                is PopupProps.Media.Web -> Web(context, popup, media)
                is PopupProps.Media.Video -> Video(context, popup, media)
                is PopupProps.Media.Image -> Image(context, popup, media)
                is PopupProps.Media.Bitmap -> Bitmap(context, popup, media)
                null -> Default(context, popup)
            }
        }
    }
}
