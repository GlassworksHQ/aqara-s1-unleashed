package io.github.aguilaair.s1kiosk.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Patches the real melspectrogram.tflite (needs the models on the host):
 *
 *     MODELS_CACHE=/tmp/oww-models tools/voice/fetch-models.sh --no-push
 *     OWW_MODELS_DIR=/tmp/oww-models ./gradlew :kiosk:testDebugUnitTest
 *
 * The patched copy is written next to the originals as melspectrogram.patched.tflite so it can be
 * checked with the Python TFLite runtime (allocate without resize, same outputs).
 */
class TfliteShapePatchTest {
    @Test
    fun patchesMelspectrogramInputShape() {
        val dir = System.getenv("OWW_MODELS_DIR")
        assumeTrue("set OWW_MODELS_DIR to a directory with melspectrogram.tflite", !dir.isNullOrBlank())
        val bytes = File(dir, "melspectrogram.tflite").readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertArrayEquals(intArrayOf(1, 1), TfliteShapePatch.inputShape(buf))
        val old = TfliteShapePatch.patchInputShape(buf, intArrayOf(1, 1760))
        assertArrayEquals(intArrayOf(1, 1), old)
        assertArrayEquals(intArrayOf(1, 1760), TfliteShapePatch.inputShape(buf))
        File(dir, "melspectrogram.patched.tflite").writeBytes(bytes)

        // Embedding / wake models: shapes are already static.
        val emb = ByteBuffer.wrap(File(dir, "embedding_model.tflite").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(intArrayOf(1, 76, 32, 1), TfliteShapePatch.inputShape(emb))
        val wake = ByteBuffer.wrap(File(dir, "hey_jarvis_v0.1.tflite").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(intArrayOf(1, 16, 96), TfliteShapePatch.inputShape(wake))
    }
}
