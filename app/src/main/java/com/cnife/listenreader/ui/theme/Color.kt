package com.cnife.listenreader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 「墨声」静态色板。外层通过 CompositionLocal 提供当前（浅/深）色板；
// 组件里使用下面的同名 Composable token，避免深色模式仍取到浅色纸面。
val LightPaper = Color(0xFFF7F6F2)
val LightCardSurface = Color(0xFFFFFFFF)
val LightSunken = Color(0xFFEFEEE8)
val LightInk = Color(0xFF17171A)
val LightInk2 = Color(0xFF5B5B57)
val LightInk3 = Color(0xFF8A8A83)
val LightRule = Color(0xFFDEDED7)
val LightRuleStrong = Color(0xFFC4C4BB)
val LightAccent = Color(0xFFB23A26)
val LightOnAccent = Color(0xFFFFFFFF)
val LightScrim = Color(0x5217171A)

val DarkPaper = Color(0xFF121210)
val DarkCardSurface = Color(0xFF1A1A18)
val DarkSunken = Color(0xFF232320)
val DarkInk = Color(0xFFECEBE5)
val DarkInk2 = Color(0xFFAAA9A1)
val DarkInk3 = Color(0xFF7B7A73)
val DarkRule = Color(0xFF2E2E2A)
val DarkRuleStrong = Color(0xFF45453F)
val DarkAccent = Color(0xFFE07054)
val DarkOnAccent = Color(0xFF121210)
val DarkScrim = Color(0x8C000000)

@Immutable
data class LrColors(
    val paper: Color,
    val cardSurface: Color,
    val sunken: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val rule: Color,
    val ruleStrong: Color,
    val accent: Color,
    val onAccent: Color,
    val scrim: Color,
)

val LightLrColors = LrColors(
    paper = LightPaper,
    cardSurface = LightCardSurface,
    sunken = LightSunken,
    ink = LightInk,
    ink2 = LightInk2,
    ink3 = LightInk3,
    rule = LightRule,
    ruleStrong = LightRuleStrong,
    accent = LightAccent,
    onAccent = LightOnAccent,
    scrim = LightScrim,
)

val DarkLrColors = LrColors(
    paper = DarkPaper,
    cardSurface = DarkCardSurface,
    sunken = DarkSunken,
    ink = DarkInk,
    ink2 = DarkInk2,
    ink3 = DarkInk3,
    rule = DarkRule,
    ruleStrong = DarkRuleStrong,
    accent = DarkAccent,
    onAccent = DarkOnAccent,
    scrim = DarkScrim,
)

val LocalLrColors = staticCompositionLocalOf { LightLrColors }

val Paper: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.paper

val CardSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.cardSurface

val Sunken: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.sunken

val Ink: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.ink

val Ink2: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.ink2

val Ink3: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.ink3

val Rule: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.rule

val RuleStrong: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.ruleStrong

val Accent: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.accent

val OnAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.onAccent

val Scrim: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLrColors.current.scrim
