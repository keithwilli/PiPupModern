package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
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
     */
    @OptIn(UnstableApi::class)
    private class Video(context: Context, popup: PopupProps, val media: PopupProps.Media.Video) :
        PopupView(context, popup) {

        private var mPlayer: ExoPlayer? = null
        private val mHandler = Handler(Looper.getMainLooper())
        private var mRetries = 0

        init {
            create()
        }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

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
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(LOG_TAG, "Playback error for ${redact(media.uri)}: ${error.errorCodeName}", error)
                            if (mRetries < MAX_RETRIES) {
                                mRetries++
                                mHandler.postDelayed({ startPlayback() }, RETRY_DELAY_MS)
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

            startPlayback()
        }

        private fun startPlayback() {
            val player = mPlayer ?: return
            val uri = Uri.parse(media.uri)
            val mediaItem = MediaItem.fromUri(uri)

            if (uri.scheme.equals("rtsp", ignoreCase = true) || uri.scheme.equals("rtsps", ignoreCase = true)) {
                // TCP transport is far more reliable than UDP for most IP cameras
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
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

        /** Hides credentials (user:password@) from URLs before logging them. */
        private fun redact(uri: String): String = uri.replace(Regex("//[^/@]*@"), "//***@")

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
