package com.cnife.listenreader.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val LrPillShape = CircleShape
val LrInputShape = RoundedCornerShape(4.dp)
val LrUtilityShape = RoundedCornerShape(8.dp)
val LrCardShape = RoundedCornerShape(12.dp)
val LrSheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
val LrIslandShape = RoundedCornerShape(16.dp)

val ListenReaderShapes = Shapes(
    extraSmall = LrInputShape,
    small = LrUtilityShape,
    medium = LrCardShape,
    large = LrSheetShape,
    extraLarge = LrSheetShape,
)
