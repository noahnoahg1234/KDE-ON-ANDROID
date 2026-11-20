package org.kdx.shell.apps

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.droid.AdBlockService

private val upstreams = listOf(
    "1.1.1.1" to "Cloudflare (fast, private)",
    "9.9.9.9" to "Quad9 (also blocks malware)",
    "8.8.8.8" to "Google",
    "94.140.14.14" to "AdGuard DNS (extra ad blocking)",
)

/** Ad Blocker – blocks ads and trackers in every Android app (and DroidSystem's Linux apps) with a local DNS VPN. */
@Composable
fun AdBlocker(state: KdxState) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AdBlockService.prefs(ctx) }
    var running by remember { mutableStateOf(AdBlockService.isRunning) }
    var queries by remember { mutableLongStateOf(0) }
    var blocked by remember { mutableLongStateOf(0) }
    var recent by remember { mutableStateOf(emptyList<String>()) }
    var allow by remember { mutableStateOf(AdBlockService.allowList(ctx).sorted()) }
    var upstream by remember { mutableStateOf(AdBlockService.upstreamDns(ctx)) }
    var listInfo by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    var newAllow by remember { mutableStateOf("") }

    fun turnOn() {
        prefs.edit().putBoolean("enabled", true).apply()
        AdBlockService.start(ctx)
        scope.launch { delay(800); running = AdBlockService.isRunning; state.syncLinuxDns() }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) turnOn() else state.toast("The ad blocker needs Android's VPN permission")
    }
    fun setOn(on: Boolean) {
        if (on) {
            val ask = VpnService.prepare(ctx)
            if (ask != null) consent.launch(ask) else turnOn()
        } else {
            prefs.edit().putBoolean("enabled", false).apply()
            AdBlockService.stop(ctx)
            scope.launch { delay(500); running = AdBlockService.isRunning; state.syncLinuxDns() }
        }
    }
    fun saveAllow(set: Set<String>) {
        prefs.edit().putStringSet("allow", set).apply()
        allow = set.sorted()
        scope.launch(Dispatchers.IO) { AdBlockService.reload(ctx) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            running = AdBlockService.isRunning
            queries = AdBlockService.queries.get(); blocked = AdBlockService.blocked.get()
            recent = AdBlockService.recentlyBlocked().take(30)
            val l = AdBlockService.current
            listInfo = if (l != null) "${"%,d".format(l.size)} domains · ${l.source}"
                else withContext(Dispatchers.IO) { AdBlockService.loadList(ctx).let { "${"%,d".format(it.size)} domains · ${it.source}" } }
            delay(1500)
        }
    }
    // Android's "Private DNS" with a specific server sends lookups past any DNS-based blocker
    val privateDns = remember { runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_mode") }.getOrNull() }

    Column(Modifier.fillMaxSize().background(c.window).verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (running) c.positive.copy(alpha = 0.15f) else c.header).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (running) Icons.Filled.Shield else Icons.Filled.RemoveModerator, null,
                tint = if (running) c.positive else c.inactiveText, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (running) "Blocking ads" else "Ad blocker is off", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (running) "In all apps, using a local VPN that only handles DNS" else "Turn on to block ads and trackers in every app",
                    color = c.inactiveText, fontSize = 12.sp)
            }
            Switch(running, { setOn(it) })
        }
        if (privateDns == "hostname") Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).background(c.neutral.copy(alpha = 0.15f)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, null, tint = c.neutral)
            Text("Android's Private DNS is set to a specific server, which lets apps skip the ad blocker. Set it to Off or Automatic.",
                Modifier.weight(1f).padding(horizontal = 8.dp), color = c.text, fontSize = 12.sp)
            TextButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Settings") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(Modifier.weight(1f), "%,d".format(blocked), "ads & trackers blocked")
            Stat(Modifier.weight(1f), "%,d".format(queries), "lookups checked")
            Stat(Modifier.weight(1f), if (queries > 0) "${blocked * 100 / queries}%" else "–", "blocked")
        }

        Section2("Recently blocked") {
            if (recent.isEmpty()) Text(if (running) "Nothing yet – open an app with ads." else "Turn the blocker on to see what it blocks.",
                color = c.inactiveText, fontSize = 13.sp)
            recent.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(d, Modifier.weight(1f), color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton({ saveAllow(allow.toSet() + d); state.toast("Allowed $d") }) { Text("Allow") }
                }
            }
        }

        Section2("Blocklist") {
            Text(listInfo, color = c.text, fontSize = 13.sp)
            Text("StevenBlack's unified hosts list (ads, trackers, malware). Some apps serve ads from the same servers as " +
                "their content (e.g. YouTube), which no DNS blocker can separate.", color = c.inactiveText, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({
                    updating = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { AdBlockService.update(ctx) } }
                        updating = false
                        r.onSuccess { state.toast("Blocklist updated: ${"%,d".format(it)} domains") }
                            .onFailure { state.toast("Update failed: ${it.message}") }
                    }
                }, enabled = !updating) { Text(if (updating) "Updating…" else "Update list") }
            }
        }

        Section2("Allowed sites (never blocked)") {
            if (allow.isEmpty()) Text("If an app or site breaks, allow its domain here.", color = c.inactiveText, fontSize = 13.sp)
            allow.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(d, Modifier.weight(1f), color = c.text, fontSize = 13.sp)
                    IconButton({ saveAllow(allow.toSet() - d) }) { Icon(Icons.Filled.Close, "Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newAllow, { newAllow = it.trim().lowercase() }, Modifier.weight(1f),
                    placeholder = { Text("example.com") }, singleLine = true)
                TextButton({ if (newAllow.contains('.')) { saveAllow(allow.toSet() + newAllow); newAllow = "" } }) { Text("Allow") }
            }
        }

        Section2("DNS server for everything else") {
            upstreams.forEach { (ip, label) ->
                Row(Modifier.fillMaxWidth().clickable {
                    upstream = ip; prefs.edit().putString("upstream", ip).apply()
                    if (running) { AdBlockService.stop(ctx); scope.launch { delay(600); AdBlockService.start(ctx) } }
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(upstream == ip, null)
                    Column { Text(label, color = c.text, fontSize = 14.sp); Text(ip, color = c.inactiveText, fontSize = 11.sp) }
                }
            }
            Text("Only another VPN app can't run at the same time. Tip: in Android's VPN settings you can make the ad blocker \"Always-on\".",
                color = c.inactiveText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Stat(modifier: Modifier, value: String, label: String) {
    val c = LocalBreeze.current
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(c.view).border(1.dp, c.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(value, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = c.inactiveText, fontSize = 11.sp)
    }
}

@Composable
private fun Section2(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBreeze.current
    Spacer(Modifier.height(14.dp))
    Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Spacer(Modifier.height(4.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.view).padding(10.dp), content = content)
}
