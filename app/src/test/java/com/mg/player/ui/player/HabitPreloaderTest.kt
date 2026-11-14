package com.mg.player.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Welcher Stream auf einem anderen Zugang vorgeladen wird. Jeder Zugang erlaubt nur eine Verbindung: Auf dem Zugang des
 * laufenden Senders darf nie vorgeladen werden, auf einem anderen nur mit nachweislich freiem Platz. In „Alle Zugänge“
 * hat ein Sender mehrere Varianten auf mehreren Zugängen – gewählt wird die Variante auf dem anderen Zugang.
 */
class HabitPreloaderTest {

    /** Sender -> Varianten (Zugang, Original-ID), beste zuerst. */
    private val variants = mapOf(
        "A1" to listOf(1L to "a1"),
        "A2" to listOf(1L to "a2"),
        "B1" to listOf(2L to "b1"),
        "C1" to listOf(3L to "c1"),
        // „Alle Zugänge“: derselbe Sender auf Zugang 1 (beste Quelle) und Zugang 2.
        "X1" to listOf(1L to "x1-a", 2L to "x1-b"),
        "X2" to listOf(1L to "x2-a", 3L to "x2-c"),
    )
    private val freeSlots = mutableSetOf<Long>()
    private val asked = mutableListOf<Long>()

    private fun pick(candidates: List<String>, active: Long = 1L) = runBlocking {
        PreloadTargets.pick(
            candidateIds = candidates,
            activePlaylistId = active,
            variantsOf = { variants[it].orEmpty() },
            hasFreeSlot = {
                asked += it
                it in freeSlots
            },
        )
    }

    @Test
    fun picksTheFirstChannelOnAFreeDifferentAccount() {
        freeSlots += 2L
        assertEquals(PreloadTarget("B1", "b1", 2L), pick(listOf("A2", "B1", "C1")))
    }

    @Test
    fun neverPicksAChannelOnTheSameAccountAsTheRunningOne() {
        freeSlots += 1L // Der laufende Zugang hätte sogar „freien Platz“ – zählt trotzdem nicht.
        assertNull(pick(listOf("A1", "A2")))
    }

    @Test
    fun skipsAccountsWithoutAConfirmedFreeSlot() {
        freeSlots += 3L
        assertEquals(PreloadTarget("C1", "c1", 3L), pick(listOf("B1", "C1")))
    }

    @Test
    fun returnsNullWhenNothingQualifies() {
        assertNull(pick(listOf("A2", "B1")))
        assertNull(pick(emptyList()))
    }

    @Test
    fun inCombinedViewItPicksTheVariantOnTheOtherAccount() {
        freeSlots += 2L
        // X1 läuft in der besten Quelle auf Zugang 1 – vorgeladen wird die Variante auf Zugang 2.
        assertEquals(PreloadTarget("X1", "x1-b", 2L), pick(listOf("X1")))
    }

    @Test
    fun inCombinedViewItFallsBackToAnotherChannelWhenTheOtherAccountIsFull() {
        freeSlots += 3L
        assertEquals(PreloadTarget("X2", "x2-c", 3L), pick(listOf("X1", "X2")))
    }

    @Test
    fun accountsWithoutARealIdAreNeverATarget() {
        val odd = mapOf("Z" to listOf(-2L to "z", 0L to "z0"))
        freeSlots += -2L
        freeSlots += 0L
        val target = runBlocking {
            PreloadTargets.pick(listOf("Z"), 1L, { odd[it].orEmpty() }, { it in freeSlots })
        }
        assertNull(target)
    }

    @Test
    fun asksEachAccountOnlyOnce() {
        pick(listOf("B1", "B1", "C1"))
        assertEquals(listOf(2L, 3L), asked)
    }
}
