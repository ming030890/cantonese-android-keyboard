package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln

/** Bounded app-private statistics. No sentences, raw touch traces, contacts or network IO. */
internal class LocalTypingLearning(context: Context, private val enabled: () -> Boolean) : TypingLearning {
    private val prefs=context.getSharedPreferences("typing_learning_v2",Context.MODE_PRIVATE)
    private fun read(key:String)=runCatching { JSONObject(prefs.getString(key,"{}")!!) }.getOrDefault(JSONObject())
    private fun store(key:String,value:JSONObject) {
        // Deterministic bounded eviction; most recent explicit evidence survives.
        while(value.length()>256) value.remove(value.keys().next())
        prefs.edit().putString(key,value.toString()).apply()
    }
    @Synchronized override fun bonus(word:String):Double {
        if(!enabled()) return 0.0
        val count=read("words").optInt(word)
        return if(count<3) 0.0 else ln(1.0+count).coerceAtMost(1.5)
    }
    @Synchronized override fun rejected(original:String,replacement:String)=
        enabled() && read("rejected").optInt("$original\t$replacement")>0
    @Synchronized override fun reject(original:String,replacement:String) {
        if(!enabled() || original.length>64 || replacement.length>64) return
        val data=read("rejected");data.put("$original\t$replacement",1);store("rejected",data)
    }
    @Synchronized override fun selected(original:String,replacement:String,centers:FloatArray,points:FloatArray) {
        if(!enabled() || replacement.length !in 1..64 || !replacement.all { it in 'a'..'z' || it in "' " }) return
        val words=read("words");words.remove(replacement).also { old -> words.put(replacement,((old as? Int) ?: 0).coerceAtMost(100)+1) };store("words",words)
        val rejected=read("rejected");rejected.remove("$original\t$replacement");store("rejected",rejected)
        // Only directly aligned, explicitly chosen words can teach touch bias.
        if(original.length!=replacement.length || centers.size!=52 || points.size!=original.length*2) return
        val bias=read("bias");var n=bias.optInt("n");var x=bias.optDouble("x",0.0);var y=bias.optDouble("y",0.0)
        replacement.forEachIndexed { i,c ->
            if(c !in 'a'..'z') return@forEachIndexed
            val index=(c-'a')*2
            val dx=(points[i*2]-centers[index]).toDouble();val dy=(points[i*2+1]-centers[index+1]).toDouble()
            if(dx.isFinite() && dy.isFinite() && kotlin.math.abs(dx)<0.75 && kotlin.math.abs(dy)<0.75) {
                val rate=1.0/(n.coerceAtMost(99)+1);x+=(dx-x)*rate;y+=(dy-y)*rate;n=(n+1).coerceAtMost(100)
            }
        }
        store("bias",JSONObject().put("n",n).put("x",x.coerceIn(-0.3,0.3)).put("y",y.coerceIn(-0.3,0.3)))
    }
    @Synchronized override fun adjust(points:FloatArray):FloatArray {
        if(!enabled()) return points
        val bias=read("bias");if(bias.optInt("n")<20) return points
        return points.mapIndexed { i,v -> v-(if(i%2==0) bias.optDouble("x",0.0) else bias.optDouble("y",0.0)).toFloat() }.toFloatArray()
    }
    @Synchronized override fun clear() { prefs.edit().clear().apply() }
}
