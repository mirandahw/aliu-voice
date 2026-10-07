package com.mirandahw.sideaccount

import android.content.Context
import android.content.SharedPreferences
import com.aliucord.Logger
import com.aliucord.api.SettingsAPI
import java.io.File

/**
 * Everything the plugin persists lives in app-private storage: small values in a private
 * SharedPreferences file, big blobs (cached server/DM lists, per-account snapshots) as files under
 * the app's private files dir. Nothing goes to /sdcard/Aliucord/settings, which any app with storage
 * access can read. Tokens have their own prefs file (see Accounts).
 */
object Storage {
    private val logger = Logger("SideAccount")
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File

    fun init(ctx: Context, legacy: SettingsAPI) {
        prefs = ctx.getSharedPreferences("sideaccount", Context.MODE_PRIVATE)
        dir = File(ctx.filesDir, "sideaccount").apply { mkdirs() }
        migrate(legacy)
    }

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getBool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun remove(key: String) = prefs.edit().remove(key).apply()

    fun readBlob(name: String): String? = File(dir, "$name.json").takeIf { it.isFile }?.readText()
    fun writeBlob(name: String, text: String) = File(dir, "$name.json").writeText(text)
    fun deleteBlob(name: String) {
        File(dir, "$name.json").delete()
    }

    /**
     * 0.1.0/0.2.0 wrote to the shared Aliucord settings file. Carry over what's worth keeping
     * (account metadata, the restart toggle, per-account snapshots), then scrub every key we ever
     * wrote from that file. Cached lists are just refetched.
     */
    private fun migrate(legacy: SettingsAPI) {
        val keys = try {
            legacy.allKeys.toList()
        } catch (t: Throwable) {
            return
        }
        if (keys.isEmpty()) return
        var moved = 0
        for (key in keys) {
            try {
                when {
                    key == "accounts" -> legacy.getString(key, null)?.let { putString(key, it); moved++ }
                    key == "restartOnSwitch" -> { putBool(key, legacy.getBool(key, true)); moved++ }
                    key.startsWith("emoji_") || key.startsWith("collapsed_") ->
                        legacy.getString(key, null)?.let { writeBlob(key, it); moved++ }
                    // side_*, pending: dropped
                }
                legacy.remove(key)
            } catch (t: Throwable) {
                logger.warn("Couldn't migrate $key", t)
            }
        }
        logger.info("Moved $moved entries out of the shared settings file and removed ${keys.size} keys from it")
    }
}
