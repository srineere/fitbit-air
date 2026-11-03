package dev.airpulse.fitbit_air_example

import android.content.Context
import org.json.JSONObject

/** Versioned, app-private settings. Chat identities never enter reports or backups. */
internal object RelayRuleStore {
    private fun prefs(context: Context) = context.getSharedPreferences("relay_rules", Context.MODE_PRIVATE)

    @Synchronized fun read(context: Context): RelayRuleConfig {
        val raw = prefs(context).getString("config", null)
        if (raw == null) {
            val legacy = context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
            val config = migrateRelayRules(RelayAppSelection.selected(context), RelayAppSelection.pulseStyle(context), legacy.getBoolean("rules_migrated", false))
            write(context, config)
            check(legacy.edit().putBoolean("rules_migrated", true).commit()) { "Could not finish migrating settings" }
            return config
        }
        // Corrupt or newer configs fail closed; never silently enable all chats/apps.
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return RelayRuleConfig()
        if (json.optInt("version") == 1) {
            // Version 1 kept saved chats and "Selected" mode outside this config.
            val config = runCatching { decode(json, RelayConversations.legacy(context)) }.getOrElse { return RelayRuleConfig() }
            write(context, config)
            RelayConversations.dropLegacy(context)
            return config
        }
        return runCatching { decode(json) }.getOrElse { RelayRuleConfig() }
    }

    /** [legacy] holds version 1's separately stored saved chats and "Selected" flag. */
    internal fun decode(json: JSONObject, legacy: Pair<Map<String, SavedChat>, Boolean>? = null): RelayRuleConfig {
        val version = json.getInt("version")
        require(version in 2..4 || (version == 1 && legacy != null))
        val apps = json.getJSONObject("apps")
        val chats = json.getJSONObject("chats")
        val quiet = json.getJSONObject("silence")
        // Configs saved before message tone existed decode with it switched off.
        val tone = json.optJSONObject("tone")
        fun tonePreset(key: String) = if (tone == null || tone.isNull(key)) null else RelayPreset.parse(tone.getString(key))
        // Configs saved before calls could ring decode with the shipped defaults.
        val ring = json.optJSONObject("ring")
        // Saved callers arrived in version 4; older settings have none.
        val callers = json.optJSONObject("callers")
        return RelayRuleConfig(
            defaultPreset = RelayPreset.parse(json.getString("defaultPreset")),
            defaultCallPreset = if (json.isNull("defaultCallPreset")) RelayPreset.RING
                else RelayPreset.parse(json.getString("defaultCallPreset")),
            apps = apps.keys().asSequence().associateWith { key ->
                val value = apps.getJSONObject(key)
                RelayAppRule(
                    value.getBoolean("enabled"),
                    if (value.isNull("preset")) null else RelayPreset.parse(value.getString("preset")),
                    if (value.isNull("callPreset")) null else RelayPreset.parse(value.getString("callPreset")),
                )
            },
            chats = if (version == 1) {
                // Version 1 stored only presets here; labels came from the saved list.
                val presets = chats.keys().asSequence().associateWith { RelayPreset.parse(chats.getString(it)) }
                legacy!!.first.mapValues { (id, chat) -> chat.copy(preset = presets[id]) }
            } else chats.keys().asSequence().associateWith { id ->
                val chat = chats.getJSONObject(id)
                SavedChat(chat.getString("packageName"), chat.getString("label"), chat.getString("kind"),
                    if (chat.isNull("preset")) null else RelayPreset.parse(chat.getString("preset")))
            },
            silence = RelaySilence(quiet.getLong("pauseUntil"), quiet.getBoolean("quietEnabled"), quiet.getInt("startMinute"), quiet.getInt("endMinute")),
            tone = RelayToneRules(tone?.getBoolean("enabled") ?: false, tonePreset("urgent"), tonePreset("negative"), tonePreset("neutral")),
            // Configs written before the ring held its session stored a
            // per-pulse reconnect interval, which no longer means anything.
            ring = if (ring == null) RelayRingSettings() else RelayRingSettings(
                ring.optInt("gapMillis", RelayRingSettings.DEFAULT_GAP_MILLIS),
                ring.getInt("maxSeconds").coerceIn(10, 180),
            ),
            callers = callers?.keys()?.asSequence()?.associateWith { id ->
                val caller = callers.getJSONObject(id)
                SavedCaller(caller.getString("packageName"), caller.getString("label"),
                    if (caller.isNull("preset")) null else RelayPreset.parse(caller.getString("preset")))
            }.orEmpty(),
            onlySavedCallers = json.optBoolean("onlySavedCallers", false),
            everyoneElseSilent = if (version == 1) legacy!!.second else json.getBoolean("everyoneElseSilent"),
        )
    }

    internal fun encode(config: RelayRuleConfig): JSONObject {
        val apps = JSONObject()
        config.apps.forEach { (key, rule) ->
            apps.put(key, JSONObject().put("enabled", rule.enabled)
                .put("preset", rule.preset?.id ?: JSONObject.NULL)
                .put("callPreset", rule.callPreset?.id ?: JSONObject.NULL))
        }
        val chats = JSONObject()
        config.chats.forEach { (id, chat) ->
            chats.put(id, JSONObject().put("packageName", chat.packageName).put("label", chat.label)
                .put("kind", chat.kind).put("preset", chat.preset?.id ?: JSONObject.NULL))
        }
        val s = config.silence
        val callers = JSONObject()
        config.callers.forEach { (id, caller) ->
            callers.put(id, JSONObject().put("packageName", caller.packageName)
                .put("label", caller.label).put("preset", caller.preset?.id ?: JSONObject.NULL))
        }
        val json = JSONObject().put("version", 4).put("defaultPreset", config.defaultPreset.id)
            .put("callers", callers).put("onlySavedCallers", config.onlySavedCallers)
            .put("defaultCallPreset", config.defaultCallPreset.id)
            .put("apps", apps).put("chats", chats).put("everyoneElseSilent", config.everyoneElseSilent)
            .put("silence", JSONObject().put("pauseUntil", s.pauseUntil).put("quietEnabled", s.quietEnabled)
                .put("startMinute", s.startMinute).put("endMinute", s.endMinute))
            .put("ring", JSONObject().put("gapMillis", config.ring.gapMillis)
                .put("maxSeconds", config.ring.maxSeconds))
        val t = config.tone
        json.put("tone", JSONObject().put("enabled", t.enabled).put("urgent", t.urgent?.id ?: JSONObject.NULL)
            .put("negative", t.negative?.id ?: JSONObject.NULL).put("neutral", t.neutral?.id ?: JSONObject.NULL))
        return json
    }

    private fun write(context: Context, config: RelayRuleConfig) {
        check(prefs(context).edit().putString("config", encode(config).toString()).commit()) { "Could not save settings" }
    }

    @Synchronized fun update(context: Context, change: (RelayRuleConfig) -> RelayRuleConfig) {
        val next = change(read(context))
        require(next.apps.size <= 128 && next.chats.size <= 64 && next.callers.size <= 128)
        require(next.selectedPackages.size <= 64)
        require(!next.defaultPreset.silent)
        // A repeat has nothing to stop it when the alert is not a ringing call.
        require(!next.defaultPreset.callsOnly && next.apps.values.none { it.preset?.callsOnly == true }) {
            "Only calls can repeat."
        }
        require(next.apps.keys.all { it != context.packageName && it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) })
        write(context, next)
        CalendarRelayService.active?.settingsChanged()
    }

    fun status(context: Context): Map<String, Any?> {
        val c = read(context)
        return mapOf("defaultPreset" to c.defaultPreset.id,
            "defaultCallPreset" to c.defaultCallPreset.id,
            "ringGapMillis" to c.ring.gapMillis, "ringMaxSeconds" to c.ring.maxSeconds,
            "apps" to c.apps.mapValues { (_, r) ->
                mapOf("enabled" to r.enabled, "preset" to r.preset?.id, "callPreset" to r.callPreset?.id)
            },
            "onlySavedCallers" to c.onlySavedCallers,
            "callers" to c.callers.map { (id, caller) ->
                mapOf("id" to id, "packageName" to caller.packageName,
                    "label" to caller.label, "preset" to caller.preset?.id)
            },
            "recentCallers" to RelayRecentCallers.list(c.callers.keys),
            "chats" to c.chats.map { (id, chat) ->
                mapOf("id" to id, "packageName" to chat.packageName, "label" to chat.label, "kind" to chat.kind,
                    "preset" to chat.preset?.id)
            },
            "everyoneElseSilent" to c.everyoneElseSilent,
            "pauseUntil" to c.silence.pauseUntil, "quietEnabled" to c.silence.quietEnabled,
            "startMinute" to c.silence.startMinute, "endMinute" to c.silence.endMinute,
            "silenceReason" to c.silence.reason(System.currentTimeMillis()),
            "toneEnabled" to c.tone.enabled, "toneUrgent" to c.tone.urgent?.id,
            "toneNegative" to c.tone.negative?.id, "toneNeutral" to c.tone.neutral?.id)
    }
}
