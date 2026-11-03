package com.lumbangaol.idm

enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/** Satu potongan file yang diunduh lewat koneksi sendiri. start..end inklusif. */
data class Segment(val start: Long, val end: Long, val downloaded: Long) {
    val length: Long get() = end - start + 1
}

data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Status = Status.QUEUED,
    val error: String? = null,
    val segments: List<Segment> = emptyList(),
    val speedBps: Long = 0,
    /** true jika server mendukung Range (bisa dipecah dan dilanjutkan). */
    val resumable: Boolean = false,
    /** true setelah info file (ukuran, nama, dukungan Range) berhasil dibaca. */
    val probed: Boolean = false,
    val savedUri: String? = null,
    val mimeType: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
