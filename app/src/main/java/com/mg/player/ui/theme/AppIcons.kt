package com.mg.player.ui.theme

import androidx.compose.ui.graphics.vector.addPathNodes

private fun stroke(path: String, motion: Motion = Motion(), width: Float = 1.9f) =
    IconPart(addPathNodes(path), fill = false, strokeWidth = width, motion = motion)
private fun fill(path: String, motion: Motion = Motion()) = IconPart(addPathNodes(path), fill = true, motion = motion)
private fun icon(name: String, vararg parts: IconPart) = AppIcon(name, parts.toList())

private const val CIRCLE_R85 = "M3.5 12A8.5 8.5 0 1 0 20.5 12A8.5 8.5 0 1 0 3.5 12Z"

/**
 * Eigene Icon-Familie im Stil von SF Symbols: runde Linienenden, 24er-Raster, einheitliche Strichstärke.
 * Jedes Icon hat eine kleine, zum Motiv passende Animation, sobald es den Fokus bekommt.
 */
object AppIcons {
    val Home = icon(
        "Home",
        stroke("M3.5 10.5L12 3.5L20.5 10.5V19A1.5 1.5 0 0 1 19 20.5H5A1.5 1.5 0 0 1 3.5 19Z"),
        stroke("M9 20V13H15V20"),
    )
    val LiveTv = icon(
        "LiveTv",
        stroke("M5.5 7.5H18.5A3 3 0 0 1 21.5 10.5V17A3 3 0 0 1 18.5 20H5.5A3 3 0 0 1 2.5 17V10.5A3 3 0 0 1 5.5 7.5Z"),
        stroke("M8.5 3L12 7.5L15.5 3", Motion(pivotX = 12f, pivotY = 7.5f, wiggle = 16f)),
        fill("M10.2 11.3L14.9 13.75L10.2 16.2Z", Motion(pivotX = 12f, pivotY = 13.75f, bounce = 0.25f)),
    )
    val Guide = icon(
        "Guide",
        stroke("M6 4.5H18A3 3 0 0 1 21 7.5V16.5A3 3 0 0 1 18 19.5H6A3 3 0 0 1 3 16.5V7.5A3 3 0 0 1 6 4.5Z"),
        stroke("M7 9.5H11.5M14.5 9.5H17M7 14.5H9M12 14.5H17", Motion(redraw = true)),
    )
    val Movie = icon(
        "Movie",
        stroke("M4.5 10.5H19.5V17.5A2.5 2.5 0 0 1 17 20H7A2.5 2.5 0 0 1 4.5 17.5Z"),
        stroke("M4.5 10.5V7.5A2 2 0 0 1 6.5 5.5H17.5A2 2 0 0 1 19.5 7.5V10.5M9 5.5L11 10.5M14 5.5L16 10.5", Motion(pivotX = 4.5f, pivotY = 10.5f, tilt = -20f)),
    )
    val Series = icon(
        "Series",
        stroke("M7.5 3.5H18.5A2.5 2.5 0 0 1 21 6V15", Motion(shiftX = 1.2f, shiftY = -1.2f)),
        stroke("M5.5 7.5H15A2.5 2.5 0 0 1 17.5 10V18A2.5 2.5 0 0 1 15 20.5H5.5A2.5 2.5 0 0 1 3 18V10A2.5 2.5 0 0 1 5.5 7.5Z"),
        fill("M8.4 11.2L12.8 14L8.4 16.8Z", Motion(pivotX = 10f, pivotY = 14f, bounce = 0.25f)),
    )
    val Recordings = icon(
        "Recordings",
        stroke(CIRCLE_R85),
        fill("M8 12A4 4 0 1 0 16 12A4 4 0 1 0 8 12Z", Motion(pulse = 0.22f)),
    )
    val Search = icon(
        "Search",
        stroke("M4 10.5A6.5 6.5 0 1 0 17 10.5A6.5 6.5 0 1 0 4 10.5ZM15.3 15.3L20.5 20.5", Motion(pivotX = 10.5f, pivotY = 10.5f, tilt = -16f, bounce = 0.08f)),
    )
    val Settings = icon(
        "Settings",
        stroke(
            "M5 12A7 7 0 1 0 19 12A7 7 0 1 0 5 12ZM9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z",
            Motion(turn = 60f),
        ),
        stroke(
            "M18.47 14.68L20.59 15.56M14.68 18.47L15.56 20.59M9.32 18.47L8.44 20.59M5.53 14.68L3.41 15.56" +
                "M5.53 9.32L3.41 8.44M9.32 5.53L8.44 3.41M14.68 5.53L15.56 3.41M18.47 9.32L20.59 8.44",
            Motion(turn = 60f),
            width = 2.6f,
        ),
    )
    val Play = icon("Play", fill("M8 5.2L19.2 12L8 18.8Z", Motion(pivotX = 12f, bounce = 0.18f)))
    val Pause = icon("Pause", fill("M6.5 5H10V19H6.5ZM14 5H17.5V19H14Z", Motion(bounce = 0.15f)))
    val SkipNext = icon(
        "SkipNext",
        fill("M5 6L14 12L5 18Z", Motion(shiftX = 1.5f)),
        stroke("M18 6V18", width = 2.4f),
    )
    val Back10 = icon(
        "Back10",
        stroke("M5 12A7 7 0 1 0 8 6.3", Motion(spin = -360f)),
        stroke("M8.5 2.8L8 6.3L11.5 7", Motion(spin = -360f)),
        stroke(
            "M9.1 10.3L9.9 9.6V14.4M12.6 9.6H13.4A1 1 0 0 1 14.4 10.6V13.4A1 1 0 0 1 13.4 14.4H12.6A1 1 0 0 1 11.6 13.4V10.6A1 1 0 0 1 12.6 9.6Z",
            width = 1.4f,
        ),
    )
    val Forward30 = icon(
        "Forward30",
        stroke("M19 12A7 7 0 1 1 16 6.3", Motion(spin = 360f)),
        stroke("M15.5 2.8L16 6.3L12.5 7", Motion(spin = 360f)),
        stroke(
            "M9 10.6H11L10 12.4A1.3 1.3 0 1 1 8.9 14.4M13.4 10.4H14.6A1 1 0 0 1 15.6 11.4V13.8A1 1 0 0 1 14.6 14.8H13.4A1 1 0 0 1 12.4 13.8V11.4A1 1 0 0 1 13.4 10.4Z",
            width = 1.3f,
        ),
    )
    val Restart = icon(
        "Restart",
        stroke("M5 4.5V9.5H10", Motion(pivotX = 12f, pivotY = 12f, spin = -360f)),
        stroke("M5.3 9.3A7.5 7.5 0 1 1 4.8 14", Motion(pivotX = 12f, pivotY = 12f, spin = -360f)),
    )
    val GoLive = icon(
        "GoLive",
        fill("M9.5 12A2.5 2.5 0 1 0 14.5 12A2.5 2.5 0 1 0 9.5 12Z", Motion(pulse = 0.25f)),
        stroke("M6.5 7.5A6.5 6.5 0 0 0 6.5 16.5M17.5 7.5A6.5 6.5 0 0 1 17.5 16.5", Motion(redraw = true)),
        stroke("M3.6 4.8A10.5 10.5 0 0 0 3.6 19.2M20.4 4.8A10.5 10.5 0 0 1 20.4 19.2", Motion(redraw = true)),
    )
    val Record = icon("Record", fill("M6 12A6 6 0 1 0 18 12A6 6 0 1 0 6 12Z", Motion(pulse = 0.18f)))
    val Stop =
        icon(
            "Stop",
            fill("M7.5 6H16.5A1.5 1.5 0 0 1 18 7.5V16.5A1.5 1.5 0 0 1 16.5 18H7.5A1.5 1.5 0 0 1 6 16.5V7.5A1.5 1.5 0 0 1 7.5 6Z", Motion(bounce = 0.12f)),
        )
    val Heart = icon(
        "Heart",
        stroke("M12 20.3C12 20.3 3.5 15.2 3.5 9.2A4.4 4.4 0 0 1 12 7A4.4 4.4 0 0 1 20.5 9.2C20.5 15.2 12 20.3 12 20.3Z", Motion(bounce = 0.22f)),
    )
    val HeartFilled = icon(
        "HeartFilled",
        fill("M12 20.3C12 20.3 3.5 15.2 3.5 9.2A4.4 4.4 0 0 1 12 7A4.4 4.4 0 0 1 20.5 9.2C20.5 15.2 12 20.3 12 20.3Z", Motion(bounce = 0.22f)),
    )
    val Star = icon(
        "Star",
        fill("M12 4L14.29 9.44L20.18 9.94L15.71 13.81L17.05 19.56L12 16.5L6.95 19.56L8.29 13.81L3.82 9.94L9.71 9.44Z", Motion(pivotY = 12.6f, spin = 72f)),
    )
    val Lock = icon(
        "Lock",
        stroke("M6.5 10.5H17.5A2 2 0 0 1 19.5 12.5V18.5A2 2 0 0 1 17.5 20.5H6.5A2 2 0 0 1 4.5 18.5V12.5A2 2 0 0 1 6.5 10.5Z"),
        stroke("M8 10.5V7.5A4 4 0 0 1 16 7.5V10.5", Motion(shiftY = -1.4f)),
    )
    val Check = icon("Check", stroke("M5 12.5L10 17.5L19.5 7", Motion(redraw = true), width = 2.2f))
    val Close = icon("Close", stroke("M6 6L18 18M18 6L6 18", Motion(turn = 90f), width = 2.1f))
    val Plus = icon("Plus", stroke("M12 5V19M5 12H19", Motion(turn = 90f), width = 2.1f))
    val ChevronRight = icon("ChevronRight", stroke("M9 5.5L15.5 12L9 18.5", Motion(shiftX = 1.5f), width = 2.1f))
    val ChevronLeft = icon("ChevronLeft", stroke("M15 5.5L8.5 12L15 18.5", Motion(shiftX = -1.5f), width = 2.1f))
    val ChevronDown = icon("ChevronDown", stroke("M5.5 9L12 15.5L18.5 9", Motion(shiftY = 1.5f), width = 2.1f))
    val ChevronUp = icon("ChevronUp", stroke("M5.5 15L12 8.5L18.5 15", Motion(shiftY = -1.5f), width = 2.1f))

    /** Verschieben: Pfeil hoch und Pfeil runter nebeneinander. */
    val Reorder = icon("Reorder", stroke("M8 4.5V19.5M4.5 8L8 4.5L11.5 8M16 19.5V4.5M12.5 16L16 19.5L19.5 16", Motion(shiftY = 1.2f), width = 2f))
    val Back = icon("Back", stroke("M19 12H5.5M11 5.5L4.5 12L11 18.5", Motion(shiftX = -1.6f), width = 2.1f))
    val Info = icon(
        "Info",
        stroke(CIRCLE_R85),
        stroke("M12 11V16.5", width = 2.1f),
        fill("M10.9 7.8A1.1 1.1 0 1 0 13.1 7.8A1.1 1.1 0 1 0 10.9 7.8Z", Motion(shiftY = -1f)),
    )
    val Audio = icon(
        "Audio",
        fill("M4 9.5H7.5L12.5 5.5V18.5L7.5 14.5H4Z"),
        stroke("M15.5 9A4 4 0 0 1 15.5 15M18.2 6.5A7.5 7.5 0 0 1 18.2 17.5", Motion(redraw = true)),
    )
    val Mic = icon(
        "Mic",
        stroke("M12 3A3 3 0 0 1 15 6V11A3 3 0 0 1 9 11V6A3 3 0 0 1 12 3Z", Motion(pivotX = 12f, pivotY = 8f, bounce = 0.15f)),
        stroke("M5.5 11A6.5 6.5 0 0 0 18.5 11M12 17.5V21M8.5 21H15.5"),
    )
    val Subtitles = icon(
        "Subtitles",
        stroke("M5.5 5H18.5A2.5 2.5 0 0 1 21 7.5V16.5A2.5 2.5 0 0 1 18.5 19H5.5A2.5 2.5 0 0 1 3 16.5V7.5A2.5 2.5 0 0 1 5.5 5Z"),
        stroke("M7 12.5H10M12.5 12.5H17M7 15.5H13M15.5 15.5H17", Motion(redraw = true)),
    )
    val ListIcon = icon(
        "List",
        stroke("M9 7H20M9 12H20M9 17H20", Motion(redraw = true)),
        fill(
            "M3.8 7A1.2 1.2 0 1 0 6.2 7A1.2 1.2 0 1 0 3.8 7ZM3.8 12A1.2 1.2 0 1 0 6.2 12A1.2 1.2 0 1 0 3.8 12ZM3.8 17A1.2 1.2 0 1 0 6.2 17A1.2 1.2 0 1 0 3.8 17Z",
        ),
    )
    val Layers = icon(
        "Layers",
        stroke("M12 3.5L21 8.5L12 13.5L3 8.5Z", Motion(shiftY = -1.3f)),
        stroke("M3 12.5L12 17.5L21 12.5"),
        stroke("M3 16.3L12 21.3L21 16.3", Motion(shiftY = 0.8f)),
    )
    val Engine = icon(
        "Engine",
        stroke("M8 6H16A2 2 0 0 1 18 8V16A2 2 0 0 1 16 18H8A2 2 0 0 1 6 16V8A2 2 0 0 1 8 6Z"),
        fill("M10 10H14V14H10Z", Motion(pulse = 0.18f)),
        stroke("M9.5 3V6M14.5 3V6M9.5 18V21M14.5 18V21M3 9.5H6M3 14.5H6M18 9.5H21M18 14.5H21", Motion(redraw = true), width = 1.7f),
    )
    val Refresh = icon(
        "Refresh",
        stroke("M19.5 12A7.5 7.5 0 0 1 6.2 16.8M4.5 12A7.5 7.5 0 0 1 17.8 7.2", Motion(spin = 180f)),
        stroke("M18.3 3.8L17.8 7.2L14.4 6.8M5.7 20.2L6.2 16.8L9.6 17.2", Motion(spin = 180f)),
    )
    val Trash = icon(
        "Trash",
        stroke("M6.5 8L7.5 19A1.8 1.8 0 0 0 9.3 20.5H14.7A1.8 1.8 0 0 0 16.5 19L17.5 8M10 11.5V17M14 11.5V17"),
        stroke("M4 6.5H20M9.5 6.5V4.5H14.5V6.5", Motion(pivotX = 20f, pivotY = 6.5f, tilt = 18f)),
    )
    val User = icon(
        "User",
        stroke("M8.3 8A3.7 3.7 0 1 0 15.7 8A3.7 3.7 0 1 0 8.3 8Z", Motion(shiftY = -0.8f)),
        stroke("M4.5 20A7.5 6.5 0 0 1 19.5 20"),
    )
    val Sun = icon(
        "Sun",
        stroke("M8 12A4 4 0 1 0 16 12A4 4 0 1 0 8 12Z"),
        stroke("M12 2.8V4.6M12 19.4V21.2M2.8 12H4.6M19.4 12H21.2M5.5 5.5L6.8 6.8M17.2 17.2L18.5 18.5M5.5 18.5L6.8 17.2M17.2 6.8L18.5 5.5", Motion(turn = 45f)),
    )
    val Moon = icon(
        "Moon",
        stroke("M19.5 14.5A8 8 0 1 1 9.5 4.5A6.5 6.5 0 0 0 19.5 14.5Z", Motion(pivotX = 12f, pivotY = 12f, tilt = -14f)),
    )
    val AutoTheme = icon(
        "AutoTheme",
        stroke(CIRCLE_R85),
        fill("M12 3.5A8.5 8.5 0 0 1 12 20.5Z", Motion(turn = 180f)),
    )
    val Palette = icon(
        "Palette",
        stroke("M12 3.5A8.5 8.5 0 0 0 12 20.5C13.4 20.5 13.8 19.4 13.2 18.4C12.6 17.4 13.2 16 14.6 16H16.5A4 4 0 0 0 20.5 12A8.5 8 0 0 0 12 3.5Z"),
        fill(
            "M7 11.5A1.3 1.3 0 1 0 9.6 11.5A1.3 1.3 0 1 0 7 11.5ZM9.9 7.6A1.3 1.3 0 1 0 12.5 7.6A1.3 1.3 0 1 0 9.9 7.6ZM14.4 8.6A1.3 1.3 0 1 0 17 8.6A1.3 1.3 0 1 0 14.4 8.6Z",
            Motion(bounce = 0.2f),
        ),
    )
    val Globe = icon(
        "Globe",
        stroke(CIRCLE_R85),
        stroke("M3.5 12H20.5M12 3.5C9.5 6 8.6 9 8.6 12C8.6 15 9.5 18 12 20.5M12 3.5C14.5 6 15.4 9 15.4 12C15.4 15 14.5 18 12 20.5", Motion(redraw = true)),
    )
    val Clock = icon(
        "Clock",
        stroke(CIRCLE_R85),
        stroke("M12 7V12L15.5 14", Motion(spin = 360f)),
    )
    val Grid = icon(
        "Grid",
        stroke(
            "M5.5 4H9A1.5 1.5 0 0 1 10.5 5.5V9A1.5 1.5 0 0 1 9 10.5H5.5A1.5 1.5 0 0 1 4 9V5.5A1.5 1.5 0 0 1 5.5 4ZM15 4H18.5A1.5 1.5 0 0 1 20 5.5V9A1.5 1.5 0 0 1 18.5 10.5H15A1.5 1.5 0 0 1 13.5 9V5.5A1.5 1.5 0 0 1 15 4Z",
        ),
        stroke(
            "M5.5 13.5H9A1.5 1.5 0 0 1 10.5 15V18.5A1.5 1.5 0 0 1 9 20H5.5A1.5 1.5 0 0 1 4 18.5V15A1.5 1.5 0 0 1 5.5 13.5ZM15 13.5H18.5A1.5 1.5 0 0 1 20 15V18.5A1.5 1.5 0 0 1 18.5 20H15A1.5 1.5 0 0 1 13.5 18.5V15A1.5 1.5 0 0 1 15 13.5Z",
            Motion(shiftY = 1f),
        ),
    )
    val EyeOff = icon(
        "EyeOff",
        stroke("M3 12C5.2 8 8.4 6 12 6C15.6 6 18.8 8 21 12C18.8 16 15.6 18 12 18C8.4 18 5.2 16 3 12Z"),
        stroke("M9.5 12A2.5 2.5 0 1 0 14.5 12A2.5 2.5 0 1 0 9.5 12Z"),
        stroke("M4.5 4.5L19.5 19.5", Motion(redraw = true), width = 2.1f),
    )
    val Phone = icon(
        "Phone",
        stroke("M8.5 2.5H15.5A2 2 0 0 1 17.5 4.5V19.5A2 2 0 0 1 15.5 21.5H8.5A2 2 0 0 1 6.5 19.5V4.5A2 2 0 0 1 8.5 2.5Z"),
        stroke("M10.5 18.5H13.5", Motion(redraw = true)),
    )
    val Download = icon(
        "Download",
        stroke("M12 4V15M7.5 10.5L12 15L16.5 10.5", Motion(shiftY = 1.6f)),
        stroke("M4.5 19.5H19.5"),
    )
    val Upload = icon(
        "Upload",
        stroke("M12 15V4M7.5 8.5L12 4L16.5 8.5", Motion(shiftY = -1.6f)),
        stroke("M4.5 19.5H19.5"),
    )
    val Document = icon(
        "Document",
        stroke("M7 3H14L19 8V19A2 2 0 0 1 17 21H7A2 2 0 0 1 5 19V5A2 2 0 0 1 7 3ZM14 3V8H19"),
        stroke("M8.5 12.5H15.5M8.5 16H13", Motion(redraw = true)),
    )
    val Shield = icon(
        "Shield",
        stroke("M12 3L19.5 6V11.5C19.5 16 16.3 19.6 12 21C7.7 19.6 4.5 16 4.5 11.5V6Z"),
        stroke("M8.8 12L11 14.2L15.4 9.8", Motion(redraw = true)),
    )
    val Wifi = icon(
        "Wifi",
        stroke("M2.8 9A13 13 0 0 1 21.2 9M5.9 12.4A8.6 8.6 0 0 1 18.1 12.4M9 15.7A4.3 4.3 0 0 1 15 15.7", Motion(redraw = true)),
        fill("M10.8 19A1.2 1.2 0 1 0 13.2 19A1.2 1.2 0 1 0 10.8 19Z"),
    )
    val Speed = icon(
        "Speed",
        stroke("M4.5 17A8.5 8.5 0 1 1 19.5 17"),
        stroke("M12 14L16 9", Motion(pivotX = 12f, pivotY = 14f, wiggle = 24f), width = 2.2f),
    )
    val Storage = icon(
        "Storage",
        stroke("M4 6.5C4 5 7.6 3.8 12 3.8C16.4 3.8 20 5 20 6.5V17.5C20 19 16.4 20.2 12 20.2C7.6 20.2 4 19 4 17.5Z"),
        stroke("M4 6.5C4 8 7.6 9.2 12 9.2C16.4 9.2 20 8 20 6.5M4 12C4 13.5 7.6 14.7 12 14.7C16.4 14.7 20 13.5 20 12", Motion(redraw = true)),
    )
    val Calendar = icon(
        "Calendar",
        stroke("M6 5H18A2 2 0 0 1 20 7V18.5A2 2 0 0 1 18 20.5H6A2 2 0 0 1 4 18.5V7A2 2 0 0 1 6 5ZM4 10H20"),
        stroke("M8.5 3V6.5M15.5 3V6.5", Motion(shiftY = -1f)),
    )
    val Archive = icon(
        "Archive",
        stroke("M4.6 13.5A7.6 7.6 0 1 0 6.4 6.6", Motion(spin = -360f)),
        stroke("M3.6 4V8H7.6", Motion(pivotX = 12f, pivotY = 12f, spin = -360f)),
        stroke("M12 8.2V12.2L14.6 13.8"),
    )
    val Swap = icon(
        "Swap",
        stroke("M5 8H18.5M15 4.5L18.5 8L15 11.5", Motion(shiftX = 1.6f), width = 2f),
        stroke("M19 16H5.5M9 12.5L5.5 16L9 19.5", Motion(shiftX = -1.6f), width = 2f),
    )
    val CheckCircle = icon(
        "CheckCircle",
        stroke(CIRCLE_R85),
        stroke("M8.2 12.3L10.9 15L15.9 9.6", Motion(redraw = true), width = 2.1f),
    )
    val Sparkle = icon(
        "Sparkle",
        fill("M12 3L13.7 10.3L21 12L13.7 13.7L12 21L10.3 13.7L3 12L10.3 10.3Z", Motion(spin = 90f, bounce = 0.12f)),
    )

    /** Pokal für die Sport-Sektion. */
    val Sport = icon(
        "Sport",
        stroke("M8 4H16V9A4 4 0 0 1 8 9Z", Motion(bounce = 0.1f)),
        stroke("M8 6H5V7.5A3 3 0 0 0 8 10.5M16 6H19V7.5A3 3 0 0 1 16 10.5"),
        stroke("M12 13V17M8.5 20H15.5M9.5 17H14.5V20H9.5Z"),
    )
}
