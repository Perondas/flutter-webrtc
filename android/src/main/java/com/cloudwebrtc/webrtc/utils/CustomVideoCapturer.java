package com.cloudwebrtc.webrtc.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import androidx.annotation.RequiresApi;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.YuvConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(api = Build.VERSION_CODES.N)
public class CustomVideoCapturer implements VideoCapturer, VideoSink {

    private static int VIEW_CAPTURER_FRAMERATE_MS = 10;
    private int width;
    private int height;
    private SurfaceView view;
    private Context context;
    private CapturerObserver capturerObserver;
    private SurfaceTextureHelper surfaceTextureHelper;
    private boolean isDisposed;
    private Bitmap viewBitmap;
    private Handler handlerPixelCopy = new Handler(Looper.getMainLooper());
    private Handler handler = new Handler(Looper.getMainLooper());
    private AtomicBoolean started = new AtomicBoolean(false);
    private long numCapturedFrames;
    private YuvConverter yuvConverter = new YuvConverter();
    private TextureBufferImpl buffer;
    private long start = System.nanoTime();
    private final Runnable viewCapturer = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.N)
        @Override
        public void run() {
            boolean dropFrame = view.getWidth() == 0 || view.getHeight() == 0;

            // Only capture the view if the dimensions have been established
            if (!dropFrame) {
                // Draw view into bitmap backed canvas
                final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                final HandlerThread handlerThread = new HandlerThread("handlerThread");
                handlerThread.start();
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        PixelCopy.request(view, bitmap, copyResult -> {
                            if (copyResult == PixelCopy.SUCCESS) {
                                viewBitmap = getResizedBitmap(bitmap, 500);
                                if (viewBitmap != null) {
                                    Log.d("BITMAP--->", viewBitmap.toString());
                                    sendToServer(viewBitmap, yuvConverter, start);
                                }
                            } else {
                                Log.e("Pixel_copy-->", "Couldn't create bitmap of the SurfaceView");
                            }
                            handlerThread.quitSafely();
                        }, new Handler(handlerThread.getLooper()));
                    } else {
                        Log.i("Pixel_copy-->", "Saving an image of a SurfaceView is only supported from API 24");
                    }
                } catch (Exception ignored) {
                }
            }
        }
    };
    private Thread captureThread;

    public CustomVideoCapturer(SurfaceView view, int framePerSecond) {
        if (framePerSecond <= 0)
            throw new IllegalArgumentException("framePersecond must be greater than 0");
        this.view = view;
        float tmp = (1f / framePerSecond) * 1000;
        VIEW_CAPTURER_FRAMERATE_MS = Math.round(tmp);
    }

    private static void bitmapToI420(Bitmap src, JavaI420Buffer dest) {
        int width = src.getWidth();
        int height = src.getHeight();
        if (width != dest.getWidth() || height != dest.getHeight())
            return;
        int strideY = dest.getStrideY();
        int strideU = dest.getStrideU();
        int strideV = dest.getStrideV();
        ByteBuffer dataY = dest.getDataY();
        ByteBuffer dataU = dest.getDataU();
        ByteBuffer dataV = dest.getDataV();
        for (int line = 0; line < height; line++) {
            if (line % 2 == 0) {
                for (int x = 0; x < width; x += 2) {
                    int px = src.getPixel(x, line);
                    byte r = (byte) ((px >> 16) & 0xff);
                    byte g = (byte) ((px >> 8) & 0xff);
                    byte b = (byte) (px & 0xff);
                    dataY.put(line * strideY + x, (byte) (((66 * r + 129 * g + 25 * b) >> 8) + 16));
                    dataU.put(line / 2 * strideU + x / 2, (byte) (((-38 * r + -74 * g + 112 * b) >> 8) + 128));
                    dataV.put(line / 2 * strideV + x / 2, (byte) (((112 * r + -94 * g + -18 * b) >> 8) + 128));
                    px = src.getPixel(x + 1, line);
                    r = (byte) ((px >> 16) & 0xff);
                    g = (byte) ((px >> 8) & 0xff);
                    b = (byte) (px & 0xff);
                    dataY.put(line * strideY + x, (byte) (((66 * r + 129 * g + 25 * b) >> 8) + 16));
                }
            } else {
                for (int x = 0; x < width; x += 1) {
                    int px = src.getPixel(x, line);
                    byte r = (byte) ((px >> 16) & 0xff);
                    byte g = (byte) ((px >> 8) & 0xff);
                    byte b = (byte) (px & 0xff);
                    dataY.put(line * strideY + x, (byte) (((66 * r + 129 * g + 25 * b) >> 8) + 16));
                }
            }
        }
    }

    public static Bitmap createFlippedBitmap(Bitmap source, boolean xFlip, boolean yFlip) {
        try {
            Matrix matrix = new Matrix();
            matrix.postScale(xFlip ? -1 : 1, yFlip ? -1 : 1, source.getWidth() / 2f, source.getHeight() / 2f);
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (Exception e) {
            return null;
        }
    }

    private void checkNotDisposed() {
        if (this.isDisposed) {
            throw new RuntimeException("capturer is disposed.");
        }
    }

    @Override
    public synchronized void initialize(SurfaceTextureHelper surfaceTextureHelper, Context context, CapturerObserver capturerObserver) {
        this.checkNotDisposed();
        if (capturerObserver == null) {
            throw new RuntimeException("capturerObserver not set.");
        } else {
            this.context = context;
            this.capturerObserver = capturerObserver;
            if (surfaceTextureHelper == null) {
                throw new RuntimeException("surfaceTextureHelper not set.");
            } else {
                this.surfaceTextureHelper = surfaceTextureHelper;
            }
        }
    }

    @Override
    public void startCapture(int width, int height, int fps) {
        this.checkNotDisposed();
        this.started.set(true);
        this.width = width;
        this.height = height;
        this.capturerObserver.onCapturerStarted(true);
        this.surfaceTextureHelper.startListening(this);
        handler.postDelayed(viewCapturer, VIEW_CAPTURER_FRAMERATE_MS);


    /*try {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        HandlerThread handlerThread = new HandlerThread(CustomVideoCapturer.class.getSimpleName());
        capturerObserver.onCapturerStarted(true);
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        YuvConverter yuvConverter = new YuvConverter();
        TextureBufferImpl buffer = new TextureBufferImpl(width, height, VideoFrame.TextureBuffer.Type.RGB, textures[0], new Matrix(), surfaceTextureHelper.getHandler(), yuvConverter, null);
     //   handlerThread.start();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            new Thread(() -> {
                while (true) {
                    PixelCopy.request(view, bitmap, copyResult -> {
                        if (copyResult == PixelCopy.SUCCESS) {
                            viewBitmap = getResizedBitmap(bitmap, 500);
                            long start = System.nanoTime();
                            Log.d("BITMAP--->", viewBitmap.toString());
                            sendToServer(viewBitmap, yuvConverter, buffer, start);
                        } else {
                            Log.e("Pixel_copy-->", "Couldn't create bitmap of the SurfaceView");
                        }
                        handlerThread.quitSafely();
                    }, new Handler(Looper.getMainLooper()));
                }
            }).start();
        }
    } catch (Exception ignored) {
    }*/
    }

    private void sendToServer(Bitmap bitmap, YuvConverter yuvConverter, long start) {
        try {
            int[] textures = new int[1];
            GLES20.glGenTextures(0, textures, 0);
            buffer = new TextureBufferImpl(width, height, VideoFrame.TextureBuffer.Type.RGB, textures[0], new Matrix(), surfaceTextureHelper.getHandler(), yuvConverter, null);
            Bitmap flippedBitmap = createFlippedBitmap(bitmap, true, false);
            surfaceTextureHelper.getHandler().post(() -> {
                if (flippedBitmap != null) {
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flippedBitmap, 0);

                    VideoFrame.I420Buffer i420Buf = yuvConverter.convert(buffer);

                    long frameTime = System.nanoTime() - start;
                    VideoFrame videoFrame = new VideoFrame(i420Buf, 180, frameTime);
                    capturerObserver.onFrameCaptured(videoFrame);
                    videoFrame.release();
                    try {
                        viewBitmap.recycle();
                    } catch (Exception e) {

                    }
                    handler.postDelayed(viewCapturer, VIEW_CAPTURER_FRAMERATE_MS);
                }
            });
        } catch (Exception ignored) {

        }
    }

    @Override
    public void stopCapture() throws InterruptedException {
        this.checkNotDisposed();
        CustomVideoCapturer.this.surfaceTextureHelper.stopListening();
        CustomVideoCapturer.this.capturerObserver.onCapturerStopped();
        started.set(false);
        handler.removeCallbacksAndMessages(null);
        handlerPixelCopy.removeCallbacksAndMessages(null);
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        this.checkNotDisposed();
        this.width = width;
        this.height = height;
    }

    @Override
    public void dispose() {
        this.isDisposed = true;
    }

    @Override
    public boolean isScreencast() {
        return true;
    }

    private void sendFrame() {
        final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

    /*surfaceTextureHelper.setTextureSize(width, height);

    int[] textures = new int[1];
    GLES20.glGenTextures(1, textures, 0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);

    Matrix matrix = new Matrix();
    matrix.preTranslate(0.5f, 0.5f);
    matrix.preScale(1f, -1f);
    matrix.preTranslate(-0.5f, -0.5f);


    YuvConverter yuvConverter = new YuvConverter();
    TextureBufferImpl buffer = new TextureBufferImpl(width, height,
            VideoFrame.TextureBuffer.Type.RGB, textures[0],  matrix,
            surfaceTextureHelper.getHandler(), yuvConverter, null);

    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, viewBitmap, 0);

    long frameTime = System.nanoTime() - captureTimeNs;
    VideoFrame videoFrame = new VideoFrame(buffer.toI420(), 0, frameTime);
    capturerObserver.onFrameCaptured(videoFrame);
    videoFrame.release();

    handler.postDelayed(viewCapturer, VIEW_CAPTURER_FRAMERATE_MS);*/

        // Create video frame
        JavaI420Buffer buffer = JavaI420Buffer.allocate(viewBitmap.getWidth(), viewBitmap.getHeight());
        bitmapToI420(viewBitmap, buffer);
        VideoFrame videoFrame = new VideoFrame(buffer,
                0, captureTimeNs);

        // Notify the listener
        if (started.get()) {
            ++this.numCapturedFrames;this.capturerObserver.onFrameCaptured(videoFrame);
        }
        if (started.get()) {
            handler.postDelayed(viewCapturer, VIEW_CAPTURER_FRAMERATE_MS);
        }
    }

    public long getNumCapturedFrames() {
        return this.numCapturedFrames;
    }

    /**
     * reduces the size of the image
     *
     * @param image
     * @param maxSize
     * @return
     */
    public Bitmap getResizedBitmap(Bitmap image, int maxSize) {
        int width = image.getWidth();
        int height = image.getHeight();

        try {
            Bitmap bitmap =  Bitmap.createScaledBitmap(image, width, height, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
            return BitmapFactory.decodeStream(new ByteArrayInputStream(out.toByteArray()));

        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onFrame(VideoFrame videoFrame) {

    }


}
