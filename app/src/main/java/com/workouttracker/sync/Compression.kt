package com.workouttracker.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The backup on Drive is gzipped. The log is JSON, which is mostly the same
 * field names over and over, so it shrinks about sixfold: a year of training
 * goes up as roughly 270 KB rather than 1.6 MB.
 *
 * gzip is lossless -- the bytes that come out are the bytes that went in --
 * but the code around it is where data could go missing, so [compressVerified]
 * checks that on every upload, and reading accepts the uncompressed files
 * written before this, which is what is on Drive until the next upload.
 */

/** Every gzip stream starts with these two bytes, and no JSON document can. */
fun isGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 &&
        (bytes[0].toInt() and 0xff) == 0x1f &&
        (bytes[1].toInt() and 0xff) == 0x8b

fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(bytes.size / 4 + 64)
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

/**
 * The backup's content, decompressed if it was compressed and as it is if it
 * was not. Throws on a damaged gzip stream, which a sync reports as an
 * unreadable backup -- and leaves alone rather than overwriting.
 */
fun gunzipIfCompressed(bytes: ByteArray): ByteArray =
    if (!isGzip(bytes)) bytes else GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

/**
 * Compresses [bytes] and proves the result decompresses to exactly them before
 * handing it back. What this replaces on Drive is the only copy of the log off
 * the phone, so it is worth a millisecond of checking every time.
 */
fun compressVerified(bytes: ByteArray): ByteArray {
    val compressed = gzip(bytes)
    check(gunzipIfCompressed(compressed).contentEquals(bytes)) {
        "Compressed backup did not decompress to the original; not uploading it"
    }
    return compressed
}
