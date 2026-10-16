package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import java.security.MessageDigest

/** Explicit internal-test authorization, independent of production qualification. */
internal object InternalTypingProfile {
    fun load(context: Context, baselineContext: () -> Long, learning: TypingLearning): EnglishCandidateEngine {
        fun digest(path:String)=context.assets.open(path).use { input ->
            val hash=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65536)
            while(true) {val n=input.read(bytes);if(n<0) break;hash.update(bytes,0,n)}
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        val modelHash=digest("typing/model.cng")
        val dictionaryHash=digest("english/en-v2.bin")
        fun policy(path:String,scope:String)=ReplacementPolicy.load(
            context.assets.open(path).bufferedReader().use { it.readText() },modelHash,scope,
            allowInternalTrial=true,dictionarySha256=dictionaryHash)
        val boundary=policy("typing/calibration.json","boundary")
        val revision=policy("typing/revision_calibration.json","context_revision")
        val model=NativeQuickEngine.load(context,"typing/model.cng")
        val shared=NativeEnglishEngine.sharedContextScorer(model::englishContextHandle)
        val scorer=object:ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>)=shared.scores(left,right,candidates)
            override fun close() {model.close()}
        }
        return try {NativeEnglishEngine.experimental(context,baselineContext,scorer,boundary,learning,revision,preserveBoundaryCorrections=true)}
        catch(error:Throwable) {scorer.close();throw error}
    }
}
