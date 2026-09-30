// Capa JNI de :yuv (ADR-0011). Convierte el cuadrado centrado de un plano
// YUV_420_888 a un Bitmap ARGB_8888, con la misma fórmula BT.601 entera que
// tenía la referencia en Kotlin (YuvFrameConverter en :app) — el test de
// paridad píxel a píxel entre ambas vive en :app/src/androidTest, porque
// necesita la referencia Kotlin, que :yuv no conoce a propósito (la
// dirección de dependencia es :app -> :yuv, nunca al revés).

#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>

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

// Bicúbico Catmull-Rom (a=-0.5), separable por eje pero calculado en un
// solo recorrido de 4x4 por píxel de salida, sin buffer intermedio
// (ADR-0022): reemplaza el bilineal de `Bitmap.createScaledBitmap` en el
// recorte+zoom (RF-07) y en la importación de imagen — medido que
// bilineal se ve notoriamente más borroso al ampliar un recorte más chico
// que 512×512 (el caso típico al hacer zoom), ver docs/desarrollo/pruebas.md.
static inline float cubicWeight(float x) {
    const float a = -0.5f;
    float ax = fabsf(x);
    if (ax <= 1.0f) {
        return (a + 2.0f) * ax * ax * ax - (a + 3.0f) * ax * ax + 1.0f;
    }
    if (ax < 2.0f) {
        return a * ax * ax * ax - 5.0f * a * ax * ax + 8.0f * a * ax - 4.0f * a;
    }
    return 0.0f;
}

// Prefiltro de promedio de área (ADR-0022, corrección): el bicúbico de
// 4 taps fijos de arriba es un filtro de INTERPOLACIÓN, pensado para
// ampliar — para reducir por un factor mayor a ~2×, muestrear solo 4
// vecinos por eje salta píxeles de origen sin promediarlos (alias). Antes
// de bisecar, se reduce por promedio de área 2×2 en pasadas sucesivas
// hasta que ambos ejes queden a lo sumo al doble del tamaño de destino —
// recién ahí el bicúbico de 4 taps ve una imagen ya prefiltrada, sin
// perder información entre muestras. Devuelve `NULL` si no hizo falta
// reducir (factor ya ≤2×); si hizo falta, devuelve un buffer nuevo
// (`malloc`, filas sin relleno) que el llamador debe liberar con `free`.
static uint8_t *reduceByAreaIfNeeded(
        const uint8_t *src, int32_t srcStride, int32_t srcW, int32_t srcH,
        int32_t dstW, int32_t dstH, int32_t *outW, int32_t *outH, int32_t *outStride) {
    if (srcW <= dstW * 2 && srcH <= dstH * 2) {
        return NULL;
    }

    const uint8_t *curBuf = src;
    int32_t curStride = srcStride;
    int32_t curW = srcW;
    int32_t curH = srcH;
    uint8_t *owned = NULL;

    while (curW > dstW * 2 || curH > dstH * 2) {
        int32_t nextW = (curW + 1) / 2;
        int32_t nextH = (curH + 1) / 2;
        uint8_t *next = (uint8_t *) malloc((size_t) nextW * (size_t) nextH * 4);
        if (next == NULL) {
            break; // sin memoria: seguir con lo que ya se redujo, mejor que nada
        }
        for (int32_t y = 0; y < nextH; y++) {
            int32_t y0 = y * 2;
            int32_t y1 = (y0 + 1 < curH) ? y0 + 1 : y0;
            const uint8_t *row0 = curBuf + (size_t) y0 * (size_t) curStride;
            const uint8_t *row1 = curBuf + (size_t) y1 * (size_t) curStride;
            uint8_t *outRow = next + (size_t) y * (size_t) nextW * 4;
            for (int32_t x = 0; x < nextW; x++) {
                int32_t x0 = x * 2;
                int32_t x1 = (x0 + 1 < curW) ? x0 + 1 : x0;
                for (int32_t c = 0; c < 4; c++) {
                    int32_t sum = row0[x0 * 4 + c] + row0[x1 * 4 + c] + row1[x0 * 4 + c] + row1[x1 * 4 + c];
                    outRow[x * 4 + c] = (uint8_t) ((sum + 2) / 4);
                }
            }
        }
        free(owned);
        owned = next;
        curBuf = next;
        curStride = nextW * 4;
        curW = nextW;
        curH = nextH;
    }

    *outW = curW;
    *outH = curH;
    *outStride = curStride;
    return owned;
}

JNIEXPORT void JNICALL
Java_io_github_capibaracasual_stickersini_yuv_NativeYuvConverter_nativeResizeBicubic(
        JNIEnv *env, jclass clazz, jobject srcBitmap, jobject dstBitmap) {
    (void) clazz;

    AndroidBitmapInfo srcInfo;
    AndroidBitmapInfo dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwConversionException(env, "No se pudo leer la información del bitmap de origen");
        return;
    }
    if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwConversionException(env, "No se pudo leer la información del bitmap de destino");
        return;
    }
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwConversionException(env, "Los bitmaps deben ser ARGB_8888");
        return;
    }

    void *srcPixels = NULL;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwConversionException(env, "No se pudo bloquear los píxeles del bitmap de origen");
        return;
    }
    void *dstPixels = NULL;
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        throwConversionException(env, "No se pudo bloquear los píxeles del bitmap de destino");
        return;
    }

    const int32_t dstW = (int32_t) dstInfo.width;
    const int32_t dstH = (int32_t) dstInfo.height;

    int32_t effW;
    int32_t effH;
    int32_t effStride;
    uint8_t *reduced = reduceByAreaIfNeeded(
            (const uint8_t *) srcPixels, (int32_t) srcInfo.stride, (int32_t) srcInfo.width, (int32_t) srcInfo.height,
            dstW, dstH, &effW, &effH, &effStride);
    const uint8_t *effSrc = reduced != NULL ? reduced : (const uint8_t *) srcPixels;
    if (reduced == NULL) {
        effW = (int32_t) srcInfo.width;
        effH = (int32_t) srcInfo.height;
        effStride = (int32_t) srcInfo.stride;
    }

    const float scaleX = (float) effW / (float) dstW;
    const float scaleY = (float) effH / (float) dstH;

    for (int32_t oy = 0; oy < dstH; oy++) {
        float sy = ((float) oy + 0.5f) * scaleY - 0.5f;
        int32_t iy = (int32_t) floorf(sy);
        float fy = sy - (float) iy;
        float wy[4];
        for (int32_t n = 0; n < 4; n++) wy[n] = cubicWeight((float) (n - 1) - fy);

        uint8_t *outRow = (uint8_t *) dstPixels + (size_t) oy * (size_t) dstInfo.stride;

        for (int32_t ox = 0; ox < dstW; ox++) {
            float sx = ((float) ox + 0.5f) * scaleX - 0.5f;
            int32_t ix = (int32_t) floorf(sx);
            float fx = sx - (float) ix;
            float wx[4];
            for (int32_t m = 0; m < 4; m++) wx[m] = cubicWeight((float) (m - 1) - fx);

            float accum[4] = {0.0f, 0.0f, 0.0f, 0.0f};
            for (int32_t n = 0; n < 4; n++) {
                int32_t sampleY = iy + n - 1;
                if (sampleY < 0) sampleY = 0;
                if (sampleY >= effH) sampleY = effH - 1;
                const uint8_t *srcRow = effSrc + (size_t) sampleY * (size_t) effStride;
                for (int32_t m = 0; m < 4; m++) {
                    int32_t sampleX = ix + m - 1;
                    if (sampleX < 0) sampleX = 0;
                    if (sampleX >= effW) sampleX = effW - 1;
                    const uint8_t *srcPixel = srcRow + (size_t) sampleX * 4;
                    float weight = wx[m] * wy[n];
                    accum[0] += weight * (float) srcPixel[0];
                    accum[1] += weight * (float) srcPixel[1];
                    accum[2] += weight * (float) srcPixel[2];
                    accum[3] += weight * (float) srcPixel[3];
                }
            }

            uint8_t *outPixel = outRow + (size_t) ox * 4;
            outPixel[0] = clamp8((int32_t) lroundf(accum[0]));
            outPixel[1] = clamp8((int32_t) lroundf(accum[1]));
            outPixel[2] = clamp8((int32_t) lroundf(accum[2]));
            outPixel[3] = clamp8((int32_t) lroundf(accum[3]));
        }
    }

    free(reduced);

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
}

// Suavizado gaussiano 3x3 liviano (ADR-0022, investigación): pesos
// 1-2-1/2-4-2/1-2-1 (suma 16), por canal, con los bordes de la imagen
// repetidos (mismo criterio de recorte que el bicúbico de arriba) —
// antes de codificar, para ver si reduce el bloqueo visible en contenido
// adverso real a calidad muy baja (RF-12 fuerza esa calidad para entrar
// en RF-10). In-place no es seguro (cada píxel de salida lee vecinos que
// ya se habrían sobreescrito), así que escribe siempre a un bitmap nuevo.
JNIEXPORT void JNICALL
Java_io_github_capibaracasual_stickersini_yuv_NativeYuvConverter_nativeLightBlur(
        JNIEnv *env, jclass clazz, jobject srcBitmap, jobject dstBitmap) {
    (void) clazz;

    AndroidBitmapInfo srcInfo;
    AndroidBitmapInfo dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwConversionException(env, "No se pudo leer la información de un bitmap");
        return;
    }
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwConversionException(env, "Los bitmaps deben ser ARGB_8888");
        return;
    }
    if (srcInfo.width != dstInfo.width || srcInfo.height != dstInfo.height) {
        throwConversionException(env, "El bitmap de destino debe medir lo mismo que el de origen");
        return;
    }

    void *srcPixels = NULL;
    void *dstPixels = NULL;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwConversionException(env, "No se pudo bloquear los píxeles del bitmap de origen");
        return;
    }
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        throwConversionException(env, "No se pudo bloquear los píxeles del bitmap de destino");
        return;
    }

    const int32_t w = (int32_t) srcInfo.width;
    const int32_t h = (int32_t) srcInfo.height;
    static const int32_t kernel[3][3] = {{1, 2, 1}, {2, 4, 2}, {1, 2, 1}};

    for (int32_t y = 0; y < h; y++) {
        uint8_t *outRow = (uint8_t *) dstPixels + (size_t) y * (size_t) dstInfo.stride;
        for (int32_t x = 0; x < w; x++) {
            int32_t accum[4] = {0, 0, 0, 0};
            for (int32_t dy = -1; dy <= 1; dy++) {
                int32_t sy = y + dy;
                if (sy < 0) sy = 0;
                if (sy >= h) sy = h - 1;
                const uint8_t *srcRow = (const uint8_t *) srcPixels + (size_t) sy * (size_t) srcInfo.stride;
                for (int32_t dx = -1; dx <= 1; dx++) {
                    int32_t sx = x + dx;
                    if (sx < 0) sx = 0;
                    if (sx >= w) sx = w - 1;
                    const uint8_t *srcPixel = srcRow + (size_t) sx * 4;
                    int32_t weight = kernel[dy + 1][dx + 1];
                    accum[0] += weight * srcPixel[0];
                    accum[1] += weight * srcPixel[1];
                    accum[2] += weight * srcPixel[2];
                    accum[3] += weight * srcPixel[3];
                }
            }
            uint8_t *outPixel = outRow + (size_t) x * 4;
            outPixel[0] = clamp8((accum[0] + 8) / 16);
            outPixel[1] = clamp8((accum[1] + 8) / 16);
            outPixel[2] = clamp8((accum[2] + 8) / 16);
            outPixel[3] = clamp8((accum[3] + 8) / 16);
        }
    }

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
}
