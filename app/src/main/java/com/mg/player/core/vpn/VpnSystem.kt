package com.mg.player.core.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.mg.player.R
import com.mg.player.core.PlayerApp
import com.mg.player.core.log.AppLog
import com.mg.player.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Benachrichtigung „VPN getrennt“ mit „Neu verbinden“, optional Ton/Vibration, Einträge im Fehlerprotokoll. */
class AndroidVpnAlerts(private val context: Context, private val store: VpnStore) : VpnAlerts {

    override fun dropped(profile: String, reason: VpnDropReason) {
        AppLog.log("VPN", "Getrennt ($profile): ${reason.text}")
        notifyDropped(reason)
        if (store.alertSound.value) beep()
    }

    override fun restored(profile: String) {
        AppLog.log("VPN", "Wieder verbunden ($profile)")
        cancel()
    }

    override fun cleared() = cancel()

    override fun detail(text: String) = AppLog.log("VPN", text)

    private fun notifyDropped(reason: VpnDropReason) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            // Leise: Ein Ton kommt nur, wenn er in den Einstellungen eingeschaltet ist (eigener Ton, s. beep()).
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VPN-Status", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reconnect = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, VpnActionReceiver::class.java).setAction(VpnActionReceiver.ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("VPN getrennt – Verbindung ungeschützt")
            .setContentText(reason.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${reason.text}. GuevenPlayer verbindet von selbst neu."))
            .setContentIntent(open)
            .addAction(0, "Neu verbinden", reconnect)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancel() {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    @Suppress("DEPRECATION")
    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            android.os.Handler(context.mainLooper).postDelayed({ tone.release() }, 800)
        }
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return@runCatching
            if (!vibrator.hasVibrator()) return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(250)
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "vpn_status"
        const val NOTIFICATION_ID = 7_401
    }
}

/** Knopf „Neu verbinden“ in der Benachrichtigung. */
class VpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECONNECT) return
        val container = (context.applicationContext as? PlayerApp)?.container ?: return
        container.vpn.restart()
    }

    companion object {
        const val ACTION_RECONNECT = "com.mg.player.vpn.RECONNECT"
    }
}

/**
 * Nach dem Einschalten des Geräts bzw. einem Update: VPN aufbauen. Der Empfänger hält den Prozess wach,
 * bis der Tunnel steht oder klar ist, dass es gerade nicht geht (höchstens 20 s).
 */
class VpnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as? PlayerApp)?.container ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.vpn.start()
                withTimeoutOrNull(BOOT_WAIT_MS) {
                    container.vpn.state.first { it !is VpnState.Connecting && !(it is VpnState.Disconnected && it.retrying) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val BOOT_WAIT_MS = 20_000L
    }
}
