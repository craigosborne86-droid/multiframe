package dev.multiframe.camera.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** Schematic etching stays in the housing margins, below text contrast. */
internal fun Modifier.instrumentEtching() = drawBehind {
    val trace = TitaniumTokens.TitaniumM6.copy(alpha = .10f)
    val edge = 5.dp.toPx()
    for (side in listOf(edge, size.width - edge)) {
        drawLine(trace, Offset(side, edge), Offset(side, size.height - edge), .5.dp.toPx())
        for (i in 1..4) {
            val y = i * size.height / 5
            drawLine(trace, Offset(side, y), Offset(side + if(side < size.width/2) 5.dp.toPx() else -5.dp.toPx(), y), .5.dp.toPx())
        }
    }
}
