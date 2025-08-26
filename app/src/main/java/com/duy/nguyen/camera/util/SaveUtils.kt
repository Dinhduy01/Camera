package com.duy.nguyen.camera.util

import android.content.Context
import android.media.Image
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


fun createOutputFile(prefix: String, ext: String): File {
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "ComposeCamera"
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