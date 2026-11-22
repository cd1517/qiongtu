package com.taoo.xiutu.raw

import java.nio.ByteBuffer

/** LibRaw 的 JNI 封装（app/src/main/cpp/raw_jni.cpp）。句柄用完必须 release。 */
object RawNative {
    val loaded: Boolean = runCatching { System.loadLibrary("xiutu_raw") }.isSuccess

    /** 解码；half = 半尺寸快速解（预览），maxDim > 0 时再面积平均缩到长边不超过它。失败返回 0。 */
    @JvmStatic external fun decode(path: String, half: Boolean, maxDim: Int): Long
    @JvmStatic external fun lastError(): String
    /** 见 raw_jni.cpp 里 meta 的排列。 */
    @JvmStatic external fun meta(handle: Long): DoubleArray
    /** 厂商、型号、镜头。 */
    @JvmStatic external fun text(handle: Long): Array<String>
    /** 半精度 RGB，传感器方向，行优先。指向原生内存，release 之后不能再用。 */
    @JvmStatic external fun pixels(handle: Long): ByteBuffer
    /** DNG OpcodeList1/2/3（list = 0/1/2）的原始字节。 */
    @JvmStatic external fun opcodes(handle: Long, list: Int): ByteArray?
    @JvmStatic external fun release(handle: Long)
}
