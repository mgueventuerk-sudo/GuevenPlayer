package com.mg.player.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.mg.player.domain.model.AccentColor
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.IntroSkipMode
import com.mg.player.domain.model.LiveCushion
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.ThemeMode
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.accentGradient

/** Bereich „Wiedergabe“: Player, Serien, Bild & Ton (Feineinstellungen auf einer Unterseite). */
internal fun LazyListScope.playbackItems(c: SettingsContext) {
    playerItems(c)
    seriesItems(c)
    pictureItems(c)
    handoffItems(c)
}

/**
 * „Auf dem Fernseher weiterschauen“: Dieses Geraet nimmt Wiedergabe vom Handy entgegen. Gesichert wird das mit
 * demselben Geheimnis wie das Pruefgeraet (Einstellungen -> Sport) - eine Kopplung je Haushalt, nicht zwei.
 */
private fun LazyListScope.handoffItems(c: SettingsContext) {
    settingsGroup(
        "handoff",
        "Vom Handy übernehmen",
        footer = "Im Player am Handy erscheint dann „Auf dem Fernseher weiterschauen“. Übertragen werden nur die " +
            "Kennung des Inhalts und die Position – der Fernseher spielt über seinen eigenen Zugang.",
    ) {
        row("tv-handoff") {
            val secret = if (c.s.sportProofDevice) c.s.sportHubCode else c.s.sportHubJoinCode
            ToggleRow(
                "Wiedergabe vom Handy übernehmen",
                c.s.tvHandoff,
                { c.vm.update { it.copy(tvHandoff = !it.tvHandoff) } },
                if (secret.isBlank()) {
                    "Dafür fehlt noch das Geheimnis des Prüfgeräts (Einstellungen → Sport) – es sichert auch diese Übergabe."
                } else {
                    "Auf dem Fernseher einschalten, nicht auf dem Handy."
                },
                AppIcons.Play,
            )
        }
    }
}

private fun LazyListScope.playerItems(c: SettingsContext) {
    val engines = PlayerEngineType.entries
    val auto = c.s.automaticOptimization
    settingsGroup("player", "Player") {
        row("automatic-optimization") {
            ToggleRow(
                "Automatisch optimieren",
                auto,
                { c.vm.update { it.copy(automaticOptimization = !it.automaticOptimization) } },
                if (auto) "Gerät: ${c.vm.deviceTier.label} – Player und Puffer stellen sich selbst ein" else "Aus – eigene Werte unten",
                AppIcons.Sparkle,
            )
        }
        if (!auto) {
            row("live-engine") {
                ChoiceRow("Player für Live-TV", c.s.liveEngine.label, {
                    c.vm.update { it.copy(liveEngine = engines.nextAfter(it.liveEngine)) }
                }, "Empfohlen: ExoPlayer", AppIcons.LiveTv)
            }
            row("vod-engine") {
                ChoiceRow("Player für Filme & Serien", c.s.vodEngine.label, {
                    c.vm.update { it.copy(vodEngine = engines.nextAfter(it.vodEngine)) }
                }, "Empfohlen: ExoPlayer (wechselt bei DTS/AVI selbst zu LibVLC)", AppIcons.Movie)
            }
            row("supervisor") {
                ToggleRow(
                    "Wiedergabe-Wächter",
                    c.s.playbackSupervisor,
                    { c.vm.update { it.copy(playbackSupervisor = !it.playbackSupervisor) } },
                    "Hilft bei Hängern, Standbild, Ton ohne Bild und Bild ohne Ton",
                    AppIcons.Refresh,
                )
            }
        }
        // Immer sichtbar (auch mit automatischer Optimierung): Abwägung zwischen Live-Nähe und Stabilität trifft der Nutzer.
        row("live-cushion") {
            ChoiceRow("Live-Puffer", c.s.liveCushion.label, {
                c.vm.update { it.copy(liveCushion = LiveCushion.entries.nextAfter(it.liveCushion)) }
            }, "Fängt kurze Aussetzer ab – Bild läuft so viel hinter Live", AppIcons.Wifi)
        }
        row("start-last") {
            ToggleRow("Mit dem letzten Sender starten", c.s.startWithLastChannel, {
                c.vm.update { it.copy(startWithLastChannel = !it.startWithLastChannel) }
            }, icon = AppIcons.Play)
        }
    }
}

private fun LazyListScope.pictureItems(c: SettingsContext) {
    val auto = c.s.automaticOptimization
    settingsGroup("picture", "Bild & Ton") {
        // Am Handy wird die Bildwiederholrate nicht umgeschaltet – der Schalter hätte dort keine Wirkung.
        if (!c.touch) {
            row("afr") {
                ToggleRow("Auto-Frame-Rate", c.s.autoFrameRate, {
                    c.vm.update { it.copy(autoFrameRate = !it.autoFrameRate) }
                }, "Bildwiederholrate des TVs anpassen (24/25/50/60 Hz)", AppIcons.Speed)
            }
        }
        if (!auto) {
            row("advanced", modifier = c.returnFocus("advanced")) {
                ActionRow(
                    "Erweitert",
                    { c.onOpen(SettingsSub.PlaybackAdvanced, "advanced") },
                    "Puffer, Decoder, Dolby/DTS, Tunneling",
                    icon = AppIcons.Engine,
                )
            }
        }
    }
}

/** Unterseite „Bild & Ton – Erweitert“ (nur ohne automatische Optimierung). */
internal fun LazyListScope.playbackAdvancedItems(c: SettingsContext) {
    item(key = "advanced-header", contentType = "header") {
        SettingsPageHeader("Bild & Ton – Erweitert", "Nur ändern, wenn etwas nicht läuft", breadcrumb = SettingsPage.PLAYBACK.label.takeUnless { c.touch })
    }
    settingsGroup("advanced-main") {
        row("buffer") {
            ChoiceRow("Puffer-Größe", c.s.bufferProfile.label, {
                c.vm.update { it.copy(bufferProfile = BufferProfile.entries.nextAfter(it.bufferProfile)) }
            }, "Stabil hilft bei langsamem WLAN gegen Ruckeln", AppIcons.Wifi)
        }
        row("hw") {
            ChoiceRow("Hardware-Beschleunigung", c.s.hwDecoding.label, {
                c.vm.update { it.copy(hwDecoding = HwDecoding.entries.nextAfter(it.hwDecoding)) }
            }, icon = AppIcons.Engine)
        }
        row("tunneling") {
            ToggleRow(
                "Hardware-Tunneling",
                c.s.tunneling,
                { c.vm.update { it.copy(tunneling = !it.tunneling) } },
                "Entlastet TV-Sticks (ExoPlayer); kann bei manchen Sendern Bild oder Ton kosten",
                AppIcons.Layers,
            )
        }
        row("passthrough") {
            ToggleRow(
                "Dolby/DTS durchreichen",
                c.s.audioPassthrough,
                { c.vm.update { it.copy(audioPassthrough = !it.audioPassthrough) } },
                "Nur mit AV-Receiver oder Soundbar – aus ist sicherer gegen stumme Sender",
                AppIcons.Audio,
            )
        }
    }
}

private fun LazyListScope.seriesItems(c: SettingsContext) {
    settingsGroup("binge", "Serien") {
        row("intro-mode") {
            ChoiceRow("Intro überspringen", c.s.introSkipMode.label, {
                c.vm.update { it.copy(introSkipMode = IntroSkipMode.entries.nextAfter(it.introSkipMode)) }
            }, icon = AppIcons.SkipNext)
        }
        row("intro-seconds") {
            ChoiceRow(
                "Intro-Sprungdauer",
                "${c.s.introSkipSeconds} s",
                { c.vm.update { it.copy(introSkipSeconds = AppSettings.INTRO_SKIP_OPTIONS.nextAfter(it.introSkipSeconds)) } },
                "Wenn weder Kapitel noch gelernte Zeiten vorliegen",
                AppIcons.Forward30,
            )
        }
        row("auto-next") {
            ToggleRow("Nächste Folge automatisch", c.s.autoPlayNext, { c.vm.update { it.copy(autoPlayNext = !it.autoPlayNext) } }, icon = AppIcons.Series)
        }
        row("next-trigger") {
            ChoiceRow(
                "Countdown vor dem Ende",
                "${c.s.nextEpisodeTriggerSeconds} s",
                { c.vm.update { it.copy(nextEpisodeTriggerSeconds = AppSettings.NEXT_TRIGGER_OPTIONS.nextAfter(it.nextEpisodeTriggerSeconds)) } },
                icon = AppIcons.Clock,
            )
        }
    }
}

/** Bereich „Darstellung“. */
internal fun LazyListScope.lookItems(c: SettingsContext) {
    settingsGroup("look", "Aussehen") {
        row("theme-mode", contentType = "segments") {
            SegmentedRow(
                label = "Erscheinungsbild",
                options = ThemeMode.entries,
                selected = c.s.themeMode,
                optionLabel = { it.label },
                optionIcon = { mode ->
                    when (mode) {
                        ThemeMode.AUTO -> AppIcons.AutoTheme
                        ThemeMode.DARK -> AppIcons.Moon
                        ThemeMode.LIGHT -> AppIcons.Sun
                    }
                },
                onSelect = { mode -> c.vm.update { it.copy(themeMode = mode) } },
                description = "Automatisch: tagsüber hell, ab 19 Uhr dunkel",
                icon = AppIcons.AutoTheme,
            )
        }
        row("accent", contentType = "swatches") {
            SwatchRow(
                label = "Akzentfarbe",
                options = AccentColor.entries,
                selected = c.s.accent,
                colorsOf = ::accentGradient,
                nameOf = { it.label },
                onSelect = { accent -> c.vm.update { it.copy(accent = accent) } },
                description = c.s.accent.label,
                icon = AppIcons.Palette,
            )
        }
    }
    lookComfortItems(c)
}

private fun LazyListScope.lookComfortItems(c: SettingsContext) {
    settingsGroup("look-comfort", "Lesbarkeit und Tempo") {
        row("large-text") {
            ToggleRow("Große Schrift", c.s.largeText, {
                c.vm.update { it.copy(largeText = !it.largeText) }
            }, "Besser lesbar aus der Entfernung (+15 %)", AppIcons.Document)
        }
        row("reduced-effects") {
            ToggleRow(
                "Effekte reduzieren",
                c.s.reducedEffects,
                { c.vm.update { it.copy(reducedEffects = !it.reducedEffects) } },
                "Ohne Glas, Leuchtschatten und Hintergrundbilder – schneller auf schwachen Sticks",
                AppIcons.Speed,
            )
        }
    }
    settingsGroup("look-home", "Startseite") {
        row("hide-guide") {
            ToggleRow(
                "Guide ausblenden",
                c.s.hideGuide,
                { c.vm.update { it.copy(hideGuide = !it.hideGuide) } },
                "Nur die Guide-Seite; das EPG bleibt in Sendern und im Player",
                AppIcons.Guide,
            )
        }
    }
}
