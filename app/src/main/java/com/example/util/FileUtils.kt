package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import java.util.Locale

object FileUtils {
    fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, String> {
        var name = "Archivo de Audio/Video"
        var sizeStr = "Tamaño Desconocido"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) {
                        val sizeBytes = cursor.getLong(sizeIndex)
                        sizeStr = formatFileSize(sizeBytes)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(name, sizeStr)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = if (digitGroups < units.size) digitGroups else units.size - 1
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }

    fun getMediaDurationMs(context: Context, uri: Uri): Long {
        var durationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (time != null) {
                durationMs = time.toLong()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (ex: Exception) {}
        }
        return durationMs
    }

    fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60))
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02dh %02dm %02ds", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02dm %02ds", minutes, seconds)
        }
    }

    fun getUriBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getMimeType(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri)
        if (!type.isNullOrEmpty()) return type
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        if (!extension.isNullOrEmpty()) {
            val mapType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.getDefault()))
            if (!mapType.isNullOrEmpty()) return mapType
        }
        return "audio/mp3" // safe default fallback
    }
}
