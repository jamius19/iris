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
}
