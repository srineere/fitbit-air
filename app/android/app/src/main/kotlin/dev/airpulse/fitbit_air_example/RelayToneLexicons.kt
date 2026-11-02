package dev.airpulse.fitbit_air_example

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** The English (VADER) and German sentiment lexicons, about 1 MB together, loaded once. */
internal class RelayToneLexicons(val english: RelayVader, val german: RelayVader) {
    companion object {
        @Volatile private var shared: RelayToneLexicons? = null
        private val preloading = AtomicBoolean(false)

        fun shared(context: Context): RelayToneLexicons = shared ?: synchronized(this) {
            shared ?: RelayToneLexicons(
                RelayVader(read(context, "vader/vader_lexicon.txt")),
                RelayVader(read(context, "tone/de_lexicon.txt"), RelayVader.Language.GERMAN),
            ).also { shared = it }
        }

        /** Loads off the main thread so the first classified notification does not wait for it. */
        fun preload(context: Context) {
            if (shared != null || !preloading.compareAndSet(false, true)) return
            val app = context.applicationContext
            Thread({ runCatching { shared(app) }; preloading.set(false) }, "tone-lexicons")
                .apply { isDaemon = true }.start()
        }

        private fun read(context: Context, path: String) =
            context.assets.open(path).bufferedReader().useLines { RelayVader.parse(it) }
    }
}
