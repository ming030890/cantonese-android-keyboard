package com.madeinhk.cantonesevoicekeyboard.english

import kotlinx.coroutines.*
import java.util.ArrayDeque

/** Serializes editor mutations while scoring a boundary, never blocks the UI thread. */
internal class BoundaryTransactionQueue(private val scope:CoroutineScope) {
    private var job:Job?=null
    private var boundary:(()->Unit)?=null
    private val actions=ArrayDeque<()->Unit>()
    private var flushing=false
    private var revising=false
    private var committing=false
    val waiting get()=boundary!=null || revising
    fun enqueue(action:()->Unit):Boolean {
        if(!waiting) return false
        actions.addLast(action);return true
    }
    fun begin(settle:suspend ()->Unit,commit:()->Unit,afterCommit:suspend ()->Unit = {}):Boolean {
        if(flushing || committing) return false
        check(!waiting)
        boundary=commit
        val task=scope.launch(start=CoroutineStart.LAZY) {
            try { settle() } finally {
                if(isActive) {
                    val action=boundary;boundary=null
                    committing=true
                    try {action?.invoke()} finally {committing=false}
                    revising=true
                    try {afterCommit()} finally {
                        revising=false;job=null
                        if(isActive) drain()
                    }
                }
            }
        }
        job=task;task.start();return true
    }
    private fun drain() { while(!waiting && actions.isNotEmpty()) actions.removeFirst().invoke() }
    fun flushLiteral() {
        job?.cancel();job=null
        val action=boundary;boundary=null;revising=false;flushing=true
        try { action?.invoke();drain() } finally { flushing=false }
    }
    fun discard() { job?.cancel();job=null;boundary=null;revising=false;actions.clear() }
}
