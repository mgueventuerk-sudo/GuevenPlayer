package com.mg.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.core.vpn.SurfsharkServer
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalParentalGate
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.SecureContent
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Anmelden, Serverliste laden, Server übernehmen. */
class SurfsharkViewModel(private val container: AppContainer) : ViewModel() {
    private val surfshark = container.surfshark
    val account: StateFlow<String?> = surfshark.account

    private val _servers = MutableStateFlow<List<SurfsharkServer>?>(null)
    val servers: StateFlow<List<SurfsharkServer>?> = _servers.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Beim Öffnen (und nach dem Übertragen einer Datei): Schlüssel suchen, dann die Serverliste laden. */
    fun refresh() = launchBusy {
        withContext(Dispatchers.Default) { surfshark.refresh() }
        if (account.value != null && _servers.value == null) fetchServers()
    }

    fun login(email: String, password: String) = launchBusy {
        surfshark.login(email, password).onSuccess { fetchServers() }.onFailure {
            _error.value =
                it.message
        }
    }

    fun loadServers() = launchBusy { fetchServers() }

    private suspend fun fetchServers() {
        surfshark.servers().onSuccess { _servers.value = sortForUser(it) }.onFailure { _error.value = it.message }
    }

    /** Übernimmt den Server und verbindet; liefert die Meldung für den Nutzer. */
    fun use(server: SurfsharkServer, onDone: (String) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.Default) { surfshark.use(server) }
            _busy.value = false
            result.onSuccess { onDone("VPN: ${it.name} wird verbunden") }.onFailure { _error.value = it.message }
        }
    }

    fun logout() {
        surfshark.logout()
        _servers.value = null
        _error.value = null
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            block()
            _busy.value = false
        }
    }

    private companion object {
        /** Länder in der Nähe zuerst (kurze Wege = schneller Senderwechsel), dann alphabetisch. */
        val NEAR = listOf("DE", "NL", "AT", "CH", "TR", "BE", "FR", "DK", "PL", "CZ")

        fun sortForUser(list: List<SurfsharkServer>): List<SurfsharkServer> = list.sortedWith(
            compareBy<SurfsharkServer>({ NEAR.indexOf(it.countryCode).let { i -> if (i < 0) NEAR.size else i } }, { it.country }, { it.location }),
        )
    }
}

/** Surfshark: einmal anmelden, dann Server per Klick übernehmen. */
@Composable
fun SurfsharkScreen() {
    SecureContent()
    val vm = appViewModel { SurfsharkViewModel(it) }
    val account by vm.account.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val touch = isTouch
    Column(Modifier.fillMaxSize().padding(horizontal = if (touch) 16.dp else 64.dp, vertical = if (touch) 12.dp else 40.dp)) {
        ScreenTitle("Surfshark")
        Text(
            "Nur GuevenPlayer läuft durchs VPN. Der Schlüssel liegt nur verschlüsselt auf dem Gerät, ein Passwort wird nie gespeichert.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        error?.let { Text(it, color = AppColors.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp)) }
        LaunchedEffect(Unit) { vm.refresh() }
        val signedIn = account
        if (signedIn == null) {
            Onboarding(busy, vm::login)
        } else {
            ServerList(signedIn, servers, busy, vm)
        }
    }
}

/** Ohne Schlüssel: einmal eine Surfshark-Datei übertragen (zuverlässig) – oder versuchen, sich anzumelden. */
@Composable
private fun Onboarding(busy: Boolean, onLogin: (String, String) -> Unit) {
    val navigator = LocalNavigator.current
    val gate = LocalParentalGate.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Einmalig einrichten", style = MaterialTheme.typography.titleMedium)
        listOf(
            "1. Im Surfshark-Konto (Handy/PC): VPN → Manuelle Einrichtung → Router → WireGuard → „Ich habe kein Schlüsselpaar“ → Schlüssel erzeugen.",
            "2. Einen beliebigen Standort wählen und die .conf-Datei herunterladen.",
            "3. Hier „Surfshark-Datei übertragen“ → QR-Code scannen → Datei senden.",
            "Danach steht hier die Liste aller Surfshark-Server – Wechsel per Klick, ohne weitere Dateien.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary) }
        ActionButton(
            "Surfshark-Datei übertragen",
            onClick = { gate.runWithPin { navigator.push(Screen.Share(ShareMode.VPN_IMPORT)) } },
            primary = true,
            icon = AppIcons.Upload,
        )
        Text(
            "Oder mit E-Mail und Passwort anmelden – Surfshark blockiert das für Apps allerdings oft (Cloudflare-Schutz):",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
        LoginForm(busy, onLogin)
    }
}

@Composable
private fun LoginForm(busy: Boolean, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val submit = { onLogin(email, password) }
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvTextField(email, { email = it }, "E-Mail (Surfshark-Konto)", keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        TvTextField(password, { password = it }, "Passwort", password = true, keyboardType = KeyboardType.Password, onSubmit = submit)
        ActionButton(if (busy) "Anmelden …" else "Anmelden", onClick = submit, icon = AppIcons.Shield)
    }
}

@Composable
private fun ServerList(account: String, servers: List<SurfsharkServer>?, busy: Boolean, vm: SurfsharkViewModel) {
    val navigator = LocalNavigator.current
    val toast = LocalToast.current
    val pick: (SurfsharkServer) -> Unit = { server ->
        vm.use(server) { message ->
            toast.show(message)
            navigator.pop()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Schlüssel: $account", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
        ActionButton("Aktualisieren", onClick = vm::loadServers, icon = AppIcons.Refresh, compact = true)
        ActionButton("Schlüssel entfernen", onClick = vm::logout, icon = AppIcons.Close, compact = true)
    }
    when {
        servers == null -> Text(if (busy) "Serverliste wird geladen …" else "Keine Serverliste", modifier = Modifier.padding(top = 16.dp))

        else -> LazyColumn(Modifier.fillMaxWidth().padding(top = 12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            // Schnellwahl: der am wenigsten ausgelastete Server in Deutschland.
            servers.filter { it.countryCode == "DE" }.minByOrNull { it.load ?: 100 }?.let { best ->
                item(key = "best") {
                    ActionRow("Schnellwahl: ${best.label}", { pick(best) }, "Am wenigsten ausgelasteter Server in Deutschland", icon = AppIcons.Speed)
                }
            }
            items(servers, key = { it.host }, contentType = { "server" }) { server ->
                ServerRow(server, onClick = { pick(server) })
            }
        }
    }
}

@Composable
private fun ServerRow(server: SurfsharkServer, onClick: () -> Unit) {
    ListSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), containerColor = AppColors.Glass) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(server.countryCode ?: "–", fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 36.dp))
            Text(server.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
            server.load?.let { Text("Auslastung $it %", style = MaterialTheme.typography.labelMedium, color = AppColors.TextMuted) }
        }
    }
}
