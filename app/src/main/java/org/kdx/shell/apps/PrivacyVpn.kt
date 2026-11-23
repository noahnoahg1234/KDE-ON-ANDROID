package org.kdx.shell.apps

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.droid.FakeLocationService
import org.kdx.shell.droid.PrivacyVpnService

/** Privacy VPN – hides the phone's IP address, traffic and location from apps, websites and the network. */
@Composable
fun PrivacyVpnApp(state: KdxState) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope { org.kdx.shell.core.StartGuard.coroutines }
    val prefs = remember { PrivacyVpnService.prefs(ctx) }
    var running by remember { mutableStateOf(PrivacyVpnService.isRunning) }
    var status by remember { mutableStateOf(PrivacyVpnService.status) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "tor") ?: "tor") }
    var country by remember { mutableStateOf(prefs.getString("country", "") ?: "") }
    var ipInfo by remember { mutableStateOf<String?>(null) }
    var traffic by remember { mutableStateOf("") }
    var skip by remember { mutableStateOf(PrivacyVpnService.skipApps(ctx)) }
    var picking by remember { mutableStateOf(false) }

    fun turnOn() { PrivacyVpnService.start(ctx); scope.launch { delay(1500); state.syncLinuxDns() } }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) turnOn() else state.toast("The Privacy VPN needs Android's VPN permission")
    }
    fun setOn(on: Boolean) {
        if (on) { val ask = VpnService.prepare(ctx); if (ask != null) consent.launch(ask) else turnOn() }
        else { PrivacyVpnService.stop(ctx); ipInfo = null; scope.launch { delay(800); state.syncLinuxDns() } }
    }
    fun restartIfOn() { if (PrivacyVpnService.isRunning) { PrivacyVpnService.restart(ctx); ipInfo = null } }

    LaunchedEffect(Unit) {
        while (true) {
            running = PrivacyVpnService.isRunning; status = PrivacyVpnService.status
            traffic = PrivacyVpnService.currentStack?.let { s ->
                "↑ ${size(s.bytesUp.get())}  ↓ ${size(s.bytesDown.get())}  · ${s.connectionCount} connections"
            } ?: ""
            delay(1000)
        }
    }

    val protected = running && status == "Protected"
    Column(Modifier.fillMaxSize().background(c.window).verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(when { protected -> c.positive.copy(alpha = 0.15f); running -> c.neutral.copy(alpha = 0.15f); else -> c.header })
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (running) Icons.Filled.VpnLock else Icons.Filled.LockOpen, null,
                tint = if (protected) c.positive else c.inactiveText, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (!running) "Not protected" else status, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(when {
                    !running -> "Apps and websites can see your IP address and where you are"
                    protected -> "Your IP address and location are hidden" + if (mode == "tor") " (through Tor)" else " (through your proxy)"
                    else -> "Nothing leaves the phone outside the VPN while it connects"
                }, color = c.inactiveText, fontSize = 12.sp)
                if (traffic.isNotEmpty()) Text(traffic, color = c.inactiveText, fontSize = 11.sp)
            }
            Switch(running, { setOn(it) })
        }

        if (protected) Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({
                ipInfo = "Checking…"
                scope.launch {
                    ipInfo = withContext(Dispatchers.IO) {
                        runCatching { PrivacyVpnService.checkIp(ctx) }.fold(
                            { (ip, tor) -> "Websites see $ip" + if (tor) " – a Tor exit, not you ✓" else "" },
                            { "Check failed: ${it.message}" })
                    }
                }
            }) { Text("Check my IP") }
            if (mode == "tor") TextButton({
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { PrivacyVpnService.newIdentity(ctx) }.isSuccess }
                    ipInfo = null
                    state.toast(if (ok) "New identity: new connections use a new address" else "Tor isn't ready yet")
                }
            }) { Text("New identity") }
        }
        ipInfo?.let { Text(it, color = if (it.contains("✓")) c.positive else c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }

        Section2("What's protected") {
            listOf(
                "Your IP address – websites and apps see the VPN's address instead",
                "Your approximate location from your IP (city, country, internet provider)",
                "Your traffic – Wi-Fi owners and your mobile carrier only see encrypted VPN traffic",
                "Your DNS lookups – which sites you visit",
                "Ads and trackers – the Ad Blocker keeps working while the VPN is on",
                "Linux apps in DroidSystem – Firefox, apt, curl and GTK apps go through the VPN (restart open ones)",
            ).forEach { Row(Modifier.padding(vertical = 2.dp)) { Text("✓ ", color = c.positive, fontSize = 13.sp); Text(it, color = c.text, fontSize = 13.sp) } }
            Text("GPS isn't part of internet traffic: use Fake location below for that. Sites you sign in to still know it's you.",
                color = c.inactiveText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Section2("Connect through") {
            listOf("tor" to ("Tor (free, no account)" to "Run by volunteers worldwide; hides you well, slower than a paid VPN. Some sites block Tor."),
                "socks" to ("My own proxy server (SOCKS5)" to "Your VPN provider's or your own server; usually faster.")).forEach { (m, t) ->
                Row(Modifier.fillMaxWidth().clickable { if (mode != m) { mode = m; prefs.edit().putString("mode", m).apply(); restartIfOn() } }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == m, null)
                    Column { Text(t.first, color = c.text, fontSize = 14.sp); Text(t.second, color = c.inactiveText, fontSize = 11.sp) }
                }
            }
            if (mode == "socks") {
                var host by remember { mutableStateOf(prefs.getString("socksHost", "") ?: "") }
                var port by remember { mutableStateOf(prefs.getInt("socksPort", 1080).toString()) }
                var user by remember { mutableStateOf(prefs.getString("socksUser", "") ?: "") }
                var pass by remember { mutableStateOf(prefs.getString("socksPass", "") ?: "") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim() }, Modifier.weight(2f), label = { Text("Server") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it }, Modifier.weight(1f), label = { Text("Username (optional)") }, singleLine = true)
                    OutlinedTextField(pass, { pass = it }, Modifier.weight(1f), label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
                TextButton({
                    prefs.edit().putString("socksHost", host).putInt("socksPort", port.toIntOrNull() ?: 1080)
                        .putString("socksUser", user).putString("socksPass", pass).apply()
                    state.toast("Proxy saved"); restartIfOn()
                }) { Text("Save") }
            }
        }

        if (mode == "tor") Section2("Appear to be in") {
            var open by remember { mutableStateOf(false) }
            val cur = PrivacyVpnService.COUNTRIES.firstOrNull { it.code == country } ?: PrivacyVpnService.COUNTRIES[0]
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Public, null, tint = c.text)
                Text(cur.name, Modifier.weight(1f).padding(start = 8.dp), color = c.text, fontSize = 14.sp)
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = c.text)
            }
            if (open) PrivacyVpnService.COUNTRIES.forEach { k ->
                Row(Modifier.fillMaxWidth().clickable {
                    country = k.code; open = false; prefs.edit().putString("country", k.code).apply(); restartIfOn()
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(country == k.code, null); Text(k.name, color = c.text, fontSize = 14.sp)
                }
            }
            Text("Picking a country can make connecting slower.", color = c.inactiveText, fontSize = 11.sp)
        }

        FakeLocationSection(state)
        LocationAppsSection()

        Section2("Apps that skip the VPN") {
            Text("For apps that refuse to work over a VPN (some banking apps, games). They use your real connection.",
                color = c.inactiveText, fontSize = 12.sp)
            val pm = ctx.packageManager
            skip.sorted().forEach { pkg ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val label = remember(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
                    Text(label, Modifier.weight(1f), color = c.text, fontSize = 13.sp)
                    IconButton({ skip = skip - pkg; prefs.edit().putStringSet("skip", skip).apply(); restartIfOn() }) { Icon(Icons.Filled.Close, "Remove") }
                }
            }
            TextButton({ picking = true }) { Icon(Icons.Filled.Add, null); Text("Add app") }
        }
        if (picking) AppPicker(skip, { picking = false }) { pkg ->
            picking = false; skip = skip + pkg; prefs.edit().putStringSet("skip", skip).apply(); restartIfOn()
        }

        Text("Tip: in Android's VPN settings, turn on \"Always-on VPN\" and \"Block connections without VPN\" for KDX " +
            "so nothing is sent before the VPN starts.", color = c.inactiveText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        TextButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open VPN settings") }
    }
}

@Composable
private fun FakeLocationSection(state: KdxState) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val prefs = remember { PrivacyVpnService.prefs(ctx) }
    var on by remember { mutableStateOf(FakeLocationService.isRunning) }
    var allowed by remember { mutableStateOf(FakeLocationService.allowed(ctx)) }
    var city by remember { mutableStateOf(prefs.getString("fakeCity", "us") ?: "us") }
    var lat by remember { mutableStateOf(prefs.getFloat("fakeLat", 40.7128f).toString()) }
    var lon by remember { mutableStateOf(prefs.getFloat("fakeLon", -74.0060f).toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            on = FakeLocationService.isRunning; allowed = FakeLocationService.allowed(ctx)
            FakeLocationService.error?.let { if (!on) { state.toast(it) } }
            delay(1500)
        }
    }
    Section2("Fake location (GPS)") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show apps a different location", color = c.text, fontSize = 14.sp)
                Text(if (on) "Apps see ${FakeLocationService.placeName(ctx)}" else "Maps, weather and other apps get the place you pick instead of your GPS",
                    color = c.inactiveText, fontSize = 11.sp)
            }
            Switch(on, { v ->
                if (v) { if (!allowed) state.toast("First choose KDX as the mock location app (below)") else { FakeLocationService.start(ctx); on = true } }
                else { FakeLocationService.stop(ctx); on = false }
            })
        }
        if (!allowed) Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.neutral.copy(alpha = 0.15f)).padding(10.dp)) {
            Text("Android lets only one chosen app set a fake location:\n1. Settings → About phone → tap \"Build number\" 7 times\n" +
                "2. Developer options → Select mock location app → KDX", color = c.text, fontSize = 12.sp)
            TextButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text("Open Developer options")
            }
        }
        Text("Place", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        val choices = PrivacyVpnService.COUNTRIES.drop(1).map { it.code to "${it.city}, ${it.name}" } + ("custom" to "Custom coordinates")
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Place, null, tint = c.text)
            Text(choices.firstOrNull { it.first == city }?.second ?: city, Modifier.weight(1f).padding(start = 8.dp), color = c.text, fontSize = 14.sp)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = c.text)
        }
        if (open) choices.forEach { (code, name) ->
            Row(Modifier.fillMaxWidth().clickable { city = code; open = false; prefs.edit().putString("fakeCity", code).apply() }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) { RadioButton(city == code, null); Text(name, color = c.text, fontSize = 13.sp) }
        }
        if (city == "custom") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(lat, { lat = it }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true)
            OutlinedTextField(lon, { lon = it }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true)
            TextButton({
                val a = lat.toFloatOrNull(); val b = lon.toFloatOrNull()
                if (a == null || b == null || a !in -90f..90f || b !in -180f..180f) state.toast("Enter a latitude (-90…90) and longitude (-180…180)")
                else { prefs.edit().putFloat("fakeLat", a).putFloat("fakeLon", b).apply(); state.toast("Location set") }
            }) { Text("Set") }
        }
        Text("Tip: pick the same country the VPN appears to be in, so your GPS and IP agree.", color = c.inactiveText, fontSize = 11.sp)
    }
}

/** Apps allowed to read the real location, with a shortcut to take the permission away. */
@Composable
private fun LocationAppsSection() {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter { pi ->
                val ai = pi.applicationInfo ?: return@filter false
                val user = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 || ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                user && pi.packageName != ctx.packageName && listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    .any { pm.checkPermission(it, pi.packageName) == PackageManager.PERMISSION_GRANTED }
            }.map { it.packageName to (it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName) }.sortedBy { it.second.lowercase() }
        }
    }
    Section2("Apps that can see your real location") {
        val list = apps
        when {
            list == null -> Text("Loading…", color = c.inactiveText, fontSize = 13.sp)
            list.isEmpty() -> Text("No apps have location access ✓", color = c.positive, fontSize = 13.sp)
            else -> {
                Text("Turn location off for apps that don't need it.", color = c.inactiveText, fontSize = 12.sp)
                list.forEach { (pkg, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton({
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Permissions") }
                    }
                }
                TextButton({ refresh++ }) { Text("Refresh") }
            }
        }
    }
}

private fun size(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "%d KB".format(b / 1024)
    else -> "$b B"
}
