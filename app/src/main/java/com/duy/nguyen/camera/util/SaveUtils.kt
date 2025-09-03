package com.duy.nguyen.camera.util

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun createOutputFile(prefix: String, ext: String): File {
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "ComposeCamera"
    )
    if (!dir.exists()) dir.mkdirs()
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return File(dir, "${prefix}_${ts}${ext}")
}

fun saveJpegToAppDir(context: Context, image: Image) {
    try {
        val file = createOutputFile("IMG", ".jpg")
        FileOutputStream(file).use { fos ->
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            fos.write(bytes)
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    } catch (e: Exception) {
        Log.e("SaveUtils", "saveJpeg error", e)
    }
}

fun createVideoUri(context: Context, fileName: String): Uri? {
    val cv = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ComposeCamera")
    }
    return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
}
