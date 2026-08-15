package com.madeinhk.cantonesevoicekeyboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwenasr.QAsrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackagedAsrModelLoadTest {
    @Test
    fun playAssetDeliveryShardsLoadAsOneModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = ModelAssetPack.resolveModelDir(context)
        assertNotNull("Play Asset Delivery model packs are unavailable", modelDir)

        val names = modelDir!!.list()?.toSet().orEmpty()
        assertTrue("first model shard is missing", "model-00001-of-00002.safetensors" in names)
        assertTrue("second model shard is missing", "model-00002-of-00002.safetensors" in names)

        val engine = QAsrEngine()
        try {
            assertTrue(
                "native engine failed to load the packaged model",
                engine.loadModel(modelDir.absolutePath, 1),
            )
        } finally {
            engine.free()
        }
    }
}
