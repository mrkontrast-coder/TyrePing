package com.masselis.tpmsadvanced.feature.androidauto.endpoint.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

/** Stable row titles/counts allow value refreshes without consuming the host's navigation quota. */
context(screen: Screen)
@Suppress("FunctionName", "FunctionNaming", "MagicNumber")
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
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(entry.meta.title)
                    .addText(coloredRowText(entry, currentTime))
                    .build()
            )
        }
    }

    // Header intentionally absent: the same pane is valid inside TabContents.
    return PaneTemplate.Builder(paneBuilder.build()).build()
}

context(screen: Screen)
@Suppress("MagicNumber")
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

@Suppress("MagicNumber")
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

@Suppress("MagicNumber")
private fun coloredRowText(entry: WheelEntry, currentTime: Double): CharSequence {
    val status = wheelStatus(entry, currentTime)
    val state = entry.state
    val text = when (state) {
        TyreStatsStateFlow.State.NotDetected -> "Brak sygnału"
        is TyreStatsStateFlow.State.Normal -> {
            val ageText = formatAge(state.timestamp, currentTime)
            "${state.pressure.string(state.pressureUnit)} · ${state.temperature.string(state.temperatureUnit)} · $ageText"
        }
        is TyreStatsStateFlow.State.Alerting -> {
            val ageText = formatAge(state.timestamp, currentTime)
            "ALARM · ${state.pressure.string(state.pressureUnit)} · ${state.temperature.string(state.temperatureUnit)} · $ageText"
        }
    }
    val color = when (status) {
        WheelStatus.OK -> CarColor.GREEN
        WheelStatus.STALE_OR_WARN -> CarColor.YELLOW
        WheelStatus.ALERT -> CarColor.RED
        WheelStatus.MISSING -> CarColor.SECONDARY
    }
    return SpannableString(text).apply {
        setSpan(ForegroundCarColorSpan.create(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

@Suppress("MagicNumber")
private fun formatAge(timestamp: Double, currentTime: Double): String {
    val age = currentTime - timestamp
    return when {
        timestamp <= 0 || age < 0 -> "?"
        age > 120 -> "${(age / 60).toInt()} min"
        else -> "${age.toInt()}s"
    }
}

@Suppress("MagicNumber", "LongMethod")
private fun renderVehicleDashboard(entries: List<WheelEntry>, currentTime: Double): Bitmap {
    val size = 640
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Assign entries to 4 corner slots (0=FL, 1=FR, 2=RL, 3=RR)
    val slots = arrayOfNulls<WheelEntry>(4)
    entries.forEach { entry ->
        if (entry.meta.cornerIndex in 0..3 && slots[entry.meta.cornerIndex] == null) {
            slots[entry.meta.cornerIndex] = entry
        } else {
            val free = slots.indexOfFirst { it == null }
            if (free != -1) slots[free] = entry
        }
    }

    // Center top-down car silhouette
    val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF232933.toInt()
        style = Paint.Style.FILL
    }
    val bodyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF334155.toInt()
        style = Paint.Style.FILL
    }

    canvas.drawRoundRect(RectF(266f, 70f, 374f, 570f), 44f, 44f, bodyFill)
    canvas.drawRoundRect(RectF(266f, 70f, 374f, 570f), 44f, 44f, bodyStroke)
    canvas.drawRoundRect(RectF(282f, 150f, 358f, 224f), 16f, 16f, glassPaint) // front windshield
    canvas.drawRoundRect(RectF(286f, 435f, 354f, 488f), 14f, 14f, glassPaint) // rear window

    val wheelRects = listOf(
        RectF(246f, 125f, 264f, 215f), // FL
        RectF(376f, 125f, 394f, 215f), // FR
        RectF(246f, 425f, 264f, 515f), // RL
        RectF(376f, 425f, 394f, 515f), // RR
    )
    val cardRects = listOf(
        RectF(8f, 32f, 228f, 300f),   // FL
        RectF(412f, 32f, 632f, 300f), // FR
        RectF(8f, 340f, 228f, 608f),  // RL
        RectF(412f, 340f, 632f, 608f),// RR
    )

    slots.forEachIndexed { index, entry ->
        val wheelRect = wheelRects[index]
        val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        if (entry == null) {
            wheelPaint.color = 0xFF475569.toInt()
            canvas.drawRoundRect(wheelRect, 6f, 6f, wheelPaint)
            return@forEachIndexed
        }

        val status = wheelStatus(entry, currentTime)
        val (accent, cardBg, pillBg) = when (status) {
            WheelStatus.ALERT -> Triple(0xFFEF4444.toInt(), 0xFF2B1416.toInt(), 0xFF471A1E.toInt())
            WheelStatus.MISSING -> Triple(0xFF64748B.toInt(), 0xFF1A1F26.toInt(), 0xFF262D37.toInt())
            WheelStatus.STALE_OR_WARN -> Triple(0xFFF59E0B.toInt(), 0xFF241E14.toInt(), 0xFF3B2F1A.toInt())
            WheelStatus.OK -> Triple(0xFF22C55E.toInt(), 0xFF15201A.toInt(), 0xFF1E3527.toInt())
        }

        // Highlight wheel & connector line
        wheelPaint.color = accent
        canvas.drawRoundRect(wheelRect, 6f, 6f, wheelPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            strokeWidth = 5f
        }
        val card = cardRects[index]
        val isLeft = index % 2 == 0
        val lineY = wheelRect.centerY()
        if (isLeft) {
            canvas.drawLine(card.right, lineY, wheelRect.left, lineY, linePaint)
        } else {
            canvas.drawLine(wheelRect.right, lineY, card.left, lineY, linePaint)
        }

        // Card container
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBg
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        canvas.drawRoundRect(card, 28f, 28f, bgPaint)
        canvas.drawRoundRect(card, 28f, 28f, borderPaint)

        // Wheel short code (LP / PP / LT / PT)
        val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textAlign = Paint.Align.CENTER
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(entry.meta.shortCode, card.centerX(), card.top + 46f, codePaint)

        val (pressureValue, pressureUnit, tempText) = when (val s = entry.state) {
            TyreStatsStateFlow.State.NotDetected -> Triple("--.-", "", "Brak")
            is TyreStatsStateFlow.State.Normal -> {
                val parts = s.pressure.string(s.pressureUnit).trim().split(" ", limit = 2)
                Triple(parts.firstOrNull().orEmpty(), parts.getOrNull(1).orEmpty(), s.temperature.string(s.temperatureUnit))
            }
            is TyreStatsStateFlow.State.Alerting -> {
                val parts = s.pressure.string(s.pressureUnit).trim().split(" ", limit = 2)
                Triple(parts.firstOrNull().orEmpty(), parts.getOrNull(1).orEmpty(), s.temperature.string(s.temperatureUnit))
            }
        }

        // Big pressure value
        val pressurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (status == WheelStatus.MISSING) 0xFF94A3B8.toInt() else 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (pressureValue.length <= 4) 74f else 56f
        }
        canvas.drawText(pressureValue, card.centerX(), card.top + 132f, pressurePaint)

        if (pressureUnit.isNotEmpty()) {
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFCBD5E1.toInt()
                textAlign = Paint.Align.CENTER
                textSize = 28f
            }
            canvas.drawText(pressureUnit, card.centerX(), card.top + 168f, unitPaint)
        }

        // Temperature pill
        val pillRect = RectF(card.left + 20f, card.top + 190f, card.right - 20f, card.bottom - 20f)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pillBg
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(pillRect, 20f, 20f, pillPaint)

        val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (status == WheelStatus.MISSING) 0xFF94A3B8.toInt() else 0xFFF8FAFC.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 32f
        }
        canvas.drawText(tempText, card.centerX(), card.top + 232f, tempPaint)
    }

    return bitmap
}

internal fun autoReading(state: TyreStatsStateFlow.State): String = when (state) {
    TyreStatsStateFlow.State.NotDetected -> "Sensor not detected"
    is TyreStatsStateFlow.State.Normal ->
        "${state.pressure.string(state.pressureUnit)} · ${state.temperature.string(state.temperatureUnit)}"
    is TyreStatsStateFlow.State.Alerting ->
        "WARNING · ${state.pressure.string(state.pressureUnit)} · ${state.temperature.string(state.temperatureUnit)}"
}

@Suppress("MagicNumber")
internal fun autoFreshness(state: TyreStatsStateFlow.State, currentTime: Double): String {
    val timestamp = when (state) {
        TyreStatsStateFlow.State.NotDetected -> return "Assign or wake sensor while parked"
        is TyreStatsStateFlow.State.Normal -> state.timestamp
        is TyreStatsStateFlow.State.Alerting -> state.timestamp
    }
    val age = currentTime - timestamp
    return when {
        timestamp <= 0 || age < 0 -> "Freshness unconfirmed"
        age > 120 -> "STALE · last seen ${(age / 60).toInt()} min ago"
        else -> "Last seen ${age.toInt()}s ago"
    }
}
