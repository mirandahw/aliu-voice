version = "0.3.0"
description = "Show a second account's servers in the sidebar and its DMs behind a tab; tap to switch over."

aliucord {
    changelog.set(
        """
        # 0.3.0
        * Everything the plugin stores now lives in app-private storage; the shared Aliucord settings
          file is scrubbed on first start.
        * Side servers are recognised by id with the live stores as tiebreaker, so taps keep working
          after a background refresh (no more accidental selects + 4000 disconnect loops).
        * Side sidebars show the other account's folders, in its order; unpositioned servers go on top.
        * The selected guild is pointed at the destination before a restart switch (one fewer reconnect).
        * "Who is live" waits for a READY in this process instead of trusting the cached user.
        * In-place switch no longer runs the pre-logout pass (experiment for the unread flood).

        # 0.2.0
        * Restart-on-switch is now the default (the in-place path stays experimental and falls back to a restart).
        * Store mutations run on Discord's store dispatcher, no more half-logged-out states.
        * The live account is identified by user id; side servers/DMs are tracked by identity and never
          duplicate something the live account already has.
        * Tokens moved out of the shared settings file into app-private storage.
        * Side lists are cached between restarts and 429s are retried.
        * Side servers follow that account's own sidebar order (guild_folders).
        * Recently used emojis and collapsed channel categories are saved per account and restored
          after a switch, instead of being wiped or shared.

        # 0.1.0
        * First cut: side accounts' servers in the sidebar, DM tabs, tap-to-switch, add via login screen or token.
        """.trimIndent(),
    )
    deploy.set(true)
}
