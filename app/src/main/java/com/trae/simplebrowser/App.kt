package com.trae.simplebrowser

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        Log.i("App", "application created")
    }
}
