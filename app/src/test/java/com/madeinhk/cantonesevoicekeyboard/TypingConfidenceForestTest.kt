package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.TypingConfidenceForest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TypingConfidenceForestTest {
    private fun tree()=JSONObject("""{"features":2,"intercept":-1.0,"learning_rate":0.5,"trees":[
        {"feature":[0,-2,-2],"threshold":[0.5,-2,-2],"left":[1,-1,-1],"right":[2,-1,-1],"value":[0,-2,4]}]}""")

    @Test fun rawFeatureBranchingMatchesFloat32TreeInference() {
        val forest=TypingConfidenceForest.load(tree(),2)
        assertEquals(-2.0,forest.logit(doubleArrayOf(.5,100.0)),0.0)
        assertEquals(1.0,forest.logit(doubleArrayOf(.5001,-100.0)),0.0)
        assertEquals(-2.0,forest.logit(doubleArrayOf(.5000000001,0.0)),0.0)
        assertTrue(forest.logit(doubleArrayOf(Double.MAX_VALUE,0.0)).isNaN())
    }

    @Test fun malformedTreesCannotLoopOrReadOutsideFeatures() {
        for((field,values) in listOf("left" to "[0,-1,-1]", "right" to "[1,-1,-1]",
            "right" to "[3,-1,-1]", "feature" to "[2,-2,-2]", "left" to "[-1,-1,-1]")) {
            val json=tree();json.getJSONArray("trees").getJSONObject(0).put(field,org.json.JSONArray(values))
            try {TypingConfidenceForest.load(json,2);fail("Invalid $field accepted")}
            catch(_:IllegalArgumentException) {}
        }
    }
}
