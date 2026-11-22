package com.tracel.storage.chrono.lith

import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.hash.IntIndex
import java.lang.foreign.MemorySegment
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** The body of a lithified capsule: one tile's block rows, and coded event by event. */
internal object LithCodec {
    private val DX = IntArray(26)
    private val DY = IntArray(26)
    private val DZ = IntArray(26)

    private const val JUMP = 26
    private const val NONE = 27
    private const val EG = Coder.EG
    private const val SG = Coder.SIGNED
    private const val TREE_STATES = 512
    private const val EVLEN = 0
    private const val MILLIS = EVLEN + 2 * EG
    private const val SEQ = MILLIS + SG
    private const val ACT_SAME = SEQ + 8 * EG
    private const val ACT_RANK = ACT_SAME + 2
    private const val ACT_NEW = ACT_RANK + EG
    private const val KIND_SAME = ACT_NEW + SG
    private const val KIND_RANK = KIND_SAME + 2
    private const val KIND_NEW = KIND_RANK + EG
    private const val FIRST_NEAR = KIND_NEW + EG
    private const val FIRST = FIRST_NEAR + 1
    private const val MOVE = FIRST + 4 * SG
    private const val JUMP_GAP = MOVE + 28 * 28 * 32
    private const val CHAIN = JUMP_GAP + 2 * EG
    private const val B_SAME = CHAIN + 1
    private const val B_NEW = B_SAME + 4
    private const val B_TREE = B_NEW + 1
    private const val B_IDX = B_TREE + 65 * TREE_STATES
    private const val STATE_NEW = B_IDX + EG
    private const val A_HIT = STATE_NEW + EG
    private const val A_NEW = A_HIT + 16
    private const val A_TREE = A_NEW + 1
    private const val A_IDX = A_TREE + TREE_STATES
    private const val RAY_USE = A_IDX + EG
    private const val RAY_SAME = RAY_USE + 2
    private const val RAY_AT = RAY_SAME + 1
    private const val RAY_BIT = RAY_AT + 3 * SG
    private const val RAY_LEFT = RAY_BIT + 3 * 8 * 4
    private const val SLOTS = RAY_LEFT + EG
    private const val RAY_MIN = 4
    private const val RAY_GRID = 64.0
    private const val RAY_STEP = 0.3
    private const val RAY_REACH = 400.0

    private val RAYS: Array<DoubleArray> = buildList {
        for (i in 0..15) for (j in 0..15) for (k in 0..15) {
            if (i != 0 && i != 15 && j != 0 && j != 15 && k != 0 && k != 15) continue
            val dx = i / 15.0 * 2 - 1
            val dy = j / 15.0 * 2 - 1
            val dz = k / 15.0 * 2 - 1
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            add(doubleArrayOf(dx / len, dy / len, dz / len))
        }
    }.toTypedArray()

    private val scratch = ThreadLocal.withInitial { Scratch() }

    init {
        var k = 0
        for (len in 1..3) for (dy in -1..1) for (dz in -1..1) for (dx in -1..1) {
            if (abs(dx) + abs(dy) + abs(dz) != len) continue
            DX[k] = dx; DY[k] = dy; DZ[k] = dz
            k++
        }
    }

    fun encode(rows: Rows, yFloor: Int, ySpan: Int, rays: Boolean = false, hints: RayHints? = null): ByteArray {
        val coder = Encoder(SLOTS)
        val n = rows.size
        val keys = IntArray(n) { rows.cell[it] - (yFloor shl 8) }
        Walk(coder, ySpan, n, rows.seq[0], rows.millis[0], rays, hints, yFloor).run(rows, keys)
        return coder.finish()
    }

    fun decode(
        seg: MemorySegment, at: Long, end: Long, n: Int, yFloor: Int, ySpan: Int, firstSeq: Long, firstMillis: Long,
        rays: Boolean = false,
    ): Rows {
        val out = Rows(n, LongArray(n), LongArray(n), IntArray(n), IntArray(n), IntArray(n), IntArray(n), IntArray(n))
        Walk(Decoder(SLOTS, seg, at, end), ySpan, n, firstSeq, firstMillis, rays, null, yFloor).run(out, null)
        for (i in 0 until n) out.cell[i] += yFloor shl 8
        return out
    }

    /** Where the encoder looks for points: [bursts] by moment, and where the tile sits in the world. */
    class RayHints(val originX: Int, val originZ: Int, val bursts: Bursts)

    /**
     * Per-thread scratch sized to the largest tile seen, so a decode allocates its output and
     * little else.
     */
    private class Scratch {
        var cur = IntArray(0)
        var mark = IntArray(0)
        var visited = IntArray(0)
        var rowAt = IntArray(0)
        var outBefore = IntArray(0)
        var outAfter = IntArray(0)
        var probe = IntArray(0)
        var probeStamp = 0

        fun fit(cells: Int) {
            if (cur.size < cells) {
                cur = IntArray(cells); mark = IntArray(cells); visited = IntArray(cells)
                rowAt = IntArray(cells); outBefore = IntArray(cells); outAfter = IntArray(cells); probe = IntArray(cells)
            }
            cur.fill(-1, 0, cells)
            mark.fill(0, 0, cells)
            visited.fill(0, 0, cells)
            probe.fill(0, 0, cells)
            probeStamp = 0
        }
    }

    /** One pass over the rows. */
    private class Walk(
        private val c: Coder,
        private val ySpan: Int,
        private val n: Int,
        firstSeq: Long,
        firstMillis: Long,
        private val rays: Boolean,
        private val hints: RayHints?,
        private val yFloor: Int,
    ) {
        private var prevSeq = firstSeq
        private var prevMillis = firstMillis
        private val s = scratch.get().also { it.fit(ySpan shl 8) }
        private var actorMtf = IntArray(16)
        private var actorCount = 0
        private var maxActor = 0
        private val actorIndex = IntIndex(16)
        private var actorLastKey = IntArray(16)
        private var prevActorSame = 0
        private var kindMtf = IntArray(8)
        private var kindCount = 0
        private val kindIndex = IntIndex(8)
        private var kindByIndex = IntArray(8)
        private var prevKindSame = 0
        private var states = IntArray(64)
        private var stateCount = 0
        private val stateIndex = IntIndex(64)
        private val turnKeys = IntIndex(64)
        private var turnTo = IntArray(64)
        private var prevBefore = 64
        private var prevBSame = 0
        private var lastKey = 0
        private var stamp = 0
        private var trav = IntArray(64)
        private var sorted = IntArray(64)
        private var lastPoint = LongArray(3)
        private var hadPoint = false

        fun run(rows: Rows, keys: IntArray?) {
            val decoding = keys == null
            var i = 0
            while (i < n) {
                var len = 1
                if (!decoding) {
                    var j = i + 1
                    while (j < n && rows.millis[j] == rows.millis[i] && rows.actor[j] == rows.actor[i] &&
                        rows.kind[j] == rows.kind[i] && rows.seq[j] == rows.seq[j - 1] + 1 && keys[j] > keys[j - 1]
                    ) j++
                    len = j - i
                }
                len = c.gamma(EVLEN + prevActorSame * EG, (len - 1).toLong()).toInt() + 1

                val dMillis = c.signed(MILLIS, if (decoding) 0 else rows.millis[i] - prevMillis)
                val millis = prevMillis + dMillis
                prevMillis = millis
                val msClass = when (dMillis) {
                    0L -> 1
                    in -63..63 -> 2
                    else -> 3
                }
                val seqCtx = SEQ + ((if (len > 1) 1 else 0) + 2 * msClass) * EG
                val seq = prevSeq + c.gamma(seqCtx, if (decoding) 0 else rows.seq[i] - prevSeq)
                prevSeq = seq + len - 1

                val actor = actor(if (decoding) 0 else rows.actor[i])
                val kindIdx = kind(if (decoding) 0 else rows.kind[i])
                val kind = kindByIndex[kindIdx]

                if (trav.size < len) {
                    trav = IntArray(len * 2)
                    sorted = IntArray(len * 2)
                }
                stamp++
                if (!decoding) {
                    for (j in 0 until len) {
                        val key = keys[i + j]
                        sorted[j] = key
                        s.mark[key] = stamp
                        s.rowAt[key] = i + j
                    }
                }
                val point = if (rays && len >= RAY_MIN) point(len, millis) else null
                if (point != null) rays(len, point) else walk(len, actor)
                states(len, kindIdx, rows, decoding)

                if (decoding) {
                    trav.copyInto(sorted, 0, 0, len)
                    sorted.sort(0, len)
                    for (r in 0 until len) {
                        val key = sorted[r]
                        val row = i + r
                        rows.seq[row] = seq + r
                        rows.millis[row] = millis
                        rows.cell[row] = key
                        rows.actor[row] = actor
                        rows.kind[row] = kind
                        rows.before[row] = s.outBefore[key]
                        rows.after[row] = s.outAfter[key]
                    }
                }
                lastKey = trav[len - 1]
                actorLastKey[actorIndex.indexOf(actor)] = lastKey
                i += len
            }
        }

        private fun actor(wanted: Int): Int {
            var rank = -1
            if (!c.decoding) for (r in 0 until actorCount) if (actorMtf[r] == wanted) {
                rank = r; break
            }
            val same = if (actorCount == 0) 0 else c.bit(ACT_SAME + prevActorSame, if (rank == 0) 1 else 0)
            prevActorSame = same
            if (same == 1) return actorMtf[0]
            val coded = if (actorCount == 0) 0 else c.gamma(ACT_RANK, (if (rank < 0) actorCount - 1 else rank - 1).toLong()).toInt()
            val actor: Int
            if (actorCount == 0 || coded == actorCount - 1) {
                actor = (maxActor + c.signed(ACT_NEW, (wanted - maxActor).toLong())).toInt()
                if (actor > maxActor) maxActor = actor
                if (actorCount == actorMtf.size) actorMtf = actorMtf.copyOf(actorCount * 2)
                actorMtf.copyInto(actorMtf, 1, 0, actorCount)
                actorMtf[0] = actor
                actorCount++
                val idx = actorIndex.add(actor)
                if (idx >= actorLastKey.size) actorLastKey = actorLastKey.copyOf(actorLastKey.size * 2)
                actorLastKey[idx] = -1
            } else {
                val r = coded + 1
                actor = actorMtf[r]
                actorMtf.copyInto(actorMtf, 1, 0, r)
                actorMtf[0] = actor
            }
            return actor
        }

        private fun kind(wanted: Int): Int {
            var rank = -1
            if (!c.decoding) for (r in 0 until kindCount) if (kindMtf[r] == wanted) {
                rank = r; break
            }
            val same = if (kindCount == 0) 0 else c.bit(KIND_SAME + prevKindSame, if (rank == 0) 1 else 0)
            prevKindSame = same
            val kind: Int
            if (same == 1) {
                kind = kindMtf[0]
            } else {
                val coded = if (kindCount == 0) 0 else c.gamma(KIND_RANK, (if (rank < 0) kindCount - 1 else rank - 1).toLong()).toInt()
                if (kindCount == 0 || coded == kindCount - 1) {
                    kind = c.gamma(KIND_NEW, wanted.toLong()).toInt()
                    if (kindCount == kindMtf.size) kindMtf = kindMtf.copyOf(kindCount * 2)
                    kindMtf.copyInto(kindMtf, 1, 0, kindCount)
                    kindMtf[0] = kind
                    kindCount++
                    val idx = kindIndex.add(kind)
                    if (idx >= kindByIndex.size) kindByIndex = kindByIndex.copyOf(kindByIndex.size * 2)
                    kindByIndex[idx] = kind
                } else {
                    val r = coded + 1
                    kind = kindMtf[r]
                    kindMtf.copyInto(kindMtf, 1, 0, r)
                    kindMtf[0] = kind
                }
            }
            return kindIndex.indexOf(kind)
        }

        @Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")
        private fun point(len: Int, millis: Long): LongArray? {
            var best: LongArray? = null
            if (!c.decoding) {
                val h = hints
                var bestFound = 0
                if (h != null) for (b in h.bursts.at(millis)) {
                    val p = longArrayOf(
                        Math.round((b.x - h.originX) * RAY_GRID),
                        Math.round((b.y - yFloor) * RAY_GRID),
                        Math.round((b.z - h.originZ) * RAY_GRID),
                    )
                    if (!near(p)) continue
                    val probe = ++s.probeStamp
                    var found = 0
                    traceWithRays(p) { key, _ ->
                        if (s.probe[key] != probe) {
                            s.probe[key] = probe
                            if (s.mark[key] == stamp) found++
                        }
                        found < len
                    }
                    if (found > bestFound) {
                        bestFound = found
                        best = p
                    }
                }
                if (bestFound * 4 < len * 3) best = null
            }
            val use = c.bit(RAY_USE + if (hadPoint) 1 else 0, if (best != null) 1 else 0)
            if (use == 0) return null
            val same = if (!hadPoint) 0 else c.bit(RAY_SAME, if (!c.decoding && best!!.contentEquals(lastPoint)) 1 else 0)
            if (same == 1) return lastPoint
            val p = LongArray(3)
            for (axis in 0..2) {
                val prev = if (hadPoint) lastPoint[axis] else 0L
                p[axis] = prev + c.signed(RAY_AT + axis * SG, if (c.decoding) 0 else best!![axis] - prev)
            }
            lastPoint = p
            hadPoint = true
            return p
        }

        private fun near(p: LongArray): Boolean {
            val x = p[0] / RAY_GRID
            val z = p[2] / RAY_GRID
            val dx = if (x < 0) -x else if (x > 16) x - 16 else 0.0
            val dz = if (z < 0) -z else if (z > 16) z - 16 else 0.0
            return dx * dx + dz * dz <= RAY_REACH * RAY_REACH
        }

        private fun rays(len: Int, p: LongArray) {
            val decoding = c.decoding
            var t = 0
            var left = len
            var ray = -1
            var prev = 2
            var run = 0
            var dirClass = 0
            traceWithRays(p) { key, r ->
                if (r != ray) {
                    ray = r
                    prev = 2
                    run = 0
                    val dy = RAYS[r][1]
                    dirClass = if (dy > 0.3) 0 else if (dy < -0.3) 1 else if (dy > 0) 2 else 3
                }
                if (s.visited[key] == stamp) {
                    val hit = s.mark[key] == stamp
                    prev = if (hit) 1 else 0
                    run = if (hit) 0 else run + 1
                    return@traceWithRays true
                }
                s.visited[key] = stamp
                val bucket = when {
                    run == 0 -> 0
                    run < 2 -> 1
                    run < 4 -> 2
                    run < 8 -> 3
                    run < 16 -> 4
                    run < 64 -> 5
                    run < 256 -> 6
                    else -> 7
                }
                val bit = c.bit(RAY_BIT + (prev * 8 + bucket) * 4 + dirClass, if (!decoding && s.mark[key] == stamp) 1 else 0)
                if (bit == 1) {
                    if (decoding) s.mark[key] = stamp
                    trav[t++] = key
                    left--
                    prev = 1
                    run = 0
                } else {
                    prev = 0
                    run++
                }
                left > 0
            }
            var previous = -1
            var scan = 0
            while (left > 0) {
                var key = 0
                if (!decoding) {
                    while (s.visited[sorted[scan]] == stamp) scan++
                    key = sorted[scan]
                }
                key = previous + 1 + c.gamma(RAY_LEFT, (key - previous - 1).toLong()).toInt()
                s.visited[key] = stamp
                if (decoding) s.mark[key] = stamp
                trav[t++] = key
                previous = key
                left--
            }
        }

        private inline fun traceWithRays(p: LongArray, visit: (Int, Int) -> Boolean) {
            val ox = p[0] / RAY_GRID
            val oy = p[1] / RAY_GRID
            val oz = p[2] / RAY_GRID
            val hy = ySpan.toDouble()
            for ((r, d) in RAYS.withIndex()) {
                var lo = 0.0
                var hi = Double.MAX_VALUE
                for (axis in 0..2) {
                    val o = if (axis == 0) ox else if (axis == 1) oy else oz
                    val top = if (axis == 1) hy else 16.0
                    val v = d[axis]
                    if (abs(v) < 1e-12) {
                        if (o !in 0.0..<top) {
                            hi = -1.0
                            break
                        }
                        continue
                    }
                    val t1 = (0 - o) / v
                    val t2 = (top - o) / v
                    lo = maxOf(lo, minOf(t1, t2))
                    hi = minOf(hi, maxOf(t1, t2))
                }
                if (hi < lo) continue
                val first = maxOf(0L, floor(lo / RAY_STEP).toLong())
                val last = ceil(hi / RAY_STEP).toLong()
                var previous = -1
                for (k in first..last) {
                    val t = k * RAY_STEP
                    val x = floor(ox + d[0] * t).toInt()
                    val y = floor(oy + d[1] * t).toInt()
                    val z = floor(oz + d[2] * t).toInt()
                    if (x !in 0..15 || z < 0 || z > 15 || y < 0 || y >= ySpan) continue
                    val key = (y shl 8) or (z shl 4) or x
                    if (key == previous) continue
                    previous = key
                    if (!visit(key, r)) return
                }
            }
        }

        private fun walk(len: Int, actor: Int) {
            val decoding = c.decoding
            val known = actorLastKey[actorIndex.indexOf(actor)]
            val ref = if (known >= 0) known else lastKey
            val ry = ref shr 8
            val rz = (ref shr 4) and 15
            val rx = ref and 15
            val want = if (decoding) 0 else sorted[0]
            val wy = want shr 8
            val near = c.bit(FIRST_NEAR, if (abs(wy - ry) <= 31) 1 else 0)
            val y = ry + c.signed(if (near == 1) FIRST else FIRST + SG, (wy - ry).toLong()).toInt()
            val z = rz + c.signed(FIRST + 2 * SG, (((want shr 4) and 15) - rz).toLong()).toInt()
            val x = rx + c.signed(FIRST + 3 * SG, ((want and 15) - rx).toLong()).toInt()
            val first = (y shl 8) or (z shl 4) or x
            trav[0] = first
            s.visited[first] = stamp

            var p = first
            var d1 = NONE
            var d2 = NONE
            var jumpFrom = first
            var scan = 0
            var prevJump = 0L
            for (t in 1 until len) {
                var sym = JUMP
                if (!decoding) sym = nextStep(p, d1)
                sym = c.tree(MOVE + (d1 * 28 + d2) * 32, 5, sym)
                val q: Int
                if (sym == JUMP) {
                    var gap = 0L
                    if (!decoding) {
                        while (s.visited[sorted[scan]] == stamp) scan++
                        gap = (sorted[scan] - jumpFrom - 1).toLong()
                    }
                    gap = c.gamma(JUMP_GAP + if (prevJump < 64) 0 else EG, gap)
                    prevJump = gap
                    q = jumpFrom + 1 + gap.toInt()
                    jumpFrom = q
                    d1 = NONE
                    d2 = NONE
                } else {
                    q = p + (DY[sym] shl 8) + (DZ[sym] shl 4) + DX[sym]
                    d2 = d1
                    d1 = sym
                }
                trav[t] = q
                s.visited[q] = stamp
                p = q
            }
        }

        private fun nextStep(p: Int, d1: Int): Int {
            val px = p and 15
            val pz = (p shr 4) and 15
            val py = p shr 8
            if (d1 < 26) {
                val cand = open(px + DX[d1], py + DY[d1], pz + DZ[d1])
                if (cand >= 0) return d1
            }
            for (d in 0 until 26) if (open(px + DX[d], py + DY[d], pz + DZ[d]) >= 0) return d
            return JUMP
        }

        private fun open(x: Int, y: Int, z: Int): Int {
            if (x !in 0..15 || z < 0 || z > 15 || y < 0 || y >= ySpan) return -1
            val cand = (y shl 8) or (z shl 4) or x
            return if (s.mark[cand] == stamp && s.visited[cand] != stamp) cand else -1
        }

        private fun states(len: Int, kindIdx: Int, rows: Rows, decoding: Boolean) {
            for (t in 0 until len) {
                val key = trav[t]
                val row = if (decoding) 0 else s.rowAt[key]
                val wantB = if (decoding) 0 else rows.before[row]
                val wantA = if (decoding) 0 else rows.after[row]

                val held = s.cur[key]
                val explicit = if (held < 0) 1 else c.bit(CHAIN, if (states[held] == wantB) 0 else 1)
                val bIdx: Int
                if (explicit == 0) {
                    bIdx = held
                } else {
                    val wantIdx = if (decoding) 0 else stateIndex.indexOf(wantB)
                    val same = if (prevBefore == 64) 0 else c.bit(B_SAME + prevBSame, if (wantIdx == prevBefore) 1 else 0)
                    bIdx = if (same == 1) prevBefore else state(B_NEW, wantIdx, wantB) { idx ->
                        if (stateCount <= TREE_STATES) c.tree(B_TREE + minOf(prevBefore, 64) * TREE_STATES, 9, idx)
                        else c.gamma(B_IDX, idx.toLong()).toInt()
                    }
                    prevBSame = ((prevBSame shl 1) or same) and 3
                    prevBefore = minOf(bIdx, 63)
                }

                val turn = turnKeys.indexOf((kindIdx shl 20) or bIdx)
                val predicted = if (turn < 0) -1 else turnTo[turn]
                val wantIdx = if (decoding) 0 else stateIndex.indexOf(wantA)
                val hit = if (predicted < 0) 0 else c.bit(A_HIT + minOf(kindIdx, 15), if (predicted == wantIdx) 1 else 0)
                val aIdx: Int
                if (hit == 1) {
                    aIdx = predicted
                } else {
                    aIdx = state(A_NEW, wantIdx, wantA) { idx ->
                        if (stateCount <= TREE_STATES) c.tree(A_TREE, 9, idx) else c.gamma(A_IDX, idx.toLong()).toInt()
                    }
                    val slot = turnKeys.add((kindIdx shl 20) or bIdx)
                    if (slot >= turnTo.size) turnTo = turnTo.copyOf(turnTo.size * 2)
                    turnTo[slot] = aIdx
                }
                s.cur[key] = aIdx
                if (decoding) {
                    s.outBefore[key] = states[bIdx]
                    s.outAfter[key] = states[aIdx]
                }
            }
        }

        private inline fun state(newSlot: Int, wantIdx: Int, wanted: Int, known: (Int) -> Int): Int {
            val isNew = if (stateCount == 0) 1 else c.bit(newSlot, if (wantIdx < 0) 1 else 0)
            if (isNew == 0) return known(wantIdx)
            val id = c.gamma(STATE_NEW, wanted.toLong()).toInt()
            if (stateCount == states.size) states = states.copyOf(stateCount * 2)
            states[stateCount] = id
            stateIndex.add(id)
            return stateCount++
        }
    }
}
