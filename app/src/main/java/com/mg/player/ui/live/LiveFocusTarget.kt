package com.mg.player.ui.live

/** Nach der Rückkehr aus dem Player den zuletzt gesehenen Kanal wiederherstellen. */
internal sealed interface LiveFocusTarget {
    data class Restore(val channelId: String) : LiveFocusTarget
}

internal fun liveFocusTarget(restoreTarget: String?): LiveFocusTarget? = restoreTarget?.let(LiveFocusTarget::Restore)
