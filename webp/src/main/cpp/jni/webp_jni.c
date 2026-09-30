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

// Cuerpo real de la codificación, con `quality` en `float` (lo que
// `WebPConfig.quality` es de verdad — ver `webp/encode.h`). Compartido por
// `nativeEncode` (calidad entera, la vía de producción/medición de
// siempre), `nativeEncodeFloat` (calidad fraccionaria, investigación de
// ADR-0022 sobre el salto de tamaño entre calidades enteras consecutivas)
// y `nativeEncodeTuned` (filtro de bloques configurable, investigación de
// bloques visibles en contenido adverso real — ver
// docs/desarrollo/pruebas.md). `filterStrength`/`autofilter`/
// `filterSharpness`/`snsStrength` son los mismos valores que
// `WebPConfigInit` ya deja por defecto (60/0/0/50) en `nativeEncode`/
// `nativeEncodeFloat` — pasarlos explícitos no cambia nada ahí, solo los
// hace configurables para `nativeEncodeTuned`.
static jbyteArray encodeAnimation(
        JNIEnv *env, jobjectArray bitmaps, jintArray durationsMs, jfloat quality,
        jboolean minimizeSize, jint method, jint threadLevel, jboolean useSharpYuv,
        jint filterStrength, jboolean autofilter, jint filterSharpness, jint snsStrength) {
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
    // minimize_size prueba cada fotograma como keyframe y como diferencia
    // contra el anterior y se queda con el más pequeño: más lento (el
    // propio header de libwebp lo marca "(slow)"). Antes se dejaba en 1
    // siempre, incluida cada pasada de la búsqueda de calidad en
    // WebpAnimEncoder — un descuido, no una decisión: multiplicaba el costo
    // lento por cada intento de la bisección en vez de pagarlo una sola vez
    // en el resultado final. Ahora lo decide quien llama (WebpAnimEncoder
    // usa minimizeSize=false durante la búsqueda y minimizeSize=true solo
    // en la pasada final). Ver ADR-0006 y docs/desarrollo/pruebas.md.
    encOptions.minimize_size = minimizeSize ? 1 : 0;

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
                        config.quality = quality;
                        // Antes se dejaba en el valor por defecto de
                        // WebPConfigInit (4) sin decirlo en ningún lado.
                        // Ahora lo decide quien llama: el camino de
                        // producción (NativeWebpEncoder.encode de 3
                        // argumentos) pasa 0 explícito (ADR-0006: la
                        // medición no mostró que un method más alto compre
                        // una mejora de tamaño que compense su costo). El
                        // parámetro sigue existiendo para poder medir otros
                        // valores (ver WebpEncodeMethodBenchmarkTest).
                        config.method = method;
                        // thread_level=1 solo pide multi-hilo; libwebp
                        // decide adentro si de verdad lo usa (ver
                        // analysis_enc.c: para method<=1, que es el caso de
                        // producción, ADR-0006, divide la fase de análisis
                        // en dos mitades entre el hilo principal y uno
                        // nuevo). No existe una vía pública para paralelizar
                        // fotogramas entre sí: WebPAnimEncoderAdd necesita
                        // el fotograma anterior para decidir si el actual
                        // sale como diferencia o como cuadro clave, así que
                        // solo puede llamarse en orden. Este parámetro
                        // existe para medir ese único paralelismo real
                        // dentro de un fotograma (ver
                        // WebpThreadLevelBenchmarkTest); producción sigue
                        // pasando 0 hasta que una medición diga lo contrario.
                        config.thread_level = threadLevel;
                        // use_sharp_yuv: RGB→YUV420 más nítido en bordes de
                        // alto contraste (texto, UI de una grabación de
                        // pantalla) a costa de tiempo de codificación — ver
                        // ADR-0022, medido en docs/desarrollo/pruebas.md.
                        config.use_sharp_yuv = useSharpYuv ? 1 : 0;
                        // Filtro de bloques (deblocking) post-cuantización:
                        // ver ADR-0022, investigación sobre bloques
                        // visibles en contenido adverso real (bordes de un
                        // personaje en movimiento). Valores por defecto de
                        // WebPConfigInit (60/off/0/50) salvo que
                        // nativeEncodeTuned pida otra cosa.
                        config.filter_strength = filterStrength;
                        config.autofilter = autofilter ? 1 : 0;
                        config.filter_sharpness = filterSharpness;
                        config.sns_strength = snsStrength;
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

// Valores por defecto de WebPConfigInit para el filtro de bloques (ver
// config_enc.c, WEBP_PRESET_DEFAULT): nativeEncode/nativeEncodeFloat los
// pasan explícitos para no duplicar el cuerpo, sin cambiar nada de lo ya
// medido en ADR-0022.
#define DEFAULT_FILTER_STRENGTH 60
#define DEFAULT_AUTOFILTER JNI_FALSE
#define DEFAULT_FILTER_SHARPNESS 0
#define DEFAULT_SNS_STRENGTH 50

JNIEXPORT jbyteArray JNICALL
Java_io_github_capibaracasual_stickersini_webp_NativeWebpEncoder_nativeEncode(
        JNIEnv *env, jclass clazz, jobjectArray bitmaps, jintArray durationsMs, jint quality,
        jboolean minimizeSize, jint method, jint threadLevel, jboolean useSharpYuv) {
    (void) clazz;
    return encodeAnimation(
            env, bitmaps, durationsMs, (jfloat) quality, minimizeSize, method, threadLevel, useSharpYuv,
            DEFAULT_FILTER_STRENGTH, DEFAULT_AUTOFILTER, DEFAULT_FILTER_SHARPNESS, DEFAULT_SNS_STRENGTH);
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_capibaracasual_stickersini_webp_NativeWebpEncoder_nativeEncodeFloat(
        JNIEnv *env, jclass clazz, jobjectArray bitmaps, jintArray durationsMs, jfloat quality,
        jboolean minimizeSize, jint method, jint threadLevel, jboolean useSharpYuv) {
    (void) clazz;
    return encodeAnimation(
            env, bitmaps, durationsMs, quality, minimizeSize, method, threadLevel, useSharpYuv,
            DEFAULT_FILTER_STRENGTH, DEFAULT_AUTOFILTER, DEFAULT_FILTER_SHARPNESS, DEFAULT_SNS_STRENGTH);
}

// Filtro de bloques configurable (ADR-0022, investigación): bloques
// visibles reportados en contenido adverso real (bordes de un personaje
// en movimiento) a calidad muy baja (RF-12 la fuerza para entrar en RF-10
// con este tipo de contenido). `threadLevel=0`, `useSharpYuv=false`
// fijos: ya medidos en ADR-0022, sin relación con lo que se investiga acá.
JNIEXPORT jbyteArray JNICALL
Java_io_github_capibaracasual_stickersini_webp_NativeWebpEncoder_nativeEncodeTuned(
        JNIEnv *env, jclass clazz, jobjectArray bitmaps, jintArray durationsMs, jfloat quality,
        jint method, jint filterStrength, jboolean autofilter, jint filterSharpness, jint snsStrength) {
    (void) clazz;
    return encodeAnimation(
            env, bitmaps, durationsMs, quality, JNI_FALSE, method, 0, JNI_FALSE,
            filterStrength, autofilter, filterSharpness, snsStrength);
}
