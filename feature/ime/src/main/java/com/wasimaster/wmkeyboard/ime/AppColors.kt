package com.wasimaster.wmkeyboard.ime

import android.content.Context
import android.util.LruCache
import com.wasimaster.wmkeyboard.core.theme.AppColorProbe
import com.wasimaster.wmkeyboard.core.theme.AppColorSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The colours of the app the keyboard is typing into, for the theme to wear
 * (issue #592).
 *
 * A process-wide flow rather than UI state, the way the rotating photo's state
 * is: the theme provider and the Themes tool both read it, and neither should
 * need a callback threaded through the whole board to get it.
 *
 * Looking at an app costs a few milliseconds, so each package is looked at once
 * per process and kept. A package seen before is answered on the spot, so the
 * keyboard comes up already in that app's colours; a new one comes up in the
 * plain theme and crossfades into its colours a moment later.
 */
object AppColors {

    private val _current = MutableStateFlow<AppColorSample?>(null)

    /** The app the keyboard is in now, or null when it should wear no app's colours. */
    val current: StateFlow<AppColorSample?> = _current.asStateFlow()

    private val cache = LruCache<String, AppColorSample>(CACHE_SIZE)
    private var job: Job? = null

    /**
     * The keyboard came up in [packageName]. Null, the keyboard's own
     * package (its settings screens, where a theme is being edited and must be
     * seen as it is), or the feature being off all clear the colours.
     */
    fun onApp(context: Context, scope: CoroutineScope, packageName: String?, enabled: Boolean) {
        job?.cancel()
        if (!enabled || packageName.isNullOrEmpty() || packageName == context.packageName) {
            _current.value = null
            return
        }
        cache.get(packageName)?.let {
            _current.value = it
            return
        }
        _current.value = null
        val appContext = context.applicationContext
        job = scope.launch {
            val sample = withContext(Dispatchers.Default) {
                AppColorProbe.sample(appContext, packageName)
            } ?: return@launch
            cache.put(packageName, sample)
            _current.value = sample
        }
    }

    private const val CACHE_SIZE = 32
}
