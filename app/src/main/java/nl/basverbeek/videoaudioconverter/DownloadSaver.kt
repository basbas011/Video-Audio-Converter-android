package nl.basverbeek.videoaudioconverter

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * Kopieert een gedownload bestand uit de app-cache naar de openbare map
 * Download/Video Audio Converter, zodat het in de Bestanden-app en mediaspelers verschijnt.
 */
object DownloadSaver {

    const val SUBFOLDER = "Video Audio Converter"

    fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    /** @return leesbaar pad van het opgeslagen bestand */
    @Throws(IOException::class)
    fun saveToDownloads(context: Context, source: File): String {
        val mime = mimeTypeFor(source)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, source, mime)
        } else {
            saveLegacy(context, source, mime)
        }
    }

    private fun saveWithMediaStore(context: Context, source: File, mime: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("Niet ondersteund")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + SUBFOLDER
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Kon geen bestand aanmaken in Downloads")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Kon bestand in Downloads niet openen")
            out.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return "Download/$SUBFOLDER/${source.name}"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, source: File, mime: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Kon map niet aanmaken: $dir")

        var target = File(dir, source.name)
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${source.nameWithoutExtension} ($counter).${source.extension}")
            counter++
        }
        source.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return target.absolutePath
    }
}
