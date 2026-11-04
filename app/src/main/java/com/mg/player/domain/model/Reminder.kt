package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

/** Erinnerung an eine kommende Sendung oder ein Sport-Event: kurz vor [startMs] meldet sich die App. */
@Immutable
data class Reminder(val channelId: String, val channelName: String, val title: String, val startMs: Long) {
    /** Je Sender und Startzeit höchstens eine Erinnerung. */
    val id: String get() = "$channelId@$startMs"
}
