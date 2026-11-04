package com.mg.player.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

/** Die Activity hinter einem (evtl. eingewickelten) Compose-Context. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Text übers Teilen-Menü des Handys weitergeben (WhatsApp, Mail, …). false, wenn keine App dafür da ist. */
fun Context.shareText(subject: String, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, subject).apply { if (findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    return runCatching { startActivity(chooser) }.isSuccess
}
