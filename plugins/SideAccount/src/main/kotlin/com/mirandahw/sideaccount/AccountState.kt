package com.mirandahw.sideaccount

import com.aliucord.Logger
import com.discord.stores.StoreCollapsedChannelCategories
import com.discord.stores.StoreEmoji
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.frecency.FrecencyTracker
import com.discord.utilities.persister.Persister
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Discord keeps a few bits of per-user state in caches that have no idea which user they belong to:
 *
 *  - "recently used" emojis: StoreEmoji's FrecencyTracker, persisted under one global key
 *    (EMOJI_HISTORY_V4). A local logout clears it; a restart as another user just inherits it.
 *  - collapsed channel categories: StoreCollapsedChannelCategories, persisted in shared prefs and
 *    pruned on every READY to the guilds in the payload, so switching accounts throws the other
 *    account's state away.
 *
 * So: snapshot per account right before a switch, restore after the new account's READY has landed.
 * Restore mutates store internals and must run on the store dispatcher.
 */
object AccountState {
    private val logger = Logger("SideAccount")

    private fun field(cls: Class<*>, name: String) = cls.getDeclaredField(name).apply { isAccessible = true }

    private val emojiFrecency by lazy { field(StoreEmoji::class.java, "frecency") }
    private val emojiCache by lazy { field(StoreEmoji::class.java, "frecencyCache") }
    private val frecencyHistory by lazy { field(FrecencyTracker::class.java, "history") }
    private val frecencyDirty by lazy { field(FrecencyTracker::class.java, "dirty") }
    private val collapsedMap by lazy { field(StoreCollapsedChannelCategories::class.java, "collapsedCategories") }

    /** StoreStream only exposes this store through an internal getter, so pull it off the instance by type. */
    private val collapsedStore: StoreCollapsedChannelCategories by lazy {
        val stream = ChannelSelector.getInstance().stream
        val f = StoreStream::class.java.declaredFields.first { it.type == StoreCollapsedChannelCategories::class.java }
        f.isAccessible = true
        f.get(stream) as StoreCollapsedChannelCategories
    }

    /**
     * StoreV2.markChanged() is what makes a store re-snapshot and persist on dispatch end. It's
     * protected, so reflection; accept either the no-arg or the vararg UpdateSource overload.
     */
    private val markChanged: Pair<Method, Boolean>? by lazy {
        var found: Pair<Method, Boolean>? = null
        var c: Class<*>? = StoreCollapsedChannelCategories::class.java
        val seen = ArrayList<String>()
        while (c != null && found == null) {
            val methods = try {
                c.declaredMethods.toList()
            } catch (t: Throwable) {
                logger.warn("declaredMethods failed on ${c.name}", t)
                emptyList()
            }
            for (m in methods) {
                if (m.name != "markChanged") continue
                seen += "${c.simpleName}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
                if (m.parameterTypes.isEmpty()) {
                    found = m.apply { isAccessible = true } to false
                    break
                }
                if (m.parameterTypes.size == 1 && m.parameterTypes[0].isArray) {
                    found = m.apply { isAccessible = true } to true
                }
            }
            c = c.superclass
        }
        if (found == null) logger.warn("markChanged not found; saw: $seen")
        found
    }

    fun snapshot(accountId: Long) {
        if (accountId == 0L) return
        snapshotEmoji(accountId)
        snapshotCollapsed(accountId)
    }

    /** Store thread. */
    fun restore(accountId: Long) {
        if (accountId == 0L) return
        restoreEmoji(accountId)
        restoreCollapsed(accountId)
    }

    // ---- emoji history ------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun history(tracker: Any) = frecencyHistory.get(tracker) as HashMap<Any, MutableList<Long>>

    private fun snapshotEmoji(accountId: Long) {
        try {
            val tracker = emojiFrecency.get(StoreStream.getEmojis()) ?: return
            val json = JSONObject()
            for ((key, samples) in HashMap(history(tracker))) json.put(key.toString(), JSONArray(samples))
            Storage.writeBlob("emoji_$accountId", json.toString())
        } catch (t: Throwable) {
            logger.warn("Couldn't snapshot emoji history", t)
        }
    }

    private fun restoreEmoji(accountId: Long) {
        val raw = Storage.readBlob("emoji_$accountId") ?: return
        try {
            val store = StoreStream.getEmojis()
            val tracker = emojiFrecency.get(store) ?: return
            val json = JSONObject(raw)
            val history = history(tracker)
            history.clear()
            for (key in json.keys()) {
                val arr = json.getJSONArray(key)
                history[key] = MutableList(arr.length()) { arr.getLong(it) }
            }
            frecencyDirty.setBoolean(tracker, true)
            @Suppress("UNCHECKED_CAST")
            (emojiCache.get(store) as Persister<Any>).set(tracker, false)
        } catch (t: Throwable) {
            logger.warn("Couldn't restore emoji history", t)
        }
    }

    // ---- collapsed categories -----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun collapsed(store: Any) = collapsedMap.get(store) as MutableMap<Long, MutableSet<Long>>

    private fun snapshotCollapsed(accountId: Long) {
        try {
            val json = JSONObject()
            for ((guildId, categories) in HashMap(collapsed(collapsedStore))) {
                json.put(guildId.toString(), JSONArray(categories))
            }
            Storage.writeBlob("collapsed_$accountId", json.toString())
        } catch (t: Throwable) {
            logger.warn("Couldn't snapshot collapsed categories", t)
        }
    }

    private fun restoreCollapsed(accountId: Long) {
        val raw = Storage.readBlob("collapsed_$accountId") ?: return
        try {
            val store = collapsedStore
            val map = collapsed(store)
            val json = JSONObject(raw)
            map.clear()
            for (key in json.keys()) {
                val arr = json.getJSONArray(key)
                map[key.toLong()] = HashSet<Long>().apply { for (i in 0 until arr.length()) add(arr.getLong(i)) }
            }
            val mc = markChanged
            if (mc == null) {
                logger.warn("collapsed categories restored in memory only")
            } else if (mc.second) {
                mc.first.invoke(store, java.lang.reflect.Array.newInstance(mc.first.parameterTypes[0].componentType!!, 0))
            } else {
                mc.first.invoke(store)
            }
        } catch (t: Throwable) {
            logger.warn("Couldn't restore collapsed categories", t)
        }
    }
}
