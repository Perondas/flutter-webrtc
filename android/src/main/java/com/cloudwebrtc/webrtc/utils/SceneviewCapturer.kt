package com.cloudwebrtc.webrtc.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.PixelCopy.OnPixelCopyFinishedListener
import androidx.annotation.RequiresApi
import com.cloudwebrtc.webrtc.ViewHolder
import org.webrtc.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/*
*  Copyright 2016 The WebRTC Project Authors. All rights reserved.
*
*  Use of this source code is governed by a BSD-style license
*  that can be found in the LICENSE file in the root of the source
*  tree. An additional intellectual property rights grant can be found
*  in the file PATENTS.  All contributing project authors may
*  be found in the AUTHORS file in the root of the source tree.
*/

class SceneviewCapturer(private val holder: ViewHolder) : VideoCapturer {
    /**
     * Read video data from file for the .y4m container.
     */
    private var capturerObserver: CapturerObserver? = null
    private var timer: Timer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var counter = 200

    @RequiresApi(Build.VERSION_CODES.N)
    fun tick() {
        val captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
        val yuvConverter = YuvConverter()
        val textures = IntArray(1)
        if (counter > 0) {
            var bmp = Bitmap.createBitmap(200,200, Bitmap.Config.ARGB_8888)
            counter--
            val buffer = TextureBufferImpl(
                bmp!!.width, bmp!!.height, VideoFrame.TextureBuffer.Type.RGB,
                textures[0], Matrix(), surfaceTextureHelper!!.handler, yuvConverter, null
            )
            val flippedBitmap = createFlippedBitmap(bmp!!, false, false)
           // bmp!!.recycle()
            surfaceTextureHelper!!.handler.post {
                if (flippedBitmap != null) {
                    GLES20.glTexParameteri(
                        GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MIN_FILTER,
                        GLES20.GL_NEAREST
                    )
                    GLES20.glTexParameteri(
                        GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER,
                        GLES20.GL_NEAREST
                    )
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flippedBitmap, 0)
                    val i420Buf = yuvConverter.convert(buffer)
                    val videoFrame = VideoFrame(i420Buf, 180, captureTimeNs)
                    capturerObserver!!.onFrameCaptured(videoFrame)
                    videoFrame.release()
                }
            }
            if (holder.lastFrame != null) {
                holder.lastFrame!!.recycle()
                holder.lastFrame = null
            }
            holder.lastFrame = bmp
            return
        }
        if (holder.view == null) return
        val view = holder.view!!
        if (view.width == 0 || view.height == 0) return;


        // Draw view into bitmap backed canvas
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val handlerThread = HandlerThread(ThreadLocalRandom.current().nextInt(0, 1000000+ 1).toString())
        handlerThread.start()
        GLES20.glGenTextures(0, textures, 0)
        try {
            PixelCopy.request(view, bitmap, OnPixelCopyFinishedListener { copyResult: Int ->
                if (copyResult == PixelCopy.SUCCESS) {
                    val bmp = getResizedBitmap(bitmap, 500)
                    if (bmp != null) {
                        val buffer = TextureBufferImpl(
                            bmp!!.width, bmp!!.height, VideoFrame.TextureBuffer.Type.RGB,
                            textures[0], Matrix(), surfaceTextureHelper!!.handler, yuvConverter, null
                        )
                        val flippedBitmap = createFlippedBitmap(bmp!!, true, false)
                        surfaceTextureHelper!!.handler.post {
                            if (bmp != null) {
                                GLES20.glTexParameteri(
                                    GLES20.GL_TEXTURE_2D,
                                    GLES20.GL_TEXTURE_MIN_FILTER,
                                    GLES20.GL_NEAREST
                                )
                                GLES20.glTexParameteri(
                                    GLES20.GL_TEXTURE_2D,
                                    GLES20.GL_TEXTURE_MAG_FILTER,
                                    GLES20.GL_NEAREST
                                )
                                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                                val i420Buf = yuvConverter.convert(buffer)
                                val videoFrame = VideoFrame(i420Buf, 180, captureTimeNs)
                                capturerObserver!!.onFrameCaptured(videoFrame)
                                videoFrame.release()
                            }
                        }
                        if (holder.lastFrame != null) {
                            holder.lastFrame!!.recycle()
                            holder.lastFrame = null
                        }
                        holder.lastFrame = bmp
                    } else {
                        Log.d("Holder", "No bitmap")
                    }
                } else {
                    Log.d("Holder", "failed Copy")
                    Log.e("Pixel_copy-->", "Couldn't create bitmap of the SurfaceView")
                }
                handlerThread.quitSafely()
            }, Handler(handlerThread.looper))
        } catch (e: Exception) {
            Log.e("ScreenviewCapturer","Failed to capture screen ")
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        capturerObserver: CapturerObserver
    ) {
        this.capturerObserver = capturerObserver
        this.surfaceTextureHelper = surfaceTextureHelper
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (timer != null) {
            timer!!.cancel()
        }
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                tick()
            }
        }, 0, (1000 / framerate).toLong())
    }

    @Throws(InterruptedException::class)
    override fun stopCapture() {
        timer?.cancel()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Empty on purpose
    }

    override fun dispose() {
        // Empty on purpose
        timer?.cancel()
    }

    override fun isScreencast(): Boolean {
        return false
    }

    private fun bitmapToI420(src: Bitmap, dest: JavaI420Buffer) {
        val width = src.width
        val height = src.height
        if (width != dest.width || height != dest.height) return
        val strideY = dest.strideY
        val strideU = dest.strideU
        val strideV = dest.strideV
        val dataY = dest.dataY
        val dataU = dest.dataU
        val dataV = dest.dataV
        for (line in 0 until height) {
            if (line % 2 == 0) {
                var x = 0
                while (x < width) {
                    var px = src.getPixel(x, line)
                    var r = (px shr 16 and 0xff).toByte()
                    var g = (px shr 8 and 0xff).toByte()
                    var b = (px and 0xff).toByte()
                    dataY.put(line * strideY + x, ((66 * r + 129 * g + 25 * b shr 8) + 16).toByte())
                    dataU.put(
                        line / 2 * strideU + x / 2,
                        ((-38 * r + -74 * g + 112 * b shr 8) + 128).toByte()
                    )
                    dataV.put(
                        line / 2 * strideV + x / 2,
                        ((112 * r + -94 * g + -18 * b shr 8) + 128).toByte()
                    )
                    px = src.getPixel(x + 1, line)
                    r = (px shr 16 and 0xff).toByte()
                    g = (px shr 8 and 0xff).toByte()
                    b = (px and 0xff).toByte()
                    dataY.put(line * strideY + x, ((66 * r + 129 * g + 25 * b shr 8) + 16).toByte())
                    x += 2
                }
            } else {
                var x = 0
                while (x < width) {
                    val px = src.getPixel(x, line)
                    val r = (px shr 16 and 0xff).toByte()
                    val g = (px shr 8 and 0xff).toByte()
                    val b = (px and 0xff).toByte()
                    dataY.put(line * strideY + x, ((66 * r + 129 * g + 25 * b shr 8) + 16).toByte())
                    x += 1
                }
            }
        }
    }


    /**
     * reduces the size of the image
     *
     * @param image
     * @param maxSize
     * @return
     */
    fun getResizedBitmap(image: Bitmap, maxSize: Int): Bitmap? {
        val width = image.width
        val height = image.height
        return try {
            val bitmap = Bitmap.createScaledBitmap(image, width, height, true)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
            BitmapFactory.decodeStream(ByteArrayInputStream(out.toByteArray()))
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private fun createFlippedBitmap(source: Bitmap, xFlip: Boolean, yFlip: Boolean): Bitmap? {
            return try {
                val matrix = Matrix()
                matrix.postScale(
                    (if (xFlip) -1 else 1.toFloat()) as Float,
                    (if (yFlip) -1 else 1.toFloat()) as Float,
                    source.width / 2f,
                    source.height / 2f
                )
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            } catch (e: Exception) {
                null
            }
        }
    }
}