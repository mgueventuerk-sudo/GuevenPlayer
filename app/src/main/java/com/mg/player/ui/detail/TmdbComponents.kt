package com.mg.player.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mg.player.domain.model.CastMember
import com.mg.player.domain.model.SearchHit
import com.mg.player.domain.model.TmdbInfo
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.Pill
import com.mg.player.ui.common.PosterCard
import com.mg.player.ui.common.PosterItem
import com.mg.player.ui.common.SectionLabel
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.Dimens
import java.text.NumberFormat
import java.util.Locale

/** TMDB-Bewertung mit Stimmenzahl, FSK und Genres. */
@Composable
fun TmdbMeta(info: TmdbInfo, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val rating = info.rating
        if (rating != null) {
            val votes = NumberFormat.getIntegerInstance(Locale.GERMANY).format(info.voteCount)
            Pill("TMDB ★ ${"%.1f".format(Locale.GERMANY, rating)}  ·  $votes Stimmen", color = AppColors.AccentSoft)
        }
        info.certification?.let { Pill("FSK $it") }
        if (info.genres.isNotEmpty()) {
            MarqueeText(
                info.genres.take(4).joinToString(", "),
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Besetzung als fokussierbare Reihe (so lässt sie sich mit der Fernbedienung durchblättern). */
@Composable
fun CastRow(cast: List<CastMember>, modifier: Modifier = Modifier) {
    if (cast.isEmpty()) return
    val context = LocalContext.current
    Column(modifier) {
        SectionLabel("Besetzung", Modifier.padding(bottom = 8.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(FocusRequester.Default),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 32.dp, top = 4.dp, bottom = 4.dp),
        ) {
            items(cast, key = { it.name + it.character }, contentType = { "cast" }) { member ->
                val request = remember(member.photoUrl) { ImageRequest.Builder(context).data(member.photoUrl).size(185, 278).build() }
                FocusSurface(onClick = {}, shape = AppShapes.Card, glow = false) {
                    Column(Modifier.width(110.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(AppColors.GlassRaised), contentAlignment = Alignment.Center) {
                            if (member.photoUrl != null) {
                                AsyncImage(request, member.name, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                            } else {
                                Text(member.name.take(1), style = MaterialTheme.typography.titleLarge, color = AppColors.TextMuted)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(member.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center)
                        member.character?.let {
                            MarqueeText(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Ähnliche Titel, die es auch in der eigenen Playlist gibt. */
@Composable
fun SimilarRow(hits: List<SearchHit>, onOpen: (SearchHit) -> Unit, modifier: Modifier = Modifier) {
    if (hits.isEmpty()) return
    Column(modifier) {
        SectionLabel("Ähnliche Titel in deiner Playlist", Modifier.padding(bottom = 8.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(FocusRequester.Default),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 32.dp, top = 6.dp, bottom = 10.dp),
        ) {
            items(hits, key = { it.id }, contentType = { "similar" }) { hit ->
                PosterCard(
                    item = PosterItem(hit.id, hit.title, hit.imageUrl, null, null),
                    onClick = { onOpen(hit) },
                    modifier = Modifier.width(130.dp),
                )
            }
        }
    }
}

@Composable
fun TmdbAttribution(modifier: Modifier = Modifier) {
    Text(
        "Zusatzdaten von TMDB",
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = AppColors.TextMuted,
        fontWeight = FontWeight.Normal,
    )
}

/** Öffnet den Trailer in der YouTube-App (oder im Browser). */
fun openTrailer(context: Context, youtubeKey: String): Boolean {
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, "vnd.youtube:$youtubeKey".toUri()),
        Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$youtubeKey".toUri()),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
    return false
}
