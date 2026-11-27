package com.cnife.listenreader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ReadingTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 18.sp,
    lineHeight = 31.5.sp,
    letterSpacing = 0.sp,
)

val PreviewBodyTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 16.5.sp,
    lineHeight = 30.sp,
    letterSpacing = 0.sp,
)

private fun style(
    size: Float,
    weight: FontWeight,
    lineHeight: Float,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val ListenReaderTypography = Typography(
    displayLarge = style(32f, FontWeight.Bold, 40f, -0.6f),
    displayMedium = style(26f, FontWeight.Bold, 34f, -0.3f),
    displaySmall = style(24f, FontWeight.Bold, 32f, -0.3f),
    headlineLarge = style(24f, FontWeight.Bold, 32f, -0.3f),
    headlineMedium = style(22f, FontWeight.Bold, 30f, -0.2f),
    headlineSmall = style(21f, FontWeight.Bold, 28f),
    titleLarge = style(17f, FontWeight.SemiBold, 24f),
    titleMedium = style(16f, FontWeight.Medium, 24f),
    titleSmall = style(15.5f, FontWeight.Medium, 22f),
    bodyLarge = style(16f, FontWeight.Normal, 24f),
    bodyMedium = style(15f, FontWeight.Normal, 22.5f),
    bodySmall = style(12.5f, FontWeight.Normal, 18f),
    labelLarge = style(16f, FontWeight.Medium, 24f),
    labelMedium = style(13f, FontWeight.Medium, 20f),
    labelSmall = style(11.5f, FontWeight.SemiBold, 16f, 0.6f),
)
