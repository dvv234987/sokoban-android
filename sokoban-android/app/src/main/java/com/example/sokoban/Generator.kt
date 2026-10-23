package com.example.sokoban

import java.util.Random
import kotlin.math.max
import kotlin.math.min

// Kotlin port of miki151/sokoban (src/sokoban.cpp, bfsearch.cpp, util.*)

data class V(val x: Int, val y: Int) {
    operator fun plus(o: V) = V(x + o.x, y + o.y)
    operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(k: Int) = V(x * k, y * k)
}

/** Half-open rectangle, like the C++ Rectangle (right/bottom exclusive). */
class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    constructor(p: V, k: V) : this(p.x, p.y, k.x, k.y)
    init { require(right > left && bottom > top) }
    val width get() = right - left
    val height get() = bottom - top
    operator fun contains(v: V) = v.x in left until right && v.y in top until bottom
    fun cells(): List<V> = (left until right).flatMap { x -> (top until bottom).map { y -> V(x, y) } }
}

class Grid(val w: Int, val h: Int, fill: Char) {
    private val a = CharArray(w * h) { fill }
    fun inside(v: V) = v.x in 0 until w && v.y in 0 until h
    private fun idx(v: V): Int { require(inside(v)); return v.x * h + v.y }
    operator fun get(v: V) = a[idx(v)]
    operator fun set(v: V, c: Char) { a[idx(v)] = c }
    fun copy() = Grid(w, h, ' ').also { System.arraycopy(a, 0, it.a, 0, a.size) }
    fun find(c: Char): V? {
        for (x in 0 until w) for (y in 0 until h) if (a[x * h + y] == c) return V(x, y)
        return null
    }
    fun count(c: Char) = a.count { it == c }
}

class Rng(seed: Long) {
    private val r = Random(seed)
    fun get(min: Int, max: Int): Int { require(max > min); return min + r.nextInt(max - min) }
    fun <T> perm(l: List<T>): List<T> = l.shuffled(r)
}

class SokobanMaker(
    private val rnd: Rng, private val W: Int, private val H: Int,
    private val numBoulders: Int, private val numNodes: Int,
    private val numRooms: Int = 3, private val numDoors: Int = 1
) {
    private val level = Grid(W, H, '#')
    private var best = Grid(W, H, '?')
    var maxDepth = -1; private set
    private var finalPos = V(0, 0)
    private var middleLine = 0
    private var workArea = Rect(0, 0, 1, 1)
    private val boulders = ArrayList<V>()
    private var curPos = V(0, 0)
    private val dirs = listOf(V(0, 1), V(1, 0), V(0, -1), V(-1, 0))

    fun result() = best

    private fun prepareBoulderRooms(area: Rect, mainLo: Int, mainHi: Int, otherLo: Int, otherHi: Int) {
        val mainSize = V(rnd.get(mainLo, mainHi), rnd.get(mainLo, mainHi))
        val mainPos = V((area.width - mainSize.x) / 2, (area.height - mainSize.y) / 2)
        val mainRect = Rect(mainPos, mainPos + mainSize)
        for (v in mainRect.cells()) level[v] = '.'
        val sides = rnd.perm(listOf(0, 1, 2, 3))
        for (i in 0 until numRooms - 1) {
            var size: V
            do { size = V(rnd.get(otherLo, otherHi), rnd.get(otherLo, otherHi)) } while (size.x <= 2 && size.y <= 2)
            var pos = V(0, 0)
            val door = i < numDoors
            when (sides[i % 4]) {
                0 -> {
                    pos = V(mainRect.right, rnd.get(max(area.top, mainRect.top - size.y + 1), min(area.bottom, mainRect.bottom)))
                    if (door) {
                        pos += V(1, 0)
                        level[V(mainRect.right, rnd.get(max(mainRect.top, pos.y), min(mainRect.bottom, pos.y + size.y)))] = '.'
                    }
                }
                1 -> {
                    pos = V(rnd.get(max(area.left, mainRect.left - size.x + 1), min(area.right, mainRect.right)), mainRect.bottom)
                    if (door) {
                        pos += V(0, 1)
                        level[V(rnd.get(max(mainRect.left, pos.x), min(mainRect.right, pos.x + size.x)), mainRect.bottom)] = '.'
                    }
                }
                2 -> {
                    pos = V(mainRect.left - size.x, rnd.get(max(area.top, mainRect.top - size.y + 1), min(area.bottom, mainRect.bottom)))
                    if (door) {
                        pos += V(-1, 0)
                        level[V(mainRect.left - 1, rnd.get(max(mainRect.top, pos.y), min(mainRect.bottom, pos.y + size.y)))] = '.'
                    }
                }
                else -> {
                    pos = V(rnd.get(max(area.left, mainRect.left - size.x + 1), min(area.right, mainRect.right)), mainRect.top - size.y)
                    if (door) {
                        pos += V(0, -1)
                        level[V(rnd.get(max(mainRect.left, pos.x), min(mainRect.right, pos.x + size.x)), mainRect.top - 1)] = '.'
                    }
                }
            }
            for (v in Rect(pos, pos + size).cells()) level[v] = '.'
        }
    }

    /** Returns true if a usable level was produced. May throw on invalid geometry (caller retries). */
    fun make(): Boolean {
        val prizeRoomRadius = 1
        val boulderRoomWidth = W - 1 - 2 * prizeRoomRadius - 1 - numBoulders
        prepareBoulderRooms(Rect(0, 0, boulderRoomWidth, H), 3, 5, 2, 4)
        var start: V? = null
        search@ for (x in (W - 1 - prizeRoomRadius) downTo prizeRoomRadius)
            for (y in rnd.perm((prizeRoomRadius until H - prizeRoomRadius).toList()))
                if (level[V(x, y)] == '.') { start = V(x, y); break@search }
        if (start == null) return false
        middleLine = start.x
        workArea = Rect(0, 0, start.x + numBoulders, H)
        for (i in 1..numBoulders) {
            val p = start + V(i, 0)
            level[p] = '0'
            boulders.add(p)
        }
        level[start + V(numBoulders + 1, 0)] = '+'
        val c = start + V(numBoulders + prizeRoomRadius + 2, 0)
        for (v in Rect(c - V(prizeRoomRadius, prizeRoomRadius), c + V(prizeRoomRadius + 1, prizeRoomRadius + 1)).cells())
            level[v] = '.'
        curPos = start
        moveBoulder(0, HashSet())
        for (i in 1..numBoulders) {
            val hole = start + V(i, 0)
            if (hole == finalPos || best[hole] != '.') return false
            best[hole] = '^'
        }
        best[finalPos] = '@'
        return true
    }

    private fun isFree(p: V) = p in workArea && level[p] == '.'

    private fun reachable(from: V): BooleanArray {
        val r = BooleanArray(W * H)
        val q = ArrayDeque<V>()
        r[from.x * H + from.y] = true
        q.add(from)
        while (q.isNotEmpty()) {
            val p = q.removeFirst()
            for (d in dirs) {
                val n = p + d
                if (n in workArea && !r[n.x * H + n.y] && isFree(n)) { r[n.x * H + n.y] = true; q.add(n) }
            }
        }
        return r
    }

    private fun hash(): Int {
        var seed = 0
        for (b in boulders) {
            seed = seed xor (b.x + 0x9e3779b9.toInt() + (seed shl 6) + (seed shr 2))
            seed = seed xor (b.y + 0x9e3779c9.toInt() + (seed shl 6) + (seed shr 2))
        }
        return seed
    }

    private fun moveBoulder(depth: Int, visited: HashSet<Int>) {
        if (depth > maxDepth) { best = level.copy(); maxDepth = depth; finalPos = curPos }
        if (visited.size > numNodes) return
        val reach = reachable(curPos)
        fun isReach(v: V) = v.x in 0 until W && v.y in 0 until H && reach[v.x * H + v.y]
        for (boulderPos in rnd.perm(boulders.toList())) {
            for (v in rnd.perm(dirs)) {
                if (!isReach(boulderPos + v) || (boulderPos.x >= middleLine - 1 && v.x > 0)) continue
                val pos = boulderPos + v
                var dest: V? = null
                var i = 1
                while (isFree(pos + v * i) && (v.x <= 0 || pos.x + v.x * i <= middleLine)) {
                    dest = pos + v * rnd.get(1, i + 1)
                    i++
                }
                if (dest == null) continue
                val idx = boulders.indexOf(pos - v)
                boulders[idx] = dest - v
                level[dest - v] = '0'
                level[pos - v] = '.'
                val prev = curPos
                curPos = dest
                val h = hash()
                if (visited.add(h)) moveBoulder(depth + 1, visited)
                level[dest - v] = '.'
                level[pos - v] = '0'
                boulders[idx] = pos - v
                curPos = prev
            }
        }
    }
}

object Generator {
    /** Runs many randomized attempts for ~budgetMs and returns the hardest level found (most reverse-moves). */
    fun generate(boulders: Int, budgetMs: Long, width: Int = 28, height: Int = 16): Grid? {
        val rnd = Rng(System.nanoTime())
        val t0 = System.currentTimeMillis()
        var bestDepth = -1
        var bestGrid: Grid? = null
        while (true) {
            val el = System.currentTimeMillis() - t0
            if ((bestGrid != null && el >= budgetMs) || el > 60_000) break
            try {
                val m = SokobanMaker(rnd, width, height, boulders, 500)
                if (m.make() && m.maxDepth > bestDepth) { bestDepth = m.maxDepth; bestGrid = m.result() }
            } catch (e: RuntimeException) { /* bad random geometry: retry, as the C++ would assert */ }
        }
        return bestGrid
    }
}
