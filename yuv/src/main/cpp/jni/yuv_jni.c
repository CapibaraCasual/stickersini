// Capa JNI de :yuv (ADR-0011). Convierte el cuadrado centrado de un plano
// YUV_420_888 a un Bitmap ARGB_8888, con la misma fórmula BT.601 entera que
// tenía la referencia en Kotlin (YuvFrameConverter en :app) — el test de
// paridad píxel a píxel entre ambas vive en :app/src/androidTest, porque
// necesita la referencia Kotlin, que :yuv no conoce a propósito (la
// dirección de dependencia es :app -> :yuv, nunca al revés).

#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>

static void throwConversionException(JNIEnv *env, const char *message) {
    jclass exceptionClass = (*env)->FindClass(
            env, "io/github/capibaracasual/stickersini/yuv/YuvConversionException");
    if (exceptionClass != NULL) {
        (*env)->ThrowNew(env, exceptionClass, message);
    }
}

static inline uint8_t clamp8(int32_t value) {
    if (value < 0) return 0;
    if (value > 255) return 255;
    return (uint8_t) value;
}

JNIEXPORT void JNICALL
Java_io_github_capibaracasual_stickersini_yuv_NativeYuvConverter_nativeConvert(
        JNIEnv *env, jclass clazz, jobject bitmap,
        jobject yBuffer, jint yRowStride,
        jobject uBuffer, jint uRowStride, jint uPixelStride,
        jobject vBuffer, jint vRowStride, jint vPixelStride,
        jint xOffset, jint yOffset, jint size) {
    (void) clazz;

    const uint8_t *yData = (const uint8_t *) (*env)->GetDirectBufferAddress(env, yBuffer);
    const uint8_t *uData = (const uint8_t *) (*env)->GetDirectBufferAddress(env, uBuffer);
    const uint8_t *vData = (const uint8_t *) (*env)->GetDirectBufferAddress(env, vBuffer);
    if (yData == NULL || uData == NULL || vData == NULL) {
        throwConversionException(env, "Los planos YUV deben ser buffers directos");
        return;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwConversionException(env, "No se pudo leer la información del bitmap de salida");
        return;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwConversionException(env, "El bitmap de salida debe ser ARGB_8888");
        return;
    }
    if ((jint) info.width != size || (jint) info.height != size) {
        throwConversionException(env, "El bitmap de salida debe medir size x size");
        return;
    }

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwConversionException(env, "No se pudo bloquear los píxeles del bitmap de salida");
        return;
    }

    for (jint row = 0; row < size; row++) {
        jint sourceRow = row + yOffset;
        const uint8_t *yRow = yData + (size_t) sourceRow * (size_t) yRowStride;
        jint uvRow = sourceRow / 2;
        const uint8_t *uRow = uData + (size_t) uvRow * (size_t) uRowStride;
        const uint8_t *vRow = vData + (size_t) uvRow * (size_t) vRowStride;
        uint8_t *outRow = (uint8_t *) pixels + (size_t) row * (size_t) info.stride;

        for (jint col = 0; col < size; col++) {
            jint sourceCol = col + xOffset;
            int32_t y = (int32_t) yRow[sourceCol] - 16;
            jint uvCol = sourceCol / 2;
            int32_t u = (int32_t) uRow[uvCol * uPixelStride] - 128;
            int32_t v = (int32_t) vRow[uvCol * vPixelStride] - 128;

            // Misma fórmula BT.601 entera que la referencia en Kotlin: no
            // cambiar un lado sin cambiar el otro, o el test de paridad
            // deja de tener sentido.
            int32_t yScaled = 298 * y;
            uint8_t *outPixel = outRow + (size_t) col * 4;
            outPixel[0] = clamp8((yScaled + 409 * v + 128) >> 8);
            outPixel[1] = clamp8((yScaled - 100 * u - 208 * v + 128) >> 8);
            outPixel[2] = clamp8((yScaled + 516 * u + 128) >> 8);
            outPixel[3] = 0xFF;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}
