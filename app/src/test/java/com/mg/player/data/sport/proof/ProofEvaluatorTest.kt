package com.mg.player.data.sport.proof

import com.mg.player.data.sport.proof.ProofEvaluator.Game
import com.mg.player.data.sport.proof.ProofEvaluator.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bildbeweis (Plan „Spiel-Finder“ C, Tests 1–3, 10, 11). */
class ProofEvaluatorTest {

    private val alkmaarJuve = Game("AZ Alkmaar", "Juventus", 0 to 1, 23)

    @Test
    fun scoreBugWithBothTeamsAndMatchingMinuteIsProof() {
        assertEquals(Verdict.PROVEN, ProofEvaluator.evaluate(listOf("AZ 0 : 1 JUV", "24:12"), alkmaarJuve, staticPicture = false))
    }

    @Test
    fun oneTeamPlusScoreIsProof() {
        assertEquals(Verdict.PROVEN, ProofEvaluator.evaluate(listOf("JUV 1-0", "23'"), alkmaarJuve, staticPicture = false))
    }

    @Test
    fun abbreviationsFromNames() {
        assertTrue("juv" in ProofEvaluator.candidates("Juventus"))
        assertTrue("az" in ProofEvaluator.candidates("AZ Alkmaar"))
        assertTrue("gal" in ProofEvaluator.candidates("Galatasaray"))
        assertTrue("bay" in ProofEvaluator.candidates("FC Bayern München"))
    }

    @Test
    fun ufcFighterNames() {
        val fight = Game("Islam Makhachev", "Arman Tsarukyan", null, null)
        assertEquals(Verdict.PROVEN, ProofEvaluator.evaluate(listOf("MAKHACHEV", "TSARUKYAN", "ROUND 2"), fight, staticPicture = false))
    }

    /** 05.11.: „PRIME DE 016“ zeigte „Keine Event“ mit Musik. */
    @Test
    fun placeholderIsNeverProof() {
        assertEquals(Verdict.PLACEHOLDER, ProofEvaluator.evaluate(listOf("Keine Event"), alkmaarJuve, staticPicture = false))
        assertEquals(Verdict.PLACEHOLDER, ProofEvaluator.evaluate(listOf("AZ 0:1 JUV"), alkmaarJuve, staticPicture = true))
        assertEquals(Verdict.PLACEHOLDER, ProofEvaluator.evaluate(listOf("NO EVENT"), alkmaarJuve, staticPicture = false))
    }

    @Test
    fun replayWithRightScoreButWrongMinuteIsNoProof() {
        assertEquals(Verdict.OTHER, ProofEvaluator.evaluate(listOf("AZ 0:1 JUV", "71:05"), alkmaarJuve, staticPicture = false))
    }

    @Test
    fun otherMatchIsOther() {
        assertEquals(Verdict.OTHER, ProofEvaluator.evaluate(listOf("LEV 2:0 OM", "23:40"), alkmaarJuve, staticPicture = false))
    }

    @Test
    fun nothingReadableIsUnknown() {
        assertEquals(Verdict.UNKNOWN, ProofEvaluator.evaluate(emptyList(), alkmaarJuve, staticPicture = false))
        assertEquals(Verdict.UNKNOWN, ProofEvaluator.evaluate(listOf("Champions League"), alkmaarJuve, staticPicture = false))
    }

    @Test
    fun staticDetection() {
        val a = IntArray(32 * 18) { 100 }
        val b = IntArray(32 * 18) { 101 }
        val moving = IntArray(32 * 18) { if (it % 2 == 0) 20 else 220 }
        assertTrue(FrameDiff.isStatic(listOf(a, b, a)))
        assertFalse(FrameDiff.isStatic(listOf(a, moving)))
        assertFalse(FrameDiff.isStatic(listOf(a)))
    }

    private val family: (String) -> String? = { name ->
        val n = name.lowercase()
        when {
            "dazn" in n -> "dazn"
            "sky" in n -> "sky"
            else -> null
        }
    }

    @Test
    fun foreignLogoInSeveralFramesDisprovesChannel() {
        assertTrue(ProofEvaluator.foreignLogo(listOf("DAZN", "12:30", "DAZN"), "DE: Sky Sport Bundesliga 1 HD", family))
    }

    @Test
    fun ownLogoOrSingleSightingIsNoProofOfWrongChannel() {
        assertFalse(ProofEvaluator.foreignLogo(listOf("DAZN", "sky sport", "DAZN"), "DE: Sky Sport Bundesliga 1", family))
        assertFalse(ProofEvaluator.foreignLogo(listOf("DAZN"), "DE: Sky Sport Bundesliga 1", family))
        assertFalse(ProofEvaluator.foreignLogo(listOf("Jetzt auch bei DAZN streamen", "Mehr bei DAZN online sehen"), "Sky Sport 1", family))
        assertFalse(ProofEvaluator.foreignLogo(listOf("DAZN", "DAZN"), "beIN SPORTS 1", family))
    }
}
