package com.cloudwebrtc.webrtc.utils;

/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.SystemClock;

import android.util.Log;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.YuvConverter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

public class FileVideoCapturer implements VideoCapturer {

    /**
     * Read video data from file for the .y4m container.
     */
    @SuppressWarnings("StringSplitter")

    private CapturerObserver capturerObserver;
    private Timer timer = null;

    private Bitmap bmp;
    private SurfaceTextureHelper surfaceTextureHelper;


    public void setBmp(byte[] bytes) {
        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    public void tick() {
        if (bmp == null) return;
        final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());
        YuvConverter yuvConverter = new YuvConverter();
        int[] textures = new int[1];
        GLES20.glGenTextures(0, textures, 0);
        TextureBufferImpl  buffer = new TextureBufferImpl(bmp.getWidth(), bmp.getHeight(), VideoFrame.TextureBuffer.Type.RGB, textures[0], new Matrix(), surfaceTextureHelper.getHandler(), yuvConverter, null);
        Bitmap flippedBitmap = createFlippedBitmap(bmp, true, false);
        surfaceTextureHelper.getHandler().post(() -> {
            if (flippedBitmap != null) {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flippedBitmap, 0);

                VideoFrame.I420Buffer i420Buf = yuvConverter.convert(buffer);

                VideoFrame videoFrame = new VideoFrame(i420Buf, 180, captureTimeNs);
                capturerObserver.onFrameCaptured(videoFrame);
                videoFrame.release();
            }
        });
    }


    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context context, CapturerObserver capturerObserver) {
        this.capturerObserver = capturerObserver;
        this.surfaceTextureHelper = surfaceTextureHelper;
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        if (timer != null) {
            timer.cancel();
        }
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        }, 0, 1000 / framerate);
    }

    @Override
    public void stopCapture() throws InterruptedException {
        timer.cancel();
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        // Empty on purpose
    }

    @Override
    public void dispose() {
        // Empty on purpose
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    private  void bitmapToI420(Bitmap src, JavaI420Buffer dest) {
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
    private static Bitmap createFlippedBitmap(Bitmap source, boolean xFlip, boolean yFlip) {
        try {
            Matrix matrix = new Matrix();
            matrix.postScale(xFlip ? -1 : 1, yFlip ? -1 : 1, source.getWidth() / 2f, source.getHeight() / 2f);
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (Exception e) {
            return null;
        }
    }
}