package nl.rogro82.pipup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File

class PiPupService : Service(), WebServer.Handler {
    private val mHandler = Handler(Looper.getMainLooper())
    private var mOverlay: FrameLayout? = null
    private var mPopup: PopupView? = null
    private var mWebServer: WebServer? = null

    override fun onCreate() {
        super.onCreate()

        initNotificationChannel(CHANNEL_ID, "Service channel", "Keeps the PiPup server running")

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PiPup")
            .setContentText("Service running")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, serviceType)

        try {
            mWebServer = WebServer(SERVER_PORT, this).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            Log.d(LOG_TAG, "WebServer started on port $SERVER_PORT")
        } catch (ex: Exception) {
            Log.e(LOG_TAG, "Failed to start WebServer (is another app using port $SERVER_PORT?)", ex)
        }
    }

    override fun onDestroy() {
        mHandler.post { removePopup(true) }
        mWebServer?.stop()
        mWebServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun initNotificationChannel(id: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
        channel.description = description
        notificationManager.createNotificationChannel(channel)
    }

    private fun removePopup(removeOverlay: Boolean = false) {
        mHandler.removeCallbacksAndMessages(null)

        mPopup?.destroy()
        mPopup = null

        mOverlay?.let { overlay ->
            overlay.removeAllViews()
            if (removeOverlay) {
                try {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeViewImmediate(overlay)
                } catch (ex: Exception) {
                    Log.w(LOG_TAG, "Failed to remove overlay", ex)
                }
                mOverlay = null
            }
        }
    }

    private fun createPopup(popup: PopupProps) {
        try {
            Log.d(LOG_TAG, "Create popup: $popup")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.e(
                    LOG_TAG,
                    "Missing overlay permission. Run: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
                )
                return
            }

            // remove current popup
            removePopup()

            // create or reuse the current overlay
            val overlay = mOverlay ?: FrameLayout(this).apply {
                setPadding(20, 20, 20, 20)

                @Suppress("DEPRECATION")
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )

                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(this, params)
            }
            mOverlay = overlay

            // inflate the popup layout
            val popupView = PopupView.build(this, popup)
            mPopup = popupView

            overlay.addView(
                popupView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = when (popup.position) {
                        PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
                        PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
                        PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                        PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                        PopupProps.Position.Center -> Gravity.CENTER
                    }
                }
            )

            // schedule removal
            mHandler.postDelayed({ removePopup(true) }, popup.duration * 1000L)

        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Failed to create popup", ex)
            removePopup(true)
        }
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val content = ByteArray(contentLength)
        var offset = 0
        // InputStream.read may return fewer bytes than requested, so keep reading
        while (offset < contentLength) {
            val read = session.inputStream.read(content, offset, contentLength - offset)
            if (read < 0) break
            offset += read
        }
        return String(content, 0, offset, Charsets.UTF_8)
    }

    private fun parseMultipart(session: NanoHTTPD.IHTTPSession): PopupProps {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)

        // flatten parameters
        val params = session.parameters.mapValues { it.value.firstOrNull() }

        val media = files["image"]?.let { path ->
            val bitmap = File(path).absoluteFile.inputStream().use { BitmapFactory.decodeStream(it) }
                ?: throw Exception("could not decode image")
            val imageWidth = params["imageWidth"]?.toIntOrNull() ?: PopupProps.DEFAULT_MEDIA_WIDTH
            PopupProps.Media.Bitmap(image = bitmap, width = imageWidth)
        }

        return PopupProps(
            duration = params["duration"]?.toIntOrNull() ?: PopupProps.DEFAULT_DURATION,
            position = PopupProps.Position.fromIndex(params["position"]?.toIntOrNull()),
            backgroundColor = params["backgroundColor"] ?: PopupProps.DEFAULT_BACKGROUND_COLOR,
            title = params["title"],
            titleSize = params["titleSize"]?.toFloatOrNull() ?: PopupProps.DEFAULT_TITLE_SIZE,
            titleColor = params["titleColor"] ?: PopupProps.DEFAULT_TITLE_COLOR,
            message = params["message"],
            messageSize = params["messageSize"]?.toFloatOrNull() ?: PopupProps.DEFAULT_MESSAGE_SIZE,
            messageColor = params["messageColor"] ?: PopupProps.DEFAULT_MESSAGE_COLOR,
            media = media
        )
    }

    override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
        if (session == null) return invalidRequest()

        return when (session.method) {
            NanoHTTPD.Method.GET -> when (session.uri) {
                "/", "/status" -> ok(
                    "PiPup ${Utils.versionName(this)} running\n\n" +
                            "Recent video events (newest last):\n" +
                            Diagnostics.report()
                )
                else -> invalidRequest("unknown uri: ${session.uri}")
            }
            NanoHTTPD.Method.POST -> when (session.uri) {
                "/cancel" -> {
                    mHandler.post { removePopup(true) }
                    ok()
                }
                "/notify" -> {
                    try {
                        val contentType = session.headers["content-type"] ?: APPLICATION_JSON
                        val popup = when {
                            contentType.startsWith(APPLICATION_JSON) -> PopupProps.fromJson(readBody(session))
                            contentType.startsWith(MULTIPART_FORM_DATA) -> parseMultipart(session)
                            else -> throw Exception("invalid content-type")
                        }

                        Log.d(LOG_TAG, "received popup: $popup")
                        mHandler.post { createPopup(popup) }
                        ok("$popup")
                    } catch (ex: Throwable) {
                        Log.e(LOG_TAG, "Invalid request", ex)
                        invalidRequest(ex.message)
                    }
                }
                else -> invalidRequest("unknown uri: ${session.uri}")
            }
            else -> invalidRequest("invalid method")
        }
    }

    companion object {
        const val LOG_TAG = "PiPupService"
        const val SERVER_PORT = 7979
        const val ONGOING_NOTIFICATION_ID = 123
        const val CHANNEL_ID = "service_channel"
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val APPLICATION_JSON = "application/json"

        fun ok(message: String? = null): NanoHTTPD.Response =
            newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message ?: "OK")

        fun invalidRequest(message: String? = null): NanoHTTPD.Response =
            newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: $message")

        /** Starts the service in the foreground. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, PiPupService::class.java)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (ex: Exception) {
                Log.e(LOG_TAG, "Failed to start service", ex)
            }
        }
    }
}
