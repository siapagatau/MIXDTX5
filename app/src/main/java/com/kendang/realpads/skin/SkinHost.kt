package com.kendang.realpads.skin

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val SkinBg: Color get() = com.kendang.realpads.BackdropColor

/**
 * Menampilkan skin aktif memenuhi area (stretch) + menangani sentuhan multi-touch.
 * Knob: geser vertikal. Slider: ikuti jari sepanjang jalur. Toggle/tombol: tap.
 * Semua nilai lewat [SkinState] (tahap 3 menghubungkannya ke engine audio).
 */
@Composable
fun SkinHost(skin: SkinDefinition, state: SkinState, modifier: Modifier = Modifier, transparentBg: Boolean = false) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var renderedSize by remember(skin, transparentBg) { mutableStateOf<IntSize?>(null) }
    var core by remember(skin, transparentBg) { mutableStateOf<SkinCore?>(null) }
    val layers = remember(skin, transparentBg) { mutableStateMapOf<String, Bitmap>() }

    LaunchedEffect(skin) { state.initDefaults(skin) }

    LaunchedEffect(skin, size, transparentBg) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val cur = renderedSize
        if (core != null && cur != null && SkinRenderCache.fits(cur.width, cur.height, size.width.toFloat(), size.height.toFloat())) {
            return@LaunchedEffect
        }
        val spec = RenderSpec(size.width, size.height, transparentBg)
        val scope = this
        try {
            withContext(Dispatchers.Default) {
                val cached = SkinRenderCache.load(context, skin, spec)
                if (cached != null) {
                    core = cached.core
                    layers.clear(); layers.putAll(cached.layers)
                    renderedSize = size
                    return@withContext
                }
                val c = SkinRenderer.buildCore(skin, spec)
                core = c
                layers.clear()
                renderedSize = size
                val collected = HashMap<String, Bitmap>()
                SkinRenderer.buildDetails(skin, spec, { scope.isActive }) { k, b -> collected[k] = b; layers[k] = b }
                if (scope.isActive) SkinRenderCache.save(context, skin, spec, c, collected)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SkinManager.reportRenderFailure(e.message ?: e.javaClass.simpleName)
        }
    }

    val bmpPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val hiPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF } }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (transparentBg) Modifier else Modifier.background(SkinBg))
            .onSizeChanged { size = it }
            .pointerInput(skin) {
                awaitPointerEventScope {
                    val owner = HashMap<PointerId, SkinControl>()
                    val lastY = HashMap<PointerId, Float>()
                    val dragRange = 180f * density
                    while (true) {
                        val event = awaitPointerEvent()
                        val zone = SkinZone.of(skin, size.width.toFloat(), size.height.toFloat())
                        for (ch in event.changes) {
                            val pid = ch.id
                            if (ch.pressed && !ch.previousPressed) {
                                val c = HitTester.of(skin, zone).hit(ch.position.x, ch.position.y, density) ?: continue
                                when (c.type) {
                                    ControlType.BUTTON -> {
                                        owner[pid] = c; state.pressed[c.id] = true
                                        state.onButton(c)
                                    }
                                    ControlType.TOGGLE -> {
                                        val v = !(state.toggles[c.id] ?: false)
                                        state.toggles[c.id] = v
                                        state.onToggle(c, v)
                                    }
                                    ControlType.ROTARY -> { owner[pid] = c; lastY[pid] = ch.position.y }
                                    ControlType.SLIDER -> {
                                        owner[pid] = c
                                        val v = HitTester.sliderValue(c, zone, ch.position.x, ch.position.y)
                                        state.values[c.id] = v; state.onValue(c, v)
                                    }
                                    else -> {}
                                }
                                ch.consume()
                            } else if (ch.pressed) {
                                val c = owner[pid] ?: continue
                                when (c.type) {
                                    ControlType.ROTARY -> {
                                        val prev = lastY[pid] ?: ch.position.y
                                        val v = ((state.values[c.id] ?: 0.5f) - (ch.position.y - prev) / dragRange).coerceIn(0f, 1f)
                                        lastY[pid] = ch.position.y
                                        state.values[c.id] = v; state.onValue(c, v)
                                    }
                                    ControlType.SLIDER -> {
                                        val v = HitTester.sliderValue(c, zone, ch.position.x, ch.position.y)
                                        state.values[c.id] = v; state.onValue(c, v)
                                    }
                                    else -> {}
                                }
                                ch.consume()
                            } else if (ch.previousPressed) {
                                val c = owner.remove(pid)
                                lastY.remove(pid)
                                if (c != null && c.type == ControlType.BUTTON) state.pressed[c.id] = false
                                ch.consume()
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cr = core ?: return@Canvas
            val zone = SkinZone.of(skin, this.size.width, this.size.height)
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                // semua digambar di koordinat SVG; matriks ini men-stretch zona skin ke seluruh layar
                nc.translate(zone.dst.l, zone.dst.t)
                nc.scale(zone.sx, zone.sy)
                nc.translate(-zone.svg.x, -zone.svg.y)

                nc.drawBitmap(cr.base, null, RectF(zone.svg.x, zone.svg.y, zone.svg.right, zone.svg.bottom), bmpPaint)

                for (c in skin.controls) {
                    val hit = c.hit
                    when (c.type) {
                        ControlType.ROTARY -> {
                            val r = c.rotary ?: continue
                            val bmp = cr.handles[c.id] ?: continue
                            val reg = r.region(hit ?: continue)
                            val v = state.values[c.id] ?: r.defaultValue
                            nc.save()
                            nc.rotate(r.angleMin + (r.angleMax - r.angleMin) * v, r.cx, r.cy)
                            nc.drawBitmap(bmp, null, RectF(reg.x, reg.y, reg.right, reg.bottom), bmpPaint)
                            nc.restore()
                        }
                        ControlType.SLIDER -> {
                            val s = c.slider ?: continue
                            val bmp = cr.handles[c.id] ?: continue
                            val h = hit ?: continue
                            val v = state.values[c.id] ?: s.defaultValue
                            val t = v - s.defaultValue
                            nc.save()
                            nc.translate(t * (s.x2 - s.x1), t * (s.y2 - s.y1))
                            nc.drawBitmap(bmp, null, RectF(h.x, h.y, h.right, h.bottom), bmpPaint)
                            nc.restore()
                        }
                        ControlType.TOGGLE -> {
                            val h = hit ?: continue
                            if (state.toggles[c.id] == true) {
                                layers[LayerKey.toggleOn(c.id)]?.let { nc.drawBitmap(it, null, RectF(h.x, h.y, h.right, h.bottom), bmpPaint) }
                            }
                        }
                        ControlType.BUTTON -> {
                            val h = hit ?: continue
                            if (state.pressed[c.id] == true) {
                                val l = layers[LayerKey.buttonActive(c.id)]
                                if (l != null) nc.drawBitmap(l, null, RectF(h.x, h.y, h.right, h.bottom), bmpPaint)
                                else nc.drawRoundRect(RectF(h.x, h.y, h.right, h.bottom), 10f, 10f, hiPaint)
                            }
                        }
                        ControlType.METER -> {
                            val m = c.meter ?: continue
                            val lit = ((state.meters[c.id] ?: 0f).coerceIn(0f, 1f) * m.segmentIds.size).toInt()
                            for (i in 0 until lit) {
                                val r = m.segRects.getOrNull(i) ?: continue
                                layers[LayerKey.meterSeg(c.id, i + 1)]?.let { nc.drawBitmap(it, null, RectF(r.x, r.y, r.right, r.bottom), bmpPaint) }
                            }
                        }
                        ControlType.DISPLAY -> {
                            val d = c.display ?: continue
                            val lines = state.displayText(c)
                            if (lines.isNotEmpty()) {
                                val lineH = d.rect.h / lines.size
                                textPaint.color = com.kendang.realpads.ThemeState.uiAccent.toArgb()
                                textPaint.textSize = lineH * 0.62f
                                lines.forEachIndexed { i, s ->
                                    nc.drawText(s, d.rect.x + 10f, d.rect.y + lineH * (i + 1) - lineH * 0.26f, textPaint)
                                }
                            }
                        }
                    }
                }
                nc.restore()
            }
        }
    }
}
