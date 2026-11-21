package org.kdx.shell.apps

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Turns touchscreen gestures into X mouse events for the Linux Display (X apps only understand a mouse over VNC).
 *
 * Touch mode:
 *  - tap = click; a second tap within [doubleTapMs] lands exactly on the first one, so apps see a double-click
 *  - drag = scroll that follows the finger (one wheel step per [scrollStepPx] screen pixels), with fling
 *  - long press = right-click (with [haptic] when it triggers); long press then drag = drag with the left button
 *  - two fingers: tap = right-click, move = scroll (or pan when zoomed in), pinch = zoom the view
 *  - three-finger tap = middle-click (paste)
 * Mouse mode: drag = drag with the left button, tap = click, long press = right-click.
 *
 * Positions are screen pixels; [toFb] maps them to X screen coordinates. Pointer events go to [out] as
 * (x, y, button mask): 1 left, 2 middle, 4 right, 8/16 wheel up/down, 32/64 wheel left/right.
 */
class TouchTranslator(
    private val slopPx: Float,
    private val toFb: (Offset) -> Pair<Int, Int>,
    private val out: (Int, Int, Int) -> Unit,
    /** Screen pixels the finger moves for one wheel step (~ how far an app scrolls per step, at the current scale). */
    var scrollStepPx: () -> Float = { 60f },
    var touchMode: () -> Boolean = { true },
    var zoomed: () -> Boolean = { false },
    private val onPinch: (factor: Float, centroid: Offset) -> Unit = { _, _ -> },
    private val onPan: (Offset) -> Unit = {},
    private val haptic: () -> Unit = {},
    private val mark: (Offset, Kind) -> Unit = { _, _ -> },
    /** Called with the X position of a double-tap (after its two clicks were sent). */
    var onDoubleTap: (Int, Int) -> Unit = { _, _ -> },
    private val longPressMs: Long = 450,
    private val doubleTapMs: Long = 350,
) {
    enum class Kind { TAP, RIGHT, MIDDLE, HOLD }

    private val pointers = LinkedHashMap<Long, Offset>()
    private var maxPointers = 0
    private var start = Offset.Zero
    private var t0 = 0L
    private var moved = false
    private var held = false
    private var dragging = false
    private var fb = 0 to 0

    // two-finger state
    private var multiMode = 0 // 0 undecided, 1 scroll/pan, 2 pinch
    private var startDist = 0f; private var lastDist = 0f
    private var startCen = Offset.Zero; private var lastCen = Offset.Zero
    private var multiMoved = false

    // scrolling: leftover finger movement that didn't add up to a whole wheel step yet
    private var remX = 0f; private var remY = 0f
    private var scrollAt = 0 to 0
    private val samples = ArrayDeque<Triple<Long, Float, Float>>()
    private var flingVx = 0f; private var flingVy = 0f; private var lastTick = 0L

    // double tap
    private var lastTapAt = 0L; private var lastTapPos = Offset.Zero; private var lastTapFb = 0 to 0

    val flinging get() = flingVx != 0f || flingVy != 0f

    /** X coordinates for a real mouse at [p]. */
    fun mapForMouse(p: Offset) = toFb(p)

    fun down(id: Long, p: Offset, now: Long) {
        if (pointers.isEmpty()) {
            stopFling()
            start = p; t0 = now; moved = false; held = false; dragging = false; maxPointers = 0
            multiMode = 0; multiMoved = false; remX = 0f; remY = 0f; samples.clear()
            fb = toFb(p)
            out(fb.first, fb.second, 0) // move first, so hover effects and menus see where the click is
        }
        pointers[id] = p
        maxPointers = maxOf(maxPointers, pointers.size)
        if (pointers.size == 2) {
            if (dragging) { out(fb.first, fb.second, 0); dragging = false }
            val (a, b) = two()
            startDist = dist(a, b); lastDist = startDist
            startCen = (a + b) / 2f; lastCen = startCen
            remX = 0f; remY = 0f; samples.clear()
        }
    }

    fun move(id: Long, p: Offset, now: Long) {
        if (id !in pointers) return
        pointers[id] = p
        if (maxPointers >= 2) { if (pointers.size >= 2) multiMove(now); return }
        if (!moved && (p - start).getDistance() > slopPx) {
            moved = true
            if (!touchMode() || held) { dragging = true; out(fb.first, fb.second, 1) }
            else { scrollAt = fb; lastScroll = p; record(now, p) }
        }
        if (!moved) return
        if (dragging) { fb = toFb(p); out(fb.first, fb.second, 1); return }
        record(now, p)
        scrollBy(p.x - lastScroll.x, p.y - lastScroll.y)
        lastScroll = p
    }
    private var lastScroll = Offset.Zero

    fun up(id: Long, p: Offset, now: Long) {
        if (id !in pointers) return
        pointers[id] = p
        if (pointers.size == 1) finish(p, now)
        pointers.remove(id)
    }

    fun cancel() {
        if (dragging) out(fb.first, fb.second, 0)
        pointers.clear(); dragging = false
    }

    /** Call regularly (every frame): long-press detection and fling. */
    fun tick(now: Long) {
        if (pointers.size == 1 && maxPointers == 1 && !moved && !held && now - t0 >= longPressMs) {
            held = true; haptic(); mark(start, Kind.HOLD)
        }
        if (flinging) {
            val dt = (now - lastTick).coerceIn(1, 50) / 1000f
            lastTick = now
            scrollBy(flingVx * dt, flingVy * dt, at = scrollAt)
            val decay = exp(-dt / 0.35f) // ~Android's fling friction
            flingVx *= decay; flingVy *= decay
            if (hypot(flingVx, flingVy) < 120f) stopFling()
        }
    }

    fun stopFling() { flingVx = 0f; flingVy = 0f }

    private fun finish(p: Offset, now: Long) {
        when {
            maxPointers >= 3 -> if (!multiMoved) { click(startCen, 2); mark(startCen, Kind.MIDDLE) }
            maxPointers == 2 -> if (!multiMoved && multiMode == 0) { click(startCen, 4); mark(startCen, Kind.RIGHT) }
            dragging -> { fb = toFb(p); out(fb.first, fb.second, 0) }
            moved -> { // scroll: keep going if the finger was flicked
                record(now, p)
                val (vx, vy) = velocity()
                if (hypot(vx, vy) > slopPx * 40) { flingVx = vx; flingVy = vy; lastTick = now }
            }
            held || now - t0 >= longPressMs -> { out(fb.first, fb.second, 4); out(fb.first, fb.second, 0); mark(start, Kind.RIGHT) }
            else -> {
                // a quick second tap near the first one lands exactly on it: apps see a double-click
                val double = now - lastTapAt < doubleTapMs && (p - lastTapPos).getDistance() < slopPx * 3
                val at = if (double) lastTapFb else fb
                out(at.first, at.second, 0); out(at.first, at.second, 1); out(at.first, at.second, 0)
                lastTapAt = if (double) 0L else now; lastTapPos = p; lastTapFb = at
                if (double) onDoubleTap(at.first, at.second)
                mark(start, Kind.TAP)
            }
        }
    }

    private fun multiMove(now: Long) {
        val (a, b) = two()
        val d = dist(a, b); val c = (a + b) / 2f
        if (multiMode == 0) {
            val pinch = abs(d - startDist); val pan = (c - startCen).getDistance()
            if (pinch > slopPx * 2 && pinch > pan) multiMode = 2
            else if (pan > slopPx) { multiMode = 1; scrollAt = toFb(c) }
            if (multiMode != 0) { multiMoved = true; lastDist = d; lastCen = c }
            return
        }
        if (multiMode == 2) { if (lastDist > 0) onPinch(d / lastDist, c) }
        else if (zoomed()) onPan(c - lastCen)
        else { record(now, c); scrollBy(c.x - lastCen.x, c.y - lastCen.y) }
        lastDist = d; lastCen = c
    }

    /** Finger movement → wheel steps: finger up = content moves up = wheel down (natural scrolling). */
    private fun scrollBy(dx: Float, dy: Float, at: Pair<Int, Int> = scrollAt) {
        val step = scrollStepPx().coerceAtLeast(8f)
        remY += dy; remX += dx
        var n = 0
        while (abs(remY) >= step && n < 20) {
            val btn = if (remY < 0) 16 else 8
            out(at.first, at.second, btn); out(at.first, at.second, 0)
            remY -= if (remY < 0) -step else step; n++
        }
        while (abs(remX) >= step * 1.5f && n < 20) {
            val btn = if (remX < 0) 64 else 32
            out(at.first, at.second, btn); out(at.first, at.second, 0)
            remX -= if (remX < 0) -step * 1.5f else step * 1.5f; n++
        }
    }

    private fun record(now: Long, p: Offset) {
        samples.addLast(Triple(now, p.x, p.y))
        while (samples.size > 2 && now - samples.first().first > 120) samples.removeFirst()
    }

    /** Pixels per second over the last ~0.1 s of movement. */
    private fun velocity(): Pair<Float, Float> {
        if (samples.size < 2) return 0f to 0f
        val a = samples.first(); val b = samples.last()
        val dt = (b.first - a.first) / 1000f
        if (dt <= 0.01f) return 0f to 0f
        return (b.second - a.second) / dt to (b.third - a.third) / dt
    }

    private fun click(p: Offset, button: Int) { val (x, y) = toFb(p); out(x, y, 0); out(x, y, button); out(x, y, 0) }
    private fun two(): Pair<Offset, Offset> { val v = pointers.values.toList(); return v[0] to v[1] }
    private fun centroid(): Offset { val v = pointers.values; return Offset(v.sumOf { it.x.toDouble() }.toFloat() / v.size, v.sumOf { it.y.toDouble() }.toFloat() / v.size) }
    private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)
}
