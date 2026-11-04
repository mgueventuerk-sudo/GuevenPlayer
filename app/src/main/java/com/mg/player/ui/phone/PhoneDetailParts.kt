package com.mg.player.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/** Großes Titelbild oben auf der Detailseite, unten weich in den Hintergrund übergehend, mit Zurück-Knopf. */
@Composable
fun PhoneDetailHeader(imageUrl: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(imageUrl) { ImageRequest.Builder(context).data(imageUrl).size(1280, 720).build() }
    val background = AppColors.Background
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to background)),
        )
        PhoneIconButton(AppIcons.Back, "Zurück", onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
    }
}

/** Knopfleiste der Detailseite: waagerecht wischbar, falls sie nicht auf die Breite passt. */
@Composable
fun PhoneActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
