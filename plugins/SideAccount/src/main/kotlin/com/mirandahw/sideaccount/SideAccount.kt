package com.mirandahw.sideaccount

import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.discord.api.channel.Channel
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.guilds.list.GuildListItem
import com.discord.widgets.guilds.list.GuildListViewHolder
import com.discord.widgets.guilds.list.WidgetGuildsList
import com.discord.widgets.guilds.list.WidgetGuildsListViewModel
import java.util.WeakHashMap

/**
 * Shows a second (third, ...) account's servers in the guild sidebar and its DMs behind a tab at the
 * top of the DM panel. Tapping any of them switches the client to that account and lands on it.
 *
 * Only one account is ever "live": Discord's stores, gateway and REST all run as whoever is logged in.
 * The other accounts' server/DM lists are fetched with their own tokens over plain REST.
 */
@AliucordPlugin(requiresRestart = true)
class SideAccount : Plugin() {
    init {
        settingsTab = SettingsTab(SideAccountSettings::class.java)
    }

    /** Per-WidgetChannelsList state: our tab row and the last real DM model Discord gave us. */
    private class PanelState(val tabs: DmTabs) {
        var lastDmModel: WidgetChannelListModel? = null
        var wrappedSelect: ((Channel) -> Unit)? = null
    }

    private val panels = WeakHashMap<WidgetChannelsList, PanelState>()

    override fun start(context: Context) {
        Accounts.settings = settings
        Switcher.settings = settings
        AccountState.settings = settings
        Accounts.init(context)

        // Make sure the live account is on the list, then pull data for the others.
        Accounts.currentToken()?.let { tok ->
            if (!Accounts.knowsToken(tok)) Accounts.registerAsync(tok) { Accounts.refreshSideDataAsync() }
            else Accounts.refreshSideDataAsync()
        }
        Switcher.resumePending(context)

        patchAuth()
        patchGuildSidebar()
        patchDmPanel()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        panels.clear()
    }

    // ---- auth -------------------------------------------------------------------------------

    /** Any login (Discord's screen, TokenLogin, our own switch) ends up in setAuthed. Remember the token. */
    private fun patchAuth() {
        patcher.after<StoreAuthentication>("setAuthed", String::class.java) { param ->
            val token = param.args[0] as? String ?: return@after
            if (!Accounts.knowsToken(token)) {
                Accounts.registerAsync(token) { Accounts.refreshSideDataAsync() }
            }
        }
    }

    // ---- guild sidebar ----------------------------------------------------------------------

    private fun patchGuildSidebar() {
        // Append the other accounts' guilds, behind a divider, right above the bottom-nav spacer.
        patcher.before<WidgetGuildsList>("configureUI", WidgetGuildsListViewModel.ViewState::class.java) { param ->
            val loaded = param.args[0] as? WidgetGuildsListViewModel.ViewState.Loaded ?: return@before
            val extra = sideGuildItems()
            if (extra.isEmpty()) return@before

            val items = ArrayList<GuildListItem>(loaded.items)
            val insertAt = items.indexOfLast { it is GuildListItem.SpaceItem }.takeIf { it >= 0 } ?: items.size
            items.addAll(insertAt, listOf(GuildListItem.DividerItem.INSTANCE) + extra)
            param.args[0] = WidgetGuildsListViewModel.ViewState.Loaded(items, loaded.hasChannels, loaded.wasDragResult)
        }

        // Tap: switch instead of letting Discord select a guild the live account isn't in.
        patcher.before<WidgetGuildsListViewModel>(
            "onItemClicked",
            GuildListItem::class.java,
            Context::class.java,
            FragmentManager::class.java,
        ) { param ->
            val item = param.args[0] as? GuildListItem.GuildItem ?: return@before
            val owner = Accounts.ownerOfGuild(item.guild) ?: return@before
            param.result = null
            Switcher.switchTo(owner, Pending(owner.id, guildId = item.guild.id), param.args[1] as Context)
        }

        // Long press would open a context menu for a guild the stores don't know. Swallow it.
        patcher.before<WidgetGuildsListViewModel>("onItemLongPressed", GuildListItem::class.java) { param ->
            val item = param.args[0] as? GuildListItem.GuildItem ?: return@before
            if (Accounts.isSideGuild(item.guild)) param.result = null
        }

        // No drag and drop for side guilds: Discord would try to save folder positions with foreign ids.
        patcher.after<GuildListViewHolder.GuildViewHolder>("canDrag") { param ->
            val data = guildViewHolderData(this) ?: return@after
            if (Accounts.isSideGuild(data.guild)) param.result = false
        }

        // Dim side guilds a touch so they read as "elsewhere".
        patcher.after<GuildListViewHolder.GuildViewHolder>("configure", GuildListItem.GuildItem::class.java) { param ->
            val item = param.args[0] as GuildListItem.GuildItem
            itemView.alpha = if (Accounts.isSideGuild(item.guild)) 0.7f else 1f
        }
    }

    private fun sideGuildItems(): List<GuildListItem> {
        val out = ArrayList<GuildListItem>()
        val liveGuilds = try {
            StoreStream.getGuilds().guilds
        } catch (t: Throwable) {
            emptyMap<Long, Any>()
        }
        for (acc in Accounts.others()) {
            for (guild in Accounts.sideData(acc.id).guilds) {
                // A server both accounts are in is already in the sidebar; don't show it twice.
                if (liveGuilds.containsKey(guild.id)) continue
                out += GuildListItem.GuildItem(
                    guild,
                    0, // mentionCount
                    false, // isSelected
                    false, // isUnread
                    false, // isConnectedToVoice
                    null, // folderId
                    false, // isTargetedForFolderCreation
                    false, // hasActiveStageChannel / isConnectedToStageChannel
                    false,
                    null, // isLastGuildInFolder
                    null, // applicationStatus
                    false, // isPendingGuild
                    false, // isLurkingGuild
                    false, // hasOngoingApplicationStream
                    false, // hasActiveScheduledEvent
                )
            }
        }
        return out
    }

    private val guildVhDataField by lazy {
        GuildListViewHolder.GuildViewHolder::class.java.getDeclaredField("data").apply { isAccessible = true }
    }

    private fun guildViewHolderData(vh: GuildListViewHolder.GuildViewHolder): GuildListItem.GuildItem? =
        guildVhDataField.get(vh) as? GuildListItem.GuildItem

    // ---- DM panel ---------------------------------------------------------------------------

    private fun patchDmPanel() {
        // Mount the account chips under the panel's app bar.
        patcher.after<WidgetChannelsList>("onViewBound", View::class.java) {
            val widget = this
            val binding = WidgetChannelsList.`access$getBinding$p`(widget)
            val tabs = DmTabs(requireContext()) { _ -> rerender(widget) }
            tabs.visibility = View.GONE
            binding.b.addView(tabs)
            panels[widget] = PanelState(tabs)
        }

        // Swap the DM model for the selected account's list, and keep the chips in sync.
        patcher.before<WidgetChannelsList>("configureUI", WidgetChannelListModel::class.java) { param ->
            val model = param.args[0] as WidgetChannelListModel
            val state = panels[this] ?: return@before
            val others = Accounts.others()

            if (model.isGuildSelected || others.isEmpty()) {
                state.tabs.visibility = View.GONE
                return@before
            }
            state.tabs.visibility = View.VISIBLE
            state.tabs.rebuild(Accounts.current(), others)
            state.lastDmModel = model

            val selected = state.tabs.selected
            if (selected == 0L) return@before
            param.args[0] = sideDmModel(model, selected)
        }

        // Route taps on side DMs to a switch; everything else goes to Discord as before.
        patcher.after<WidgetChannelsList>("configureUI", WidgetChannelListModel::class.java) {
            val state = panels[this] ?: return@after
            val adapter = WidgetChannelsList.`access$getAdapter$p`(this)
            val current = adapter.onSelectChannel
            if (current === state.wrappedSelect) return@after
            val ctx = requireContext()
            val wrapped: (Channel) -> Unit = { channel ->
                val owner = Accounts.ownerOfDm(channel)
                if (owner != null) Switcher.switchTo(owner, Pending(owner.id, channelId = channel.id), ctx)
                else current.invoke(channel)
            }
            state.wrappedSelect = wrapped
            adapter.onSelectChannel = wrapped

            val currentOptions = adapter.onSelectChannelOptions
            adapter.onSelectChannelOptions = { channel ->
                if (!Accounts.isSideDm(channel)) currentOptions.invoke(channel)
            }
        }
    }

    private fun sideDmModel(real: WidgetChannelListModel, accountId: Long): WidgetChannelListModel {
        val items = ArrayList<ChannelListItem>()
        // Keep whatever Discord puts before/after the DM rows (notices, bottom-nav spacer).
        val realItems = real.items
        val firstPrivate = realItems.indexOfFirst { it is ChannelListItemPrivate }
        val lastPrivate = realItems.indexOfLast { it is ChannelListItemPrivate }
        val head = if (firstPrivate > 0) realItems.subList(0, firstPrivate) else emptyList()
        val tail = if (lastPrivate >= 0 && lastPrivate + 1 < realItems.size) realItems.subList(lastPrivate + 1, realItems.size)
        else if (lastPrivate < 0) realItems else emptyList()

        items.addAll(head)
        for (channel in Accounts.sideData(accountId).dms) {
            items += ChannelListItemPrivate(channel, null, false, 0, false, false)
        }
        items.addAll(tail)
        // isGuildSelected=false, showPremiumGuildHint=false, showEmptyState only when there's nothing to show

        return WidgetChannelListModel(null, items, false, false, items.none { it is ChannelListItemPrivate }, emptyList())
    }

    private fun rerender(widget: WidgetChannelsList) {
        val model = panels[widget]?.lastDmModel ?: return
        Utils.mainThread.post {
            try {
                WidgetChannelsList.`access$configureUI`(widget, model)
            } catch (t: Throwable) {
                logger.error("Failed to re-render DM panel", t)
            }
        }
    }
}
