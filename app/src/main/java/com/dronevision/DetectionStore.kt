package com.dronevision

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles the "save" feature. Writes annotated JPEG frames plus a small text
 * sidecar into the app's own external files dir. No storage permission needed
 * on Android 8+, which keeps the app simple and avoids a permission prompt.
 */
object DetectionStore {

    private const val DIR_NAME = "captures"

    private fun dir(context: Context): File {
        val d = File(context.getExternalFilesDir(null), DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Save an annotated frame + its findings. Returns the saved file, or null on failure. */
    fun save(context: Context, annotated: Bitmap, detections: List<Detection>): File? {
        return try {
            val stamp = System.currentTimeMillis()
            val imageFile = File(dir(context), "capture_$stamp.jpg")
            FileOutputStream(imageFile).use { out ->
                annotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            // Sidecar with the findings so History can show what was detected.
            val summary = buildSummary(detections)
            File(dir(context), "capture_$stamp.txt").writeText("$stamp|$summary")
            imageFile
        } catch (e: Exception) {
            android.util.Log.e("DetectionStore", "save failed", e)
            null
        }
    }

    /** Load all saved records, newest first. */
    fun loadAll(context: Context): List<SavedRecord> {
        val d = dir(context)
        val images = d.listFiles { f -> f.name.startsWith("capture_") && f.name.endsWith(".jpg") }
            ?: return emptyList()
        return images.sortedByDescending { it.lastModified() }.map { img ->
            val sidecar = File(d, img.nameWithoutExtension + ".txt")
            var ts = img.lastModified()
            var summary = "No details"
            if (sidecar.exists()) {
                val parts = sidecar.readText().split("|", limit = 2)
                parts.getOrNull(0)?.toLongOrNull()?.let { ts = it }
                parts.getOrNull(1)?.let { if (it.isNotBlank()) summary = it }
            }
            SavedRecord(img.absolutePath, ts, summary)
        }
    }

    fun delete(record: SavedRecord) {
        try {
            val img = File(record.imagePath)
            File(img.parentFile, img.nameWithoutExtension + ".txt").delete()
            img.delete()
        } catch (_: Exception) { }
    }

    private fun buildSummary(detections: List<Detection>): String {
        if (detections.isEmpty()) return "No disease detected"
        // Group by label, keep the highest confidence per label.
        return detections
            .groupBy { it.label }
            .map { (label, list) ->
                val best = list.maxOf { it.confidence }
                "$label ${(best * 100).toInt()}%"
            }
            .joinToString(", ")
    }

    fun formatTime(millis: Long): String {
        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(millis))
    }
}
