package dev.airpulse.fitbit_air_example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strictly metadata: no notification keys, text, sender names, or BLE identifiers.
 * [preset] is the chosen vibration and [tone] the message tone category, both as ids.
 */
internal data class RelayActivity(
    val commandId: String,
    val sourcePackage: String?,
    val stage: String,
    val atMillis: Long,
    val latencyMillis: Long? = null,
    val errorCode: String? = null,
    val preset: String? = null,
    val tone: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "commandId" to commandId, "sourcePackage" to sourcePackage,
        "stage" to stage, "atMillis" to atMillis,
        "latencyMillis" to latencyMillis, "errorCode" to errorCode,
        "preset" to preset, "tone" to tone,
    )
}

internal class RelayActivityBuffer(initial: List<RelayActivity> = emptyList()) {
    private val entries = initial.takeLast(100).toMutableList()
    fun add(entry: RelayActivity) {
        entries.add(entry)
        while (entries.size > 100) entries.removeAt(0)
    }
    fun snapshot(): List<RelayActivity> = entries.toList()
}

internal object RelayHistory {
    fun read(context: Context): List<RelayActivity> = runCatching {
        val text = context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
            .getString("history", "[]") ?: "[]"
        val array = JSONArray(text)
        (maxOf(0, array.length() - 100) until array.length()).map { i ->
            val value = array.getJSONObject(i)
            RelayActivity(value.getString("commandId"),
                if (value.isNull("sourcePackage")) null else value.getString("sourcePackage"),
                value.getString("stage"), value.getLong("atMillis"),
                if (value.isNull("latencyMillis")) null else value.getLong("latencyMillis"),
                if (value.isNull("errorCode")) null else value.getString("errorCode"),
                // Entries saved before these fields existed have neither key.
                if (value.isNull("preset")) null else value.getString("preset"),
                if (value.isNull("tone")) null else value.getString("tone"))
        }
    }.getOrDefault(emptyList())

    fun add(context: Context, entry: RelayActivity) {
        val buffer = RelayActivityBuffer(read(context))
        buffer.add(entry)
        val array = JSONArray()
        buffer.snapshot().forEach { event ->
            val objectValue = JSONObject()
            event.toMap().forEach { (key, value) -> objectValue.put(key, value ?: JSONObject.NULL) }
            array.put(objectValue)
        }
        context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
            .edit().putString("history", array.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
            .edit().remove("history").apply()
    }
}

internal data class RelayWorkerResult(val stage: String, val errorCode: String?) {
    companion object {
        fun decode(result: Any?): RelayWorkerResult {
            val map = result as? Map<*, *> ?: return RelayWorkerResult("failed", "WORKER_PROTOCOL")
            val stage = when (map["status"]) {
                "completed" -> "verified"
                "expired" -> "expired"
                "queueFull" -> "queue_full"
                "closed" -> "cancelled"
                "duplicate" -> "duplicate"
                "failed" -> "failed"
                else -> return RelayWorkerResult("failed", "WORKER_PROTOCOL")
            }
            val code = (map["errorCode"] as? String)?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
            return RelayWorkerResult(stage, code)
        }
    }
}
