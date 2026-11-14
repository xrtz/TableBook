package com.example.tablebook.feature.booking

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.tablebook.domain.booking.FloorPlan
import com.example.tablebook.domain.booking.FloorTable
import com.example.tablebook.domain.booking.TableShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FloorMap(plan: FloorPlan, available: Set<Long>, guests: Int, selection: Long?, enabled: Boolean, select: (FloorTable) -> Unit) {
    var scale by remember(plan) { mutableFloatStateOf(1f) }
    var translation by remember(plan) { mutableStateOf(Offset.Zero) }
    val frame = RoundedCornerShape(20.dp)
    val palette = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(plan.width.toFloat() / plan.height)
            .clip(frame).background(palette.surfaceVariant).border(1.dp, palette.outlineVariant, frame)
            .pointerInput(plan) {
                detectTransformGestures { center, pan, zoom, _ ->
                    val target = (scale * zoom).coerceIn(1f, 3f)
                    val midpoint = Offset(size.width / 2f, size.height / 2f)
                    val anchor = center - midpoint
                    val proposed = anchor - (anchor - translation) * (target / scale) + pan
                    val maxX = midpoint.x * (target - 1f)
                    val maxY = midpoint.y * (target - 1f)
                    translation = Offset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
                    scale = target
                }
            },
    ) {
        val unit = maxWidth / plan.width.toFloat()
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = translation.x
                translationY = translation.y
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = 20.dp.toPx()
                val columns = (size.width / cell).toInt()
                val rows = (size.height / cell).toInt()
                for (x in 1..columns) {
                    for (y in 1..rows) drawCircle(palette.outlineVariant, 0.8.dp.toPx(), Offset(x * cell, y * cell))
                }
                drawRoundRect(
                    palette.outlineVariant, topLeft = Offset(size.width * 0.72f, size.height * 0.12f),
                    size = Size(size.width * 0.22f, size.height * 0.12f), cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
            Text(
                stringResource(R.string.booking_entrance), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
            )
            for (table in plan.tables) {
                val diameter = (unit * table.size.toFloat()).coerceAtLeast(48.dp)
                TableTile(
                    table, table.table.id in available, table.table.id == selection, guests, enabled,
                    Modifier.offset(unit * table.x.toFloat() - diameter / 2, unit * table.y.toFloat() - diameter / 2).size(diameter),
                ) { select(table) }
            }
        }
        if (scale > 1f) {
            TextButton(
                onClick = { scale = 1f; translation = Offset.Zero },
                modifier = Modifier.align(Alignment.TopEnd).background(palette.surface, RoundedCornerShape(bottomStart = 16.dp)),
            ) { Text(stringResource(R.string.booking_reset_zoom)) }
        }
    }
}

@Composable
private fun TableTile(table: FloorTable, available: Boolean, selected: Boolean, guests: Int, enabled: Boolean, modifier: Modifier, select: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val canFit = guests <= table.table.capacity
    val state = when {
        selected -> R.string.booking_table_selected
        !canFit -> R.string.booking_wrong_capacity
        available -> R.string.booking_table_free
        else -> R.string.booking_table_unavailable
    }
    val chairs = pluralStringResource(R.plurals.booking_guests, table.table.capacity, table.table.capacity)
    val label = stringResource(R.string.booking_table_accessibility, table.label, chairs)
    val description = stringResource(state)
    val fill by animateColorAsState(
        when {
            selected -> palette.primary
            available -> palette.primaryContainer
            else -> palette.surface
        },
        label = "table-fill",
    )
    val emphasis by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.55f), label = "table-selection")
    val chairProgress by animateFloatAsState(
        if (canFit) guests.coerceAtMost(12).toFloat() else 0f,
        label = "table-chairs",
    )
    val shape = if (table.shape == TableShape.ROUND) CircleShape else RoundedCornerShape(9.dp)
    Box(
        modifier.graphicsLayer { scaleX = emphasis; scaleY = emphasis }
            .semantics {
                contentDescription = label
                stateDescription = description
            }.clickable(enabled = enabled && available, role = Role.Button, onClick = select),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val count = table.table.capacity.coerceAtMost(12)
            for (index in 0 until count) {
                val angle = index * 2.0 * PI / count - PI / 2
                val radius = size.minDimension * 0.44f
                val position = center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
                val weight = (chairProgress - index).coerceIn(0f, 1f)
                drawCircle(palette.outline, 2.dp.toPx(), position)
                drawCircle(palette.primary, (2f + weight).dp.toPx(), position, alpha = weight)
            }
        }
        Box(
            Modifier.fillMaxSize(0.72f).background(fill, shape)
                .border(1.dp, if (selected || available) palette.primary else palette.outline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                table.label, style = MaterialTheme.typography.labelMedium,
                color = when {
                    selected -> palette.onPrimary
                    available -> palette.onPrimaryContainer
                    else -> palette.onSurface
                },
            )
        }
    }
}
