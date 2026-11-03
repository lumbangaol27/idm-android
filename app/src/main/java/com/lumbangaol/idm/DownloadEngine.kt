package com.lumbangaol.idm

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import javax.net.ssl.SSLException

/**
 * Mesin unduhan: antrean, pause/resume, dan unduh multi-segmen (file dipecah
 * menjadi beberapa bagian yang diunduh paralel lewat HTTP Range, seperti IDM).
 */
class DownloadEngine(private val context: Context) {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) IDMAndroid/1.0"
        private const val SEGMENTS = 4
        private const val MAX_PARALLEL = 2
        private const val MIN_SPLIT_BYTES = 1_048_576L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val store = DownloadStore(context)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    private val _items = MutableStateFlow(
        store.load().map { item ->
            // Unduhan yang terputus saat aplikasi ditutup dianggap dijeda.
            if (item.status == Status.DOWNLOADING || item.status == Status.QUEUED) {
                item.copy(status = Status.PAUSED, speedBps = 0)
            } else {
                item.copy(speedBps = 0)
            }
        }
    )
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    // ---------------------------------------------------------------- API publik

    fun add(urls: List<String>) {
        val now = System.currentTimeMillis()
        val created = urls.mapIndexed { index, url ->
            DownloadItem(
                id = UUID.randomUUID().toString(),
                url = url,
                fileName = guessFileName(url),
                createdAt = now + index
            )
        }
        _items.update { it + created }
        persistNow()
        pump()
        DownloadService.start(context)
    }

    fun pause(id: String) {
        update(id) {
            if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED) {
                it.copy(status = Status.PAUSED, speedBps = 0)
            } else {
                it
            }
        }
        jobs[id]?.cancel()
    }

    fun pauseAll() {
        _items.update { list ->
            list.map {
                if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED) {
                    it.copy(status = Status.PAUSED, speedBps = 0)
                } else {
                    it
                }
            }
        }
        persistNow()
        jobs.values.forEach { it.cancel() }
    }

    fun resume(id: String) {
        val old = jobs[id]
        scope.launch {
            old?.join()
            update(id) {
                if (it.status == Status.PAUSED || it.status == Status.FAILED) {
                    it.copy(status = Status.QUEUED, error = null)
                } else {
                    it
                }
            }
            pump()
            DownloadService.start(context)
        }
    }

    fun remove(id: String, deleteFile: Boolean) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        val job = jobs[id]
        _items.update { list -> list.filterNot { it.id == id } }
        persistNow()
        job?.cancel()
        scope.launch {
            job?.cancelAndJoin()
            tempFile(id).delete()
            val saved = item.savedUri
            if (deleteFile && saved != null) {
                runCatching { context.contentResolver.delete(Uri.parse(saved), null, null) }
            }
        }
    }

    // ---------------------------------------------------------------- antrean

    private fun pump() {
        synchronized(lock) {
            val running = _items.value.count { it.status == Status.DOWNLOADING }
            var slots = MAX_PARALLEL - running
            val waiting = _items.value
                .filter { it.status == Status.QUEUED }
                .sortedBy { it.createdAt }
            for (item in waiting) {
                if (slots <= 0) break
                start(item.id)
                slots--
            }
        }
    }

    private fun start(id: String) {
        update(id) { it.copy(status = Status.DOWNLOADING, error = null, speedBps = 0) }
        val job = scope.launch(start = CoroutineStart.LAZY) { runDownload(id) }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            pump()
        }
        job.start()
    }

    // ---------------------------------------------------------------- proses unduh

    private suspend fun runDownload(id: String) {
        val ctx = currentCoroutineContext()
        var counters: AtomicLongArray? = null
        var segs: List<Segment> = emptyList()
        try {
            var item = _items.value.firstOrNull { it.id == id } ?: return

            if (!item.probed) {
                val info = probe(item)
                val segments = if (info.resumable) makeSegments(info.totalBytes) else emptyList()
                item = info.copy(probed = true, segments = segments, downloadedBytes = 0)
                val probed = item
                update(id) {
                    it.copy(
                        fileName = probed.fileName,
                        totalBytes = probed.totalBytes,
                        resumable = probed.resumable,
                        mimeType = probed.mimeType,
                        probed = true,
                        segments = probed.segments,
                        downloadedBytes = 0
                    )
                }
            }

            val current = item
            val file = tempFile(id)

            if (current.resumable) {
                segs = current.segments
                if (!file.exists()) segs = segs.map { it.copy(downloaded = 0) }
                RandomAccessFile(file, "rw").use { raf ->
                    if (raf.length() != current.totalBytes) raf.setLength(current.totalBytes)
                }
                val arr = AtomicLongArray(segs.size)
                segs.forEachIndexed { i, s -> arr.set(i, s.downloaded) }
                counters = arr
            } else {
                segs = emptyList()
                file.delete()
                counters = AtomicLongArray(1)
            }

            val c: AtomicLongArray = checkNotNull(counters)
            val segments = segs
            coroutineScope {
                val ticker = launch { tick(id, c, segments) }
                if (current.resumable) {
                    segments.mapIndexed { index, seg ->
                        async { downloadSegment(current.url, file, seg, index, c) }
                    }.awaitAll()
                } else {
                    downloadSingle(id, current.url, file, c)
                }
                ticker.cancel()
            }

            val downloaded = c.total()
            update(id) {
                it.copy(
                    totalBytes = if (it.totalBytes > 0) it.totalBytes else downloaded,
                    downloadedBytes = downloaded,
                    speedBps = 0
                )
            }
            val done = _items.value.firstOrNull { it.id == id } ?: return
            val savedUri = saveToDownloads(done.fileName, done.mimeType, file)
            file.delete()
            update(id) {
                it.copy(
                    status = Status.COMPLETED,
                    savedUri = savedUri,
                    segments = emptyList(),
                    speedBps = 0,
                    error = null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ctx.ensureActive()
            update(id) { it.copy(status = Status.FAILED, error = friendlyError(e), speedBps = 0) }
        } finally {
            val c = counters
            if (c != null) {
                val snapshotSegs = segs
                withContext(NonCancellable) {
                    update(id) {
                        if (it.status == Status.COMPLETED) {
                            it
                        } else {
                            it.copy(
                                downloadedBytes = c.total(),
                                segments = if (snapshotSegs.isEmpty()) {
                                    it.segments
                                } else {
                                    snapshotSegs.mapIndexed { i, s -> s.copy(downloaded = c.get(i)) }
                                },
                                speedBps = 0
                            )
                        }
                    }
                }
            }
        }
    }

    /** Membaca ukuran, nama, dan dukungan Range dari server dengan satu permintaan kecil. */
    private suspend fun probe(item: DownloadItem): DownloadItem {
        val request = Request.Builder()
            .url(item.url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).executeCancellable { resp ->
            val code = resp.code
            if (code != 200 && code != 206) throw IOException("Server membalas HTTP $code")

            val headerName = parseDispositionName(resp.header("Content-Disposition"))
            val name = sanitizeFileName(headerName ?: item.fileName).ifBlank { "download" }
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()

            var total = -1L
            var resumable = false
            if (code == 206) {
                val size = resp.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                if (size != null && size > 0) {
                    total = size
                    resumable = true
                }
            } else {
                val length = resp.body?.contentLength() ?: -1L
                if (length > 0) total = length
            }
            item.copy(
                fileName = name,
                totalBytes = total,
                resumable = resumable,
                mimeType = resolveMime(name, mime)
            )
        }
    }

    private fun makeSegments(total: Long): List<Segment> {
        val count = if (total < MIN_SPLIT_BYTES) 1 else SEGMENTS
        val chunk = total / count
        return (0 until count).map { i ->
            val start = i * chunk
            val end = if (i == count - 1) total - 1 else start + chunk - 1
            Segment(start, end, 0)
        }
    }

    private suspend fun downloadSegment(
        url: String,
        file: File,
        seg: Segment,
        index: Int,
        c: AtomicLongArray
    ) {
        val ctx = currentCoroutineContext()
        var attempt = 0
        while (true) {
            val done = c.get(index)
            if (done >= seg.length) return
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=${seg.start + done}-${seg.end}")
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(request).executeCancellable { resp ->
                    if (resp.code != 206) {
                        throw IOException("Server tidak mendukung unduh bertahap (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw IOException("Respons kosong")
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(seg.start + done)
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ctx.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            val room = seg.length - c.get(index)
                            val toWrite = if (n.toLong() > room) room.toInt() else n
                            if (toWrite > 0) {
                                raf.write(buffer, 0, toWrite)
                                c.addAndGet(index, toWrite.toLong())
                            }
                            if (c.get(index) >= seg.length) break
                        }
                    }
                }
                if (c.get(index) >= seg.length) return
                throw IOException("Koneksi terputus sebelum selesai")
            } catch (e: IOException) {
                ctx.ensureActive()
                attempt++
                if (attempt > 3) throw e
                delay(1000L * attempt)
            }
        }
    }

    private suspend fun downloadSingle(id: String, url: String, file: File, c: AtomicLongArray) {
        val ctx = currentCoroutineContext()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).executeCancellable { resp ->
            if (!resp.isSuccessful) throw IOException("Server membalas HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Respons kosong")
            val length = body.contentLength()
            if (length > 0) update(id, save = false) { it.copy(totalBytes = length) }
            FileOutputStream(file).use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ctx.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    c.addAndGet(0, n.toLong())
                }
            }
        }
    }

    /** Memperbarui progres dan kecepatan tiap 0,5 detik. */
    private suspend fun tick(id: String, c: AtomicLongArray, base: List<Segment>) {
        var last = c.total()
        var lastTime = System.nanoTime()
        var speed = 0L
        var count = 0
        while (true) {
            delay(500)
            val now = System.nanoTime()
            val current = c.total()
            val seconds = (now - lastTime) / 1_000_000_000.0
            val instant = if (seconds > 0) ((current - last) / seconds).toLong() else 0L
            speed = if (speed == 0L) instant else (speed * 0.6 + instant * 0.4).toLong()
            last = current
            lastTime = now
            count++
            val snapshot = base.mapIndexed { i, s -> s.copy(downloaded = c.get(i)) }
            update(id, save = count % 6 == 0) {
                it.copy(
                    downloadedBytes = current,
                    speedBps = speed,
                    segments = if (snapshot.isEmpty()) it.segments else snapshot
                )
            }
        }
    }

    // ---------------------------------------------------------------- penyimpanan

    private fun tempFile(id: String): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "downloads")
        dir.mkdirs()
        return File(dir, "$id.part")
    }

    /** Menyalin file selesai ke folder Download/IDM (terlihat di aplikasi File dan Galeri). */
    private fun saveToDownloads(fileName: String, mimeType: String?, source: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType ?: resolveMime(fileName, null))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IDM")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gagal membuat file di folder Download")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Gagal menulis file")
            out.use { stream ->
                source.inputStream().use { input -> input.copyTo(stream, 256 * 1024) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri.toString()
    }

    // ---------------------------------------------------------------- util

    private fun update(id: String, save: Boolean = true, block: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) block(it) else it } }
        if (save) persistNow()
    }

    private fun persistNow() {
        store.save(_items.value)
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is UnknownHostException -> "Server tidak ditemukan atau tidak ada internet"
        is SocketTimeoutException -> "Koneksi timeout"
        is ConnectException -> "Tidak bisa terhubung ke server"
        is SSLException -> "Masalah koneksi aman (SSL)"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Menjalankan request secara blocking, tetapi langsung memutus koneksi
     * ketika coroutine dibatalkan (misalnya saat tombol Jeda ditekan).
     */
    private suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
        val call = this@executeCancellable
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use(block)
        } finally {
            watcher.cancel()
        }
    }
}
