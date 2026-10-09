# JTTYAF native module.
#
# Builds a single libjttyaf.so containing:
#   - The clean-room JTTY codec (jtty_lib: source coding, CRC-12 + tail-biting
#     convolutional code, 4-GFSK modulator, streaming receiver) and its JNI glue
#     (jttyaf_glue/jtty_jni.cpp)
#   - USB Audio Class capture/playback (libusb-backed)
#   - Waterfall FFT display (kissfft + fft_display)
#   - Audio resampler JNI (rational resampler / FIR decimator headers)
#   - hamlib CAT bridge
#
# The Java-side System.loadLibrary("jttyaf") call sites (UsbAudioNative,
# FT8Resample, SpectrumFragment, HamlibNative, NativeJttyCodec) were renamed
# together with this library; JNI symbol names are derived from the Java class
# names and are unaffected by the library name. The ft8af_glue/ directory keeps
# its heritage name because the JNI symbols inside it are bound to
# com.k1af.ft8af.* classes that must not change.
#
# Why libusb: Android's UsbRequest API silently fails to drive
# isochronous transfers on some kernels (notably automotive Android
# variants), throwing IllegalStateException on first queue. libusb
# uses USBDEVFS ioctls directly and is the most battle-tested option
# for USB audio on Android.

cmake_minimum_required(VERSION 3.18.1)

project(jttyaf_native)

# ---------------------------------------------------------------------------
# libusb 1.0.27 (static)
# ---------------------------------------------------------------------------
# Source list mirrors libusb/android/jni/libusb.mk — only the Linux/USBFS
# backend + POSIX threads/events; the macOS/Windows/Haiku/etc. backends in
# libusb/os/ are intentionally not compiled.

set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

add_library(usb1.0 STATIC
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)

target_include_directories(usb1.0
        PUBLIC
        ${LIBUSB_ROOT}/libusb
        PRIVATE
        ${LIBUSB_ROOT}/android
        ${LIBUSB_ROOT}/libusb/os
)

# libusb assumes POSIX threads, eventfd, timerfd, etc. — all present on
# Android. Hidden visibility matches upstream's Android build so we don't
# accidentally re-export libusb symbols from our .so.
target_compile_options(usb1.0 PRIVATE
        -fvisibility=hidden
        -pthread
        -Wno-pointer-arith
        -Wno-deprecated-declarations
)

# ---------------------------------------------------------------------------
# jtty_dsp — the clean-room JTTY codec (pure C11, host-testable)
# ---------------------------------------------------------------------------
# Same sources the host test scripts (jttyaf_glue/run_jtty_host_tests.*)
# compile; the glob keeps the two in sync as the codec grows. Re-run the CMake
# configure step after adding a file (Gradle does this on a clean build).

file(GLOB JTTY_LIB_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/jtty_lib/*.c)

add_library(jtty_dsp STATIC ${JTTY_LIB_SOURCES})

target_include_directories(jtty_dsp PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/jtty_lib
)

target_compile_options(jtty_dsp PRIVATE
        -std=c11
        -O2
        -Wall
        -Wno-unused-function
)

# ---------------------------------------------------------------------------
# hamlib (prebuilt static, per-ABI) — CAT protocol engine
# ---------------------------------------------------------------------------
# libhamlib.a is cross-compiled from the pinned source in hamlib/vendor/ by
# hamlib/build_android.sh. It is NOT checked into git (large + rebuildable) —
# the block below builds it on demand per ABI (see hamlib/.gitignore). hamlib
# supplies per-radio CAT framing; ft8af_glue/hamlib_jni.cpp drives it over a
# loopback-TCP socket bridged to the app's USB-serial transport. See
# hamlib/HAMLIB_PIN.txt for the design + build instructions.

set(HAMLIB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/hamlib)
set(HAMLIB_A ${HAMLIB_ROOT}/prebuilt/${ANDROID_ABI}/libhamlib.a)

# libhamlib.a is NOT checked into git (it is large and rebuildable). Produce it
# on demand: on Linux/macOS (incl. CI) run the cross-compile here; on Windows the
# autotools build can't run against the Windows NDK, so require a one-time WSL
# build. Once present it is reused (build_android.sh is skipped).
if(NOT EXISTS ${HAMLIB_A})
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        message(FATAL_ERROR
            "Missing ${HAMLIB_A}.\n"
            "On Windows, build the hamlib static libs once under WSL:\n"
            "  ANDROID_NDK_HOME=<linux-NDK> bash ${HAMLIB_ROOT}/build_android.sh\n"
            "See ${HAMLIB_ROOT}/HAMLIB_PIN.txt.")
    else()
        message(STATUS "hamlib: building libhamlib.a for ${ANDROID_ABI} (one-time)")
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_HOME=${CMAKE_ANDROID_NDK}
                    bash ${HAMLIB_ROOT}/build_android.sh ${ANDROID_ABI}
            RESULT_VARIABLE HAMLIB_BUILD_RC)
        if(NOT HAMLIB_BUILD_RC EQUAL 0)
            message(FATAL_ERROR "hamlib build failed for ${ANDROID_ABI} (rc=${HAMLIB_BUILD_RC})")
        endif()
    endif()
endif()

add_library(hamlib STATIC IMPORTED)
set_target_properties(hamlib PROPERTIES IMPORTED_LOCATION ${HAMLIB_A})

# ---------------------------------------------------------------------------
# libjttyaf.so — unified JNI bridge
# ---------------------------------------------------------------------------

add_library(jttyaf SHARED
        # USB audio
        usb_audio.cpp
        usb_audio_capture.cpp
        # kissfft (waterfall FFT)
        kissfft/kiss_fft.c
        kissfft/kiss_fftr.c
        # JNI wrappers — FFT display (waterfall)
        ft8af_glue/ft8_fft_jni.cpp
        ft8af_glue/fft_display.c
        # JNI wrappers — audio resampler
        ft8af_glue/ft8_resample_jni.cpp
        # JNI wrappers — JTTY codec (NativeJttyCodec)
        jttyaf_glue/jtty_jni.cpp
        # JNI wrappers — hamlib CAT bridge + Bionic API-gap shims
        ft8af_glue/hamlib_jni.cpp
        ft8af_glue/hamlib_compat.c
)

target_include_directories(jttyaf PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/kissfft
        ${HAMLIB_ROOT}/include
)

find_library(log-lib log)
find_library(android-lib android)
find_library(m-lib m)

target_link_options(jttyaf PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(jttyaf
        usb1.0
        jtty_dsp
        hamlib
        ${log-lib}
        ${android-lib}
        ${m-lib}
)
