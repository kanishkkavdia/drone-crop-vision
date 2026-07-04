package com.dronevision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runs crop-disease classification over a frame using a tiled approach:
 * the frame is split into a grid, each tile is classified, and tiles whose
 * top class is a disease (above a confidence threshold) become boxes.
 *
 * MODEL: expects "crop_disease_model.tflite" in assets, input 224x224x3 float
 * normalised to [0,1], output = float[NUM_CLASSES] probabilities. Class names
 * come from assets/labels.txt (one per line, same order as the model output).
 *
 * If the model file is missing, the classifier runs in DEMO MODE so the whole
 * capture/overlay/save pipeline still works — demo results are clearly marked.
 */
class DiseaseClassifier(context: Context) {

    companion object {
        private const val TAG = "DiseaseClassifier"
        private const val MODEL_FILE = "crop_disease_model.tflite"
        private const val INPUT_SIZE = 224
        private const val GRID = 3            // 3x3 tiles per frame
        private const val THRESHOLD = 0.60f   // min confidence to draw a box
        // A label is treated as "healthy" (no box drawn) if it contains this.
        private const val HEALTHY_KEYWORD = "healthy"
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    val isDemoMode: Boolean get() = interpreter == null

    init {
        try {
            labels = context.assets.open("labels.txt").bufferedReader()
                .readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val model = loadModelFile(context)
            interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
            Log.i(TAG, "Loaded model with ${labels.size} classes")
        } catch (e: Exception) {
            Log.w(TAG, "No model found -> DEMO MODE. Drop $MODEL_FILE into assets/ to enable real detection.", e)
            interpreter = null
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        FileInputStream(fd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /**
     * Classify one frame. [frame] is the full captured bitmap.
     * Returns boxes in the frame's pixel coordinates.
     * Wrapped so a bad frame logs and returns empty instead of crashing.
     */
    fun detect(frame: Bitmap): List<Detection> {
        return try {
            if (isDemoMode) demoDetect(frame) else realDetect(frame)
        } catch (e: Exception) {
            Log.e(TAG, "detect failed on a frame; skipping", e)
            emptyList()
        }
    }

    private fun realDetect(frame: Bitmap): List<Detection> {
        val results = mutableListOf<Detection>()
        val tileW = frame.width / GRID
        val tileH = frame.height / GRID
        if (tileW <= 0 || tileH <= 0) return results

        val output = Array(1) { FloatArray(labels.size) }

        for (row in 0 until GRID) {
            for (col in 0 until GRID) {
                val left = col * tileW
                val top = row * tileH
                val tile = Bitmap.createBitmap(frame, left, top, tileW, tileH)
                val scaled = Bitmap.createScaledBitmap(tile, INPUT_SIZE, INPUT_SIZE, true)
                val input = toInputBuffer(scaled)

                interpreter?.run(input, output)

                val probs = output[0]
                var bestIdx = 0
                for (i in probs.indices) if (probs[i] > probs[bestIdx]) bestIdx = i
                val conf = probs[bestIdx]
                val label = labels.getOrElse(bestIdx) { "class_$bestIdx" }

                if (conf >= THRESHOLD && !label.lowercase().contains(HEALTHY_KEYWORD)) {
                    results.add(
                        Detection(label, conf, left, top, left + tileW, top + tileH)
                    )
                }
                tile.recycle()
                scaled.recycle()
            }
        }
        return results
    }

    private fun toInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buffer.putFloat(Color.red(p) / 255.0f)
            buffer.putFloat(Color.green(p) / 255.0f)
            buffer.putFloat(Color.blue(p) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    /** Demo mode: draws one clearly-labelled placeholder box so you can verify
     *  the capture -> overlay -> save pipeline before adding a real model. */
    private fun demoDetect(frame: Bitmap): List<Detection> {
        val w = frame.width
        val h = frame.height
        return listOf(
            Detection(
                "DEMO (no model loaded)", 0.99f,
                (w * 0.3f).toInt(), (h * 0.35f).toInt(),
                (w * 0.7f).toInt(), (h * 0.65f).toInt()
            )
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
