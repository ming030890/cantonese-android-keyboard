package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.PromptedInkPoint
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.PromptedInkSnapshot
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.promptedHandwritingBitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptedHandwritingStoreTest {
    @Test
    fun captureStoresReplayableTrajectoryAndExactRaster() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = PromptedInkSnapshot(
            strokes = listOf(
                listOf(
                    point(20f, 20f, 0),
                    point(100f, 120f, 20),
                ),
                listOf(
                    point(100f, 120f, 40),
                    point(180f, 20f, 60),
                ),
            ),
            canvasSize = IntSize(200, 140),
        )
        val bitmap = promptedHandwritingBitmap(snapshot)
        val id = PromptedHandwritingStore.capture(
            context,
            character = "人",
            classId = 0,
            inventoryHash = "inventory",
            snapshot = snapshot,
            bitmap = bitmap,
        )
        try {
            assertTrue(
                PromptedHandwritingStore.finalizeInference(
                    context,
                    id,
                    modelInput = null,
                    error = "test_unavailable",
                )
            )
            val record = PromptedHandwritingStore.list(context).first { it.id == id }
            assertTrue(record.readyToSync)
            val metadata = JSONObject(record.directory.resolve("sample.json").readText())
            val strokes = JSONObject(record.directory.resolve("strokes.json").readText())
            val image = BitmapFactory.decodeFile(record.directory.resolve("original.png").absolutePath)
            assertEquals("eval", metadata.getString("split"))
            assertEquals(2, metadata.getInt("stroke_count"))
            assertEquals(4, metadata.getInt("point_count"))
            assertEquals(60, metadata.getLong("duration_ms"))
            assertEquals(2, strokes.getJSONArray("strokes").length())
            assertEquals(200, image.width)
            assertEquals(140, image.height)
        } finally {
            PromptedHandwritingStore.deletePayload(context, id)
        }
    }

    private fun point(x: Float, y: Float, time: Long) = PromptedInkPoint(
        x = x,
        y = y,
        elapsedMillis = time,
        pressure = 1f,
        size = 0.1f,
        orientationRadians = 0f,
        tiltRadians = null,
        toolType = MotionEvent.TOOL_TYPE_FINGER,
    )
}
