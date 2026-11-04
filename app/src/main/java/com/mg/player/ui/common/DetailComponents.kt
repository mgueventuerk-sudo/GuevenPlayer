package com.mg.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/** Großes Hintergrundbild mit Verlauf, damit Text darüber gut lesbar bleibt. */
@Composable
fun DetailBackdrop(imageUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(imageUrl) { ImageRequest.Builder(context).data(imageUrl).size(1280, 720).build() }
    Box(modifier.fillMaxSize()) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to AppColors.Background,
                        0.45f to AppColors.Background.copy(alpha = 0.85f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to AppColors.Background.copy(alpha = 0.5f),
                        0.3f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to AppColors.Background,
                    ),
                ),
        )
    }
}

@Composable
fun MetaLine(parts: List<String>, modifier: Modifier = Modifier, badges: List<String> = emptyList()) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = parts.filter { it.isNotBlank() }.joinToString("  ·  "),
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextSecondary,
        )
        // Qualitätsmerkmale aus dem Titel ("4K", "HEVC" …) direkt neben Jahr und Laufzeit.
        badges.take(3).forEach { QualityBadge(it, Modifier.padding(start = 10.dp)) }
    }
}

/** Auswahl der Player-Engine für genau diese Wiedergabe. */
@Composable
fun EnginePicker(selected: PlayerEngineType?, onPick: (PlayerEngineType) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "Player-Engine für diese Wiedergabe",
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayerEngineType.entries.forEach { type ->
                ChoiceChip(type.label, selected = type == selected, onClick = { onPick(type) }, icon = AppIcons.Engine)
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Box(
        modifier
            .glass(RoundedCornerShape(50), fill = color)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
    }
}
