package com.mg.player.data.filter

/**
 * Was der Länderfilter mit einer Gruppe tut (rein, ohne Datenbank). Er ändert eine Gruppe nur, wenn sich **sein**
 * Urteil gegenüber der vorigen Auswahl ändert. Bis 3.60 setzte jedes Anwenden alle Gruppen auf das Urteil des Filters:
 * Eine von Hand ausgeblendete Gruppe eines gewählten Landes kam wieder, und „Filter aufheben“ blendete alle von Hand
 * ausgeblendeten Gruppen wieder ein.
 */
internal object CountryFilterRules {

    fun visible(code: String?, selection: CountrySelection?): Boolean = when {
        selection == null -> true
        code == null -> selection.keepUnknown
        else -> code in selection.countries
    }

    /**
     * Neuer Zustand „ausgeblendet“ oder null = nichts ändern. [existingHidden] null = noch keine Einstellung für die Gruppe,
     * [previous] null = bisher kein Filter (alles sichtbar).
     */
    fun hiddenAfter(
        code: String?,
        existingHidden: Boolean?,
        selection: CountrySelection,
        previous: CountrySelection?,
        onlyNew: Boolean,
    ): Boolean? {
        val visible = visible(code, selection)
        if (existingHidden == null) return if (visible) null else true
        if (onlyNew) return null
        // Urteil unverändert: Die Gruppe steht so, wie man sie zuletzt selbst gestellt hat – nicht anfassen.
        if (visible(code, previous) == visible) return null
        return if (existingHidden == !visible) null else !visible
    }
}
