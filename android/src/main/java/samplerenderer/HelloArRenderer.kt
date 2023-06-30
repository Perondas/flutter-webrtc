/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package samplerenderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.cloudwebrtc.webrtc.SceneViewWrapper
import com.cloudwebrtc.webrtc.ViewHolder
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import io.flutter.plugin.common.MethodChannel
import org.webrtc.JniCommon
import samplerenderer.arcore.BackgroundRenderer
import samplerenderer.arcore.PlaneRenderer
import samplerenderer.arcore.SpecularCubemapFilter
import samplerenderer.helpers.DisplayRotationHelper
import samplerenderer.helpers.TrackingStateHelper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.concurrent.thread


/** Renders the HelloAR application using our example Renderer. */
class HelloArRenderer(val activity: SceneViewWrapper, val holder: ViewHolder) :
  SampleRender.Renderer {
  companion object {
    val TAG = "HelloArRenderer"

    // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
    // constants.
    private val sphericalHarmonicFactors =
      floatArrayOf(
        0.282095f,
        -0.325735f,
        0.325735f,
        -0.325735f,
        0.273137f,
        -0.273137f,
        0.078848f,
        -0.273137f,
        0.136569f
      )

    private val Z_NEAR = 0.1f
    private val Z_FAR = 100f

    // Assumed distance from the device camera to the surface on which user will try to place
    // objects.
    // This value affects the apparent scale of objects while the tracking method of the
    // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
    // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
    // values for AR experiences where users are expected to place objects on surfaces close to the
    // camera. Use larger values for experiences where the user will likely be standing and trying
    // to
    // place an object on the ground or floor in front of them.
    val APPROXIMATE_DISTANCE_METERS = 2.0f

    val CUBEMAP_RESOLUTION = 16
    val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
  }

  //private val scope = CoroutineScope(Dispatchers.Main)

  public val requestMutex: Object = Object()
  public var request: MethodChannel.Result? = null
  public val markMutex: Object = Object()
  public var markRequest: MarkStore? = null

  lateinit var render: SampleRender
  private lateinit var planeRenderer: PlaneRenderer
  private lateinit var backgroundRenderer: BackgroundRenderer
  var virtualSceneFramebuffer: Framebuffer? = null
  private var hasSetTextureNames = false

  // Point Cloud
  private lateinit var pointCloudVertexBuffer: VertexBuffer
  private lateinit var pointCloudMesh: Mesh
  private lateinit var pointCloudShader: Shader

  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  private var lastPointCloudTimestamp: Long = 0

  // Virtual object (ARCore pawn)
  private lateinit var virtualObjectMesh: Mesh
  private lateinit var virtualObjectShader: Shader
  private lateinit var virtualObjectAlbedoTexture: Texture
  private lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

  private val wrappedAnchors = mutableListOf<WrappedAnchor>()

  // Environmental HDR
  private lateinit var dfgTexture: Texture
  private lateinit var cubemapFilter: SpecularCubemapFilter

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private val modelMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val projectionMatrix = FloatArray(16)
  private val modelViewMatrix = FloatArray(16) // view x model

  private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  private val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
  private val viewInverseMatrix = FloatArray(16)
  private val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
  private val viewLightDirection = FloatArray(4) // view x world light direction

  private val session
    get() = activity.arCoreSessionHelper.session

  private val displayRotationHelper = DisplayRotationHelper(activity.context)
  private val trackingStateHelper = TrackingStateHelper(activity.activity)

 fun onResume() {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

 fun onPause() {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      planeRenderer = PlaneRenderer(render)
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)



      cubemapFilter =
        SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
      // Load environmental lighting values lookup table
      dfgTexture =
        Texture(
          render,
          Texture.Target.TEXTURE_2D,
          Texture.WrapMode.CLAMP_TO_EDGE,
          /*useMipmaps=*/ false
        )
      // The dfg.raw file is a raw half-float texture with two channels.
      val dfgResolution = 64
      val dfgChannels = 2
      val halfFloatSize = 2

      val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
      activity.context.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
      GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        /*level=*/ 0,
        GLES30.GL_RG16F,
        /*width=*/ dfgResolution,
        /*height=*/ dfgResolution,
        /*border=*/ 0,
        GLES30.GL_RG,
        GLES30.GL_HALF_FLOAT,
        buffer
      )
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

      // Point cloud
      pointCloudShader =
        Shader.createFromAssets(
            render,
            "shaders/point_cloud.vert",
            "shaders/point_cloud.frag",
            /*defines=*/ null
          )
          .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
          .setFloat("u_PointSize", 5.0f)

      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
        VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
      val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer)
      pointCloudMesh =
        Mesh(render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers)

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
        Texture.createFromAsset(
          render,
          "models/arrow_tex.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectAlbedoInstantPlacementTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_albedo_instant_placement.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      val virtualObjectPbrTexture =
        Texture.createFromAsset(
          render,
          "models/arrow_ao.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.LINEAR
        )
      virtualObjectMesh = Mesh.createFromAsset(render, "models/arrow_small.obj")
      virtualObjectShader =
        Shader.createFromAssets(
            render,
            "shaders/environmental_hdr.vert",
            "shaders/environmental_hdr.frag",
            mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
          )
          .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
          .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
          .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
          .setTexture("u_DfgTexture", dfgTexture)

      hasSetTextureNames = false
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer!!.resize(width, height)
  }

  private var tempBuf: Framebuffer? = null

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    virtualSceneFramebuffer!!.colorTexture

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      } catch (e: java.lang.Exception) {
        Log.e(TAG, "Other error", e)
        return
      }


    val camera = frame.camera

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
        render,
        activity.depthSettings.depthColorVisualizationEnabled()
      )
      backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
      return
    }

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)
    val shouldGetDepthImage =
      activity.depthSettings.useDepthForOcclusion() ||
        activity.depthSettings.depthColorVisualizationEnabled()
    if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
      try {
        val depthImage = frame.acquireDepthImage16Bits()
        backgroundRenderer.updateCameraDepthTexture(depthImage)
        depthImage.close()
      } catch (e: NotYetAvailableException) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    /*
    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
    val message: String? =
      when {
        camera.trackingState == TrackingState.PAUSED &&
          camera.trackingFailureReason == TrackingFailureReason.NONE ->
          activity.getString(R.string.searching_planes)
        camera.trackingState == TrackingState.PAUSED ->
          TrackingStateHelper.getTrackingFailureReasonString(camera)
        session.hasTrackingPlane() && wrappedAnchors.isEmpty() ->
          activity.getString(R.string.waiting_taps)
        session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
        else -> activity.getString(R.string.searching_planes)
      }
    if (message == null) {
      activity.view.snackbarHelper.hide(activity)
    } else {
      activity.view.snackbarHelper.showMessage(activity, message)
    }
     */

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      //return
    }

    // -- Draw non-occluded virtual objects (planes, point cloud)

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)
    frame.acquirePointCloud().use { pointCloud ->
      if (pointCloud.timestamp > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.points)
        lastPointCloudTimestamp = pointCloud.timestamp
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(pointCloudMesh, pointCloudShader)
    }

    /*
    // Visualize planes.
    planeRenderer.drawPlanes(
      render,
      session.getAllTrackables<Plane>(Plane::class.java),
      camera.displayOrientedPose,
      projectionMatrix
    )
     */

    // -- Draw occluded virtual objects

    // Update lighting parameters in the shader
    updateLightEstimation(frame.lightEstimate, viewMatrix)

    // Visualize anchors created by touch.
    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    for ((anchor, trackable) in
      wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
      // Get the current pose of an Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      anchor.pose.toMatrix(modelMatrix, 0)

      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      val texture =
        if ((trackable as? InstantPlacementPoint)?.trackingMethod ==
            InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
        ) {
          virtualObjectAlbedoInstantPlacementTexture
        } else {
          virtualObjectAlbedoTexture
        }
      virtualObjectShader.setTexture("u_AlbedoTexture", texture)
      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    var needsNew = false
    synchronized(holder.lock) {
      holder.height = activity.view.height / 3
      holder.width = activity.view.width / 3
      needsNew = holder.needsNewFrame

      if (holder.byteBuffer != null && holder.needsNewFrame) {
        JniCommon.nativeFreeByteBuffer(holder.byteBuffer)
        holder.byteBuffer = null
      }
    }

    if (needsNew) {
      if (tempBuf == null) {
        tempBuf = Framebuffer(render, holder.width!!, holder.height!!)
      } else {
        tempBuf!!.resize(holder.width!!, holder.height!!)
      }

      GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);

      GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, tempBuf!!.framebufferId)
      GLError.maybeThrowGLException("", "glBindFramebuffer")

      GLES30.glBlitFramebuffer(0,0,activity.view.width, activity.view.height, 0,0,  holder.width!!, holder.height!!, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
      GLError.maybeThrowGLException("", "glBlitFramebuffer")

      GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, tempBuf!!.framebufferId);

      val byteBuffer = JniCommon.nativeAllocateByteBuffer(
        holder.height!! *
                holder.width!! * 4
      )

      GLES30.glReadPixels(
        0,
        0,
        holder.width!!,
        holder.height!!,
        GLES30.GL_RGBA,
        GLES30.GL_UNSIGNED_BYTE,
        byteBuffer
      )
      GLError.maybeThrowGLException("", "glReadPixels")

      handleRequest(frame, camera, byteBuffer)

      GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);

      synchronized(holder.lock) {
        if (holder.byteBuffer != null) {
          JniCommon.nativeFreeByteBuffer(holder.byteBuffer)
          holder.byteBuffer = null
        }
        holder.byteBuffer = byteBuffer
        holder.needsNewFrame = false
      }
    }
  }

  /** Checks if we detected at least one plane. */
  private fun Session.hasTrackingPlane() =
    getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

  /** Update state based on the current frame's light estimation. */
  private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
    if (lightEstimate.state != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false)
      return
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true)
    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
    updateMainLight(
      lightEstimate.environmentalHdrMainLightDirection,
      lightEstimate.environmentalHdrMainLightIntensity,
      viewMatrix
    )
    updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
  }

  private fun updateMainLight(
    direction: FloatArray,
    intensity: FloatArray,
    viewMatrix: FloatArray
  ) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0]
    worldLightDirection[1] = direction[1]
    worldLightDirection[2] = direction[2]
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
    virtualObjectShader.setVec3("u_LightIntensity", intensity)
  }

  private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
    require(coefficients.size == 9 * 3) {
      "The given coefficients array must be of length 27 (3 components per 9 coefficients"
    }

    // Apply each factor to every component of each coefficient
    for (i in 0 until 9 * 3) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
    }
    virtualObjectShader.setVec3Array(
      "u_SphericalHarmonicsCoefficients",
      sphericalHarmonicsCoefficients
    )
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun handleRequest(frame: Frame, camera: Camera, byteBuffer: ByteBuffer) {
    if (camera.trackingState != TrackingState.TRACKING) return
    synchronized(requestMutex) {
      val req = request ?: return@synchronized
      request = null


      val pjM = FloatArray(16)
      camera.getProjectionMatrix(pjM, 0, Z_NEAR, Z_FAR)

      val vM = FloatArray(16)
      camera.getViewMatrix(vM, 0 )

      val anchor = session!!.createAnchor(camera.pose)

      val height = holder.height!!
      val width = holder.width!!

      synchronized(markMutex) {
        markRequest = MarkStore(vM, pjM, anchor, null, null, null, holder.height!!, holder.width!!)
      }

      val img = ByteArray(byteBuffer.capacity())
      byteBuffer.get(img, 0, byteBuffer.capacity())

      byteBuffer.rewind()

      var map = HashMap<String, Any>()

      map["img"] = img
      map["height"] = holder.height!!
      map["width"] = holder.width!!

      req.success(map)
    }

    synchronized(markMutex) {
      val req = markRequest ?: return
      if (req.x == null || req.y == null) return
      markRequest = null

      val ray = createRay(req.x!!, req.y!!, req.projMatrix, req.viewMatrix, req.height, req.width)

      val hitResultList = frame.hitTest(req.origin.pose.translation, 0, ray, 0)

      // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, Depth Point,
      // or Instant Placement Point.
      val firstHitResult =
        hitResultList.firstOrNull { hit ->
          when (val trackable = hit.trackable!!) {
            is Plane ->
              trackable.isPoseInPolygon(hit.hitPose) &&
                      PlaneRenderer.calculateDistanceToPlane(hit.hitPose, req.origin.pose) > 0
            is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            is InstantPlacementPoint -> true
            // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
            is DepthPoint -> true
            else -> false
          }
        }

      req.origin.detach()

      req.ret?.success(null)

      if (firstHitResult != null) {
        // Cap the number of objects created. This avoids overloading both the
        // rendering system and ARCore.
        if (wrappedAnchors.size >= 20) {
          wrappedAnchors[0].anchor.detach()
          wrappedAnchors.removeAt(0)
        }

        // Adding an Anchor tells ARCore that it should track this position in
        // space. This anchor is created on the Plane to place the 3D model
        // in the correct position relative both to the world and to the plane.

        wrappedAnchors.add(WrappedAnchor(firstHitResult.createAnchor(), firstHitResult.trackable))
      }
    }
  }

  private fun showError(errorMessage: String)  {
    Log.e("RENDERER", errorMessage)
  }
  /*
  activity.view.snackbarHelper.showError(activity, errorMessage)

   */


  private fun createRay(x: Float, y: Float, projection: FloatArray, view: FloatArray, height: Int, width: Int) : FloatArray {
    // Normalised Device Coordinates
    val xNorm = (2f * x) / width - 1f
    val yNorm = 1f - (2f * y) / height

    // Homogeneous Clip Coordinates
    val rayClip = arrayOf(xNorm, yNorm, -1f, 1f).toFloatArray()

    // 4d Eye (Camera) Coordinates
    val invProj = FloatArray(16)
    Matrix.invertM(invProj, 0, projection, 0)

    val rayEye = FloatArray(4)
    Matrix.multiplyMV(rayEye, 0, invProj, 0, rayClip, 0)

    rayEye[2] = -1f
    rayEye[3] = 0f

    // 4d World Coordinates
    val invView = FloatArray(16)
    Matrix.invertM(invView, 0, view, 0)

    val rayWorld = FloatArray(4)
    Matrix.multiplyMV(rayWorld, 0, invView, 0, rayEye, 0)

    // Normalise
    val len = rayWorld.map { it * it }
      .subList(0,3).fold(0f) { acc: Float, i: Float ->
      acc + i
    }

    return rayWorld.map { it / len }.subList(0,3).toFloatArray()
  }

  fun removeMarker() {
    wrappedAnchors.forEach { it.anchor.detach() }
    wrappedAnchors.clear()
  }
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
private data class WrappedAnchor(
  val anchor: Anchor,
  val trackable: Trackable?,
)

public data class MarkStore (
  val viewMatrix: FloatArray,
  val projMatrix: FloatArray,
  val origin: Anchor,
  var ret: MethodChannel.Result?,
  var x: Float?,
  var y: Float?,
  val height: Int,
  val width: Int,
)

public data class MarkRequest (
  val x: Float,
  val y: Float,
)
