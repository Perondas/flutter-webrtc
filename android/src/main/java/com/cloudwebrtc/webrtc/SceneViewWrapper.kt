package com.cloudwebrtc.webrtc
import android.R
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*
import com.google.ar.sceneform.ArSceneView
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.platform.PlatformView

class SceneViewWrapper(
    context: Context,
    private val activity: Activity,
    id: Int,
    private val messenger: BinaryMessenger,
    private val holder: ViewHolder
): PlatformView, MethodCallHandler {
    private val TAG: String = SceneViewWrapper::class.java.name

    private var mUserRequestedInstall = true

    private val sceneView: ArSceneView = ArSceneView(context)
    //private val _mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks
    private val _channel = MethodChannel(messenger, "scene_view_$id")

    init {
        _channel.setMethodCallHandler(this)
        setupLifeCycle()
        onResume()
        holder.view = sceneView;

        sceneView.scene.addOnUpdateListener {

        }
    }

    override fun getView(): View {
        return sceneView
    }

    override fun dispose() {
        activity.application.unregisterActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
        holder.view = null
        //_mainScope.cancel()
        try {
            onPause()
            onDestroy()
            ArSceneView.destroyAllResources()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

/*
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

 */

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
        // Create session if there is none
        if (sceneView.session == null) {
            Log.d(TAG, "ARSceneView session is null. Trying to initialize")
            try {
                var session: Session? =
                    if (ArCoreApk.getInstance().requestInstall(activity, mUserRequestedInstall) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    Log.d(TAG, "Install of ArCore APK requested")
                    null
                } else {
                    Session(activity)
                }

                if (session == null) {
                    // Ensures next invocation of requestInstall() will either return
                    // INSTALLED or throw an exception.
                    mUserRequestedInstall = false
                    return
                } else {
                    val config = Config(session)
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    config.focusMode = Config.FocusMode.AUTO

                    session.configure(config)
                    sceneView.setupSession(session)
                }
            } catch (ex: UnavailableUserDeclinedInstallationException) {
                // Display an appropriate message to the user zand return gracefully.
                Toast.makeText(
                    activity,
                    "TODO: handle exception " + ex.localizedMessage,
                    Toast.LENGTH_LONG)
                    .show()
                return
            } catch (ex: UnavailableArcoreNotInstalledException) {
                Toast.makeText(activity, "Please install ARCore", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableApkTooOldException) {
                Toast.makeText(activity, "Please update ARCore", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableSdkTooOldException) {
                Toast.makeText(activity, "Please update this app", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableDeviceNotCompatibleException) {
                Toast.makeText(activity, "This device does not support AR", Toast.LENGTH_LONG)
                    .show()
                return
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed to create AR session", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            sceneView.resume()
        } catch (ex: CameraNotAvailableException) {
            Log.d(TAG, "Unable to get camera$ex")
            activity.finish()
            return
        } catch (e : Exception){
            return
        }
    }

    private  fun onPause() {
        // hide instructions view if no longer required
        /*
        if (showAnimatedGuide){
            val view = activity.findViewById(R.id.content) as ViewGroup
            view.removeView(animatedGuide)
            showAnimatedGuide = false
        }

         */
        sceneView.pause()
    }

    private fun onDestroy() {
        try {
            sceneView.session?.close()
            sceneView.destroy()
            //sceneView.scene?.removeOnUpdateListener(sceneUpdateListener)
            //sceneView.scene?.removeOnPeekTouchListener(onNodeTapListener)
        }catch (e : Exception){
            e.printStackTrace();
        }
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
            result.success(null)
            return
        }
        result.notImplemented()
    }
}