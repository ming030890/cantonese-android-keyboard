package com.madeinhk.cantonesevoicekeyboard.english

import java.util.Locale

internal data class EnglishCandidate(val word: String, val score: Double, val editCost: Double,
    val edits: Int, val completedChars: Int)
internal data class EnglishPrediction(val candidates: List<EnglishCandidate> = emptyList(),
    val correction: String? = null, val plausiblePrefix: Boolean = false, val known: Boolean = false,
    val replaceBefore: Int = 0, val reason: String = "legacy", val confidence: Double? = null,
    val replacementSpans: Map<String, Int> = emptyMap(),
    val reconsiderKnown:Boolean=false,val requiredConfidence:Double=.999)

/** Immutable snapshot in key-width units; NaN denotes absent touch evidence. */
data class EnglishTouchEvidence(val x: Float, val y: Float, val centers: FloatArray)

internal interface EnglishCandidateEngine {
    val supportsAutomaticCorrection: Boolean get() = true
    fun nextWords(left: String, right: String): List<String> = emptyList()
    fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction
    /** Keep observed casing available to real engines, while existing engines receive canonical words. */
    fun predictObserved(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
        predict(word.lowercase(Locale.ROOT).replace('’','\''),centers,points,left,right)
    /** Requires separately approved calibration for revising already committed words. */
    val supportsContextRevision: Boolean get() = false
    /** Contextual profile can judge short English despite overlapping Quick codes. */
    val allowsQuickCodeCorrections:Boolean get()=false
    fun reconsider(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction = EnglishPrediction()
    fun reconsiderObserved(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
        reconsider(word.lowercase(Locale.ROOT).replace('’','\''),centers,points,left,right)
    fun rankCandidates(left: String, right: String, candidates: List<String>): List<String> = candidates
    fun selected(word: String, replacement: String, centers: FloatArray, points: FloatArray) {}
    fun rejected(word: String, replacement: String) {}
}
