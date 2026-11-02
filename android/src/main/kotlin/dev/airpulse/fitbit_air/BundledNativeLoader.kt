package dev.airpulse.fitbit_air

import com.fitbit.goldengate.bindings.GoldenGate

/**
 * Loads the Golden Gate transport built from the public Apache-2.0 source and
 * bundled with this plugin (`libgg.so` core and `libxp.so` JNI bridge). Nothing
 * is read from Google Health.
 */
internal object BundledNativeLoader {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        // libxp.so links against libgg.so; load the core first.
        System.loadLibrary("gg")
        GoldenGate.init(loggingEnabled = false)
        initialized = true
    }
}
