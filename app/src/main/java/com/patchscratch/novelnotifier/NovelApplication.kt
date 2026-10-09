package com.patchscratch.novelnotifier

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.Logger

/**
 * App process entry. Sets up the file debug log and a Coil ImageLoader that
 * pipes image failures into it (a cover that 403s/timeouts is invisible
 * otherwise — the tile just renders grey).
 */
class NovelApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.log("APP", "process start")
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .logger(object : Logger {
                // Coil filters events below this before calling log(); 4 = INFO
                // on Coil's scale, so request failures always come through.
                override var level: Int = 4
                override fun log(tag: String, priority: Int, message: String?, throwable: Throwable?) {
                    DebugLog.log("COIL", message ?: "(no message)", throwable)
                }
            })
            .build()
    }
}
