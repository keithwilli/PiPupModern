package nl.rogro82.pipup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the most recent video events in memory so they can be read from the
 * status page (GET http://<tv-ip>:7979/) without needing adb logcat.
 */
object Diagnostics {
    private const val MAX_EVENTS = 20
    private val events = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun record(message: String) {
        events.addLast("${timeFormat.format(Date())}  $message")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun report(): String =
        if (events.isEmpty()) "No video events yet." else events.joinToString("\n")

    /** Hides credentials (user:password@) from URLs. */
    fun redact(uri: String): String = uri.replace(Regex("//[^/@]*@"), "//***@")
}
