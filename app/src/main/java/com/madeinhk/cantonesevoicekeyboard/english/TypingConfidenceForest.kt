package com.madeinhk.cantonesevoicekeyboard.english

import org.json.JSONObject

/** Bounded inference for a calibrated gradient-boosted confidence model. */
internal class TypingConfidenceForest private constructor(
    private val intercept: Double, private val rate: Double, private val features: Int,
    private val trees: List<Tree>,
) {
    private data class Tree(val feature: IntArray, val threshold: DoubleArray,
        val left: IntArray, val right: IntArray, val value: DoubleArray)

    fun logit(input: DoubleArray): Double {
        require(input.size == features && input.all { it.isFinite() })
        // sklearn's tree predictor converts observations to float32 before branching.
        val x = FloatArray(features) { input[it].toFloat() }
        if (x.any { !it.isFinite() }) return Double.NaN
        var result = intercept
        for (tree in trees) {
            var node = 0
            while (tree.left[node] != -1) {
                node = if (x[tree.feature[node]].toDouble() <= tree.threshold[node]) tree.left[node] else tree.right[node]
            }
            result += rate * tree.value[node]
        }
        return result
    }

    companion object {
        fun load(json: JSONObject, features: Int): TypingConfidenceForest {
            require(json.getInt("features") == features)
            val intercept = json.getDouble("intercept").also { require(it.isFinite()) }
            val rate = json.getDouble("learning_rate").also { require(it.isFinite() && it > 0 && it <= 1) }
            val array = json.getJSONArray("trees")
            require(array.length() in 1..256)
            val trees = (0 until array.length()).map { index ->
                val tree = array.getJSONObject(index)
                val size = tree.getJSONArray("feature").length()
                require(size in 1..511)
                fun ints(name: String): IntArray = tree.getJSONArray(name).let { a ->
                    require(a.length() == size)
                    IntArray(size) { a.getInt(it).also { v -> require(a.getDouble(it) == v.toDouble()) } }
                }
                fun doubles(name: String): DoubleArray = tree.getJSONArray(name).let { a ->
                    require(a.length() == size)
                    DoubleArray(size) { a.getDouble(it).also { v -> require(v.isFinite()) } }
                }
                Tree(ints("feature"), doubles("threshold"), ints("left"), ints("right"), doubles("value")).also { t ->
                    val parents = IntArray(size)
                    val depth = IntArray(size)
                    for (node in 0 until size) {
                        if (t.left[node] == -1) require(t.right[node] == -1 && t.feature[node] == -2)
                        else {
                            require(t.feature[node] in 0 until features)
                            for (child in intArrayOf(t.left[node], t.right[node])) {
                                require(child > node && child < size)
                                require(++parents[child] == 1)
                                depth[child] = depth[node] + 1
                                require(depth[child] <= 8)
                            }
                        }
                    }
                    require(parents[0] == 0 && (1 until size).all { parents[it] == 1 })
                }
            }
            return TypingConfidenceForest(intercept, rate, features, trees)
        }
    }
}
