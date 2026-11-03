package com.lumbangaol.idm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Menyimpan daftar unduhan ke SharedPreferences (JSON) supaya bertahan setelah aplikasi ditutup. */
class DownloadStore(context: Context) {

    private val prefs = context.getSharedPreferences("idm_store", Context.MODE_PRIVATE)

    @Synchronized
    fun save(items: List<DownloadItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    @Synchronized
    fun load(): List<DownloadItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).toItem() }
        }.getOrDefault(emptyList())
    }
}

private fun DownloadItem.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id)
    o.put("url", url)
    o.put("fileName", fileName)
    o.put("total", totalBytes)
    o.put("downloaded", downloadedBytes)
    o.put("status", status.name)
    o.put("error", error ?: JSONObject.NULL)
    o.put("resumable", resumable)
    o.put("probed", probed)
    o.put("savedUri", savedUri ?: JSONObject.NULL)
    o.put("mime", mimeType ?: JSONObject.NULL)
    o.put("createdAt", createdAt)
    val segs = JSONArray()
    segments.forEach {
        val s = JSONObject()
        s.put("s", it.start)
        s.put("e", it.end)
        s.put("d", it.downloaded)
        segs.put(s)
    }
    o.put("segments", segs)
    return o
}

private fun JSONObject.toItem(): DownloadItem {
    val segs = optJSONArray("segments")
    val segments = if (segs == null) emptyList() else (0 until segs.length()).map {
        val s = segs.getJSONObject(it)
        Segment(s.getLong("s"), s.getLong("e"), s.getLong("d"))
    }
    return DownloadItem(
        id = getString("id"),
        url = getString("url"),
        fileName = getString("fileName"),
        totalBytes = optLong("total", -1),
        downloadedBytes = optLong("downloaded", 0),
        status = runCatching { Status.valueOf(getString("status")) }.getOrDefault(Status.PAUSED),
        error = if (isNull("error")) null else getString("error"),
        segments = segments,
        resumable = optBoolean("resumable", false),
        probed = optBoolean("probed", false),
        savedUri = if (isNull("savedUri")) null else getString("savedUri"),
        mimeType = if (isNull("mime")) null else getString("mime"),
        createdAt = optLong("createdAt", System.currentTimeMillis())
    )
}
