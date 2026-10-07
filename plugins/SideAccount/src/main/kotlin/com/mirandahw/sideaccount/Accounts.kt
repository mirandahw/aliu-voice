package com.mirandahw.sideaccount

import android.content.Context
import android.content.SharedPreferences
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/**
 * An account the plugin knows about. Display metadata only; the token lives in app-private
 * SharedPreferences (see [Accounts.tokenOf]), never in the shared /sdcard/Aliucord settings file.
 */
data class Account(
    val id: Long,
    val username: String,
    val discriminator: String,
    val avatar: String?,
) {
    val tag get() = if (discriminator == "0" || discriminator.isEmpty()) username else "$username#$discriminator"
}

/** Gson-friendly holder, avoids needing TypeToken (obfuscated in the Discord APK). */
class AccountList(val accounts: ArrayList<Account> = arrayListOf())

/** What we last fetched for an account that isn't the live one. */
class SideData(
    val guilds: List<Guild> = emptyList(),
    val dms: List<Channel> = emptyList(),
)

object Accounts {
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_SIDE_PREFIX = "side_"
    private const val TOKEN_PREFS = "sideaccount_tokens"
    private val logger = Logger("SideAccount")

    lateinit var settings: SettingsAPI
    private lateinit var tokens: SharedPreferences

    @Volatile
    private var cached: AccountList? = null

    /** In-memory guilds/DMs per account id, for accounts other than the live one. */
    private val side = HashMap<Long, SideData>()

    /**
     * The Guild / Channel objects we built ourselves, mapped to their owner. Side items are recognised by
     * identity, not by id, so a server both accounts are in never gets mistaken for a side item.
     */
    private val sideObjects: MutableMap<Any, Long> = Collections.synchronizedMap(IdentityHashMap())

    fun init(ctx: Context) {
        tokens = ctx.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        migrateTokens()
        loadCaches()
    }

    val all: List<Account>
        get() = (cached ?: settings.getObject(KEY_ACCOUNTS, AccountList()).also { cached = it }).accounts

    fun tokenOf(id: Long): String? = tokens.getString(id.toString(), null)

    fun knowsToken(token: String) = all.any { tokenOf(it.id) == token }

    fun currentToken(): String? = try {
        RestAPI.AppHeadersProvider.INSTANCE.authToken
    } catch (t: Throwable) {
        null
    }

    /** The id of whoever the stores say is logged in, or 0 before READY. */
    fun liveUserId(): Long = try {
        StoreStream.getUsers().me.id
    } catch (t: Throwable) {
        0L
    }

    /** The live account: by user id once the stores are up, by token before that. */
    fun current(): Account? {
        val me = liveUserId()
        if (me != 0L) all.firstOrNull { it.id == me }?.let { return it }
        val tok = currentToken() ?: return null
        return all.firstOrNull { tokenOf(it.id) == tok }
    }

    /** Every known account except the live one. */
    fun others(): List<Account> {
        val cur = current()
        return all.filter { it.id != cur?.id }
    }

    fun byId(id: Long) = all.firstOrNull { it.id == id }

    fun sideData(accountId: Long): SideData = side[accountId] ?: SideData()

    fun ownerOfGuild(guild: Guild): Account? = sideObjects[guild]?.let(::byId)
    fun ownerOfDm(channel: Channel): Account? = sideObjects[channel]?.let(::byId)
    fun isSideGuild(guild: Guild) = sideObjects.containsKey(guild)
    fun isSideDm(channel: Channel) = sideObjects.containsKey(channel)

    private fun save(list: AccountList) {
        cached = list
        settings.setObject(KEY_ACCOUNTS, list)
    }

    fun remove(id: Long) {
        save(AccountList(ArrayList(all.filter { it.id != id })))
        tokens.edit().remove(id.toString()).apply()
        settings.remove(KEY_SIDE_PREFIX + id)
        side.remove(id)?.let { data ->
            data.guilds.forEach(sideObjects::remove)
            data.dms.forEach(sideObjects::remove)
        }
    }

    /** Verifies a token against /users/@me and stores (or updates) the account. Blocking, call off the main thread. */
    @Throws(Exception::class)
    fun register(token: String): Account {
        val me = JSONObject(get("/users/@me", token))
        val acc = Account(
            id = me.getString("id").toLong(),
            username = me.getString("username"),
            discriminator = me.optString("discriminator", "0"),
            avatar = me.optString("avatar", null),
        )
        tokens.edit().putString(acc.id.toString(), token).apply()
        save(AccountList(ArrayList(all.filter { it.id != acc.id }).apply { add(acc) }))
        return acc
    }

    /** Fire-and-forget version used from hooks. */
    fun registerAsync(token: String, then: ((Account) -> Unit)? = null) {
        Utils.threadPool.execute {
            try {
                val acc = register(token)
                then?.invoke(acc)
            } catch (t: Throwable) {
                logger.error("Failed to register account for token", t)
            }
        }
    }

    /** Refreshes guild + DM lists for every account except the live one. Blocking. */
    fun refreshSideData() {
        for ((index, acc) in others().withIndex()) {
            val token = tokenOf(acc.id) ?: continue
            if (index > 0) Thread.sleep(600) // be gentle, the per-route bucket is small
            try {
                val guildsRaw = get("/users/@me/guilds", token)
                val dmsRaw = get("/users/@me/channels", token)
                // The account's own sidebar order lives in its user settings (guild_folders, or the
                // older guild_positions). Without it /users/@me/guilds comes back in no useful order.
                val order = try {
                    guildOrder(get("/users/@me/settings", token))
                } catch (t: Throwable) {
                    logger.warn("Couldn't fetch sidebar order for ${acc.tag}", t)
                    emptyList()
                }
                install(acc.id, guildsRaw, dmsRaw, order)
                settings.setString(
                    KEY_SIDE_PREFIX + acc.id,
                    JSONObject()
                        .put("guilds", JSONArray(guildsRaw))
                        .put("dms", dmsRaw)
                        .put("order", JSONArray(order))
                        .toString(),
                )
            } catch (t: Throwable) {
                logger.error("Failed to fetch data for ${acc.tag}", t)
            }
        }
    }

    fun refreshSideDataAsync(then: (() -> Unit)? = null) {
        Utils.threadPool.execute {
            refreshSideData()
            then?.let { Utils.mainThread.post(it) }
        }
    }

    // ---- internals ----------------------------------------------------------------------------

    /** Cached lists from the last run, so the sidebar is populated right after a restart. */
    private fun loadCaches() {
        for (acc in all) {
            val raw = settings.getString(KEY_SIDE_PREFIX + acc.id, null) ?: continue
            try {
                val o = JSONObject(raw)
                val order = o.optJSONArray("order")?.let { arr -> List(arr.length()) { arr.getLong(it) } } ?: emptyList()
                install(acc.id, o.getJSONArray("guilds").toString(), o.getString("dms"), order)
            } catch (t: Throwable) {
                logger.warn("Dropping unreadable cache for ${acc.tag}", t)
                settings.remove(KEY_SIDE_PREFIX + acc.id)
            }
        }
    }

    private fun install(accountId: Long, guildsRaw: String, dmsRaw: String, order: List<Long>) {
        side.remove(accountId)?.let { old ->
            old.guilds.forEach(sideObjects::remove)
            old.dms.forEach(sideObjects::remove)
        }
        val position = order.withIndex().associate { (i, id) -> id to i }
        val guilds = parseGuilds(guildsRaw).sortedBy { position[it.id] ?: Int.MAX_VALUE }
        val dms = parseDms(dmsRaw)
        guilds.forEach { sideObjects[it] = accountId }
        dms.forEach { sideObjects[it] = accountId }
        side[accountId] = SideData(guilds, dms)
    }

    /** Flattens guild_folders (or legacy guild_positions) from /users/@me/settings into one ordered id list. */
    private fun guildOrder(settingsRaw: String): List<Long> {
        val o = JSONObject(settingsRaw)
        val out = ArrayList<Long>()
        val folders = o.optJSONArray("guild_folders")
        if (folders != null) {
            for (i in 0 until folders.length()) {
                val ids = folders.getJSONObject(i).optJSONArray("guild_ids") ?: continue
                for (j in 0 until ids.length()) out += ids.getString(j).toLong()
            }
        } else {
            val positions = o.optJSONArray("guild_positions") ?: return out
            for (i in 0 until positions.length()) out += positions.getString(i).toLong()
        }
        return out
    }

    /** 0.1.0 kept tokens inside the shared settings file. Move them to private prefs once. */
    private fun migrateTokens() {
        val raw = try {
            settings.getString(KEY_ACCOUNTS, null)
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            val arr = JSONObject(raw).optJSONArray("accounts") ?: return
            var moved = false
            val editor = tokens.edit()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tok = o.optString("token", "")
                if (tok.isNotEmpty()) {
                    editor.putString(o.getString("id"), tok)
                    o.remove("token")
                    moved = true
                }
            }
            if (moved) {
                editor.apply()
                settings.setString(KEY_ACCOUNTS, JSONObject().put("accounts", arr).toString())
                cached = null
                logger.info("Moved stored tokens out of the shared settings file")
            }
        } catch (t: Throwable) {
            logger.warn("Token migration skipped", t)
        }
    }

    private fun get(route: String, token: String, attempt: Int = 0): String {
        val res = Http.Request.newDiscordRNRequest(route)
            .setHeader("Authorization", token)
            .setRequestTimeout(15000)
            .execute()
        if (res.statusCode == 429 && attempt < 3) {
            val wait = try {
                (JSONObject(res.text()).optDouble("retry_after", 1.0) * 1000).toLong() + 100
            } catch (t: Throwable) {
                1500L
            }
            Thread.sleep(wait.coerceIn(200L, 10_000L))
            return get(route, token, attempt + 1)
        }
        res.assertOk()
        return res.text()
    }

    /**
     * /users/@me/guilds returns partial guilds. Rather than pushing them through Discord's
     * Guild(ApiGuild) constructor (which expects a full gateway guild and would blow up on the
     * nulls), build empty model guilds and fill in the handful of fields the sidebar renders.
     */
    private fun parseGuilds(raw: String): List<Guild> {
        val arr = JSONArray(raw)
        val out = ArrayList<Guild>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Guild().also { g ->
                setField(g, "id", o.getString("id").toLong())
                setField(g, "name", o.getString("name"))
                setField(g, "icon", o.optString("icon", null))
                setField(g, "features", HashSet<Any>())
            }
        }
        return out
    }

    /** /users/@me/channels gives full DM/group DM objects; Gson with Discord's naming policy maps them straight. */
    private fun parseDms(raw: String): List<Channel> {
        val channels = GsonUtils.gsonRestApi.fromJson(raw, Array<Channel>::class.java)
        // Most recent conversations first, same as Discord's DM list.
        return channels.sortedByDescending { lastMessageId(it) }
    }

    private val lastMessageIdField by lazy {
        Channel::class.java.getDeclaredField("lastMessageId").apply { isAccessible = true }
    }

    private fun lastMessageId(ch: Channel): Long = try {
        lastMessageIdField.getLong(ch)
    } catch (t: Throwable) {
        0L
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    @Suppress("unused")
    private fun channelId(ch: Channel) = ch.id
}
