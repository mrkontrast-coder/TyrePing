package com.masselis.tpmsadvanced.feature.androidauto.endpoint.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.core.graphics.drawable.IconCompat
import com.masselis.tpmsadvanced.core.common.now
import com.masselis.tpmsadvanced.feature.androidauto.endpoint.ui.viewmodel.TabScreenViewModel.State
import com.masselis.tpmsadvanced.feature.main.interfaces.composable.appendLoc
import com.masselis.tpmsadvanced.feature.main.usecase.FleetHealth
import com.masselis.tpmsadvanced.feature.main.usecase.TyreStatsStateFlow

/** Stable item titles/counts allow value refreshes without consuming the host's navigation quota. */
context(screen: Screen)
@Suppress("FunctionName", "FunctionNaming", "MagicNumber")
internal fun VehicleTemplate(tab: State.Tabs.Tab.Displayed, rowLimit: Int = carGridLimit()): GridTemplate {
    val limit = rowLimit.coerceAtLeast(1)
    val health = FleetHealth.values.value.firstOrNull { it.vehicle.uuid == tab.vehicle.uuid }
    val currentTime = now()
    val items = ItemList.Builder()

    tab.vehicle.kind.locations.take(limit).forEach { location ->
        val state = tab.tyres[location]?.second ?: TyreStatsStateFlow.State.NotDetected
        val wheel = health?.wheels?.firstOrNull { it.location == location }
        val rawLabel = buildString { appendLoc(location, false, capitalized = true) }
        val meta = wheelMeta(rawLabel)
        val bitmap = renderTyreCard(
            meta = meta,
            state = state,
            currentTime = currentTime,
            hasWarning = wheel?.warnings?.isNotEmpty() == true,
        )
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()

        items.addItem(
            GridItem.Builder()
                .setTitle(meta.title)
                .setText(cardSubtitle(state, currentTime, wheel?.warnings?.firstOrNull()))
                .setImage(icon, GridItem.IMAGE_TYPE_LARGE)
                .build()
        )
    }

    // Header intentionally absent: the same grid is valid inside TabContents.
    return GridTemplate.Builder().setSingleList(items.build()).build()
}

context(screen: Screen)
@Suppress("MagicNumber")
private fun carGridLimit(): Int = runCatching {
    screen.carContext.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
}.getOrDefault(6)

private data class WheelMeta(
    val shortCode: String,
    val title: String,
    val cornerIndex: Int, // 0=FL, 1=FR, 2=RL, 3=RR, -1=other
)

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
private fun cardSubtitle(
    state: TyreStatsStateFlow.State,
    currentTime: Double,
    warning: String?,
): String {
    if (state is TyreStatsStateFlow.State.NotDetected) return "Brak czujnika"
    val timestamp = when (state) {
        is TyreStatsStateFlow.State.Normal -> state.timestamp
        is TyreStatsStateFlow.State.Alerting -> state.timestamp
        TyreStatsStateFlow.State.NotDetected -> 0.0
    }
    val age = currentTime - timestamp
    val freshness = when {
        timestamp <= 0 || age < 0 -> "Oczekiwanie"
        age > 120 -> "Stare · ${(age / 60).toInt()} min"
        else -> "${age.toInt()}s temu"
    }
    return when {
        state is TyreStatsStateFlow.State.Alerting -> "ALARM · $freshness"
        warning != null -> "Uwaga · $freshness"
        else -> freshness
    }
}

@Suppress("MagicNumber", "LongMethod")
private fun renderTyreCard(
    meta: WheelMeta,
    state: TyreStatsStateFlow.State,
    currentTime: Double,
    hasWarning: Boolean,
): Bitmap {
    val size = 320
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val isAlert = state is TyreStatsStateFlow.State.Alerting
    val isMissing = state is TyreStatsStateFlow.State.NotDetected
    val timestamp = when (state) {
        is TyreStatsStateFlow.State.Normal -> state.timestamp
        is TyreStatsStateFlow.State.Alerting -> state.timestamp
        TyreStatsStateFlow.State.NotDetected -> 0.0
    }
    val age = currentTime - timestamp
    val isStale = !isMissing && (timestamp <= 0 || age > 120)

    val accentColor: Int
    val cardBgColor: Int
    val pillBgColor: Int
    when {
        isAlert -> {
            accentColor = 0xFFEF4444.toInt()
            cardBgColor = 0xFF2B1416.toInt()
            pillBgColor = 0xFF471A1E.toInt()
        }
        isMissing -> {
            accentColor = 0xFF64748B.toInt()
            cardBgColor = 0xFF1A1F26.toInt()
            pillBgColor = 0xFF262D37.toInt()
        }
        isStale || hasWarning -> {
            accentColor = 0xFFF59E0B.toInt()
            cardBgColor = 0xFF241E14.toInt()
            pillBgColor = 0xFF3B2F1A.toInt()
        }
        else -> {
            accentColor = 0xFF22C55E.toInt()
            cardBgColor = 0xFF15201A.toInt()
            pillBgColor = 0xFF1E3527.toInt()
        }
    }

    val cardRect = RectF(8f, 8f, size - 8f, size - 8f)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cardBgColor
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    canvas.drawRoundRect(cardRect, 36f, 36f, bgPaint)
    canvas.drawRoundRect(cardRect, 36f, 36f, borderPaint)

    // Top-left wheel code (LP, PP, LT, PT)
    val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText(meta.shortCode, 34f, 66f, codePaint)

    // Top-right mini car silhouette with highlighted active wheel
    if (meta.cornerIndex in 0..3) {
        val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        canvas.drawRoundRect(RectF(245f, 30f, 275f, 82f), 10f, 10f, chassisPaint)

        val wheelRects = listOf(
            RectF(234f, 35f, 243f, 52f), // FL
            RectF(277f, 35f, 286f, 52f), // FR
            RectF(234f, 60f, 243f, 77f), // RL
            RectF(277f, 60f, 286f, 77f), // RR
        )
        val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        wheelRects.forEachIndexed { index, rect ->
            wheelPaint.color = if (index == meta.cornerIndex) accentColor else 0xFF475569.toInt()
            canvas.drawRoundRect(rect, 3f, 3f, wheelPaint)
        }
    }

    // Center pressure value & unit
    val (pressureValue, pressureUnit, tempText) = when (state) {
        TyreStatsStateFlow.State.NotDetected -> Triple("--.-", "", "Brak sygnału")
        is TyreStatsStateFlow.State.Normal -> {
            val parts = state.pressure.string(state.pressureUnit).trim().split(" ", limit = 2)
            Triple(
                parts.firstOrNull().orEmpty(),
                parts.getOrNull(1).orEmpty(),
                state.temperature.string(state.temperatureUnit),
            )
        }
        is TyreStatsStateFlow.State.Alerting -> {
            val parts = state.pressure.string(state.pressureUnit).trim().split(" ", limit = 2)
            Triple(
                parts.firstOrNull().orEmpty(),
                parts.getOrNull(1).orEmpty(),
                state.temperature.string(state.temperatureUnit),
            )
        }
    }

    val pressurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isMissing) 0xFF94A3B8.toInt() else 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = if (pressureValue.length <= 4) 84f else 66f
    }
    canvas.drawText(pressureValue, size / 2f, 178f, pressurePaint)

    if (pressureUnit.isNotEmpty()) {
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }
        canvas.drawText(pressureUnit, size / 2f, 214f, unitPaint)
    }

    // Bottom temperature pill
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pillBgColor
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(RectF(36f, 236f, size - 36f, 290f), 24f, 24f, pillPaint)

    val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isMissing) 0xFF94A3B8.toInt() else 0xFFF8FAFC.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 30f
    }
    canvas.drawText(tempText, size / 2f, 274f, tempPaint)

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
