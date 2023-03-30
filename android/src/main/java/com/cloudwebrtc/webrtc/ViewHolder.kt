package com.cloudwebrtc.webrtc

import android.graphics.Bitmap
import org.webrtc.VideoFrame
import samplerenderer.HelloArRenderer

class ViewHolder {
    public var view: HelloArRenderer? = null;

    public var frame: VideoFrame? = null;

    public var height: Int? = null;
    public var width: Int? = null;
}