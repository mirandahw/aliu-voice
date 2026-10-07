package com.mirandahw.sideaccount

import android.content.Context
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.models.authentication.AuthState
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector

/** Where to land after a switch. Either a guild or a DM channel, both optional. */
data class Pending(val accountId: Long, val guildId: Long = 0L, val channelId: Long = 0L)

object Switcher {
    private const val KEY_PENDING = "pending"
    const val KEY_RESTART_ON_SWITCH = "restartOnSwitch"

    private val logger = Logger("SideAccount")
    lateinit var settings: SettingsAPI

    @Volatile
    var switching = false
        private set

    val restartOnSwitch get() = settings.getBool(KEY_RESTART_ON_SWITCH, false)

    /**
     * Swap the client over to [account] and then navigate to [target] once the new session is up.
     * The token is handed to Discord's own auth store, so everything downstream (gateway, REST, stores)
     * reconnects as that user. We deliberately never call StoreAuthentication.logout(): that hits
     * /auth/logout and would invalidate the token we want to come back to.
     */
    fun switchTo(account: Account, target: Pending, ctx: Context) {
        if (switching) return
        if (account.token == Accounts.currentToken()) {
            navigate(target)
            return
        }
        switching = true
        settings.setString(KEY_PENDING, "${target.accountId}:${target.guildId}:${target.channelId}")
        Utils.showToast("Switching to ${account.tag}…")

        if (restartOnSwitch) {
            // setAuthed persists the token through AuthStateCache, so a cold start comes up as the new account.
            StoreStream.getAuthentication().setAuthed(account.token)
            Utils.mainThread.postDelayed({ Utils.restartAliucord(ctx) }, 250)
            return
        }

        Utils.mainThread.post {
            try {
                // Clears per-user store state the same way a logout does, minus the network call.
                preLogout()
                StoreStream.getAuthentication().setAuthed(account.token)
            } catch (t: Throwable) {
                logger.error("In-place switch failed, falling back to a restart", t)
                try {
                    StoreStream.getAuthentication().setAuthed(account.token)
                } catch (_: Throwable) {
                }
                Utils.restartAliucord(ctx)
                return@post
            }
            waitAndNavigate(target, ctx)
        }
    }

    /** Called on plugin start: if a restart-based switch left a navigation target, finish it. */
    fun resumePending(ctx: Context) {
        val raw = settings.getString(KEY_PENDING, null) ?: return
        val parts = raw.split(":")
        if (parts.size != 3) {
            settings.remove(KEY_PENDING)
            return
        }
        val pending = Pending(parts[0].toLong(), parts[1].toLong(), parts[2].toLong())
        val acc = Accounts.byId(pending.accountId)
        if (acc == null || acc.token != Accounts.currentToken()) {
            settings.remove(KEY_PENDING)
            return
        }
        waitAndNavigate(pending, ctx)
    }

    /** Polls the stores until the target exists (gateway READY has populated them), then selects it. */
    private fun waitAndNavigate(target: Pending, ctx: Context, attempt: Int = 0) {
        val ready = try {
            when {
                target.guildId != 0L -> StoreStream.getGuilds().getGuild(target.guildId) != null
                target.channelId != 0L -> StoreStream.getChannels().getChannel(target.channelId) != null
                else -> StoreStream.getUsers().me.id == target.accountId
            }
        } catch (t: Throwable) {
            false
        }
        if (ready) {
            finish(target)
            return
        }
        if (attempt >= 60) { // 30s
            logger.warn("Gave up waiting for stores after switching accounts")
            finish(Pending(target.accountId))
            return
        }
        Utils.mainThread.postDelayed({ waitAndNavigate(target, ctx, attempt + 1) }, 500)
    }

    private fun finish(target: Pending) {
        switching = false
        settings.remove(KEY_PENDING)
        navigate(target)
        Accounts.refreshSideDataAsync()
    }

    private fun navigate(target: Pending) {
        try {
            when {
                target.guildId != 0L -> StoreStream.getGuildSelected().set(target.guildId)
                target.channelId != 0L -> ChannelSelector.getInstance().findAndSet(Utils.appActivity, target.channelId)
            }
        } catch (t: Throwable) {
            logger.error("Navigation after switch failed", t)
        }
    }

    /**
     * "Add account" via Discord's own login screen: remember the live account, then drop the local
     * session so the auth landing shows. Whatever logs in next is captured by the setAuthed hook.
     */
    fun signOutLocally(ctx: Context) {
        val tok = Accounts.currentToken()
        if (tok != null && Accounts.current() == null) {
            // Make sure we can come back to the account we're leaving.
            Accounts.register(tok)
        }
        Utils.mainThread.post {
            try {
                preLogout()
                // handleAuthState(null) is what a real logout does locally: clears the cached auth state and
                // resets persisted store data, which drops the app onto the auth landing screen.
                val auth = StoreStream.getAuthentication()
                StoreAuthentication::class.java
                    .getDeclaredMethod("handleAuthState\$app_productionGoogleRelease", AuthState::class.java)
                    .apply { isAccessible = true }
                    .invoke(auth, null)
            } catch (t: Throwable) {
                logger.error("Local sign-out failed", t)
                Utils.showToast("Couldn't sign out locally, add the account by token instead")
            }
        }
    }

    /** StoreStream.handlePreLogout is private; its synthetic accessor isn't. */
    private fun preLogout() {
        StoreStream.`access$handlePreLogout`(ChannelSelector.getInstance().stream)
    }
}
