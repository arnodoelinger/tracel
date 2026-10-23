package com.tracel.plugin.command.scrub

/**
 * The edges of a pile of blocks, the way a structure block draws its box:
 * only where the shape sticks out, never between two blocks that lie side by side,
 * and never in a crease where one part rests against another, so a block on a platform grows out of it.
 */
internal object Outline {
    /** A straight line of [length] blocks from the corner ([x], [y], [z]) along [axis]. */
    class Line(val axis: Axis, val x: Int, val y: Int, val z: Int, val length: Int)

    /** Line axis. */
    enum class Axis { X, Y, Z }

    /** The lines around the packed [blocks], each as long as it can be. */
    fun of(blocks: Collection<Long>): List<Line> {
        val solid = LongSet(blocks.size)
        for (pos in blocks) solid.add(pos)
        val lines = ArrayList<Line>()
        for (axis in Axis.entries) {
            val edges = LongSet(blocks.size)
            val checked = LongSet(blocks.size * 2)
            solid.forEach { pos ->
                val x = Packed.x(pos)
                val y = Packed.y(pos)
                val z = Packed.z(pos)
                // The four edges of this block that run along the axis
                for (a in 0..1) for (b in 0..1) {
                    val corner = when (axis) {
                        Axis.X -> Packed.of(x, y + a, z + b)
                        Axis.Y -> Packed.of(x + a, y, z + b)
                        Axis.Z -> Packed.of(x + a, y + b, z)
                    }
                    if (checked.add(corner) && bends(solid, axis, corner)) edges.add(corner)
                }
            }
            edges.forEach { start ->
                if (before(start, axis) in edges) return@forEach
                var length = 1
                var next = after(start, axis)
                while (next in edges) {
                    length++
                    next = after(next, axis)
                }
                lines += Line(axis, Packed.x(start), Packed.y(start), Packed.z(start), length)
            }
        }
        return lines
    }

    // Of the four blocks around an edge, an edge is drawn where one is solid, or two that only touch at a corner
    private fun bends(solid: LongSet, axis: Axis, edge: Long): Boolean {
        val x = Packed.x(edge)
        val y = Packed.y(edge)
        val z = Packed.z(edge)
        fun at(a: Int, b: Int): Boolean = Packed.of(
            when (axis) {
                Axis.X -> x
                Axis.Y -> x - 1 + a
                Axis.Z -> x - 1 + a
            },
            when (axis) {
                Axis.X -> y - 1 + a
                Axis.Y -> y
                Axis.Z -> y - 1 + b
            },
            when (axis) {
                Axis.X -> z - 1 + b
                Axis.Y -> z - 1 + b
                Axis.Z -> z
            },
        ) in solid

        val a = at(0, 0)
        val b = at(1, 0)
        val c = at(0, 1)
        val d = at(1, 1)
        val count = (if (a) 1 else 0) + (if (b) 1 else 0) + (if (c) 1 else 0) + (if (d) 1 else 0)
        return when (count) {
            1 -> true
            2 -> a == d
            else -> false
        }
    }

    private fun before(edge: Long, axis: Axis): Long = step(edge, axis, -1)

    private fun after(edge: Long, axis: Axis): Long = step(edge, axis, 1)

    private fun step(edge: Long, axis: Axis, by: Int): Long = Packed.of(
        Packed.x(edge) + if (axis == Axis.X) by else 0,
        Packed.y(edge) + if (axis == Axis.Y) by else 0,
        Packed.z(edge) + if (axis == Axis.Z) by else 0,
    )
}
