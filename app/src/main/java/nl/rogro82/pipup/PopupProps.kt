package nl.rogro82.pipup

import org.json.JSONObject

data class PopupProps(
    val duration: Int = DEFAULT_DURATION,
    val position: Position = DEFAULT_POSITION,
    val backgroundColor: String = DEFAULT_BACKGROUND_COLOR,
    val title: String? = null,
    val titleSize: Float = DEFAULT_TITLE_SIZE,
    val titleColor: String = DEFAULT_TITLE_COLOR,
    val message: String? = null,
    val messageSize: Float = DEFAULT_MESSAGE_SIZE,
    val messageColor: String = DEFAULT_MESSAGE_COLOR,
    val media: Media? = null
) {
    sealed class Media {
        data class Video(
            val uri: String,
            val width: Int = DEFAULT_MEDIA_WIDTH,
            val muted: Boolean = false
        ) : Media()

        data class Image(val uri: String, val width: Int = DEFAULT_MEDIA_WIDTH) : Media()
        data class Web(val uri: String, val width: Int = 640, val height: Int = 480) : Media()
        data class Bitmap(val image: android.graphics.Bitmap, val width: Int = DEFAULT_MEDIA_WIDTH) : Media()
    }

    enum class Position {
        TopRight,
        TopLeft,
        BottomRight,
        BottomLeft,
        Center;

        companion object {
            fun fromIndex(index: Int?): Position =
                entries.getOrNull(index ?: 0) ?: DEFAULT_POSITION
        }
    }

    companion object {
        const val DEFAULT_DURATION: Int = 30
        const val DEFAULT_BACKGROUND_COLOR = "#CC000000"
        const val DEFAULT_TITLE_SIZE = 16f
        const val DEFAULT_TITLE_COLOR = "#ffffff"
        const val DEFAULT_MESSAGE_SIZE = 12f
        const val DEFAULT_MESSAGE_COLOR = "#ffffff"
        const val DEFAULT_MEDIA_WIDTH = 480

        val DEFAULT_POSITION: Position = Position.TopRight

        /**
         * Parses the JSON body of a /notify request. Accepts the same format as the
         * original PiPup, e.g. "media": { "video": { "uri": "...", "width": 480 } }
         */
        fun fromJson(text: String): PopupProps {
            val json = JSONObject(text)

            return PopupProps(
                duration = json.optInt("duration", DEFAULT_DURATION),
                position = Position.fromIndex(json.optIntOrNull("position")),
                backgroundColor = json.optStringOrNull("backgroundColor") ?: DEFAULT_BACKGROUND_COLOR,
                title = json.optStringOrNull("title"),
                titleSize = json.optDouble("titleSize", DEFAULT_TITLE_SIZE.toDouble()).toFloat(),
                titleColor = json.optStringOrNull("titleColor") ?: DEFAULT_TITLE_COLOR,
                message = json.optStringOrNull("message"),
                messageSize = json.optDouble("messageSize", DEFAULT_MESSAGE_SIZE.toDouble()).toFloat(),
                messageColor = json.optStringOrNull("messageColor") ?: DEFAULT_MESSAGE_COLOR,
                media = json.optJSONObject("media")?.let { parseMedia(it) }
            )
        }

        private fun parseMedia(media: JSONObject): Media? {
            media.optJSONObject("video")?.let {
                return Media.Video(
                    uri = it.getString("uri"),
                    width = it.optInt("width", DEFAULT_MEDIA_WIDTH),
                    muted = it.optBoolean("muted", false)
                )
            }
            media.optJSONObject("image")?.let {
                return Media.Image(
                    uri = it.getString("uri"),
                    width = it.optInt("width", DEFAULT_MEDIA_WIDTH)
                )
            }
            media.optJSONObject("web")?.let {
                return Media.Web(
                    uri = it.getString("uri"),
                    width = it.optInt("width", 640),
                    height = it.optInt("height", 480)
                )
            }
            return null
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null
    }
}
