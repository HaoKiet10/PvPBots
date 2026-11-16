package pvpbots.math

/** Inclusive block bounds. */
data class Bounds(val min: BlockPos, val max: BlockPos) {
    fun contains(p: BlockPos) =
        p.x in min.x..max.x && p.y in min.y..max.y && p.z in min.z..max.z

    /** Point lies inside the block volume [min, max + 1). */
    fun contains(p: Vec3) =
        p.x >= min.x && p.x < max.x + 1.0 &&
            p.y >= min.y && p.y < max.y + 1.0 &&
            p.z >= min.z && p.z < max.z + 1.0

    /** Nearest point inside, inset 0.5 from the min/max faces' block origins (i.e. block centres at the edges). */
    fun clamp(p: Vec3) = Vec3(
        p.x.coerceIn(min.x + 0.5, max.x + 0.5),
        p.y.coerceIn(min.y + 0.5, max.y + 0.5),
        p.z.coerceIn(min.z + 0.5, max.z + 0.5),
    )
}
