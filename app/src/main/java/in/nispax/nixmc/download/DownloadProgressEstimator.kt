package `in`.nispax.nixmc.download

/**
 * Maps transfer/item counts to overall setup stages for the persistent notification.
 * The ranges are intentionally coarse: Mojang metadata does not provide a total byte
 * size for the entire dependency/assets graph before installation starts.
 */
internal object DownloadProgressEstimator {
    private val percentPattern = Regex("""\b(\d{1,3})%""")
    private val fractionPattern = Regex("""\b(\d+)\s*/\s*(\d+)\b""")

    fun estimate(action: String, message: String, previousProgress: Int): Int {
        val previous = previousProgress.coerceIn(0, 99)
        val estimated = when (action) {
            DownloadActions.JAVA -> {
                if (!message.contains("Downloading Java 21 runtime", ignoreCase = true)) {
                    return previous
                }
                val percent = percentPattern.find(message)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return previous
                percent.coerceIn(0, 99)
            }

            DownloadActions.GAME -> when {
                message.contains("Downloading Minecraft client", ignoreCase = true) ->
                    mapPercent(message, start = 5, span = 25) ?: return previous

                message.contains("Installing library files", ignoreCase = true) ->
                    mapFraction(message, start = 30, span = 30) ?: return previous

                message.contains("Downloading Asset index", ignoreCase = true) ->
                    mapPercent(message, start = 60, span = 5) ?: return previous

                message.contains("Installing verified asset objects", ignoreCase = true) ->
                    mapFraction(message, start = 65, span = 34) ?: return previous

                else -> return previous
            }

            else -> return previous
        }
        return maxOf(previous, estimated).coerceIn(0, 99)
    }

    private fun mapPercent(message: String, start: Int, span: Int): Int? {
        val percent = percentPattern.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return start + percent.coerceIn(0, 100) * span / 100
    }

    private fun mapFraction(message: String, start: Int, span: Int): Int? {
        val match = fractionPattern.find(message) ?: return null
        val completed = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[2].toLongOrNull() ?: return null
        if (total <= 0L) return null
        return start + (completed.coerceAtMost(total) * span / total).toInt()
    }
}
