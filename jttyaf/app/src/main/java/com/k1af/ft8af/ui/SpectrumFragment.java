package com.k1af.ft8af.ui;

/**
 * Host for the waterfall's native FFT entry points.
 *
 * <p>Historically the legacy View-based spectrum screen; that UI is gone (the Compose
 * {@code WaterfallScreen} draws the spectrum), but the class survives because the JNI
 * symbols in {@code cpp/ft8af_glue/ft8_fft_jni.cpp} are bound to this exact class name
 * ({@code Java_com_k1af_ft8af_ui_SpectrumFragment_*}). {@code WaterfallScreen}'s
 * {@code FFTBridge} instantiates one of these and calls through it.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */
public class SpectrumFragment {

    static {
        System.loadLibrary("jttyaf");
    }

    public native void getFFTData(int[] data, int fftData[]);

    public native void getFFTDataFloat(float[] data, int fftData[]);

    public native void getFFTDataRaw(int[] data, int fftData[]);

    public native void getFFTDataRawFloat(float[] data, int fftData[]);

    /**
     * Sets the display FFT window function and cross-frame averaging mode
     * (issue #428). Values are the wire format shared with the native side:
     * window 0=Rect 1=Hann 2=Hamming 3=Blackman 4=Blackman-Harris;
     * averaging 0=off 1=EMA(0.5) 2=EMA(0.25). Affects the waterfall/spectrum
     * display only, never decoding. Must be called on the same thread as the
     * getFFTData* methods (both run on the main-thread LiveData observer).
     */
    public static native void setFFTDisplayParams(int windowType, int averagingMode);

}