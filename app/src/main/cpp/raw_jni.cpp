// RAW 解码：LibRaw 读文件 → 线性 sRGB（D65 日光白平衡、不套曲线、不截断高光余量）→ 半精度浮点 RGB。
// 输出保持传感器方向（不旋转），方向、白平衡系数、相机矩阵、DNG 操作码交给上层自己处理。
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "libraw/libraw.h"

#define TAG "XiuTuRaw"

namespace {

struct Decoded {
    int width = 0, height = 0;
    uint16_t *pixels = nullptr; // RGB half，width*height*3
    std::vector<double> meta;
    std::vector<uint8_t> opcodes[3];
    std::string make, model, lens;
    ~Decoded() { free(pixels); }
};

std::mutex gLock;
std::string gError;

// float → IEEE 半精度（四舍五入，溢出到 65504）
inline uint16_t toHalf(float f) {
    uint32_t x;
    memcpy(&x, &f, 4);
    uint32_t sign = (x >> 16) & 0x8000;
    int32_t exp = (int32_t)((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffff;
    if (exp <= 0) {
        if (exp < -10) return (uint16_t)sign;
        mant |= 0x800000;
        uint32_t shift = (uint32_t)(14 - exp);
        uint32_t h = mant >> shift;
        if ((mant >> (shift - 1)) & 1) h++;
        return (uint16_t)(sign | h);
    }
    if (exp >= 31) return (uint16_t)(sign | 0x7bff);
    uint32_t h = sign | ((uint32_t)exp << 10) | (mant >> 13);
    if (mant & 0x1000) h++;
    return (uint16_t)h;
}

Decoded *decodeFile(const char *path, bool half, int maxDim) {
    LibRaw *rp = new LibRaw();
    auto &P = rp->imgdata.params;
    P.output_color = 1;     // sRGB 原色
    P.output_bps = 16;
    P.gamm[0] = 1.0;        // 线性
    P.gamm[1] = 1.0;
    P.no_auto_bright = 1;
    P.use_camera_wb = 0;    // 按日光（D65）白平衡解，拍摄白平衡交给着色器
    P.use_auto_wb = 0;
    P.use_camera_matrix = 1;
    P.highlight = 2;        // 混合：高光不偏色，并保留通道间的余量
    P.half_size = half ? 1 : 0;
    P.user_qual = half ? 0 : 3; // 原尺寸用 AHD
    P.user_flip = 0;        // 不旋转（imgdata.image 本来就是传感器方向）

    int r = rp->open_file(path);
    if (r == LIBRAW_SUCCESS) r = rp->unpack();
    if (r != LIBRAW_SUCCESS) {
        gError = libraw_strerror(r);
        delete rp;
        return nullptr;
    }
    auto &C = rp->imgdata.color;
    auto &S = rp->imgdata.sizes;
    auto &I = rp->imgdata.idata;
    auto &O = rp->imgdata.other;

    auto *d = new Decoded();
    // 处理之前记下白平衡系数和相机矩阵（处理时会被归一化）
    double preMul[4], camMul[4];
    for (int c = 0; c < 4; c++) { preMul[c] = C.pre_mul[c]; camMul[c] = C.cam_mul[c]; }
    int cfa[4];
    for (int i = 0; i < 4; i++) cfa[i] = I.filters ? rp->COLOR(i / 2, i % 2) : -1;
    for (int i = 0; i < 3; i++) {
        auto &op = C.dng_levels.rawopcodes[i];
        if (op.data && op.len > 0) d->opcodes[i].assign((uint8_t *)op.data, (uint8_t *)op.data + op.len);
    }
    d->make = I.make;
    d->model = I.model;
    d->lens = rp->imgdata.lens.Lens;
    int rawW = S.width, rawH = S.height;
    int flip = S.flip;

    r = rp->dcraw_process();
    if (r != LIBRAW_SUCCESS || !rp->imgdata.image) {
        gError = libraw_strerror(r);
        delete d;
        delete rp;
        return nullptr;
    }
    int iw = S.iwidth, ih = S.iheight;
    // 解码后再取一次矩阵（rgb_cam 处理前后不变；保险起见用处理后的）
    double rgbCam[12];
    for (int i = 0; i < 3; i++) for (int j = 0; j < 4; j++) rgbCam[i * 4 + j] = C.rgb_cam[i][j];

    int ow = iw, oh = ih;
    if (maxDim > 0 && (iw > maxDim || ih > maxDim)) {
        double s = (double)maxDim / (iw > ih ? iw : ih);
        ow = (int)std::max(1.0, std::floor(iw * s));
        oh = (int)std::max(1.0, std::floor(ih * s));
    }
    d->width = ow;
    d->height = oh;
    d->pixels = (uint16_t *)malloc((size_t)ow * oh * 3 * sizeof(uint16_t));
    if (!d->pixels) {
        gError = "内存不足";
        delete d;
        delete rp;
        return nullptr;
    }
    const ushort(*img)[4] = rp->imgdata.image;
    const float k = 1.0f / 65535.0f;
    if (ow == iw && oh == ih) {
        size_t n = (size_t)iw * ih;
#pragma omp parallel for
        for (long i = 0; i < (long)n; i++) {
            for (int c = 0; c < 3; c++) d->pixels[i * 3 + c] = toHalf(img[i][c] * k);
        }
    } else {
        // 面积平均缩小
#pragma omp parallel for
        for (int y = 0; y < oh; y++) {
            int y0 = (int)((long)y * ih / oh), y1 = (int)((long)(y + 1) * ih / oh);
            if (y1 <= y0) y1 = y0 + 1;
            for (int x = 0; x < ow; x++) {
                int x0 = (int)((long)x * iw / ow), x1 = (int)((long)(x + 1) * iw / ow);
                if (x1 <= x0) x1 = x0 + 1;
                double acc[3] = {0, 0, 0};
                for (int yy = y0; yy < y1; yy++) {
                    const ushort(*row)[4] = img + (size_t)yy * iw;
                    for (int xx = x0; xx < x1; xx++) {
                        acc[0] += row[xx][0];
                        acc[1] += row[xx][1];
                        acc[2] += row[xx][2];
                    }
                }
                double inv = k / ((double)(y1 - y0) * (x1 - x0));
                uint16_t *o = d->pixels + ((size_t)y * ow + x) * 3;
                for (int c = 0; c < 3; c++) o[c] = toHalf((float)(acc[c] * inv));
            }
        }
    }

    auto &m = d->meta;
    m.assign(48, 0.0);
    m[0] = ow; m[1] = oh; m[2] = flip; m[3] = rawW; m[4] = rawH;
    for (int c = 0; c < 4; c++) { m[5 + c] = preMul[c]; m[9 + c] = camMul[c]; }
    for (int i = 0; i < 12; i++) m[13 + i] = rgbCam[i];
    m[25] = C.dng_levels.baseline_exposure;
    m[26] = O.iso_speed; m[27] = O.shutter; m[28] = O.aperture; m[29] = O.focal_len;
    m[30] = I.colors; m[31] = I.filters ? 1 : 0;
    for (int i = 0; i < 4; i++) m[32 + i] = cfa[i];
    m[36] = I.dng_version ? 1 : 0;
    m[37] = iw; m[38] = ih; // 解码出来的尺寸（缩小前）
    rp->recycle();
    delete rp;
    return d;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_taoo_xiutu_raw_RawNative_decode(JNIEnv *env, jclass, jstring jpath, jboolean half, jint maxDim) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    Decoded *d = nullptr;
    {
        std::lock_guard<std::mutex> g(gLock);
        gError.clear();
        try {
            d = decodeFile(path, half, maxDim);
        } catch (...) {
            gError = "解码异常";
            d = nullptr;
        }
    }
    env->ReleaseStringUTFChars(jpath, path);
    if (!d) __android_log_print(ANDROID_LOG_WARN, TAG, "decode failed: %s", gError.c_str());
    return (jlong)(intptr_t)d;
}

JNIEXPORT jstring JNICALL
Java_com_taoo_xiutu_raw_RawNative_lastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(gError.c_str());
}

JNIEXPORT jdoubleArray JNICALL
Java_com_taoo_xiutu_raw_RawNative_meta(JNIEnv *env, jclass, jlong h) {
    auto *d = (Decoded *)(intptr_t)h;
    jdoubleArray a = env->NewDoubleArray((jsize)d->meta.size());
    env->SetDoubleArrayRegion(a, 0, (jsize)d->meta.size(), d->meta.data());
    return a;
}

JNIEXPORT jobjectArray JNICALL
Java_com_taoo_xiutu_raw_RawNative_text(JNIEnv *env, jclass, jlong h) {
    auto *d = (Decoded *)(intptr_t)h;
    jobjectArray a = env->NewObjectArray(3, env->FindClass("java/lang/String"), nullptr);
    env->SetObjectArrayElement(a, 0, env->NewStringUTF(d->make.c_str()));
    env->SetObjectArrayElement(a, 1, env->NewStringUTF(d->model.c_str()));
    env->SetObjectArrayElement(a, 2, env->NewStringUTF(d->lens.c_str()));
    return a;
}

JNIEXPORT jobject JNICALL
Java_com_taoo_xiutu_raw_RawNative_pixels(JNIEnv *env, jclass, jlong h) {
    auto *d = (Decoded *)(intptr_t)h;
    return env->NewDirectByteBuffer(d->pixels, (jlong)d->width * d->height * 3 * sizeof(uint16_t));
}

JNIEXPORT jbyteArray JNICALL
Java_com_taoo_xiutu_raw_RawNative_opcodes(JNIEnv *env, jclass, jlong h, jint list) {
    auto *d = (Decoded *)(intptr_t)h;
    if (list < 0 || list > 2 || d->opcodes[list].empty()) return nullptr;
    auto &v = d->opcodes[list];
    jbyteArray a = env->NewByteArray((jsize)v.size());
    env->SetByteArrayRegion(a, 0, (jsize)v.size(), (const jbyte *)v.data());
    return a;
}

JNIEXPORT void JNICALL
Java_com_taoo_xiutu_raw_RawNative_release(JNIEnv *, jclass, jlong h) {
    delete (Decoded *)(intptr_t)h;
}

} // extern "C"
