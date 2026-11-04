package com.mg.player.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mg.player.core.net.LocalNetwork

/**
 * Fragt (nur ab Android 17) einmal je Bildschirm nach „Geräte in der Nähe“, sobald [needed] gilt.
 * Abgelehnt: kurzer Hinweis [deniedHint]; die App läuft weiter, nur das Heimnetz bleibt gesperrt.
 */
@Composable
fun LocalNetworkPermissionEffect(needed: Boolean, deniedHint: String) {
    val context = LocalContext.current
    val toast = LocalToast.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast.show(deniedHint)
    }
    LaunchedEffect(needed) {
        if (needed && !asked && !LocalNetwork.granted(context)) {
            asked = true
            launcher.launch(LocalNetwork.PERMISSION)
        }
    }
}
