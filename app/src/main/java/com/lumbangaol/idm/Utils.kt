package com.lumbangaol.idm

import android.net.Uri
import android.webkit.MimeTypeMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLongArray

fun AtomicLongArray.total(): Long {
    var sum = 0L
    for (i in 0 until length()) sum += get(i)
    return sum
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "$seconds dtk"
    seconds < 3600 -> "${seconds / 60} mnt"
    else -> "${seconds / 3600} jam ${(seconds % 3600) / 60} mnt"
}

fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.').take(120)

fun guessFileName(url: String): String {
    val segment = runCatching { Uri.parse(url).lastPathSegment }.getOrNull() ?: ""
    val name = sanitizeFileName(segment)
    return if (name.isBlank()) "download" else name
}

fun parseDispositionName(header: String?): String? {
    if (header == null) return null
    val star = Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(header)
    if (star != null) {
        val decoded = runCatching { URLDecoder.decode(star.groupValues[1].trim(), "UTF-8") }.getOrNull()
        if (!decoded.isNullOrBlank()) return decoded
    }
    val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
    if (plain != null) {
        val name = plain.groupValues[1].trim()
        if (name.isNotBlank()) return name
    }
    return null
}

fun resolveMime(fileName: String, headerMime: String?): String {
    val generic = headerMime.isNullOrBlank() ||
        headerMime.equals("application/octet-stream", ignoreCase = true) ||
        headerMime.equals("binary/octet-stream", ignoreCase = true)
    if (!generic && headerMime != null) return headerMime
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

/** Mengambil semua link http(s) dari teks. Jika teks hanya satu kata mirip domain, awali dengan https://. */
fun parseUrls(text: String): List<String> {
    val found = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.trimEnd(',', ')', ';', '.') }
        .filter { it.toHttpUrlOrNull() != null }
        .toList()
    if (found.isNotEmpty()) return found
    val single = text.trim()
    if (single.isNotEmpty() && !single.any { it.isWhitespace() } && single.contains('.')) {
        val candidate = "https://$single"
        if (candidate.toHttpUrlOrNull() != null) return listOf(candidate)
    }
    return emptyList()
}

fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull() ?: url
