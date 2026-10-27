package `in`.nispax.nixmc.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressEstimatorTest {
    @Test
    fun gameClientPercentMapsToClientStage() {
        assertEquals(17, DownloadProgressEstimator.estimate(
            DownloadActions.GAME, "Downloading Minecraft client… 50%", 0
        ))
    }

    @Test
    fun librariesAndAssetsMapToSeparateStages() {
        assertEquals(45, DownloadProgressEstimator.estimate(
            DownloadActions.GAME, "Installing library files… 50/100", 30
        ))
        assertEquals(82, DownloadProgressEstimator.estimate(
            DownloadActions.GAME, "Installing verified asset objects… 500/1000", 65
        ))
    }

    @Test
    fun retriesAndUnrelatedMessagesDoNotParseRetryCountsAsProgress() {
        assertEquals(21, DownloadProgressEstimator.estimate(
            DownloadActions.GAME, "Connection failed; retrying (2/3)…", 21
        ))
    }

    @Test
    fun progressDoesNotMoveBackwardsWhenNextStageStarts() {
        assertEquals(82, DownloadProgressEstimator.estimate(
            DownloadActions.GAME, "Downloading Minecraft client… 80%", 82
        ))
    }

    @Test
    fun javaRuntimeUsesItsArchivePercentage() {
        assertEquals(37, DownloadProgressEstimator.estimate(
            DownloadActions.JAVA, "Downloading Java 21 runtime… 37%", 20
        ))
    }
}
