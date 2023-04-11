package com.cloudwebrtc.webrtc

import android.graphics.Bitmap
import org.webrtc.VideoFrame
import samplerenderer.HelloArRenderer
import java.nio.ByteBuffer
import java.util.Objects

import java.util.concurrent.locks.Lock

class ViewHolder {
    public var needsNewFrame: Boolean = true;

    public var view: HelloArRenderer? = null;

    public var frame: VideoFrame? = null;

    public var byteBuffer: ByteBuffer? = null;

    public var height: Int? = null;
    public var width: Int? = null;
}