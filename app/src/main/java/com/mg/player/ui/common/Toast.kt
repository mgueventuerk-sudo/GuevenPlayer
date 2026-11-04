package com.mg.player.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Kurze Bestätigung unten in der Mitte ("Zu Favoriten hinzugefügt" …), app-weit verfügbar. */
@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    var serial by mutableIntStateOf(0)
        private set

    /** Grün hinterlegt (z. B. „VPN wieder verbunden“). */
    var success by mutableStateOf(false)
        private set

    fun show(text: String, success: Boolean = false) {
        message = text
        this.success = success
        serial++
    }

    fun clear() {
        message = null
    }
}

val LocalToast = staticCompositionLocalOf { ToastState() }

@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.serial) {
        if (state.message != null) {
            delay(2_500)
            state.clear()
        }
    }
    AnimatedContent(
        targetState = state.message,
        modifier = modifier,
        transitionSpec = {
            (fadeIn() + slideInVertically { it / 2 } + scaleIn(initialScale = 0.92f)) togetherWith (fadeOut() + slideOutVertically { it / 2 })
        },
        label = "toast",
    ) { message ->
        if (message != null) {
            Text(
                message,
                modifier = Modifier
                    .glass(RoundedCornerShape(50), fill = if (state.success) AppColors.Success.copy(alpha = 0.92f) else AppColors.Panel)
                    .padding(horizontal = 26.dp, vertical = 13.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (state.success) Color.White else AppColors.TextPrimary,
            )
        }
    }
}
