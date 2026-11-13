/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris

import android.app.Application

class IrisApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The on-device model takes hundreds of MB; it reloads at the next session.
        if (level >= TRIM_MEMORY_BACKGROUND) container.onDeviceRecognizer.release()
    }
}
