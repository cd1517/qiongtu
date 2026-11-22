package com.taoo.xiutu.history

import android.content.Context

// 占位：待移植
object HistoryStore {
    var enabled: Boolean = true
        private set
    val count: Int get() = 0
    val hasPassword: Boolean get() = false
    fun init(context: Context) {}
    fun setEnabled(context: Context, on: Boolean) { enabled = on }
    fun add(context: Context, data: ByteArray, ext: String, source: String) {}
}
