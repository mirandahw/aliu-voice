package com.mirandahw.sideaccount

import android.content.Context
import com.aliucord.Logger
import com.aliucord.Utils
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector

/** Where to land after a switch. Either a guild or a DM channel, both optional. */
data class Pending(val accountId: Long, val guildId: Long = 0L, val channelId: Long = 0L)

object Switcher {
    private const val KEY_PENDING = "pending"
    const val KEY_RESTART_ON_SWITCH = "restartOnSwitch"

    private val logger = Logger("SideAccount")

    @Volatile
    var switching = false
        private set

    /** True once a gateway READY has been handled in this process (set by the plugin's READY hook). */
    @Volatile
    var readySeen = false

    val restartOnSwitch get() = Storage.getBool(KEY_RESTART_ON_SWITCH, true)

    /**
     * Swap the client over to [account] and then navigate to [target] once the new session is up.
     * The token is handed to Discord's own auth store, so everything downstream (gateway, REST, stores)
     * reconnects as that user. We deliberately never call StoreAuthentication.logout(): that hits
     * /auth/logout and would invalidate the token we want to come back to.
     */
    fun switchTo(account: Account, target: Pending, ctx: Context) {
        if (switching) return
        val token = Accounts.tokenOf(account.id)
        if (token == null) {
            Utils.showToast("No token stored for ${account.tag}, add it again")
            return
        }
        if (account.id == Accounts.liveUserId()) {
            navigate(target)
            return
        }
        switching = true
        Storage.putString(KEY_PENDING, "${target.accountId}:${target.guildId}:${target.channelId}")
        Utils.showToast("Switching to ${account.tag}…")
        AccountState.snapshot(Accounts.liveUserId())

        if (restartOnSwitch) {
            // Discord persists the selected guild and subscribes to it right after READY. If that's the
            // old account's guild the server kicks the socket (4000) and it reconnects once more. Point
            // it at where we're going (or at Home) before the restart; set() runs on the dispatcher and
            // is persisted on dispatch end, hence the short delay.
            try {
                StoreStream.getGuildSelected().set(target.guildId)
            } catch (t: Throwable) {
                logger.warn("Couldn't pre-select guild before restart", t)
            }
            // setAuthed persists the token through AuthStateCache, so a cold start comes up as the new account.
            StoreStream.getAuthentication().setAuthed(token)
            Utils.mainThread.postDelayed({ Utils.restartAliucord(ctx) }, 700)
            return
        }

        // Experimental in-place switch. The gateway only reconnects when the token goes null and back,
        // because a token change on an open socket is a no-op (StoreGatewayConnection.handleClientStateUpdate).
        // setAuthed(null) is a full local logout (clears cached auth state, persisted stores, the
        // selected guild), so handlePreLogout is not needed on top; skipping it is also the experiment
        // for the "everything unread after an in-place switch" report.
        readySeen = false
        StoreStream.getDispatcherYesThisIsIntentional().schedule {
            try {
                val auth = StoreStream.getAuthentication()
                auth.setAuthed(null)
                auth.setAuthed(token)
            } catch (t: Throwable) {
                logger.error("In-place switch failed, falling back to a restart", t)
                Utils.mainThread.post { restartInto(token, ctx) }
                return@schedule
            }
            Utils.mainThread.post { waitAndNavigate(target, ctx, restartOnTimeout = true) }
        }
    }

    /** Called on plugin start: if a restart-based switch left a navigation target, finish it. */
    fun resumePending(ctx: Context) {
        val raw = Storage.getString(KEY_PENDING) ?: return
        val parts = raw.split(":")
        if (parts.size != 3) {
            Storage.remove(KEY_PENDING)
            return
        }
        val pending = Pending(parts[0].toLong(), parts[1].toLong(), parts[2].toLong())
        val acc = Accounts.byId(pending.accountId)
        if (acc == null || Accounts.tokenOf(acc.id) != Accounts.currentToken()) {
            Storage.remove(KEY_PENDING)
            return
        }
        switching = true
        waitAndNavigate(pending, ctx, restartOnTimeout = false)
    }

    /**
     * Polls until a READY for the new user has been handled and the target exists, then selects it.
     * [restartOnTimeout] is for the in-place path: no READY for the new user within 30s means the hot
     * switch didn't take, so do it the reliable way.
     */
    private fun waitAndNavigate(target: Pending, ctx: Context, restartOnTimeout: Boolean, attempt: Int = 0) {
        val userIn = Accounts.liveUserId() == target.accountId
        val ready = userIn && try {
            when {
                target.guildId != 0L -> StoreStream.getGuilds().getGuild(target.guildId) != null
                target.channelId != 0L -> StoreStream.getChannels().getChannel(target.channelId) != null
                else -> true
            }
        } catch (t: Throwable) {
            false
        }
        if (ready) {
            finish(target)
            return
        }
        if (attempt >= 60) { // 30s
            if (restartOnTimeout) {
                logger.warn("No READY for the new account after an in-place switch, restarting instead")
                val token = Accounts.tokenOf(target.accountId)
                if (token != null) {
                    restartInto(token, ctx)
                    return
                }
            }
            logger.warn("Gave up waiting for stores after switching accounts")
            finish(if (userIn) Pending(target.accountId) else target.copy(guildId = 0L, channelId = 0L))
            return
        }
        Utils.mainThread.postDelayed({ waitAndNavigate(target, ctx, restartOnTimeout, attempt + 1) }, 500)
    }

    private fun restartInto(token: String, ctx: Context) {
        try {
            StoreStream.getAuthentication().setAuthed(token)
        } catch (_: Throwable) {
        }
        Utils.restartAliucord(ctx)
    }

    private fun finish(target: Pending) {
        switching = false
        Storage.remove(KEY_PENDING)
        if (Accounts.liveUserId() == target.accountId) {
            // READY has been processed by now (that's what we waited for), so the stores' own
            // pruning is done and it's safe to put this account's state back.
            StoreStream.getDispatcherYesThisIsIntentional().schedule { AccountState.restore(target.accountId) }
            navigate(target)
        }
        Utils.mainThread.postDelayed({ Accounts.refreshSideDataAsync() }, 3000)
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
     * Blocking (verifies the live token first), call off the main thread.
     */
    fun signOutLocally() {
        val tok = Accounts.currentToken()
        if (tok != null && !Accounts.knowsToken(tok)) {
            // Make sure we can come back to the account we're leaving.
            Accounts.register(tok)
        }
        AccountState.snapshot(Accounts.liveUserId())
        readySeen = false
        StoreStream.getDispatcherYesThisIsIntentional().schedule {
            try {
                // Same as what Discord's own logout does locally (minus the /auth/logout call):
                // publishes a null auth state, which clears cached auth + persisted stores and
                // drops the app onto the auth landing screen.
                StoreStream.getAuthentication().setAuthed(null)
            } catch (t: Throwable) {
                logger.error("Local sign-out failed", t)
                Utils.showToast("Couldn't sign out locally, add the account by token instead")
            }
        }
    }
}
