package org.kdx.shell.droid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Turns the ad blocker back on after a reboot, if it was on (and Android still has the user's VPN permission). */
class AdBlockBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (VpnService.prepare(ctx) != null) return
        if (PrivacyVpnService.prefs(ctx).getBoolean("enabled", false)) { runCatching { PrivacyVpnService.start(ctx) }; return }
        if (AdBlockService.prefs(ctx).getBoolean("enabled", false)) {
            runCatching { AdBlockService.start(ctx) }
        }
    }
}
