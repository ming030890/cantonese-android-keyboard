package com.madeinhk.cantonesevoicekeyboard.english

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Opt-in, text-free local diagnostics. Never keeps words or coordinates. */
internal object TypingDiagnostics {
    private val state=MutableStateFlow("Diagnostics disabled")
    val summary=state.asStateFlow()
    @Volatile var enabled=false
        set(value) { field=value; if(!value) state.value="Diagnostics disabled" }
    @Volatile private var latest="{}"
    fun update(reason:String,touch:Boolean=false,candidates:Int=0,revision:Long=0L) {
        if(!enabled) return
        latest=JSONObject().put("schema_version",1).put("reason",reason).put("touch_available",touch)
            .put("candidate_count",candidates).put("revision",revision).toString()
        state.value="$reason · touch=${if(touch) "yes" else "no"} · candidates=$candidates"
    }
    fun export():String=if(enabled) latest else "{}"
}
