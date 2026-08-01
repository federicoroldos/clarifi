package com.clarifi

import android.app.Application
import com.clarifi.data.repo.seedIfEmpty
import com.clarifi.work.FixedDueNotifications
import com.clarifi.work.FixedDueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the single [AppContainer] for the process.
 *
 * Dependencies are wired by hand rather than with a DI framework: at this app's
 * size the whole graph fits in one readable file, and a broken wiring is a
 * compile error on a line you can read instead of generated code you cannot.
 */
class ClariFiApp : Application() {

    lateinit var container: AppContainer
        private set

    /** Lives as long as the process; used for work that outlives any one screen. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch { container.database.seedIfEmpty() }

        FixedDueNotifications.ensureChannel(this)
        FixedDueWorker.schedule(this)
    }
}
