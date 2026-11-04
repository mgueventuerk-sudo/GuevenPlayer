package com.mg.player.ui.live

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.screenshots.TvHostPlain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ChannelRowQualityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun titleBasedQualityHintIsNotPresentedAsMeasuredResolution() {
        val channel = Channel(
            id = "trt",
            name = "TRT TURK",
            logoUrl = null,
            streamUrl = "https://example.invalid/trt.m3u8",
            categoryId = Category.ALL,
            epgKey = "trt",
            nowTitle = null,
            nowStartMs = null,
            nowEndMs = null,
            quality = "4K",
        )
        compose.setContent {
            TvHostPlain {
                ChannelRow(channel, number = 1, nowMs = 0, onClick = {})
            }
        }

        compose.onNode(hasText("TRT TURK")).assertExists()
        compose.onNode(hasText("4K", substring = false)).assertDoesNotExist()
    }

    @Test
    fun rowShowsDecoderDimensionsInsteadOfTheTitleHint() {
        val channel = Channel(
            id = "trt",
            name = "TRT TURK",
            logoUrl = null,
            streamUrl = "https://example.invalid/trt.m3u8",
            categoryId = Category.ALL,
            epgKey = "trt",
            nowTitle = null,
            nowStartMs = null,
            nowEndMs = null,
            quality = "4K",
        )
        compose.setContent {
            TvHostPlain {
                ChannelRow(channel, number = 1, nowMs = 0, onClick = {}, measuredResolution = "1920×1080")
            }
        }

        compose.onNode(hasText("1920×1080", substring = false)).assertExists()
        compose.onNode(hasText("4K", substring = false)).assertDoesNotExist()
    }
}
