package dev.airpulse.fitbit_air

import android.util.Log
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import io.reactivex.Observable
import io.reactivex.Single
import java.util.concurrent.TimeUnit

/**
 * Asks `/settings` which settings exist, one selector at a time.
 *
 * `/settings` refuses GET, so the only way to look at it is the request the
 * haptic read already uses: a PUT whose body is `0a 01 NN`, a selector and
 * nothing else. Reading haptic intensity sends exactly that with selector 1,
 * constantly and without changing anything, so varying only the selector byte
 * is the smallest possible departure from a known-safe call.
 *
 * It carries no value to assign, so there is nothing for the device to store
 * even if it treated the message as a write. That is a reasoned expectation
 * from the shape of a message, not a guarantee about undocumented firmware.
 *
 * Selector 1 is deliberately skipped: it is haptics, it is already understood,
 * and leaving it out keeps the probe away from the one setting known to have a
 * side effect on the device.
 */
internal object AirSettingsProbe {
    private val selectors = (2..12).toList()

    /** Enough to see a setting's shape; a settings value is not an identifier. */
    private const val MAX_LOGGED_BYTES = 32

    fun probe(endpoint: CoapEndpoint): Single<Map<String, Any>> =
        Observable.fromIterable(selectors).concatMapSingle { selector ->
            val request = OutgoingRequestBuilder("/settings", Method.PUT)
                .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0)
                .body(byteArrayOf(0x0a, 0x01, selector.toByte()))
                .build()
            endpoint.responseFor(request).flatMap { response ->
                response.body.asData().map { body ->
                    val code = "${response.responseCode.responseClass}.${response.responseCode.detail}"
                    val hex = body.take(MAX_LOGGED_BYTES)
                        .joinToString(" ") { "%02x".format(it.toInt() and 255) }
                    Log.i("AirBridge", "setting $selector -> $code bytes=${body.size} [$hex]")
                    mapOf<String, Any>(
                        "selector" to selector,
                        "responseClass" to response.responseCode.responseClass.toInt(),
                        "detail" to response.responseCode.detail.toInt(),
                        "bodyLength" to body.size,
                        "body" to hex,
                    )
                }
            }.timeout(5, TimeUnit.SECONDS).onErrorReturn {
                Log.i("AirBridge", "setting $selector -> no answer")
                mapOf("selector" to selector, "errorCode" to "SETTING_READ_FAILED")
            }
        }.toList().map { results -> mapOf("settings" to results) }
}
