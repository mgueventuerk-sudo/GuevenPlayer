package com.mg.player.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Welche Streams auf anderen Zugängen vorgeladen werden. Jeder Zugang erlaubt nur eine Verbindung: Auf dem Zugang des
 * laufenden Senders darf nie vorgeladen werden, je anderem Zugang höchstens ein Stream, und nur mit nachweislich freiem
 * Platz. In „Alle Zugänge“ hat ein Sender mehrere Varianten auf mehreren Zugängen – gewählt wird die auf dem anderen Zugang.
 */
class HabitPreloaderTest {

    /** Sender -> Varianten (Zugang, Original-ID), beste zuerst. */
    private val variants = mapOf(
        "A1" to listOf(1L to "a1"),
        "A2" to listOf(1L to "a2"),
        "B1" to listOf(2L to "b1"),
        "B2" to listOf(2L to "b2"),
        "C1" to listOf(3L to "c1"),
        // „Alle Zugänge“: derselbe Sender auf Zugang 1 (beste Quelle) und Zugang 2 bzw. 3.
        "X1" to listOf(1L to "x1-a", 2L to "x1-b"),
        "X2" to listOf(1L to "x2-a", 3L to "x2-c"),
        "X3" to listOf(2L to "x3-b", 3L to "x3-c"),
    )
    private val freeSlots = mutableSetOf<Long>()
    private val asked = mutableListOf<Long>()

    private fun pick(candidates: List<String>, active: Long = 1L, max: Int = 1) = runBlocking {
        PreloadTargets.pick(
            candidateIds = candidates,
            activePlaylistId = active,
            maxTargets = max,
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
        assertEquals(listOf(PreloadTarget("B1", "b1", 2L)), pick(listOf("A2", "B1", "C1")))
    }

    @Test
    fun neverPicksAChannelOnTheSameAccountAsTheRunningOne() {
        freeSlots += 1L // Der laufende Zugang hätte sogar „freien Platz“ – zählt trotzdem nicht.
        assertTrue(pick(listOf("A1", "A2"), max = 3).isEmpty())
    }

    @Test
    fun skipsAccountsWithoutAConfirmedFreeSlot() {
        freeSlots += 3L
        assertEquals(listOf(PreloadTarget("C1", "c1", 3L)), pick(listOf("B1", "C1")))
    }

    @Test
    fun returnsNothingWhenNothingQualifies() {
        assertTrue(pick(listOf("A2", "B1")).isEmpty())
        assertTrue(pick(emptyList()).isEmpty())
    }

    @Test
    fun inCombinedViewItPicksTheVariantOnTheOtherAccount() {
        freeSlots += 2L
        // X1 läuft in der besten Quelle auf Zugang 1 – vorgeladen wird die Variante auf Zugang 2.
        assertEquals(listOf(PreloadTarget("X1", "x1-b", 2L)), pick(listOf("X1")))
    }

    @Test
    fun inCombinedViewItFallsBackToAnotherChannelWhenTheOtherAccountIsFull() {
        freeSlots += 3L
        assertEquals(listOf(PreloadTarget("X2", "x2-c", 3L)), pick(listOf("X1", "X2")))
    }

    @Test
    fun oneStreamPerAccountButSeveralAccountsAtOnce() {
        freeSlots += 2L
        freeSlots += 3L
        // Zwei Plätze: B1 belegt Zugang 2, B2 wäre ein zweiter Stream auf Zugang 2 (nicht erlaubt) – C1 bekommt Zugang 3.
        val targets = pick(listOf("B1", "B2", "C1"), max = 2)
        assertEquals(listOf(PreloadTarget("B1", "b1", 2L), PreloadTarget("C1", "c1", 3L)), targets)
    }

    @Test
    fun theRankOfTheCandidatesDecidesWhichChannelGetsTheAccount() {
        freeSlots += 2L
        freeSlots += 3L
        // X3 steht vorn: Es bekommt Zugang 2 (beste freie Variante); X2 bekommt dann Zugang 3. Derselbe Sender nie zweimal.
        val targets = pick(listOf("X3", "X2", "X1"), max = 3)
        assertEquals(listOf(PreloadTarget("X3", "x3-b", 2L), PreloadTarget("X2", "x2-c", 3L)), targets)
    }

    @Test
    fun neverMoreStreamsThanTheDeviceAllows() {
        freeSlots += 2L
        freeSlots += 3L
        assertEquals(1, pick(listOf("B1", "C1"), max = 1).size)
        assertTrue(pick(listOf("B1", "C1"), max = 0).isEmpty())
    }

    @Test
    fun accountsWithoutARealIdAreNeverATarget() {
        val odd = mapOf("Z" to listOf(-2L to "z", 0L to "z0"))
        freeSlots += -2L
        freeSlots += 0L
        val targets = runBlocking {
            PreloadTargets.pick(listOf("Z"), 1L, 3, { odd[it].orEmpty() }, { it in freeSlots })
        }
        assertTrue(targets.isEmpty())
    }

    @Test
    fun asksEachAccountOnlyOnce() {
        pick(listOf("B1", "B1", "C1"), max = 3)
        assertEquals(listOf(2L, 3L), asked)
    }

    @Test
    fun channelWithAllVariantsOnTheActiveAccountIsNeverPicked() {
        val same = mapOf("X" to listOf(1L to "x-a", 1L to "x-b"))
        val picked = runBlocking { PreloadTargets.pick(listOf("X"), 1L, 2, { same[it].orEmpty() }, { true }) }
        assertTrue(picked.isEmpty())
    }

    @Test
    fun secondChannelFallsBackToItsNextVariantWhenTheAccountIsTaken() {
        val two = mapOf("A" to listOf(2L to "a2"), "B" to listOf(2L to "b2", 3L to "b3"))
        val picked = runBlocking { PreloadTargets.pick(listOf("A", "B"), 1L, 2, { two[it].orEmpty() }, { true }) }
        assertEquals(listOf(PreloadTarget("A", "a2", 2L), PreloadTarget("B", "b3", 3L)), picked)
    }
}
