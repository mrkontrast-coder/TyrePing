@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package com.masselis.tpmsadvanced.feature.androidauto.endpoint.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import com.masselis.tpmsadvanced.core.common.now
import com.masselis.tpmsadvanced.feature.androidauto.endpoint.ui.viewmodel.TabScreenViewModel.State
import com.masselis.tpmsadvanced.feature.main.interfaces.composable.appendLoc
import com.masselis.tpmsadvanced.feature.main.usecase.FleetHealth
import com.masselis.tpmsadvanced.feature.main.usecase.TyreStatsStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Stable row titles/counts allow value refreshes without consuming the host's navigation quota. */
context(screen: Screen)
@Suppress("FunctionName", "FunctionNaming")
internal fun VehicleTemplate(tab: State.Tabs.Tab.Displayed, rowLimit: Int = carPaneLimit()): PaneTemplate {
    val limit = rowLimit.coerceAtLeast(1)
    val health = FleetHealth.values.value.firstOrNull { it.vehicle.uuid == tab.vehicle.uuid }
    val currentTime = now()

    val entries = tab.vehicle.kind.locations.map { location ->
        val state = tab.tyres[location]?.second ?: TyreStatsStateFlow.State.NotDetected
        val wheel = health?.wheels?.firstOrNull { it.location == location }
        val rawLabel = buildString { appendLoc(location, false, capitalized = true) }
        WheelEntry(
            meta = wheelMeta(rawLabel),
            state = state,
            hasWarning = wheel?.warnings?.isNotEmpty() == true,
        )
    }

    val carBitmap = renderVehicleDashboard(entries, currentTime)
    val paneBuilder = Pane.Builder()
        .setImage(CarIcon.Builder(IconCompat.createWithBitmap(carBitmap)).build())

    if (entries.isEmpty()) {
        paneBuilder.addRow(Row.Builder().setTitle("Brak przypisanych kół").build())
    } else {
        entries.take(limit).forEach { entry ->
            val status = wheelStatus(entry, currentTime)
            val rowIcon = CarIcon.Builder(
                IconCompat.createWithBitmap(renderRowTyreIcon(status))
            ).build()

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(entry.meta.title)
                    .setImage(rowIcon, Row.IMAGE_TYPE_LARGE)
                    .addText(coloredRowText(entry, status, currentTime))
                    .build()
            )
        }
    }

    // Header intentionally absent: the same pane is valid inside TabContents.
    return PaneTemplate.Builder(paneBuilder.build()).build()
}

context(screen: Screen)
private fun carPaneLimit(): Int = runCatching {
    screen.carContext.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
}.getOrDefault(4)

private data class WheelMeta(
    val shortCode: String,
    val title: String,
    val cornerIndex: Int, // 0=FL, 1=FR, 2=RL, 3=RR, -1=other
)

private data class WheelEntry(
    val meta: WheelMeta,
    val state: TyreStatsStateFlow.State,
    val hasWarning: Boolean,
)

private enum class WheelStatus { OK, STALE_OR_WARN, ALERT, MISSING }

private data class StatusPalette(
    val accent: Int,
    val cardTopBg: Int,
    val cardBottomBg: Int,
    val pillBg: Int,
    val carColor: CarColor,
    val badgeText: String,
)

private fun StatusPaletteFor(status: WheelStatus): StatusPalette = when (status) {
    WheelStatus.OK -> StatusPalette(
        accent = 0xFF22E584.toInt(),
        cardTopBg = 0xFF163526.toInt(),
        cardBottomBg = 0xFF101C17.toInt(),
        pillBg = 0xFF1C3F2D.toInt(),
        carColor = CarColor.GREEN,
        badgeText = "● OK",
    )
    WheelStatus.STALE_OR_WARN -> StatusPalette(
        accent = 0xFFF59E0B.toInt(),
        cardTopBg = 0xFF332613.toInt(),
        cardBottomBg = 0xFF1C1710.toInt(),
        pillBg = 0xFF423117.toInt(),
        carColor = CarColor.YELLOW,
        badgeText = "● STARE",
    )
    WheelStatus.ALERT -> StatusPalette(
        accent = 0xFFEF4444.toInt(),
        cardTopBg = 0xFF3A1518.toInt(),
        cardBottomBg = 0xFF1E1113.toInt(),
        pillBg = 0xFF4E1B20.toInt(),
        carColor = CarColor.RED,
        badgeText = "● ALARM",
    )
    WheelStatus.MISSING -> StatusPalette(
        accent = 0xFF64748B.toInt(),
        cardTopBg = 0xFF1F2630.toInt(),
        cardBottomBg = 0xFF151920.toInt(),
        pillBg = 0xFF28313D.toInt(),
        carColor = CarColor.SECONDARY,
        badgeText = "● BRAK",
    )
}

private fun Int.withAlpha(alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (this and 0x00FFFFFF)

private fun wheelMeta(rawLabel: String): WheelMeta {
    val lower = rawLabel.lowercase()
    return when {
        "front" in lower && "left" in lower -> WheelMeta("LP", "Lewy przód", 0)
        "front" in lower && "right" in lower -> WheelMeta("PP", "Prawy przód", 1)
        "rear" in lower && "left" in lower -> WheelMeta("LT", "Lewy tył", 2)
        "rear" in lower && "right" in lower -> WheelMeta("PT", "Prawy tył", 3)
        "front" in lower -> WheelMeta("P", "Przód", -1)
        "rear" in lower -> WheelMeta("T", "Tył", -1)
        else -> WheelMeta(rawLabel.take(2).uppercase(), rawLabel, -1)
    }
}

private fun wheelStatus(entry: WheelEntry, currentTime: Double): WheelStatus {
    val state = entry.state
    if (state is TyreStatsStateFlow.State.Alerting) return WheelStatus.ALERT
    if (state is TyreStatsStateFlow.State.NotDetected) return WheelStatus.MISSING
    val timestamp = (state as TyreStatsStateFlow.State.Normal).timestamp
    val age = currentTime - timestamp
    return if (timestamp <= 0 || age > 120 || entry.hasWarning) {
        WheelStatus.STALE_OR_WARN
    } else {
        WheelStatus.OK
    }
}

private fun coloredRowText(
    entry: WheelEntry,
    status: WheelStatus,
    currentTime: Double,
): CharSequence {
    val palette = StatusPaletteFor(status)
    val state = entry.state
    if (state is TyreStatsStateFlow.State.NotDetected) {
        val text = "${palette.badgeText} · Brak sygnału czujnika"
        return SpannableString(text).apply {
            setSpan(ForegroundCarColorSpan.create(palette.carColor), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    val (pressureStr, tempStr, timestamp) = when (state) {
        is TyreStatsStateFlow.State.Normal -> Triple(
            state.pressure.string(state.pressureUnit),
            state.temperature.string(state.temperatureUnit),
            state.timestamp,
        )
        is TyreStatsStateFlow.State.Alerting -> Triple(
            state.pressure.string(state.pressureUnit),
            state.temperature.string(state.temperatureUnit),
            state.timestamp,
        )
        TyreStatsStateFlow.State.NotDetected -> Triple("", "", 0.0)
    }

    val prefix = "${palette.badgeText} · $pressureStr · $tempStr"
    val suffix = " · ${formatAge(timestamp, currentTime)}"
    val full = prefix + suffix
    return SpannableString(full).apply {
        setSpan(
            ForegroundCarColorSpan.create(palette.carColor),
            0,
            prefix.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        setSpan(
            ForegroundCarColorSpan.create(CarColor.SECONDARY),
            prefix.length,
            full.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

private fun formatAge(timestamp: Double, currentTime: Double): String {
    val age = currentTime - timestamp
    return when {
        timestamp <= 0 || age < 0 -> "?"
        age > 120 -> "${(age / 60).toInt()} min temu"
        else -> "${age.toInt()}s temu"
    }
}

private fun renderRowTyreIcon(status: WheelStatus): Bitmap {
    val size = 144
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val palette = StatusPaletteFor(status)

    // Dark rounded tile with subtle neon glow edge
    val tileRect = RectF(10f, 10f, size - 10f, size - 10f)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, tileRect.top, 0f, tileRect.bottom,
            palette.cardTopBg, palette.cardBottomBg,
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(tileRect, 28f, 28f, bgPaint)

    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = palette.accent.withAlpha(65)
        strokeWidth = 9f
    }
    canvas.drawRoundRect(tileRect, 28f, 28f, glowPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = palette.accent.withAlpha(190)
        strokeWidth = 3.5f
    }
    canvas.drawRoundRect(tileRect, 28f, 28f, borderPaint)

    // Rubber tyre + metallic 5-spoke alloy rim
    val cx = size / 2f - 4f
    val cy = size / 2f
    val rubberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            cx - 10f, cy - 10f, 48f,
            0xFF475569.toInt(), 0xFF1E242D.toInt(),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawCircle(cx, cy, 44f, rubberPaint)

    val treadRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF0F141B.toInt()
        strokeWidth = 3f
    }
    canvas.drawCircle(cx, cy, 38f, treadRing)

    val rimHole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141921.toInt() }
    canvas.drawCircle(cx, cy, 27f, rimHole)

    val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFCBD5E1.toInt()
        strokeWidth = 4.5f
    }
    canvas.drawCircle(cx, cy, 25f, rimPaint)

    val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    for (i in 0 until 5) {
        val angle = (i * 72.0 - 90.0) * PI / 180.0
        val ex = cx + (23f * cos(angle)).toFloat()
        val ey = cy + (23f * sin(angle)).toFloat()
        canvas.drawLine(cx, cy, ex, ey, spokePaint)
    }

    val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2E8F0.toInt() }
    canvas.drawCircle(cx, cy, 7f, hubPaint)

    // Glowing status indicator pill in bottom-right corner
    val dotRect = RectF(size - 40f, size - 48f, size - 20f, size - 20f)
    val dotHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent.withAlpha(90) }
    canvas.drawRoundRect(
        RectF(dotRect.left - 4f, dotRect.top - 4f, dotRect.right + 4f, dotRect.bottom + 4f),
        12f, 12f, dotHalo,
    )
    val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
    canvas.drawRoundRect(dotRect, 9f, 9f, dotFill)

    return bitmap
}

private fun renderVehicleDashboard(entries: List<WheelEntry>, currentTime: Double): Bitmap {
    val size = 720
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Assign entries to 4 corner slots (0=FL, 1=FR, 2=RL, 3=RR)
    val slots = arrayOfNulls<WheelEntry>(
