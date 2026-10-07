package com.mirandahw.sideaccount

import android.content.Context
import android.content.SharedPreferences
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject

/** An account the plugin knows about. Display metadata only; the token lives in [Accounts.tokenOf]. */
data class Account(
    val id: Long,
    val username: String,
    val discriminator: String,
    val avatar: String?,
) {
    val tag get() = if (discriminator == "0" || discriminator.isEmpty()) username else "$username#$discriminator"
}

/** One entry of an account's sidebar, in sidebar order: a folder, or a single guild. */
class SideFolder(val id: Long, val name: String?, val color: Int?, val guildIds: List<Long>)

/** What we last fetched for an account that isn't the live one. */
class SideData(
    /** All guilds by id. */
    val guilds: Map<Long, Guild> = emptyMap(),
    /** Sidebar order: folders (possibly single-guild, unnamed ones) as Discord stores them. */
    val folders: List<SideFolder> = emptyList(),
    val dms: List<Channel> = emptyList(),
)

object Accounts {
    private const val KEY_ACCOUNTS = "accounts"
    private const val TOKEN_PREFS = "sideaccount_tokens"
    private val logger = Logger("SideAccount")

    private lateinit var tokens: SharedPreferences

    /** Invoked (main thread) whenever side data changes, so the sidebar can re-render. */
    var onSideDataChanged: (() -> Unit)? = null

    @Volatile
    private var cached: List<Account>? = null

    private val side = HashMap<Long, SideData>()

    /** guild id / DM channel id / folder id -> owning account id, for everything in [side]. */
    private val guildOwner = HashMap<Long, Long>()
    private val dmOwner = HashMap<Long, Long>()
    private val folderOwner = HashMap<Long, Long>()

    fun init(ctx: Context) {
        tokens = ctx.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        loadCaches()
    }

    val all: List<Account>
        get() = cached ?: load().also { cached = it }

    private fun load(): List<Account> {
        val raw = Storage.getString(KEY_ACCOUNTS) ?: return emptyList()
        return try {
            val arr = JSONObject(raw).getJSONArray("accounts")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Account(o.getLong("id"), o.getString("username"), o.optString("discriminator", "0"), o.optString("avatar", null))
            }
        } catch (t: Throwable) {
            logger.warn("Unreadable account list, starting empty", t)
            emptyList()
        }
    }

    private fun save(list: List<Account>) {
        cached = list
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().put("id", a.id).put("username", a.username).put("discriminator", a.discriminator).put("avatar", a.avatar))
        }
        Storage.putString(KEY_ACCOUNTS, JSONObject().put("accounts", arr).toString())
    }

    fun tokenOf(id: Long): String? = tokens.getString(id.toString(), null)

    fun knowsToken(token: String) = all.any { tokenOf(it.id) == token }

    fun currentToken(): String? = try {
        RestAPI.AppHeadersProvider.INSTANCE.authToken
    } catch (t: Throwable) {
        null
    }

    /**
     * The id of whoever the stores say is logged in. StoreUser.me is restored from Discord's disk
     * cache at startup, so it's only trusted once a READY has landed in this process.
     */
    fun liveUserId(): Long = if (!Switcher.readySeen) 0L else try {
        StoreStream.getUsers().me.id
    } catch (t: Throwable) {
        0L
    }

    /** The live account: by user id after READY, by token before that. */
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

    // ---- "is this one of ours?" -----------------------------------------------------------------
    // Keyed by id, with the live stores as the tiebreaker: anything the live account actually has is
    // never a side item, whatever another account's list says. That keeps the answer stable across
    // refreshes (objects get rebuilt, ids don't).

    private fun liveHasGuild(id: Long) = try {
        StoreStream.getGuilds().getGuild(id) != null
    } catch (t: Throwable) {
        false
    }

    private fun liveHasChannel(id: Long) = try {
        StoreStream.getChannels().getChannel(id) != null
    } catch (t: Throwable) {
        false
    }

    fun ownerOfGuild(guild: Guild): Account? {
        if (liveHasGuild(guild.id)) return null
        val owner = guildOwner[guild.id] ?: return null
        return byId(owner)?.takeIf { it.id != current()?.id }
    }

    fun ownerOfDm(channel: Channel): Account? {
        if (liveHasChannel(channel.id)) return null
        val owner = dmOwner[channel.id] ?: return null
        return byId(owner)?.takeIf { it.id != current()?.id }
    }

    fun isSideGuild(guild: Guild) = ownerOfGuild(guild) != null
    fun isSideDm(channel: Channel) = ownerOfDm(channel) != null
    fun isSideFolder(folderId: Long) = folderOwner[folderId]?.let { it != current()?.id } ?: false

    // ---- mutations ------------------------------------------------------------------------------

    fun remove(id: Long) {
        save(all.filter { it.id != id })
        tokens.edit().remove(id.toString()).apply()
        Storage.deleteBlob("side_$id")
        Storage.deleteBlob("emoji_$id")
        Storage.deleteBlob("collapsed_$id")
        uninstall(id)
        notifyChanged()
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
        save(all.filter { it.id != acc.id } + acc)
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
        var changed = false
        for ((index, acc) in others().withIndex()) {
            val token = tokenOf(acc.id) ?: continue
            if (index > 0) Thread.sleep(600) // be gentle, the per-route bucket is small
            try {
                val guildsRaw = get("/users/@me/guilds", token)
                val dmsRaw = get("/users/@me/channels", token)
                // The account's own sidebar order lives in its user settings (guild_folders, or the
                // older guild_positions). Without it /users/@me/guilds comes back in no useful order.
                val foldersRaw = try {
                    foldersJson(get("/users/@me/settings", token))
                } catch (t: Throwable) {
                    logger.warn("Couldn't fetch sidebar order for ${acc.tag}", t)
                    JSONArray()
                }
                val blob = JSONObject().put("guilds", JSONArray(guildsRaw)).put("dms", dmsRaw).put("folders", foldersRaw).toString()
                install(acc.id, blob)
                Storage.writeBlob("side_${acc.id}", blob)
                changed = true
            } catch (t: Throwable) {
                logger.error("Failed to fetch data for ${acc.tag}", t)
            }
        }
        if (changed) notifyChanged()
    }

    fun refreshSideDataAsync(then: (() -> Unit)? = null) {
        Utils.threadPool.execute {
            refreshSideData()
            then?.let { Utils.mainThread.post(it) }
        }
    }

    private fun notifyChanged() {
        onSideDataChanged?.let { Utils.mainThread.post(it) }
    }

    // ---- internals ------------------------------------------------------------------------------

    /** Cached lists from the last run, so the sidebar is populated right after a restart. */
    private fun loadCaches() {
        for (acc in all) {
            val raw = Storage.readBlob("side_${acc.id}") ?: continue
            try {
                install(acc.id, raw)
            } catch (t: Throwable) {
                logger.warn("Dropping unreadable cache for ${acc.tag}", t)
                Storage.deleteBlob("side_${acc.id}")
            }
        }
    }

    private fun uninstall(accountId: Long) {
        side.remove(accountId)?.let { old ->
            old.guilds.keys.forEach { if (guildOwner[it] == accountId) guildOwner.remove(it) }
            old.dms.forEach { if (dmOwner[it.id] == accountId) dmOwner.remove(it.id) }
            old.folders.forEach { if (folderOwner[it.id] == accountId) folderOwner.remove(it.id) }
        }
    }

    private fun install(accountId: Long, blob: String) {
        val o = JSONObject(blob)
        val guilds = parseGuilds(o.getJSONArray("guilds").toString())
        val dms = parseDms(o.getString("dms"))
        val folders = parseFolders(o.optJSONArray("folders") ?: JSONArray())
        synchronized(side) {
            uninstall(accountId)
            guilds.keys.forEach { guildOwner[it] = accountId }
            dms.forEach { dmOwner[it.id] = accountId }
            folders.forEach { if (it.name != null || it.guildIds.size > 1) folderOwner[it.id] = accountId }
            side[accountId] = SideData(guilds, folders, dms)
        }
    }

    /**
     * guild_folders entries: {id, name, color, guild_ids}. A plain top-level server is an entry with a
     * null id and one guild. Legacy guild_positions is turned into one such entry per guild.
     */
    private fun foldersJson(settingsRaw: String): JSONArray {
        val o = JSONObject(settingsRaw)
        o.optJSONArray("guild_folders")?.let { return it }
        val out = JSONArray()
        val positions = o.optJSONArray("guild_positions") ?: return out
        for (i in 0 until positions.length()) {
            out.put(JSONObject().put("guild_ids", JSONArray().put(positions.getString(i))))
        }
        return out
    }

    private fun parseFolders(arr: JSONArray): List<SideFolder> {
        val out = ArrayList<SideFolder>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ids = o.optJSONArray("guild_ids") ?: continue
            val guildIds = List(ids.length()) { ids.getString(it).toLong() }
            if (guildIds.isEmpty()) continue
            val id = if (o.isNull("id")) -guildIds.first() else o.getLong("id") // single guilds get a synthetic id
            val color = if (o.isNull("color")) null else o.getInt("color")
            out += SideFolder(id, if (o.isNull("name")) null else o.getString("name"), color, guildIds)
        }
        return out
    }

    private fun get(route: String, token: String, attempt: Int = 0): String {
        val res = Http.Request.newDiscordRNRequest(route)
            .setHeader("Authorization", token)
            .setRequestTimeout(15000)
            .execute()
        if (res.statusCode == 429 && attempt < 3) {
            // The body (retry_after) isn't readable through Http.Response on an error status; back off blind.
            Thread.sleep(1500L * (attempt + 1))
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
    private fun parseGuilds(raw: String): Map<Long, Guild> {
        val arr = JSONArray(raw)
        val out = LinkedHashMap<Long, Guild>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id").toLong()
            out[id] = Guild().also { g ->
                setField(g, "id", id)
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
}
