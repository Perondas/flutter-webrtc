package com.cloudwebrtc.webrtc
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.loadHdrIndirectLight
import io.github.sceneview.loaders.loadHdrSkybox
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.nodes.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SceneViewWrapper(
    context: Context,
    private val activity: Activity,
    id: Int,
    private val messenger: BinaryMessenger,
    holder: ViewHolder
): PlatformView, MethodCallHandler {
    private val sceneView: SceneView = SceneView(context)
    private val _mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks
    private val _channel = MethodChannel(messenger, "scene_view_$id")

    init {
        _channel.setMethodCallHandler(this)
        setupLifeCycle()
        sceneView.pause()
        sceneView.resume()
        holder.view = sceneView;
    }

    override fun getView(): View {
        return sceneView
    }

    override fun dispose() {
        activity.application.unregisterActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
        _mainScope.cancel()
    }


    private suspend fun showModel() {
        val hdrFile = "environments/studio_small_09_2k.hdr"
        sceneView.loadHdrIndirectLight(hdrFile, specularFilter = true) {
            intensity(30_000f)
        }
        sceneView.loadHdrSkybox(hdrFile) {
            intensity(50_000f)
        }

        val model = sceneView.modelLoader.loadModel("models/MaterialSuite.glb")!!
        val modelNode = ModelNode(sceneView, model).apply {
            transform(
                position = Position(z = -4.0f),
                rotation = Rotation(x = 15.0f)
            )
            scaleToUnitsCube(2.0f)
            // TODO: Fix centerOrigin
            //     centerOrigin(Position(x=-1.0f, y=-1.0f))
            playAnimation()
        }
        sceneView.addChildNode(modelNode)
        Log.d("Done", "Done")
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
                sceneView.resume()
            }

            override fun onActivityPaused(activity: Activity) {
                Log.d("Wrapper","Paused")
                sceneView.pause()
            }

            override fun onActivityStopped(activity: Activity) {

            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                Log.d("Wrapper","Destroyed")
                sceneView.destroy()
            }
        }

        activity.application.registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
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
            _mainScope.launch {
                showModel()
            }
            result.success(null)
            return
        }
        result.notImplemented()
    }
}