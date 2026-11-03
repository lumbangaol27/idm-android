package com.lumbangaol.idm

import android.app.Application

class IdmApp : Application() {
    lateinit var engine: DownloadEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(this)
    }
}
