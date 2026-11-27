package com.cnife.listenreader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val StickerSky = Color(0xFF62AEF0)
val StickerPurple = Color(0xFFD6B6F6)
val StickerPurpleInk = Color(0xFF391C57)
val StickerTeal = Color(0xFF2A9D99)
val StickerGreen = Color(0xFF1AAE39)
val StickerOrange = Color(0xFFDD5B00)

private val LightCanvas = Color(0xFFF6F5F4)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSunken = Color(0xFFEFEEEB)
private val LightHairline = Color(0xFFE6E6E6)
private val LightRuleStrong = Color(0xFFC4C4BB)
private val LightInk = Color(0xFF1A1A19)
private val LightMuted = Color(0xFF615D59)
private val LightMeta = Color(0xFF78736C)
private val LightMetaOnCanvas = Color(0xFF716C65)
private val LightPrimary = Color(0xFF0075DE)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightLink = Color(0xFF005BAB)
private val LightNight = Color(0xFF213183)
private val LightFieldBorder = Color(0xFFDDDDDD)
private val LightCurrentBg = Color(0xFFEAF3FD)
private val LightCurrentBar = Color(0xFF0075DE)
private val LightSelectedPill = Color(0xFFFFFFFF)
private val LightScrim = Color(0x52000000)

private val DarkCanvas = Color(0xFF191919)
private val DarkSurface = Color(0xFF202020)
private val DarkSunken = Color(0xFF262624)
private val DarkHairline = Color(0xFF2E2E2C)
private val DarkRuleStrong = Color(0xFF4A4A46)
private val DarkInk = Color(0xFFE9E9E7)
private val DarkMuted = Color(0xFFA6A29D)
private val DarkMeta = Color(0xFF9A958E)
private val DarkPrimary = Color(0xFF0075DE)
private val DarkOnPrimary = Color(0xFFFFFFFF)
private val DarkLink = Color(0xFF62AEF0)
private val DarkNight = Color(0xFF2A3166)
private val DarkFieldBorder = Color(0xFF3A3A37)
private val DarkCurrentBg = Color(0xFF1C2A3A)
private val DarkCurrentBar = Color(0xFF62AEF0)
private val DarkSelectedPill = Color(0xFF2C2C2A)
private val DarkScrim = Color(0x99000000)

@Immutable
data class LrColors(
    val canvas: Color,
    val surface: Color,
    val sunken: Color,
    val hairline: Color,
    val ruleStrong: Color,
    val ink: Color,
    val muted: Color,
    val meta: Color,
    val metaOnCanvas: Color,
    val primary: Color,
    val onPrimary: Color,
    val link: Color,
    val selected: Color,
    val night: Color,
    val onNight: Color,
    val nightMeta: Color,
    val nightEyebrow: Color,
    val nightTrack: Color,
    val fieldBorder: Color,
    val currentBg: Color,
    val currentBar: Color,
    val selectedPill: Color,
    val scrim: Color,
)

val LightLrColors = LrColors(
    canvas = LightCanvas,
    surface = LightSurface,
    sunken = LightSunken,
    hairline = LightHairline,
    ruleStrong = LightRuleStrong,
    ink = LightInk,
    muted = LightMuted,
    meta = LightMeta,
    metaOnCanvas = LightMetaOnCanvas,
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    link = LightLink,
    selected = LightPrimary,
    night = LightNight,
    onNight = Color(0xFFFFFFFF),
    nightMeta = Color(0xFFB9C1E4),
    nightEyebrow = Color(0xFF8FB3EC),
    nightTrack = Color(0x38FFFFFF),
    fieldBorder = LightFieldBorder,
    currentBg = LightCurrentBg,
    currentBar = LightCurrentBar,
    selectedPill = LightSelectedPill,
    scrim = LightScrim,
)

val DarkLrColors = LrColors(
    canvas = DarkCanvas,
    surface = DarkSurface,
    sunken = DarkSunken,
    hairline = DarkHairline,
    ruleStrong = DarkRuleStrong,
    ink = DarkInk,
    muted = DarkMuted,
    meta = DarkMeta,
    metaOnCanvas = DarkMeta,
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    link = DarkLink,
    selected = DarkLink,
    night = DarkNight,
    onNight = Color(0xFFFFFFFF),
    nightMeta = Color(0xFFC5CCE8),
    nightEyebrow = Color(0xFF8FB3EC),
    nightTrack = Color(0x38FFFFFF),
    fieldBorder = DarkFieldBorder,
    currentBg = DarkCurrentBg,
    currentBar = DarkCurrentBar,
    selectedPill = DarkSelectedPill,
    scrim = DarkScrim,
)

val LocalLrColors = staticCompositionLocalOf { LightLrColors }

val Canvas: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.canvas

val Surface: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.surface

val Sunken: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.sunken

val Hairline: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.hairline

val RuleStrong: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.ruleStrong

val Ink: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.ink

val Muted: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.muted

val Meta: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.meta

val MetaOnCanvas: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.metaOnCanvas

val Primary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.primary

val OnPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.onPrimary

val Link: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.link

val Selected: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.selected

val Night: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.night

val OnNight: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.onNight

val NightMeta: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.nightMeta

val NightEyebrow: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.nightEyebrow

val NightTrack: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.nightTrack

val FieldBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.fieldBorder

val CurrentBg: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.currentBg

val CurrentBar: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.currentBar

val SelectedPill: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.selectedPill

val Scrim: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.scrim
