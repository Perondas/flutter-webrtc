package com.cloudwebrtc.webrtc

import android.R.attr.*
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.platform.PlatformView
import samplerenderer.HelloArRenderer
import samplerenderer.SampleRender
import samplerenderer.helpers.ARCoreSessionLifecycleHelper
import samplerenderer.helpers.DepthSettings
import samplerenderer.helpers.InstantPlacementSettings
import java.nio.ByteBuffer


class SceneViewWrapper(
    val context: Context,
    val activity: Activity,
    id: Int,
    private val messenger: BinaryMessenger,
    private val holder: ViewHolder
): PlatformView, MethodCallHandler {
    private val TAG: String = SceneViewWrapper::class.java.name


    val view: GLSurfaceView = GLSurfaceView(context)
    private lateinit var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks

    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private lateinit var renderer: HelloArRenderer

    val instantPlacementSettings = InstantPlacementSettings()
    val depthSettings = DepthSettings()

    private val _channel = MethodChannel(messenger, "scene_view_$id")

    init {
        arCoreSessionHelper = ARCoreSessionLifecycleHelper(activity)

        arCoreSessionHelper.exceptionCallback =
            { exception ->
                val message =
                    when (exception) {
                        is UnavailableUserDeclinedInstallationException ->
                            "Please install Google Play Services for AR"
                        is UnavailableApkTooOldException -> "Please update ARCore"
                        is UnavailableSdkTooOldException -> "Please update this app"
                        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                        is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                        else -> "Failed to create AR session: $exception"
                    }
                Log.e(TAG, "ARCore threw an exception", exception)
            }

        arCoreSessionHelper.beforeSessionResume = ::configureSession

        renderer = HelloArRenderer(this, holder)



        SampleRender(view, renderer, context.assets)

        //activity.setContentView(view)

        depthSettings.onCreate(context)
        instantPlacementSettings.onCreate(context)

        _channel.setMethodCallHandler(this)
        setupLifeCycle()

        //onPause()

    }

    override fun getView(): View {
        return view
    }

    override fun dispose() {
        activity.application.unregisterActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
        //_mainScope.cancel()
        try {
            onPause()
            onDestroy()
           // ArSceneView.destroyAllResources()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Configure the session, using Lighting Estimation, and Depth mode.
    private fun configureSession(session: Session) {
        session.configure(
            session.config.apply {
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

                // Depth API is used if it is configured in Hello AR's settings.
                depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }

                // Instant Placement is used if it is configured in Hello AR's settings.
                instantPlacementMode =
                    if (instantPlacementSettings.isInstantPlacementEnabled) {
                        Config.InstantPlacementMode.LOCAL_Y_UP
                    } else {
                        Config.InstantPlacementMode.DISABLED
                    }
            }
        )
    }

    private fun setupLifeCycle() {
        activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                Log.d("c","c")
            }

            override fun onActivityStarted(activity: Activity) {
                Log.d("s","s")
            }

            override fun onActivityResumed(activity: Activity) {
                Log.d("Wrapper","Resumed")

                onResume()
            }

            override fun onActivityPaused(activity: Activity) {
                Log.d("Wrapper","Paused")
                onPause()
            }

            override fun onActivityStopped(activity: Activity) {

            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                Log.d("Wrapper","Destroyed")
                onDestroy()
            }
        }

        activity.application.registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
    }

    private fun onResume() {
        view.onResume()
        arCoreSessionHelper.onResume()
        renderer.onResume()

    }

    private  fun onPause() {
        view.onPause()
        renderer.onPause()
        arCoreSessionHelper.onPause()
    }

    private fun onDestroy() {
        arCoreSessionHelper.onDestroy()
    }

    /**
     * Handles the specified method call received from Flutter.
     *
     *
     * Handler implementations must submit a result for all incoming calls, by making a single
     * call on the given [Result] callback. Failure to do so will result in lingering Flutter
     * result handlers. The result may be submitted asynchronously and on any thread. Calls to
     * unknown or unimplemented methods should be handled using [Result.notImplemented].
     *
     *
     * Any uncaught exception thrown by this method will be caught by the channel implementation
     * and logged, and an error result will be sent back to Flutter.
     *
     *
     * The handler is called on the platform thread (Android main thread). For more details see
     * [Threading in
 * the Flutter Engine](https://github.com/flutter/engine/wiki/Threading-in-the-Flutter-Engine).
     *
     * @param call A [MethodCall].
     * @param result A [Result] used for submitting the result of the call.
     */
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        // Methods called from the method channel in sceneview_controller.dart will end up here
        // Simply interact wiht your sceneView instance from here
        if (call.method == "showDemo") {
            onResume()
            result.success(null)
            return
        }

        if (call.method == "markRequest") {
            synchronized(renderer.requestMutex) {
                renderer.request = result
            }
            return
        }

        else if (call.method == "markNow") {
            val x: Double = call.argument("x")!!
            val y: Double = call.argument("y")!!
            synchronized(renderer.markMutex) {
                renderer.markRequest?.x = x.toFloat()
                renderer.markRequest?.y = y.toFloat()
                renderer.markRequest?.ret = result
            }
            return
        }

        result.notImplemented()
    }
}

data class MarkRequest(
    val width: Int,
    val height: Int,
    val viewM: FloatArray,
    val projM: FloatArray,
    val anchor: Anchor,
    val frame: Bitmap
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MarkRequest

        if (width != other.width) return false
        if (height != other.height) return false
        if (!viewM.contentEquals(other.viewM)) return false
        if (!projM.contentEquals(other.projM)) return false
        if (anchor != other.anchor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + viewM.contentHashCode()
        result = 31 * result + projM.contentHashCode()
        result = 31 * result + anchor.hashCode()
        return result
    }
}