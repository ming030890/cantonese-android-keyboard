package com.madeinhk.cantonesevoicekeyboard.english

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer
import java.security.MessageDigest
import java.text.Normalizer
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/** English specialist. Unknown pieces return no evidence rather than an invented score. */
internal class WordPiece(vocabulary: List<String>) {
    private val ids=vocabulary.withIndex().associate { it.value to it.index }
    val mask=ids.getValue("[MASK]");val cls=ids.getValue("[CLS]");val sep=ids.getValue("[SEP]")
    private val unknown=ids.getValue("[UNK]")
    fun encode(text:String): List<Int> {
        val normalized=Normalizer.normalize(text.lowercase(java.util.Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{M}"),"")
        return Regex("[a-z0-9]+|[^\\s]").findAll(normalized).flatMap { match ->
            val token=match.value
            val pieces=mutableListOf<Int>();var start=0
            while(start<token.length) {
                var end=token.length;var found:Int?=null
                while(end>start) {
                    found=ids[(if(start==0) "" else "##")+token.substring(start,end)]
                    if(found!=null) break
                    end--
                }
                if(found==null) {pieces.clear();pieces+=unknown;break}
                pieces+=found;start=end
            }
            pieces.asSequence()
        }.toList()
    }
    fun supported(ids:List<Int>)=ids.isNotEmpty() && unknown !in ids
}

internal class BertContextScorer private constructor(
    private val environment: OrtEnvironment, private val session: OrtSession,
    private val tokenizer: WordPiece,
) : ContextScorer {
    override fun scores(left:String,right:String,candidates:List<String>):List<Double?> {
        require(candidates.size<=32)
        val prefix=tokenizer.encode(left).takeLast(48)
        val suffix=tokenizer.encode(right).take(16)
        val parts=candidates.map { tokenizer.encode(it) }
        data class Row(val candidate:Int,val target:Int,val input:List<Int>,val position:Int)
        val rows=mutableListOf<Row>()
        for((i,pieces) in parts.withIndex()) {
            if(!tokenizer.supported(pieces) || pieces.size>8 || candidates[i].any { it.code>127 }) continue
            for(j in pieces.indices) {
                val input=listOf(tokenizer.cls)+prefix+pieces.mapIndexed { index,id -> if(index==j) tokenizer.mask else id }+suffix+tokenizer.sep
                rows+=Row(i,pieces[j],input,1+prefix.size+j)
            }
        }
        if(rows.isEmpty()) return candidates.map { null }
        val totals=DoubleArray(candidates.size);val counts=IntArray(candidates.size)
        // Bound activation memory. The vocabulary head is only evaluated at masks.
        for(batch in rows.chunked(8)) {
            val length=batch.maxOf { it.input.size }
            val ids=LongArray(batch.size*length);val attention=LongArray(ids.size);val positions=LongArray(batch.size)
            batch.forEachIndexed { i,row ->
                positions[i]=row.position.toLong()
                row.input.forEachIndexed { j,id -> ids[i*length+j]=id.toLong();attention[i*length+j]=1 }
            }
            OnnxTensor.createTensor(environment,LongBuffer.wrap(ids),longArrayOf(batch.size.toLong(),length.toLong())).use { input ->
                OnnxTensor.createTensor(environment,LongBuffer.wrap(attention),longArrayOf(batch.size.toLong(),length.toLong())).use { mask ->
                    OnnxTensor.createTensor(environment,LongBuffer.wrap(positions),longArrayOf(batch.size.toLong())).use { pos ->
                        session.run(mapOf("input_ids" to input,"attention_mask" to mask,"mask_positions" to pos)).use { outputs ->
                            @Suppress("UNCHECKED_CAST")
                            val logits=outputs[0].value as Array<FloatArray>
                            batch.forEachIndexed { i,row ->
                                val values=logits[i];val max=values.max().toDouble()
                                val normalizer=max+ln(values.sumOf { exp(it.toDouble()-max) })
                                totals[row.candidate]+=values[row.target]-normalizer;counts[row.candidate]++
                            }
                        }
                    }
                }
            }
        }
        return counts.indices.map { i -> if(counts[i]==0) null else totals[i]/counts[i] }
    }
    override fun close() { session.close() }
    companion object {
        fun load(context:Context):BertContextScorer {
            val manifest=JSONObject(context.assets.open("typing/manifest.json").bufferedReader().use { it.readText() })
            require(manifest.getString("license")=="Apache-2.0")
            val bytes=context.assets.open("typing/english.int8.onnx").use { it.readBytes() }
            require(bytes.size<=30_000_000)
            val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(digest==manifest.getString("sha256"))
            val vocabulary=context.assets.open("typing/vocab.txt").bufferedReader().use { it.readLines() }
            val env=OrtEnvironment.getEnvironment()
            val session=OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1);options.setInterOpNumThreads(1)
                env.createSession(bytes,options)
            }
            return BertContextScorer(env,session,WordPiece(vocabulary))
        }
    }
}
