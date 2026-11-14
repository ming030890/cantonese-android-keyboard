package com.madeinhk.cantonesevoicekeyboard

import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadStatusTest {
    @Test fun confirmationIsVisibleEvenBeforeOtherPacksHaveReported() {
        assertEquals(ModelDownloadStatus.RequiresConfirmation,
            ModelAssetPack.combineStates(states(AssetPackStatus.REQUIRES_USER_CONFIRMATION)))
    }

    @Test fun waitingForWifiIsNotShownAsIndefinitePreparation() {
        assertEquals(ModelDownloadStatus.WaitingForWifi,
            ModelAssetPack.combineStates(states(AssetPackStatus.TRANSFERRING, AssetPackStatus.WAITING_FOR_WIFI)))
    }

    @Test fun failureTakesPrecedenceOverConfirmation() {
        assertEquals(ModelDownloadStatus.Failed(-2), ModelAssetPack.combineStates(
            states(AssetPackStatus.REQUIRES_USER_CONFIRMATION, AssetPackStatus.FAILED)))
    }

    @Test fun progressIncludesCompletedPacks() {
        val result = ModelAssetPack.combineStates(states(
            AssetPackStatus.COMPLETED, AssetPackStatus.DOWNLOADING, AssetPackStatus.DOWNLOADING,
            AssetPackStatus.DOWNLOADING, AssetPackStatus.DOWNLOADING))
        assertEquals(ModelDownloadStatus.Downloading(300, 500), result)
    }

    private fun states(vararg statuses: Int): Map<String, AssetPackState> =
        statuses.mapIndexed { i, status ->
            val name = "model_pack_${'a' + i}"
            name to object : AssetPackState() {
                override fun name() = name
                override fun status() = status
                override fun errorCode() = if (status == AssetPackStatus.FAILED) -2 else 0
                override fun bytesDownloaded() = if (status == AssetPackStatus.COMPLETED) 100L else 50L
                override fun totalBytesToDownload() = 100L
                override fun transferProgressPercentage() = 0
                override fun updateAvailability() = 0
                override fun availableVersionTag() = ""
                override fun installedVersionTag() = ""
            }
        }.toMap()
}
