package com.mirandahw.sideaccount

import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject

/** A logged-in account the plugin knows about. The token is the real deal, keep it in plugin settings only. */
data class Account(
    val id: Long,
    val username: String,
    val discriminator: String,
    val avatar: String?,
    val token: String,
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
    private val logger = Logger("SideAccount")

    lateinit var settings: SettingsAPI

    @Volatile
    private var cached: AccountList? = null

    /** In-memory guilds/DMs per account id, for accounts other than the live one. */
    private val side = HashMap<Long, SideData>()

    val all: List<Account>
        get() = (cached ?: settings.getObject(KEY_ACCOUNTS, AccountList()).also { cached = it }).accounts

    fun currentToken(): String? = try {
        RestAPI.AppHeadersProvider.INSTANCE.authToken
    } catch (t: Throwable) {
        null
    }

    fun current(): Account? = currentToken()?.let { tok -> all.firstOrNull { it.token == tok } }

    /** Every known account except the one the client is logged into right now. */
    fun others(): List<Account> {
        val tok = currentToken()
        return all.filter { it.token != tok }
    }

    fun byId(id: Long) = all.firstOrNull { it.id == id }

    fun sideData(accountId: Long): SideData = side[accountId] ?: SideData()

    fun ownerOfGuild(guildId: Long): Account? =
        others().firstOrNull { acc -> side[acc.id]?.guilds?.any { it.id == guildId } == true }

    fun ownerOfDm(channelId: Long): Account? =
        others().firstOrNull { acc -> side[acc.id]?.dms?.any { it.id == channelId } == true }

    fun isSideGuild(guildId: Long) = ownerOfGuild(guildId) != null
    fun isSideDm(channelId: Long) = ownerOfDm(channelId) != null

    private fun save(list: AccountList) {
        cached = list
        settings.setObject(KEY_ACCOUNTS, list)
    }

    fun remove(id: Long) {
        val list = AccountList(ArrayList(all.filter { it.id != id }))
        side.remove(id)
        save(list)
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
            token = token,
        )
        val list = AccountList(ArrayList(all.filter { it.id != acc.id }).apply { add(acc) })
        save(list)
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
        for (acc in others()) {
            try {
                side[acc.id] = SideData(fetchGuilds(acc.token), fetchDms(acc.token))
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

    private fun get(route: String, token: String): String {
        val res = Http.Request.newDiscordRNRequest(route)
            .setHeader("Authorization", token)
            .setRequestTimeout(15000)
            .execute()
        res.assertOk()
        return res.text()
    }

    /**
     * /users/@me/guilds returns partial guilds. Rather than pushing them through Discord's
     * Guild(ApiGuild) constructor (which expects a full gateway guild and would blow up on the
     * nulls), build empty model guilds and fill in the handful of fields the sidebar renders.
     */
    private fun fetchGuilds(token: String): List<Guild> {
        val arr = JSONArray(get("/users/@me/guilds", token))
        val out = ArrayList<Guild>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Guild().also { g ->
                setField(g, "id", o.getString("id").toLong())
                setField(g, "name", o.getString("name"))
                setField(g, "icon", o.optString("icon", null))
                // features is a Set in the model; the raw strings are enough for icon/name rendering
                setField(g, "features", HashSet<Any>())
            }
        }
        return out
    }

    /** /users/@me/channels gives full DM/group DM objects; Gson with Discord's naming policy maps them straight. */
    private fun fetchDms(token: String): List<Channel> {
        val raw = get("/users/@me/channels", token)
        val channels = GsonUtils.gsonRestApi.fromJson(raw, Array<Channel>::class.java)
        // Most recent conversations first, same as Discord's DM list.
        return channels.sortedByDescending { lastMessageId(it) }
    }

    private fun lastMessageId(ch: Channel): Long = try {
        Channel::class.java.getDeclaredField("lastMessageId").apply { isAccessible = true }.getLong(ch)
    } catch (t: Throwable) {
        0L
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }
}
