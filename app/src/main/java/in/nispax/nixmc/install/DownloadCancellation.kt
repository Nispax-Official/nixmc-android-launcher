package `in`.nispax.nixmc.install

import java.util.concurrent.CancellationException

/** Shared, explicit cancellation signal checked inside blocking network and extraction loops. */
object DownloadCancellation {
    @Volatile
    private var cancellationRequested = false

    fun reset() {
        cancellationRequested = false
    }

    fun request() {
        cancellationRequested = true
    }

    fun throwIfRequested() {
        if (cancellationRequested || Thread.currentThread().isInterrupted) {
            throw CancellationException("Download canceled by the user.")
        }
    }
}
