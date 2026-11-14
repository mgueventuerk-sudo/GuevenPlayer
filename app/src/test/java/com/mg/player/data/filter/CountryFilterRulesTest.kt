package com.mg.player.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Der Länderfilter überschreibt nie, was man selbst ein- oder ausgeblendet hat. */
class CountryFilterRulesTest {

    private val de = CountrySelection(setOf("DE"), keepUnknown = true)
    private val deTr = CountrySelection(setOf("DE", "TR"), keepUnknown = true)
    private val all = CountrySelection(setOf("DE", "TR", "FR"), keepUnknown = true)

    private fun hidden(code: String?, existing: Boolean?, sel: CountrySelection, prev: CountrySelection?, onlyNew: Boolean = false) =
        CountryFilterRules.hiddenAfter(code, existing, sel, prev, onlyNew)

    @Test
    fun firstFilterHidesOtherCountries() {
        assertEquals(true, hidden("FR", existing = null, sel = de, prev = null))
        assertEquals(true, hidden("FR", existing = false, sel = de, prev = null))
    }

    @Test
    fun manuallyHiddenGroupOfChosenCountryStaysHidden() {
        assertNull(hidden("DE", existing = true, sel = de, prev = null))
        assertNull(hidden("DE", existing = true, sel = deTr, prev = de))
    }

    @Test
    fun clearingShowsOnlyWhatTheFilterHid() {
        // Vom Filter ausgeblendet: wieder sichtbar.
        assertEquals(false, hidden("FR", existing = true, sel = all, prev = de))
        // Von Hand ausgeblendet (Land war gewählt): bleibt aus.
        assertNull(hidden("DE", existing = true, sel = all, prev = de))
    }

    @Test
    fun addingACountryShowsItsGroups() {
        assertEquals(false, hidden("TR", existing = true, sel = deTr, prev = de))
    }

    @Test
    fun manuallyShownGroupStaysShownWhileDecisionUnchanged() {
        assertNull(hidden("FR", existing = false, sel = deTr, prev = de))
    }


    @Test
    fun newGroupsFollowTheStoredSelection() {
        assertEquals(true, hidden("FR", existing = null, sel = de, prev = de, onlyNew = true))
        assertNull(hidden("DE", existing = null, sel = de, prev = de, onlyNew = true))
        assertNull(hidden("FR", existing = false, sel = de, prev = de, onlyNew = true))
    }

    @Test
    fun unknownGroupsFollowKeepUnknown() {
        val strict = CountrySelection(setOf("DE"), keepUnknown = false)
        assertEquals(true, hidden(null, existing = null, sel = strict, prev = null))
        assertEquals(false, hidden(null, existing = true, sel = de, prev = strict))
    }
}
