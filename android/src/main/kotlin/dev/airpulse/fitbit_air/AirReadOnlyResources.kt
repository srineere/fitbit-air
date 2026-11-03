package dev.airpulse.fitbit_air

import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import io.reactivex.Observable
import io.reactivex.Single
import java.util.concurrent.TimeUnit

/** Fixed, source-traced GET allowlist. No arbitrary resource/write interface. */
internal object AirReadOnlyResources {
    /**
     * A fixed GET allowlist. Reads only: nothing here can change the device,
     * which is the point of probing before anything is written.
     *
     * Beyond the four paths the stack is known to use, these are candidates
     * for a resource that might describe the status light. A GET cannot make
     * the light do anything; what it can show is whether such a resource
     * exists to be written to later.
     */
    private val paths = listOf(
        // Known from the decompiled stack.
        "/md/606", "liveactivity", "/alarms", "/sync/status",
        // Device metadata neighbours of the battery resource.
        "/md/600", "/md/601", "/md/602", "/md/603", "/md/604",
        "/md/605", "/md/607", "/md/608", "/md/609", "/md/610",
        // Names a status light might plausibly live under.
        "/led", "/leds", "/light", "/display", "/screen", "/ui",
        "/device", "/status", "/settings", "/notification", "/notifications",
    )

    fun probe(endpoint: CoapEndpoint): Single<Map<String, Any>> =
        Observable.fromIterable(paths).concatMapSingle { path ->
            val request = OutgoingRequestBuilder(path, Method.GET)
                .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0).build()
            endpoint.responseFor(request).flatMap { response ->
                response.body.asData().map { body ->
                    mapOf<String, Any>(
                        "path" to path,
                        "responseClass" to response.responseCode.responseClass.toInt(),
                        "detail" to response.responseCode.detail.toInt(),
                        "bodyLength" to body.size,
                    ).also {
                        // Shape only: a response class and a length, never a
                        // payload, which could hold device identifiers.
                        android.util.Log.i("AirBridge", "probe $path -> " +
                            "${response.responseCode.responseClass}.${response.responseCode.detail}" +
                            " bytes=${body.size}")
                    }
                }
            }.timeout(5, TimeUnit.SECONDS).onErrorReturn {
                // Do not expose native exception text, which can contain payloads.
                android.util.Log.i("AirBridge", "probe $path -> no answer")
                mapOf("path" to path, "errorCode" to "RESOURCE_READ_FAILED")
            }
        }.toList().map { results -> mapOf("resources" to results) }
}
