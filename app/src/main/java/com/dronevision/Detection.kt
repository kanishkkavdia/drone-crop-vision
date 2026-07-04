package com.dronevision

/** A single disease finding within one captured frame tile. */
data class Detection(
    val label: String,
    val confidence: Float,
    // Tile bounds in screen pixels, used to draw the overlay box.
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** A saved record: an annotated frame plus its findings and a timestamp. */
data class SavedRecord(
    val imagePath: String,
    val timestampMillis: Long,
    val summary: String
)
