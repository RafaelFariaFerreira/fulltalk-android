package dev.fulltalk.app

import android.app.Application
import android.content.Context
import dev.fulltalk.app.asr.Transcriber
import dev.fulltalk.app.data.Preferences
import dev.fulltalk.app.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Everything the screen and the floating microphone share, created once per process. */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val preferences = Preferences(context)
    val model = ModelManager(context.filesDir, scope)
    val transcriber = Transcriber(model.store)
}

val Context.container: AppContainer
    get() = (applicationContext as App).container
