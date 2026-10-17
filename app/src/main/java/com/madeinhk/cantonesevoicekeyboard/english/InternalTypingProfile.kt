package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import java.security.MessageDigest

/** Explicit internal-test authorization, independent of production qualification. */
internal object InternalTypingProfile {
    fun load(context: Context, baselineContext: () -> Long, learning: TypingLearning): EnglishCandidateEngine {
        System.loadLibrary("cantonese_engine")
        return fromAssets({path->context.assets.open(path).use {it.readBytes()}},EnglishLiteralLexicon.assetPath(context),baselineContext,learning)
    }

    /** Production asset validation and construction, also used by real JNI E2E tests. */
    fun fromAssets(read:(String)->ByteArray,validityPath:String,baselineContext:()->Long,learning:TypingLearning):EnglishCandidateEngine {
        fun digest(path:String)=MessageDigest.getInstance("SHA-256").digest(read(path)).joinToString("") {"%02x".format(it)}
        val modelHash=digest("typing/model.cng")
        val dictionaryHash=digest("english/en-v2.bin")
        val validityHash=digest(validityPath)
        fun policy(path:String,scope:String)=ReplacementPolicy.load(
            read(path).decodeToString(),modelHash,scope,
            allowInternalTrial=true,dictionarySha256=dictionaryHash,validitySha256=validityHash)
        val boundary=policy("typing/calibration.json","boundary")
        val revision=policy("typing/revision_calibration.json","context_revision")
        val model=NativeQuickEngine.fromAssets(read,"typing/model.cng")
        val shared=NativeEnglishEngine.sharedContextScorer(model::englishContextHandle)
        val scorer=object:ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>)=shared.scores(left,right,candidates)
            override fun wordEvidence(left:String,right:String,candidates:List<String>)=shared.wordEvidence(left,right,candidates)
            override fun knownSequences(candidates:List<String>)=shared.knownSequences(candidates)
            override fun close() {model.close()}
        }
        return try {NativeEnglishEngine.experimentalFromAssets(read,validityPath,baselineContext,scorer,boundary,learning,revision,preserveBoundaryCorrections=true)}
        catch(error:Throwable) {scorer.close();throw error}
    }
}
