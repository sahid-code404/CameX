package com.sahidcode404.camex.core.camera.raw

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RawDngWriter(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun write(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RawSavedFile {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException(
                "Public DNG saving without broad storage permission requires Android 10 or newer",
            )
        }

        val formatter = requireNotNull(FILE_TIME_FORMAT.get())
        val displayName = "IMG_${formatter.format(Date(nowEpochMs))}.dng"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, DNG_MIME_TYPE)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Camera/",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        var byteCount = 0L
        val transaction = RawSaveTransaction<Uri>(
            create = {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            },
            write = { uri ->
                resolver.openOutputStream(uri, "w")?.use { stream ->
                    CountingOutputStream(stream).use { counted ->
                        DngCreator(characteristics, result).use { creator ->
                            creator.writeImage(counted, image)
                        }
                        byteCount = counted.count
                        check(byteCount > 0L) { "DNG output was empty" }
                    }
                } ?: error("Could not open MediaStore DNG output")
            },
            commit = { uri ->
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, published, null, null) == 1) {
                    "Could not publish MediaStore DNG"
                }
            },
            delete = { uri -> resolver.delete(uri, null, null) },
        )
        val uri = transaction.execute().getOrThrow()
        return RawSavedFile(
            uri = uri.toString(),
            bytes = byteCount,
            width = image.width,
            height = image.height,
        )
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            count += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            count += length.toLong()
        }
    }

    private companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
        val FILE_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        }
    }
}
