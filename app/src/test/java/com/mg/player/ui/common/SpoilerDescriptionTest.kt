package com.mg.player.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.screenshots.TvHostPlain
import com.mg.player.screenshots.settle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lange Inhaltsbeschreibungen (TMDB liefert oft die ganze Handlung) füllten den Bildschirm und verrieten das Ende.
 * Sie beginnen jetzt eingeklappt (Anfang lesbar, „Mehr anzeigen“).
 */
// Echte Textmessung (Zeilenumbruch): ohne NATIVE misst Robolectric alles in einer Zeile.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SpoilerDescriptionTest {

    @get:Rule
    val compose = createComposeRule()

    private val long = "Anastasia zieht nach Paris, um sich ihren großen Traum zu erfüllen: Tänzerin an der renommierten " +
        "Opéra Garnier zu werden. Dort bringt der ambitionierte künstlerische Leiter die umstrittene Oper „Orpheus“ auf " +
        "die Bühne. Zwischen ihnen entwickelt sich eine Liebe, die das Schicksal herausfordert. Während die " +
        "aufreibenden Proben das Ensemble an seine Grenzen bringen und rätselhafte Unfälle die Produktion überschatten, " +
        "begegnet Anastasia eines Abends einem geheimnisvollen Pianisten. Zwischen ihnen entwickelt sich eine Liebe, " +
        "die das Schicksal herausfordert – und selbst die Grenze zwischen Leben und Tod zu überwinden scheint."

    private fun show(text: String) {
        compose.setContent {
            TvHostPlain {
                Column(Modifier.width(400.dp)) { SpoilerDescription(text) }
            }
        }
        compose.settle(200)
    }

    /** Eingeklappt, aber lesbar: Der Anfang steht da, darunter „Mehr anzeigen“. */
    @Test
    fun longDescriptionStartsCollapsedButReadable() {
        show(long)
        compose.onNodeWithText("Anastasia zieht nach Paris", substring = true).assertExists()
        compose.onNodeWithText("Mehr anzeigen").assertExists()
    }

    @Test
    fun clickExpandsAndClickCollapsesAgain() {
        show(long)
        compose.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle(300)
        compose.onNodeWithText("Weniger anzeigen").assertExists()
        compose.onNodeWithText("Anastasia zieht nach Paris", substring = true).assertExists()

        compose.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle(300)
        compose.onNodeWithText("Mehr anzeigen").assertExists()
    }

    /** Passt der Text in die Vorschau, gibt es nichts aufzuklappen. */
    @Test
    fun shortDescriptionHasNoToggle() {
        show("Ein kurzer Satz über den Film.")
        compose.onNodeWithText("Ein kurzer Satz über den Film.").assertExists()
        assertTrue("kein Schalter nötig", compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().isEmpty())
    }
}
