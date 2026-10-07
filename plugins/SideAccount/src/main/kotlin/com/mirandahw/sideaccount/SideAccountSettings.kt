package com.mirandahw.sideaccount

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.views.Button
import com.aliucord.views.DangerButton
import com.aliucord.views.Divider
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class SideAccountSettings : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Side Account")
        val ctx = requireContext()
        val current = Accounts.current()

        addHeader(ctx, "Accounts")
        if (Accounts.all.isEmpty()) {
            addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "No accounts stored yet. The one you're logged into gets added automatically."
            })
        }
        for (acc in Accounts.all.sortedBy { it.username.lowercase() }) {
            val isCurrent = acc.id == current?.id
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6.dp, 0, 6.dp)
            }
            row.addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                text = if (isCurrent) "${acc.tag}  (current)" else acc.tag
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!isCurrent) {
                row.addView(Button(ctx).apply {
                    text = "Switch"
                    setOnClickListener { Switcher.switchTo(acc, Pending(acc.id), ctx) }
                })
            }
            row.addView(DangerButton(ctx).apply {
                text = "Remove"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = 8.dp }
                setOnClickListener {
                    ConfirmDialog()
                        .setTitle("Remove ${acc.tag}?")
                        .setDescription("Only forgets the token on this device. Nothing happens to the account.")
                        .setIsDangerous(true)
                        .setOnOkListener {
                            Accounts.remove(acc.id)
                            reRender()
                        }
                        .show(parentFragmentManager, "remove_account")
                }
            })
            addView(row)
        }

        addView(Divider(ctx))
        addHeader(ctx, "Add an account")

        addView(Button(ctx).apply {
            text = "Log in with Discord's login screen"
            setOnClickListener {
                ConfirmDialog()
                    .setTitle("Sign out and log in as someone else?")
                    .setDescription(
                        "Your current account stays saved here, so you can switch straight back. " +
                            "Discord's normal login screen will open; whoever you log in as is added.",
                    )
                    .setOnOkListener {
                        Utils.threadPool.execute {
                            try {
                                Switcher.signOutLocally()
                            } catch (t: Throwable) {
                                Utils.showToast("Couldn't start: ${t.message}")
                            }
                        }
                    }
                    .show(parentFragmentManager, "add_account_login")
            }
        })

        addView(Button(ctx).apply {
            text = "Add by token"
            setOnClickListener {
                val dialog = InputDialog()
                    .setTitle("Account token")
                    .setDescription("The token is verified against Discord before it's stored.")
                    .setPlaceholderText("mfa.… or xxx.yyy.zzz")
                dialog.setOnOkListener {
                    val token = dialog.input.trim()
                    dialog.dismiss()
                    if (token.isEmpty()) return@setOnOkListener
                    Utils.threadPool.execute {
                        try {
                            val acc = Accounts.register(token)
                            Accounts.refreshSideData()
                            Utils.showToast("Added ${acc.tag}")
                            Utils.mainThread.post { reRender() }
                        } catch (t: Throwable) {
                            Utils.showToast("Invalid token: ${t.message}")
                        }
                    }
                }
                dialog.show(parentFragmentManager, "add_account_token")
            }
        })

        addView(Divider(ctx))
        addHeader(ctx, "Behaviour")

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Restart app when switching",
                "On (default): reliable, a few seconds. Off: swap the session in place (experimental; falls back to a restart if the new account never connects).",
            ).apply {
                isChecked = Switcher.restartOnSwitch
                setOnCheckedListener { Storage.putBool(Switcher.KEY_RESTART_ON_SWITCH, it) }
            },
        )

        addView(Button(ctx).apply {
            text = "Refresh other accounts' servers and DMs"
            setOnClickListener {
                Accounts.refreshSideDataAsync { Utils.showToast("Refreshed") }
            }
        })

        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = "Other accounts' servers show at the bottom of the server list; their DMs sit behind the " +
                "account chips at the top of the DM panel. Tapping either switches you over."
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorTextMuted))
            setPadding(0, 12.dp, 0, 0)
        })
    }
}
