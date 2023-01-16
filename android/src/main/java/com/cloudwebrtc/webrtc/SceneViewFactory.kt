package com.cloudwebrtc.webrtc

import android.app.Activity
import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView

class SceneViewFactory(
    private val activity: Activity,
    private val binaryMessenger: BinaryMessenger,
    private val holder: ViewHolder
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, o: Any?): PlatformView {
        return SceneViewWrapper(context, activity, viewId, binaryMessenger, holder)
    }
}