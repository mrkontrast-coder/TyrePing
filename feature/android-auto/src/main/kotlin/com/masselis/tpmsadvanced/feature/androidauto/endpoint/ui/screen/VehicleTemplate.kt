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
import androidx.car.app.model.Action
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

    paneBuilder.addAction(
        Action.Builder()
            .setTitle("Odśwież")
            .setOnClickListener { screen.invalidate() }
            .build()
    )

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
        carColor = CarColor.DEFAULT,
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
            ForegroundCarColorSpan.create(CarColor.DEFAULT),
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
    val slots = arrayOfNulls<WheelEntry>(4)
    entries.forEach { entry ->
        if (entry.meta.cornerIndex in 0..3 && slots[entry.meta.cornerIndex] == null) {
            slots[entry.meta.cornerIndex] = entry
        } else {
            val free = slots.indexOfFirst { it == null }
            if (free != -1) slots[free] = entry
        }
    }

    val wheelRects = listOf(
        RectF(262f, 146f, 286f, 228f), // FL
        RectF(434f, 146f, 458f, 228f), // FR
        RectF(262f, 490f, 286f, 572f), // RL
        RectF(434f, 490f, 458f, 572f), // RR
    )
    val cardRects = listOf(
        RectF(10f, 20f, 240f, 342f),   // FL
        RectF(480f, 20f, 710f, 342f),  // FR
        RectF(10f, 378f, 240f, 700f),  // RL
        RectF(480f, 378f, 710f, 700f), // RR
    )

    // 1. Draw glowing tyres and connectors behind the car silhouette
    slots.forEachIndexed { index, entry ->
        val wheelRect = wheelRects[index]
        val card = cardRects[index]
        val status = entry?.let { wheelStatus(it, currentTime) } ?: WheelStatus.MISSING
        val palette = StatusPaletteFor(status)

        drawTyreWithGlow(canvas, wheelRect, palette.accent, entry != null)
        if (entry != null) {
            drawConnector(canvas, card, wheelRect, isLeft = index % 2 == 0, accent = palette.accent)
        }
    }

    // 2. Draw sculpted metallic top-down car body in the center
    drawCarSilhouette(canvas)

    // 3. Draw 4 glowing data cards
    slots.forEachIndexed { index, entry ->
        if (entry != null) {
            val status = wheelStatus(entry, currentTime)
            drawTyreCard(canvas, cardRects[index], entry, status)
        }
    }

    return bitmap
}

private fun drawTyreWithGlow(canvas: Canvas, rect: RectF, accent: Int, active: Boolean) {
    if (active) {
        for (layer in 3 downTo 1) {
            val pad = layer * 4.5f
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent.withAlpha(24 * (4 - layer))
            }
            canvas.drawRoundRect(
                RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad),
                10f + pad / 2f, 10f + pad / 2f, glow,
            )
        }
    }

    val rubber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B222C.toInt() }
    canvas.drawRoundRect(rect, 7f, 7f, rubber)

    val tread = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (active) accent.withAlpha(140) else 0xFF475569.toInt()
        strokeWidth = 2.5f
    }
    var y = rect.top + 12f
    while (y < rect.bottom - 10f) {
        canvas.drawLine(rect.left + 3f, y, rect.right - 3f, y, tread)
        y += 11f
    }

    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = if (active) accent else 0xFF475569.toInt()
        strokeWidth = 3f
    }
    canvas.drawRoundRect(rect, 7f, 7f, rim)
}

private fun drawConnector(
    canvas: Canvas,
    card: RectF,
    wheel: RectF,
    isLeft: Boolean,
    accent: Int,
) {
    val startX = if (isLeft) card.right else card.left
    val endX = if (isLeft) wheel.left else wheel.right
    val startY = card.centerY()
    val endY = wheel.centerY()
    val midX = (startX + endX) / 2f

    val path = Path().apply {
        moveTo(startX, startY)
        lineTo(midX, startY)
        lineTo(endX, endY)
    }

    val glowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent.withAlpha(80)
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, glowLine)

    val coreLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, coreLine)

    val anchorDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    canvas.drawCircle(endX, endY, 5f, anchorDot)
}

private fun drawCarSilhouette(canvas: Canvas) {
    // Ground ambient shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            360f, 360f, 270f,
            0x99000000.toInt(), 0x00000000,
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawOval(RectF(254f, 66f, 466f, 654f), shadowPaint)

    // Side mirrors
    val mirrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF64748B.toInt() }
    canvas.drawRoundRect(RectF(264f, 240f, 290f, 260f), 9f, 9f, mirrorPaint)
    canvas.drawRoundRect(RectF(430f, 240f, 456f, 260f), 9f, 9f, mirrorPaint)

    // Sculpted car body path
    val bodyPath = Path().apply {
        moveTo(360f, 84f)
        cubicTo(404f, 84f, 430f, 102f, 434f, 146f)
        cubicTo(439f, 260f, 439f, 460f, 434f, 574f)
        cubicTo(430f, 618f, 408f, 636f, 360f, 636f)
        cubicTo(312f, 636f, 290f, 618f, 286f, 574f)
        cubicTo(281f, 460f, 281f, 260f, 286f, 146f)
        cubicTo(290f, 102f, 316f, 84f, 360f, 84f)
        close()
    }

    // Metallic 3D horizontal body shading
    val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            282f, 0f, 438f, 0f,
            intArrayOf(
                0xFF334155.toInt(),
                0xFF64748B.toInt(),
                0xFF94A3B8.toInt(),
                0xFF64748B.toInt(),
                0xFF334155.toInt(),
            ),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawPath(bodyPath, bodyFill)

    // Subtle hood reflection gradient
    val hoodHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            360f, 84f, 360f, 215f,
            0x44FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawPath(bodyPath, hoodHighlight)

    // Hood crease lines
    val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55E2E8F0
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    canvas.drawLine(318f, 114f, 326f, 204f, creasePaint)
    canvas.drawLine(402f, 114f, 394f, 204f, creasePaint)

    // Front windshield
    val frontGlass = Path().apply {
        moveTo(302f, 218f)
        quadTo(360f, 202f, 418f, 218f)
        lineTo(405f, 296f)
        quadTo(360f, 286f, 315f, 296f)
        close()
    }
    val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            302f, 205f, 418f, 296f,
            0xFF0F172A.toInt(), 0xFF1E293B.toInt(),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawPath(frontGlass, glassPaint)

    // Windshield specular sheen
    val glassSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(322f, 224f, 344f, 282f, glassSheen)

    // Sculpted roof panel
    val roofRect = RectF(315f, 294f, 405f, 474f)
    val roofPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            roofRect.left, 0f, roofRect.right, 0f,
            intArrayOf(0xFF475569.toInt(), 0xFF94A3B8.toInt(), 0xFF475569.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(roofRect, 18f, 18f, roofPaint)

    // Side cabin windows (narrow strips)
    val sideWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111827.toInt() }
    canvas.drawRoundRect(RectF(295f, 290f, 311f, 478f), 7f, 7f, sideWindowPaint)
    canvas.drawRoundRect(RectF(409f, 290f, 425f, 478f), 7f, 7f, sideWindowPaint)

    // Rear window
    val rearGlass = Path().apply {
        moveTo(315f, 476f)
        quadTo(360f, 484f, 405f, 476f)
        lineTo(414f, 542f)
        quadTo(360f, 554f, 306f, 542f)
        close()
    }
    canvas.drawPath(rearGlass, glassPaint)

    // Headlight LEDs (front)
    val headlightGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x77E0F2FE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }
    val headlightCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8FAFC.toInt()
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(300f, 102f, 326f, 92f, headlightGlow)
    canvas.drawLine(300f, 102f, 326f, 92f, headlightCore)
    canvas.drawLine(420f, 102f, 394f, 92f, headlightGlow)
    canvas.drawLine(420f, 102f, 394f, 92f, headlightCore)

    // Taillight LEDs (rear)
    val taillightGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88EF4444.toInt()
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }
    val taillightCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF87171.toInt()
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(304f, 622f, 336f, 629f, taillightGlow)
    canvas.drawLine(304f, 622f, 336f, 629f, taillightCore)
    canvas.drawLine(416f, 622f, 384f, 629f, taillightGlow)
    canvas.drawLine(416f, 622f, 384f, 629f, taillightCore)

    // Crisp metallic outer rim
    val bodyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCBD5E1.toInt().withAlpha(150)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawPath(bodyPath, bodyStroke)
}

private fun drawTyreCard(
    canvas: Canvas,
    card: RectF,
    entry: WheelEntry,
    status: WheelStatus,
) {
    val palette = StatusPaletteFor(status)
    val radius = 30f

    // Multi-pass soft neon outer glow
    for (layer in 3 downTo 1) {
        val spread = layer * 4.5f
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = palette.accent.withAlpha(26 * (4 - layer))
            strokeWidth = 5f + layer * 3.5f
        }
        canvas.drawRoundRect(
            RectF(card.left - spread / 2f, card.top - spread / 2f, card.right + spread / 2f, card.bottom + spread / 2f),
            radius + spread / 3f,
            radius + spread / 3f,
            glowPaint,
        )
    }

    // Dark vertical gradient card fill
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            card.left, card.top, card.left, card.bottom,
            palette.cardTopBg, palette.cardBottomBg,
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(card, radius, radius, bgPaint)

    // Crisp glowing border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        style = Paint.Style.STROKE
        strokeWidth = 5.5f
    }
    canvas.drawRoundRect(card, radius, radius, borderPaint)

    // Top-left wheel short code (LP / PP / LT / PT)
    val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        textAlign = Paint.Align.LEFT
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText(entry.meta.shortCode, card.left + 26f, card.top + 52f, codePaint)

    // Top-right mini battery icon (visual sensor-active indicator)
    drawMiniBattery(canvas, card.right - 50f, card.top + 24f, palette.accent, status)

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

    // Center big pressure value
    val pressurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (status == WheelStatus.MISSING) 0xFF94A3B8.toInt() else 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = if (pressureValue.length <= 4) 84f else 62f
    }
    canvas.drawText(pressureValue, card.centerX(), card.top + 152f, pressurePaint)

    if (pressureUnit.isNotEmpty()) {
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            textAlign = Paint.Align.CENTER
            textSize = 30f
        }
        canvas.drawText(pressureUnit, card.centerX(), card.top + 190f, unitPaint)
    }

    // Bottom temperature pill
    val pillRect = RectF(card.left + 18f, card.top + 220f, card.right - 18f, card.bottom - 18f)
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.pillBg }
    canvas.drawRoundRect(pillRect, 20f, 20f, pillPaint)

    val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = palette.accent.withAlpha(75)
        strokeWidth = 2f
    }
    canvas.drawRoundRect(pillRect, 20f, 20f, pillBorder)

    val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (status == WheelStatus.MISSING) 0xFF94A3B8.toInt() else 0xFFF8FAFC.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 38f
    }
    canvas.drawText(tempText, card.centerX(), pillRect.centerY() + 14f, tempPaint)
}

private fun drawMiniBattery(
    canvas: Canvas,
    left: Float,
    top: Float,
    accent: Int,
    status: WheelStatus,
) {
    val cap = RectF(left + 7f, top, left + 17f, top + 5f)
    val body = RectF(left, top + 5f, left + 24f, top + 35f)
    val outlineColor = if (status == WheelStatus.MISSING) 0xFF64748B.toInt() else 0xFFCBD5E1.toInt()

    val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outlineColor }
    canvas.drawRoundRect(cap, 2f, 2f, capPaint)

    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = outlineColor
        strokeWidth = 2.5f
    }
    canvas.drawRoundRect(body, 4f, 4f, outlinePaint)

    val fillRatio = when (status) {
        WheelStatus.OK -> 0.82f
        WheelStatus.STALE_OR_WARN -> 0.45f
        WheelStatus.ALERT -> 0.25f
        WheelStatus.MISSING -> 0f
    }
    if (fillRatio > 0f) {
        val innerHeight = (body.height() - 8f) * fillRatio
        val fillRect = RectF(
            body.left + 4f,
            body.bottom - 4f - innerHeight,
            body.right - 4f,
            body.bottom - 4f,
        )
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawRoundRect(fillRect, 2f, 2f, fillPaint)
    }
}

internal fun autoReading(state: TyreStatsStateFlow.State): String = when (state) {
    TyreStatsStateFlow.State.NotDetected -> "Sensor not detected"
    is TyreStatsStateFlow.State.Normal ->
        "${state.pressure.string(state.pressureUnit)} · ${state.temperature.string(state.temperatureUnit)}"
    is TyreStatsStateFlow.State.Alerting ->
        "WARNING · ${state.pressure.string(state.pressureUnit)} · ${state.temperature.string(state.temperatureUnit)}"
}

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
