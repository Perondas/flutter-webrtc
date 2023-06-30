package com.cloudwebrtc.webrtc.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.cloudwebrtc.webrtc.ViewHolder
import org.webrtc.*
import samplerenderer.GLError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.*
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

    private var textId: Int? = null

    @RequiresApi(Build.VERSION_CODES.N)
    fun tick() {

        val captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
        val yuvConverter = YuvConverter()

        var buf: ByteBuffer? = null
        var w: Int? = null
        var h: Int? = null

        synchronized(holder.lock) {
            if (holder.height == null) return
            if (holder.width == null) return

            if (holder.needsNewFrame) return
            if (holder.byteBuffer == null) return

            buf = holder.byteBuffer!!
            holder.byteBuffer = null
            holder.needsNewFrame = false

            w = holder.width
            h = holder.height
        }


        val frameWidth = w!!
        val frameHeight = h!!
        val byteBuffer = buf!!

        surfaceTextureHelper!!.handler.post {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textId!!)

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

            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth, frameHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, byteBuffer)
            GlUtil.checkNoGLES2Error("glTexImage2D")

            JniCommon.nativeFreeByteBuffer(byteBuffer)

            val m = Matrix()

            m.setScale(-1f, -1f)

            val texBuffer = TextureBufferImpl(frameWidth, frameHeight, VideoFrame.TextureBuffer.Type.RGB, textId!!, m, surfaceTextureHelper!!.handler, yuvConverter, null )
            val i420Buf = yuvConverter.convert(texBuffer)

            texBuffer.release()

            val videoFrame = VideoFrame(i420Buf, 180, captureTimeNs)

            capturerObserver!!.onFrameCaptured(videoFrame)
            videoFrame.release()
            yuvConverter.release()

            synchronized(holder.lock) {
                holder.needsNewFrame = true
            }
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        capturerObserver: CapturerObserver
    ) {
        this.capturerObserver = capturerObserver
        this.surfaceTextureHelper = surfaceTextureHelper

        val idArr = IntArray(1)
        GLES20.glGenTextures(1, idArr, 0)
        GlUtil.checkNoGLES2Error("glGenTextures")
        textId  = idArr[0]
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

        var i = IntArray(1)
        i[0] = textId!!

        GLES20.glDeleteTextures(1, i, 0)
        GlUtil.checkNoGLES2Error("glDeleteTextures")
    }

    override fun isScreencast(): Boolean {
        return false
    }
}


