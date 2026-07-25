package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugHandwritingStoreTest {
    private lateinit var context: Context
    private var wasEnabled = false
    private val createdRecordIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wasEnabled = DebugHandwritingSettings.isStoreDebugHandwritingEnabled(context)
        DebugHandwritingSettings.setStoreDebugHandwritingEnabled(context, true)
    }

    @After
    fun tearDown() {
        createdRecordIds.forEach { DebugHandwritingStore.delete(context, it) }
        DebugHandwritingSettings.setStoreDebugHandwritingEnabled(context, wasEnabled)
    }

    @Test
    fun captureStoresOriginalAndExactModelInput() {
        val id = DebugHandwritingStore.capture(
            context = context,
            originalPixels = byteArrayOf(0, 255.toByte()),
            originalWidth = 2,
            originalHeight = 1,
            modelInputPixels = byteArrayOf(255.toByte(), 0),
            modelInputWidth = 1,
            modelInputHeight = 2,
        )
        assertNotNull(id)
        createdRecordIds += requireNotNull(id)

        val record = DebugHandwritingStore.list(context).first { it.id == id }
        val original = BitmapFactory.decodeFile(
            requireNotNull(record.originalImageFile).absolutePath,
        )
        val modelInput = BitmapFactory.decodeFile(record.inputImageFile.absolutePath)

        assertEquals(2, original.width)
        assertEquals(1, original.height)
        assertEquals(0, Color.red(original.getPixel(0, 0)))
        assertEquals(255, Color.red(original.getPixel(1, 0)))

        assertEquals(1, modelInput.width)
        assertEquals(2, modelInput.height)
        assertEquals(255, Color.red(modelInput.getPixel(0, 0)))
        assertEquals(0, Color.red(modelInput.getPixel(0, 1)))

        val metadata = JSONObject(record.directory.resolve("recording.json").readText())
        assertEquals(2, metadata.getInt("schema_version"))
        assertEquals("original.png", metadata.getString("original_image_file"))
        assertEquals("model_input.png", metadata.getString("model_input_image_file"))
        assertEquals(2, metadata.getInt("original_width"))
        assertEquals(1, metadata.getInt("model_input_width"))
    }
}
