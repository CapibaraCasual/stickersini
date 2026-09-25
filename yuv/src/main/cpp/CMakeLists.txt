cmake_minimum_required(VERSION 3.22)
project(stickersini_yuv C)

add_library(stickersini_yuv SHARED
    jni/yuv_jni.c
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(stickersini_yuv
    PRIVATE
    ${log-lib}
    ${jnigraphics-lib}
)
