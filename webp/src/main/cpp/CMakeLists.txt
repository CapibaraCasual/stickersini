cmake_minimum_required(VERSION 3.22)
project(stickersini_webp C)

# Solo hace falta codificar. Se apaga todo lo que el CMakeLists.txt de
# libwebp construye por defecto (herramientas de línea de comandos,
# utilidades de animación, extras) y que este proyecto no usa: ver
# decisions/0005-libwebp-vendorizado-no-submodulo-ni-binding.md sobre qué
# directorios de la copia vendorizada dependen de cada opción.
set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_FUZZTEST OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBP_JS OFF CACHE BOOL "" FORCE)
# libwebpmux es donde vive WebPAnimEncoder (src/mux/anim_encode.c).
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)

add_subdirectory(third_party/libwebp)

add_library(stickersini_webp SHARED
    jni/webp_jni.c
)

target_include_directories(stickersini_webp PRIVATE
    third_party/libwebp/src
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(stickersini_webp
    PRIVATE
    webp
    libwebpmux
    ${log-lib}
    ${jnigraphics-lib}
)
