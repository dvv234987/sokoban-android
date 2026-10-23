package com.example.sokoban

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/** Rules: push boulders (never pull) into holes '^' to fill them; step onto the door '+' to win. */
class Game(start: Grid) {
    val grid = start.copy()
    var player: V = grid.find('@') ?: V(0, 0)
    var moves = 0
    var won = false
    private val history = ArrayList<Pair<Grid, V>>()

    init { grid[player] = '.' }

    fun move(d: V): Boolean {
        if (won) return false
        val t = player + d
        if (!grid.inside(t)) return false
        when (grid[t]) {
            '#', '^' -> return false
            '0' -> {
                val b = t + d
                if (!grid.inside(b)) return false
                val c = grid[b]
                if (c != '.' && c != '^') return false
                snapshot()
                grid[b] = if (c == '^') '.' else '0'   // boulder fills the hole and disappears
                grid[t] = '.'
            }
            else -> snapshot()
        }
        player = t
        moves++
        if (grid[t] == '+') won = true
        return true
    }

    private fun snapshot() { history.add(grid.copy() to player) }

    fun undo() {
        if (history.isEmpty()) return
        val (g, p) = history.removeAt(history.size - 1)
        for (x in 0 until g.w) for (y in 0 until g.h) grid[V(x, y)] = g[V(x, y)]
        player = p; moves--; won = false
    }
}

class BoardView(ctx: Context) : View(ctx) {
    var game: Game? = null
    var onChange: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ax = 0f; private var ay = 0f
    private var dragged = false
    private var cs = 1f; private var ox = 0f; private var oy = 0f

    override fun onDraw(c: Canvas) {
        val gm = game ?: return
        val g = gm.grid
        cs = min(width.toFloat() / g.w, height.toFloat() / g.h)
        ox = (width - cs * g.w) / 2; oy = (height - cs * g.h) / 2
        for (x in 0 until g.w) for (y in 0 until g.h) {
            val ch = g[V(x, y)]
            val r = RectF(ox + x * cs, oy + y * cs, ox + (x + 1) * cs, oy + (y + 1) * cs)
            paint.color = if (ch == '#') 0xFF3B4650.toInt() else 0xFFE9E2D0.toInt()
            c.drawRect(r, paint)
            val cx = r.centerX(); val cy = r.centerY()
            when (ch) {
                '0' -> { paint.color = 0xFF8D5A2B.toInt(); c.drawCircle(cx, cy, cs * 0.4f, paint) }
                '^' -> { paint.color = 0xFF111111.toInt(); c.drawCircle(cx, cy, cs * 0.38f, paint) }
                '+' -> { paint.color = 0xFFE0A800.toInt(); c.drawRect(r.left + cs * .15f, r.top + cs * .15f, r.right - cs * .15f, r.bottom - cs * .15f, paint) }
            }
        }
        paint.color = 0xFF1E88E5.toInt()
        c.drawCircle(ox + (gm.player.x + .5f) * cs, oy + (gm.player.y + .5f) * cs, cs * 0.36f, paint)
    }

    private fun step(dx: Float, dy: Float) {
        val d = if (abs(dx) >= abs(dy)) V(if (dx > 0) 1 else -1, 0) else V(0, if (dy > 0) 1 else -1)
        game?.move(d)
        invalidate(); onChange?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val gm = game ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { ax = e.x; ay = e.y; dragged = false }
            MotionEvent.ACTION_MOVE -> {      // drag: one step per ~cell of finger travel
                val dx = e.x - ax; val dy = e.y - ay
                if (maxOf(abs(dx), abs(dy)) >= cs * 0.9f) { dragged = true; step(dx, dy); ax = e.x; ay = e.y }
            }
            MotionEvent.ACTION_UP -> if (!dragged) {   // tap: step toward the tap, relative to the player
                val px = ox + (gm.player.x + .5f) * cs; val py = oy + (gm.player.y + .5f) * cs
                step(e.x - px, e.y - py)
            }
        }
        return true
    }
}

class MainActivity : Activity() {
    private lateinit var board: BoardView
    private lateinit var status: TextView
    private lateinit var newBtn: Button
    private var boulders = 4
    private var original: Grid? = null
    private var busy = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        board = BoardView(this)
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(24, 0, 0, 0) }
        newBtn = Button(this).apply { text = "New"; setOnClickListener { newLevel() } }
        val restart = Button(this).apply { text = "Restart"; setOnClickListener {
            original?.let { board.game = Game(it); board.invalidate(); refresh() } } }
        val undo = Button(this).apply { text = "Undo"; setOnClickListener {
            board.game?.undo(); board.invalidate(); refresh() } }
        val diff = Button(this).apply { text = "Boulders: $boulders"; setOnClickListener {
            boulders = if (boulders >= 7) 3 else boulders + 1; text = "Boulders: $boulders" } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(newBtn); addView(restart); addView(undo); addView(diff)
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1E2327.toInt())
            addView(bar)
            addView(board, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        board.onChange = { refresh() }
        newLevel()
    }

    private fun refresh() {
        val g = board.game ?: return
        status.text = if (g.won) "Solved in ${g.moves} moves!" else "Moves: ${g.moves}   Holes left: ${g.grid.count('^')}"
    }

    private fun newLevel() {
        if (busy) return
        busy = true; newBtn.isEnabled = false; status.text = "Generating…"
        val n = boulders
        // big stack: the search recurses up to ~500 levels deep
        Thread(null, {
            val g = Generator.generate(n, 5000)
            runOnUiThread {
                busy = false; newBtn.isEnabled = true
                if (g != null) { original = g; board.game = Game(g); board.invalidate() }
                refresh()
            }
        }, "generator", 32L shl 20).start()
    }
}
