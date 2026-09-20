// Capa JNI propia sobre libwebp vendorizado (ver ADR-0005). Codifica una
// sola vez, a la calidad que se le pida: no reintenta ni ajusta nada. El
// ajuste automático de RF-12 (bisección de calidad, reducción de
// fotogramas) vive en Kotlin, en WebpAnimEncoder, para poder probarlo con
// JUnit normal sin necesitar un dispositivo.

#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>

#include "webp/encode.h"
#include "webp/mux.h"

static void throwEncodeException(JNIEnv *env, const char *message) {
    jclass exceptionClass = (*env)->FindClass(
        env, "io/github/capibaracasual/stickersini/webp/WebpEncodeException");
    if (exceptionClass != NULL) {
        (*env)->ThrowNew(env, exceptionClass, message);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_capibaracasual_stickersini_webp_NativeWebpEncoder_nativeEncode(
        JNIEnv *env, jclass clazz, jobjectArray bitmaps, jintArray durationsMs, jint quality) {
    (void) clazz;

    jsize frameCount = (*env)->GetArrayLength(env, bitmaps);
    if (frameCount <= 0) {
        throwEncodeException(env, "Se necesita al menos 1 fotograma");
        return NULL;
    }
    if ((*env)->GetArrayLength(env, durationsMs) != frameCount) {
        throwEncodeException(env, "El número de duraciones no coincide con el de fotogramas");
        return NULL;
    }

    jint *durations = (*env)->GetIntArrayElements(env, durationsMs, NULL);
    if (durations == NULL) {
        throwEncodeException(env, "No se pudieron leer las duraciones de los fotogramas");
        return NULL;
    }

    jobject firstBitmap = (*env)->GetObjectArrayElement(env, bitmaps, 0);
    AndroidBitmapInfo canonicalInfo;
    if (AndroidBitmap_getInfo(env, firstBitmap, &canonicalInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        (*env)->ReleaseIntArrayElements(env, durationsMs, durations, JNI_ABORT);
        (*env)->DeleteLocalRef(env, firstBitmap);
        throwEncodeException(env, "No se pudo leer la información del primer bitmap");
        return NULL;
    }
    (*env)->DeleteLocalRef(env, firstBitmap);
    if (canonicalInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        (*env)->ReleaseIntArrayElements(env, durationsMs, durations, JNI_ABORT);
        throwEncodeException(env, "Los bitmaps deben ser ARGB_8888");
        return NULL;
    }

    WebPAnimEncoderOptions encOptions;
    if (!WebPAnimEncoderOptionsInit(&encOptions)) {
        (*env)->ReleaseIntArrayElements(env, durationsMs, durations, JNI_ABORT);
        throwEncodeException(env, "No se pudo inicializar WebPAnimEncoderOptions");
        return NULL;
    }
    // Prioriza tamaño de archivo sobre velocidad de codificación: RF-10 es
    // un límite duro, no un objetivo aproximado.
    encOptions.minimize_size = 1;

    WebPAnimEncoder *encoder = WebPAnimEncoderNew(
            (int) canonicalInfo.width, (int) canonicalInfo.height, &encOptions);
    if (encoder == NULL) {
        (*env)->ReleaseIntArrayElements(env, durationsMs, durations, JNI_ABORT);
        throwEncodeException(env, "No se pudo crear WebPAnimEncoder");
        return NULL;
    }

    int timestampMs = 0;
    int ok = 1;

    for (jsize i = 0; i < frameCount && ok; i++) {
        jobject bitmap = (*env)->GetObjectArrayElement(env, bitmaps, i);

        AndroidBitmapInfo frameInfo;
        if (AndroidBitmap_getInfo(env, bitmap, &frameInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throwEncodeException(env, "No se pudo leer la información de un fotograma");
            ok = 0;
        } else if (frameInfo.width != canonicalInfo.width || frameInfo.height != canonicalInfo.height) {
            throwEncodeException(env, "Todos los fotogramas deben medir lo mismo que el primero");
            ok = 0;
        } else if (frameInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            throwEncodeException(env, "Los bitmaps deben ser ARGB_8888");
            ok = 0;
        }

        void *pixels = NULL;
        if (ok && AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throwEncodeException(env, "No se pudo bloquear los píxeles de un fotograma");
            ok = 0;
        }

        if (ok) {
            WebPPicture picture;
            if (!WebPPictureInit(&picture)) {
                throwEncodeException(env, "No se pudo inicializar WebPPicture");
                ok = 0;
            } else {
                picture.width = (int) canonicalInfo.width;
                picture.height = (int) canonicalInfo.height;
                picture.use_argb = 1;

                if (!WebPPictureImportRGBA(&picture, (const uint8_t *) pixels, (int) frameInfo.stride)) {
                    throwEncodeException(env, "No se pudo importar un fotograma a WebPPicture");
                    ok = 0;
                }

                AndroidBitmap_unlockPixels(env, bitmap);

                if (ok) {
                    WebPConfig config;
                    if (!WebPConfigInit(&config)) {
                        throwEncodeException(env, "No se pudo inicializar WebPConfig");
                        ok = 0;
                    } else {
                        config.lossless = 0;
                        config.quality = (float) quality;
                        if (!WebPValidateConfig(&config)) {
                            throwEncodeException(env, "Configuración de codificación inválida");
                            ok = 0;
                        } else if (!WebPAnimEncoderAdd(encoder, &picture, timestampMs, &config)) {
                            throwEncodeException(env, "No se pudo añadir un fotograma a la animación");
                            ok = 0;
                        } else {
                            timestampMs += durations[i];
                        }
                    }
                }

                WebPPictureFree(&picture);
            }
        }

        (*env)->DeleteLocalRef(env, bitmap);
    }

    (*env)->ReleaseIntArrayElements(env, durationsMs, durations, JNI_ABORT);

    if (ok && !WebPAnimEncoderAdd(encoder, NULL, timestampMs, NULL)) {
        throwEncodeException(env, "No se pudo cerrar la animación");
        ok = 0;
    }

    jbyteArray result = NULL;
    if (ok) {
        WebPData webpData;
        WebPDataInit(&webpData);
        if (!WebPAnimEncoderAssemble(encoder, &webpData)) {
            throwEncodeException(env, "No se pudo ensamblar el WebP animado");
        } else {
            result = (*env)->NewByteArray(env, (jsize) webpData.size);
            if (result != NULL) {
                (*env)->SetByteArrayRegion(
                        env, result, 0, (jsize) webpData.size, (const jbyte *) webpData.bytes);
            } else {
                throwEncodeException(env, "No se pudo reservar el array de salida");
            }
        }
        WebPDataClear(&webpData);
    }

    WebPAnimEncoderDelete(encoder);

    return result;
}
