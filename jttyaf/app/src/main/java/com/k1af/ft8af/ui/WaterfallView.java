package com.k1af.ft8af.ui;
/**
 * Waterfall view custom control.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.graphics.Bitmap.Config.ARGB_8888;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.k1af.ft8af.timer.UtcTimer;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class WaterfallView extends View {
    private static final String TAG = "WaterfallView";
    private static final float FT8_SIGNAL_BANDWIDTH_HZ = 50f;

    private int blockHeight = 2;//Color block height
    private float freq_width = 1;//Frequency width
    private final int cycle = 2;
    private final int symbols = 93;
    private int spectrumWidth = 3500;//Spectrum display width in Hz
    private Bitmap lastBitMap = null;
    private Canvas _canvas;
    private final Paint linePaint = new Paint();
    private Paint touchPaint = new Paint();
    private final Paint fontPaint = new Paint();
    private final Paint messagePaint = new Paint();
    private final Paint textLinePaint = new Paint();
//    private final Paint messagePaintBack = new Paint();//Message background
    private final Paint utcPaint = new Paint();
    Paint linearPaint = new Paint();
    private final Paint utcPainBack = new Paint();
    private float pathStart = 0;
    private float pathEnd = 0;

    private int touch_x = -1;
    private int freq_hz = -1;
    private boolean drawMessage = false;//Whether to draw message content

    // Track the bitmap dimensions to avoid recreating on minor layout changes
    private int bitmapWidth = 0;
    private int bitmapHeight = 0;

    // TX frequency marker overlay
    private float txFrequency = -1f;
    private boolean txActive = false;
    private final Paint txMarkerPaint = new Paint();

    // JTTY has no T/R slots, so the UTC gridline is drawn on a fixed cadence: once every
    // TIMESTAMP_PERIOD_MS of wall-clock time (tracked by the last period index drawn).
    static final long TIMESTAMP_PERIOD_MS = 10_000L;
    private long lastTimestampPeriod = -1;
    private final Paint timestampLinePaint = new Paint();

    public WaterfallView(Context context) {
        super(context);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    /**
     * Convert dp value to pixels
     *
     * @param dp dp value
     * @return pixels
     */
    private int dpToPixel(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp
                , getResources().getDisplayMetrics());
    }

    /**
     * Height (px) of the existing waterfall region copied and scrolled down by one block on
     * each new spectrum row. Clamped to be non-negative: when the view is only one block
     * tall or shorter (e.g. measured at {@code h == 1}, where {@code blockHeight} clamps to
     * 1 and equals {@code drawHeight}), there is nothing above the new row to scroll, so the
     * caller must skip the {@code Bitmap.createBitmap} blit — that call throws
     * {@code IllegalArgumentException} on a non-positive height. Extracted as a pure static
     * helper so the geometry is unit-testable without a Canvas.
     */
    static int scrolledRegionHeight(int drawHeight, int blockHeight) {
        return Math.max(0, drawHeight - blockHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;

        // Skip bitmap recreation if the size hasn't meaningfully changed.
        // Compose layout can cause small oscillations in the view size;
        // recreating the bitmap each time wipes all accumulated waterfall data.
        if (lastBitMap != null && w == bitmapWidth && Math.abs(h - bitmapHeight) < 20) {
            return;
        }

        Log.d(TAG, String.format("onSizeChanged: w=%d h=%d oldw=%d oldh=%d", w, h, oldw, oldh));
        setClickable(true);
        bitmapWidth = w;
        bitmapHeight = h;
        blockHeight = h / (symbols * cycle);
        if (blockHeight < 1) blockHeight = 1;
        freq_width = (float) w / spectrumWidth;
        Log.d(TAG, String.format("Bitmap created: %dx%d, blockHeight=%d, freq_width=%.2f, spectrumWidth=%d",
                w, h, blockHeight, freq_width, spectrumWidth));
        lastBitMap = Bitmap.createBitmap(w, h, ARGB_8888);
        // Fresh bitmap wiped the UTC gridline, so redraw it on the next row instead of
        // waiting for the next period boundary.
        lastTimestampPeriod = -1;
        _canvas = new Canvas(lastBitMap);
        Paint blackPaint = new Paint();
        blackPaint.setColor(0xFF000000);
        _canvas.drawRect(0, 0, w, h, blackPaint);//Paint background black first to prevent text overlap

        //linePaint = new Paint();
        linePaint.setColor(0xff990000);
        touchPaint = new Paint();
        touchPaint.setColor(0xff00ffff);
        touchPaint.setStrokeWidth(getResources().getDisplayMetrics().density);


        //fontPaint = new Paint();
        fontPaint.setTextSize(dpToPixel(10));
        fontPaint.setColor(0xff00ffff);
        fontPaint.setAntiAlias(true);
        fontPaint.setDither(true);
        fontPaint.setTextAlign(Paint.Align.LEFT);


        textLinePaint.setColor(0xff00ffff);
        textLinePaint.setAntiAlias(true);
        textLinePaint.setDither(true);
        textLinePaint.setStrokeWidth(2);
        textLinePaint.setStyle(Paint.Style.FILL_AND_STROKE);


        // messagePaint = new Paint();
        messagePaint.setTextSize(dpToPixel(11));
        messagePaint.setColor(0xff00ffff);
        messagePaint.setAntiAlias(true);
        messagePaint.setDither(true);
        messagePaint.setStrokeWidth(0);
        messagePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        messagePaint.setTextAlign(Paint.Align.CENTER);
        messagePaint.setShadowLayer(10,5,5,Color.BLACK);

        //utcPaint = new Paint();
        utcPaint.setTextSize(dpToPixel(10));
        utcPaint.setColor(0xff00ffff);//
        utcPaint.setAntiAlias(true);
        utcPaint.setDither(true);
        utcPaint.setStrokeWidth(0);
        utcPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPaint.setTextAlign(Paint.Align.LEFT);


        //utcPainBack = new Paint();
        utcPainBack.setTextSize(dpToPixel(10));
        utcPainBack.setColor(0xff000000);//Opaque background
        utcPainBack.setAntiAlias(true);
        utcPainBack.setDither(true);
        utcPainBack.setStrokeWidth(dpToPixel(4));
        utcPainBack.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPainBack.setTextAlign(Paint.Align.LEFT);


        pathStart = blockHeight * 2;
        pathEnd = blockHeight * 90;
        if (pathEnd < 130 * getResources().getDisplayMetrics().density) {//Ensure there's enough space to write text
            pathEnd = 130 * getResources().getDisplayMetrics().density;
        }

        txMarkerPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        txMarkerPaint.setStyle(Paint.Style.STROKE);

        timestampLinePaint.setColor(0x6000ffff);
        timestampLinePaint.setStrokeWidth(1);
        timestampLinePaint.setStyle(Paint.Style.STROKE);

        super.onSizeChanged(w, h, oldw, oldh);

    }

    @SuppressLint("DefaultLocale")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lastBitMap == null) return;
        canvas.drawBitmap(lastBitMap, 0, 0, null);

        // Draw TX frequency marker lines
        if (txFrequency > 0 && freq_width > 0) {
            txMarkerPaint.setColor(0xFFEF4444);
            float halfBw = FT8_SIGNAL_BANDWIDTH_HZ / 2f;
            float x1 = SpectrumTouchMath.freqHzToPixelX(
                    txFrequency - halfBw, getWidth(), spectrumWidth);
            float x2 = SpectrumTouchMath.freqHzToPixelX(
                    txFrequency + halfBw, getWidth(), spectrumWidth);
            canvas.drawLine(x1, 0, x1, getHeight(), txMarkerPaint);
            canvas.drawLine(x2, 0, x2, getHeight(), txMarkerPaint);
        }

        //Draw touch cursor. freq_hz is computed in setTouch_x so getFreq_hz()
        //returns the current tap (issue #782) — reading it back here would be
        //stale on the ACTION_UP event that commits the base frequency. The blue
        //cursor is drawn at the pixel that maps back from freq_hz so it always
        //lines up with the red TX-bandwidth markers.
        if (SpectrumTouchMath.hasTapCursor(freq_hz)) {
            float cursorX = SpectrumTouchMath.freqHzToPixelX(
                    freq_hz, getWidth(), spectrumWidth);
            if (cursorX < 0) cursorX = touch_x;
            if (cursorX > getWidth() / 2f) {
                fontPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , cursorX - 10, 250, fontPaint);
            } else {
                fontPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , cursorX + 10, 250, fontPaint);
            }
            canvas.drawLine(cursorX, 0, cursorX, getHeight(), touchPaint);

        }
        // Do NOT call invalidate() here. The view is invalidated externally
        // when new data arrives via setWaveData(). Calling invalidate() from
        // onDraw() creates a continuous redraw loop that triggers layout
        // thrashing in Compose's AndroidView, causing onSizeChanged to fire
        // repeatedly and wipe all accumulated waterfall data.
    }

    public void setWaveData(int[] data) {
        if (data == null) {
            Log.w(TAG, "setWaveData: data is null, skipping");
            return;
        }
        if (data.length <= 0) {
            Log.w(TAG, "setWaveData: data is empty, skipping");
            return;
        }
        if (lastBitMap == null) {
            Log.w(TAG, "setWaveData: bitmap not initialized, skipping");
            return;
        }

        // Use bitmap dimensions for drawing, not getWidth()/getHeight() which
        // may differ from the bitmap size during Compose layout transitions.
        int drawWidth = bitmapWidth;
        int drawHeight = bitmapHeight;

        int[] colors = new int[data.length];

        //Color block distribution
        for (int i = 0; i < data.length; i++) {


            if (data[i] < 128) {//Below half volume, use blue 0~256
                colors[i] = 0xff000000 | (data[i] << 1);
            } else if (data[i] < 192) {
                colors[i] = 0xff0000ff | (((data[i] - 127)) << 10);//Amplify 4x
//                colors[i] = 0xff000000 | (data[i] * 2 * 256 + 255);
            } else {
                colors[i] = 0xff00ffff | (((data[i] - 127)) << 18);//Amplify 4x
            }
        }
        // Scale gradient so the visible portion (0..drawWidth) maps to 0..spectrumWidth Hz.
        // The FFT data covers 0 to the RX Nyquist (FT8Common.SAMPLE_RATE/2 = 6 kHz): RX
        // audio is always captured/decoded at 12 kHz, independent of the user-selectable
        // transmit audio rate (see SpectrumScale). We want spectrumWidth Hz to fill the
        // view, so the full gradient length = drawWidth * (Nyquist / spectrumWidth).
        float gradientScale = SpectrumScale.gradientScale(spectrumWidth);
        LinearGradient linearGradient = new LinearGradient(0, 0, drawWidth * gradientScale, 0, colors
                , null, Shader.TileMode.CLAMP);
        linearPaint.setShader(linearGradient);
        // Scroll the previously-drawn waterfall down by one block, then paint the new
        // spectrum row into the freed top strip. When the view is only a block tall (or
        // shorter) there is nothing above the new row to scroll: scrolledRegionHeight() is
        // 0 and we skip the blit — Bitmap.createBitmap rejects a non-positive height and
        // would otherwise throw IllegalArgumentException (seen when the view is measured at
        // h==1, where blockHeight clamps to 1 == drawHeight). The new-row paint still runs.
        int scrolledHeight = scrolledRegionHeight(drawHeight, blockHeight);
        if (scrolledHeight > 0) {
            Bitmap bitmap = Bitmap.createBitmap(lastBitMap, 0, 0, drawWidth, scrolledHeight);
            _canvas.drawBitmap(bitmap, 0, blockHeight, null);
            bitmap.recycle();
        }
        _canvas.drawRect(0, 0, drawWidth, blockHeight, linearPaint);

        // Draw a UTC timestamp line every TIMESTAMP_PERIOD_MS so the operator can read
        // elapsed time off the scrolling display (JTTY is asynchronous — there is no slot
        // boundary to mark, so this is a plain wall-clock grid).
        long utcMs = UtcTimer.getSystemTime();
        long period = utcMs / TIMESTAMP_PERIOD_MS;
        if (period != lastTimestampPeriod) {
            lastTimestampPeriod = period;
            // Draw horizontal line at the boundary between new row and scrolled content
            _canvas.drawLine(0, blockHeight, drawWidth, blockHeight, timestampLinePaint);
            // Format UTC time label
            long utcSec = utcMs / 1000;
            long h = (utcSec / 3600) % 24;
            long m = (utcSec % 3600) / 60;
            long s = utcSec % 60;
            @SuppressLint("DefaultLocale")
            String timeLabel = String.format("%02d:%02d:%02d", h, m, s);
            float textX = dpToPixel(2);
            float textY = blockHeight + utcPaint.getTextSize() + dpToPixel(1);
            // Draw outline then fill for readability over any spectrum color
            _canvas.drawText(timeLabel, textX, textY, utcPainBack);
            _canvas.drawText(timeLabel, textX, textY, utcPaint);
        }
    }

    public void setTouch_x(int touch_x) {
        this.touch_x = touch_x;
        // Compute freq_hz eagerly so a tap handler that reads getFreq_hz()
        // immediately after this call sees the fresh position instead of
        // whatever the last onDraw() left behind (issue #782). getWidth() is
        // 0 until the view is measured; guard so an early tap doesn't
        // divide by zero.
        this.freq_hz = SpectrumTouchMath.touchToFreqHz(touch_x, getWidth(), spectrumWidth);
    }

    public void setDrawMessage(boolean drawMessage) {
        this.drawMessage = drawMessage;
    }

    public int getFreq_hz() {
        return freq_hz;
    }

    public void setTxFrequency(float freq) {
        Log.d(TAG, String.format("setTxFrequency: %.1f Hz", freq));
        this.txFrequency = freq;
    }

    public void setTxActive(boolean active) {
        Log.d(TAG, String.format("setTxActive: %b", active));
        this.txActive = active;
    }

    public void setSpectrumWidth(int width) {
        Log.d(TAG, String.format("setSpectrumWidth: %d Hz (was %d)", width, spectrumWidth));
        this.spectrumWidth = width;
        if (bitmapWidth > 0) {
            freq_width = (float) bitmapWidth / spectrumWidth;
        }
    }
}
