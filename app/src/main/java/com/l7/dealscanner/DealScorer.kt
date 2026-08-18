package com.l7.dealscanner

/**
 * Deal scoring keyed to Florida used-market benchmarks for electric dirt bikes.
 * Same logic as the desktop (Python) Deal Scanner.
 *
 * Score levels: STEAL / FAIR / HIGH / UNKNOWN
 */
object DealScorer {

    data class Benchmark(val keywords: List<String>, val stealMax: Int, val fairMax: Int, val label: String)

    // Order matters: more specific models first so "talaria xxx" matches before "talaria".
    private val BENCHMARKS = listOf(
        Benchmark(listOf("storm bee", "stormbee"), 5500, 7500, "Sur-Ron Storm Bee"),
        Benchmark(listOf("sur ron", "surron", "sur-ron", "light bee", "lightbee"), 2800, 4200, "Sur-Ron Light Bee"),
        Benchmark(listOf("talaria xxx", "talaria x3", "xxx"), 3200, 4500, "Talaria XXX"),
        Benchmark(listOf("talaria", "sting"), 2600, 3900, "Talaria Sting"),
        Benchmark(listOf("e ride", "eride", "e-ride"), 4300, 6000, "E Ride Pro"),
        Benchmark(listOf("zero fx", "zero fxe", "zero fxs"), 5500, 8000, "Zero FX"),
        Benchmark(listOf("stark varg", "varg"), 8500, 11500, "Stark Varg"),
        Benchmark(listOf("cake kalk", "kalk"), 4000, 6500, "Cake Kalk"),
        Benchmark(listOf("electric dirt bike", "electric dirtbike", "ebike dirt"), 1500, 3500, "Generic Electric Dirt Bike"),
    )

    const val STEAL = "STEAL"
    const val FAIR = "FAIR"
    const val HIGH = "HIGH"
    const val UNKNOWN = "UNKNOWN"

    fun score(title: String?, price: Int?): Pair<String, String?> {
        if (title.isNullOrBlank() || price == null || price <= 0) return UNKNOWN to null
        val t = title.lowercase()
        val bench = BENCHMARKS.firstOrNull { b -> b.keywords.any { t.contains(it) } }
            ?: return UNKNOWN to null
        val level = when {
            price <= bench.stealMax -> STEAL
            price <= bench.fairMax -> FAIR
            else -> HIGH
        }
        return level to bench.label
    }

    fun badge(level: String): String = when (level) {
        STEAL -> "\uD83D\uDD25 STEAL"   // 🔥
        FAIR -> "\u2713 FAIR"           // ✓
        HIGH -> "\u26A0 HIGH"           // ⚠
        else -> "\u2022 ?"
    }
}
