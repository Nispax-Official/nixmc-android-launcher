package `in`.nispax.nixmc.download

import android.content.Context

object DownloadActions {
    const val GAME = "in.nispax.nixmc.action.DOWNLOAD_GAME"
    const val JAVA = "in.nispax.nixmc.action.DOWNLOAD_JAVA"
    const val CANCEL = "in.nispax.nixmc.action.CANCEL_DOWNLOAD"
}

data class DownloadState(
    val action: String = "",
    val phase: String = "IDLE",
    val status: String = "No background download has been started.",
    val progress: Int = 0
) {
    val isRunning: Boolean get() = phase == "RUNNING"
    val isFailed: Boolean get() = phase == "FAILED"
    val isCanceled: Boolean get() = phase == "CANCELED"
    val isComplete: Boolean get() = phase == "COMPLETE"
}

object DownloadStateStore {
    private const val PREFS = "nixmc_download_state"
    private const val ACTION = "action"
    private const val PHASE = "phase"
    private const val STATUS = "status"
    private const val PROGRESS = "progress"

    fun read(context: Context): DownloadState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DownloadState(
            action = prefs.getString(ACTION, "").orEmpty(),
            phase = prefs.getString(PHASE, "IDLE").orEmpty(),
            status = prefs.getString(STATUS, "No background download has been started.").orEmpty(),
            progress = prefs.getInt(PROGRESS, 0).coerceIn(0, 100)
        )
    }

    fun begin(context: Context, action: String, label: String) {
        write(context, DownloadState(action, "RUNNING", "Preparing $label…", 0))
    }

    fun update(context: Context, action: String, status: String) {
        val previous = read(context)
        val progress = DownloadProgressEstimator.estimate(action, status, previous.progress)
        write(context, DownloadState(action, "RUNNING", status.take(500), progress))
    }

    fun finish(context: Context, action: String, phase: String, status: String) {
        val previous = read(context)
        val progress = if (phase == "COMPLETE") 100 else previous.progress.coerceIn(0, 99)
        write(context, DownloadState(action, phase, status.take(500), progress))
    }

    private fun write(context: Context, state: DownloadState) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTION, state.action)
            .putString(PHASE, state.phase)
            .putString(STATUS, state.status)
            .putInt(PROGRESS, state.progress.coerceIn(0, 100))
            .apply()
    }

}
