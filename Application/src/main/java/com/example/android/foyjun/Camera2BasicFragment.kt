/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.foyjun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.*
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.math.abs

class Camera2BasicFragment : Fragment(), View.OnClickListener,
        ActivityCompat.OnRequestPermissionsResultCallback {

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */

    //pvHandler : thread2에서 person을 읽고 string화 하면, 그거를 textview에 표시 할때 쓰려고 함
    lateinit var sunglassesButton: ImageButton
    lateinit var mustacheButton: ImageButton
    lateinit var previewHandler : PreviewHandler
    var previewInferenceThread : ThreadClass? = null
    var isPIThreadWork : Boolean = false
    var previewPersonMessage : String? = null
    var isSunglasses : Boolean = false
    var isMustache : Boolean = false


    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture){

        }

    }

    /**
     * ID of the current [CameraDevice].
     */
    private lateinit var cameraId: String

    /**
     * An [AutoFitTextureView] for camera preview.
     */
    private lateinit var textureView: AutoFitTextureView
    /**
     * A [CameraCaptureSession] for camera preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * A reference to the opened [CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    private lateinit var previewSize: Size

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@Camera2BasicFragment.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@Camera2BasicFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@Camera2BasicFragment.activity?.finish()
        }

    }

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * An [ImageReader] that handles still image capture.
     */
    private var imageReader: ImageReader? = null

    /**
     * This is the output file for our picture.
     */
    private lateinit var file: File
    private lateinit var stampedFile: File

    /**
     * This a callback object for the [ImageReader]. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        backgroundHandler?.post(ImageSaver(it.acquireNextImage(), file))
    }

    /**
     * [CaptureRequest.Builder] for the camera preview
     */
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    /**
     * [CaptureRequest] generated by [.previewRequestBuilder]
     */
    private lateinit var previewRequest: CaptureRequest

    /**
     * The current state of camera state for taking pictures.
     *
     * @see .captureCallback
     */
    private var state = STATE_PREVIEW

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * Whether the current camera device supports Flash or not.
     */
    private var flashSupported = false

    /**
     * Orientation of the camera sensor
     */
    private var sensorOrientation = 0

    /**
     * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (state) {
                STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState == null) {
                captureStillPicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                // CONTROL_AE_STATE can be null on some devices
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    state = STATE_PICTURE_TAKEN
                    captureStillPicture()
                } else {
                    runPrecaptureSequence()
                }
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }

    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera2_basic, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lognyan()
        view.findViewById<View>(R.id.picture).setOnClickListener(this)
        textureView = view.findViewById(R.id.texture)
        textView = view.findViewById(R.id.textView)
        sunglassesButton = view.findViewById(R.id.sunglasses)
        mustacheButton = view.findViewById(R.id.mustache)
        sunglassesButton.setOnClickListener {
            when(isSunglasses){
                false->isSunglasses=true
                true->isSunglasses=false
            }
            Toast.makeText(this.context,"isSunglasses : $isSunglasses",Toast.LENGTH_SHORT).show()
            Log.d("tag","isSunglasses : $isSunglasses")
        }
        mustacheButton.setOnClickListener {
            when(isMustache){
                false->isMustache=true
                true->isMustache=false
            }
            Toast.makeText(this.context,"isMustache : $isMustache",Toast.LENGTH_SHORT).show()
            Log.d("tag","isMustache : $isMustache")
        }
        previewHandler = PreviewHandler()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        lognyan()
        super.onActivityCreated(savedInstanceState)
        file = File(activity.getExternalFilesDir(null), PIC_FILE_NAME)
        stampedFile = File(activity.getExternalFilesDir(null), PIC_FILE_NAME2)
    }

    override fun onResume() {
        lognyan()
        super.onResume()
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }


    override fun onPause() {
        lognyan()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun requestCameraPermission() {
        lognyan()
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    private fun requestReadExternalStoragePermission() {
        lognyan()
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_READ_EXTERNAL_STORAGE_PERMISSION)
        }
    }

    private fun requestWriteExternalStoragePermission() {
        lognyan()
        if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        lognyan()
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else if (requestCode == REQUEST_READ_EXTERNAL_STORAGE_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission2))
                        .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission3))
                        .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        lognyan()
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null &&
                        cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }// 후면 카메라 선택

                val map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // For still image captures, we use the largest available size.
                val largest = Collections.max(
                        Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                        CompareSizesByArea())
                imageReader = ImageReader.newInstance(largest.width, largest.height,
                        ImageFormat.JPEG, /*maxImages*/ 2).apply {
                    setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                }

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = activity.windowManager.defaultDisplay.rotation

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                val swappedDimensions = areDimensionsSwapped(displayRotation)

                val displaySize = Point()
                activity.windowManager.defaultDisplay.getSize(displaySize)
                val rotatedPreviewWidth = if (swappedDimensions) height else width
                val rotatedPreviewHeight = if (swappedDimensions) width else height
                var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
                var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) maxPreviewWidth = MAX_PREVIEW_WIDTH
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) maxPreviewHeight = MAX_PREVIEW_HEIGHT

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth, rotatedPreviewHeight,
                        maxPreviewWidth, maxPreviewHeight,
                        largest)

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(previewSize.width, previewSize.height)
                } else {
                    textureView.setAspectRatio(previewSize.height, previewSize.width)
                }

                // Check if the flash is supported.
                flashSupported =
                        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId

                // We've found a viable camera and finished setting up member variables,
                // so we don't need to iterate through other available cameras.
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
        }

    }

    /**
     * Determines if the dimensions are swapped given the phone's current rotation.
     *
     * @param displayRotation The current rotation of the display
     *
     * @return true if the dimensions are swapped, false otherwise.
     */
    private fun areDimensionsSwapped(displayRotation: Int): Boolean {
        lognyan()
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    /**
     * Opens the camera specified by [Camera2BasicFragment.cameraId].
     */
    private fun openCamera(width: Int, height: Int) {

        Log.d("tag", "from openCamera(W,H) : ${width} , ${height}")
        lognyan()
        val permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }
        val permission2 = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
        if (permission2 != PackageManager.PERMISSION_GRANTED) {
            requestReadExternalStoragePermission()
            return
        }
        val permission3 = ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (permission3 != PackageManager.PERMISSION_GRANTED) {
            requestWriteExternalStoragePermission()
            return
        }

        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }

    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        lognyan()
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    //여기서 쓰레드를 만들까
    private fun startBackgroundThread() {
        lognyan()
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
        previewInferenceThread = ThreadClass().also { it.start() }
        isPIThreadWork = true
    }

    /**
     * Stops the background thread and its [Handler].
     */
    // 여기서 죽여버리고
    private fun stopBackgroundThread() {
        lognyan()
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            isPIThreadWork = false
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        lognyan()
        try {
            val texture = textureView.surfaceTexture

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
            )
            previewRequestBuilder.addTarget(surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(Arrays.asList(surface, imageReader?.surface),
                    object : CameraCaptureSession.StateCallback() {

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (cameraDevice == null) return

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(previewRequestBuilder)

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build()
                                captureSession?.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, e.toString())
                            }

                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            activity.showToast("Failed")
                        }
                    }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        lognyan()
        activity ?: return
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        lognyan()
        try {
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START)
            // Tell #captureCallback to wait for the lock.
            state = STATE_WAITING_LOCK
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in [.captureCallback] from [.lockFocus].
     */
    private fun runPrecaptureSequence() {
        lognyan()
        try {
            // This is how to tell the camera to trigger.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            state = STATE_WAITING_PRECAPTURE
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.captureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        lognyan()
        try {
            if (activity == null || cameraDevice == null) return
            val rotation = activity.windowManager.defaultDisplay.rotation

            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(imageReader?.surface)

                // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                // We have to take that into account and rotate JPEG properly.
                // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
                // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                set(CaptureRequest.JPEG_ORIENTATION,
                        (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)

                // Use the same AE and AF modes as the preview.
                set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }?.also { setAutoFlash(it) }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult) {
                    activity.showToast("Saved: $file")
//여기서 이미 파일이 만들어졋음.

                    //그 파일로 PoseNet돌림
                    val bitmappedFile: Bitmap? = file2Bitmap()
                    val scaledBitmap_file = Bitmap.createScaledBitmap(bitmappedFile!!, 480, 640, false)
                    val scaledBitmap_stamps = Bitmap.createScaledBitmap(bitmappedFile!!, 480, 640, false)
                    val personFromFile = processImage(scaledBitmap_file)

                    val fileCanvas = Canvas(scaledBitmap_stamps)
                    val stampCanvas = drawStampsOnCanvas(scaledBitmap_file,fileCanvas, personFromFile) // 드디어 됐다.


                    //비트맵 -> 파일 with scaledBitmap_stamps
                    val fileCacheItem = File(stampedFile.path)
                    var outputStream : FileOutputStream? = null
                    try{
                        fileCacheItem.createNewFile()
                        outputStream = FileOutputStream(fileCacheItem)
                        scaledBitmap_stamps.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }catch (e : Exception){
                        Log.d("tag","$e")
                    }finally {
                        try{
                            if(outputStream != null){
                                outputStream.close()
                            }
                        }catch(e : Exception){
                            Log.d("tag","$e")
                        }
                    }

                    Log.d(TAG, file.toString())
                    unlockFocus()
                }
            }

            captureSession?.apply {
                stopRepeating()
                abortCaptures()
                capture(captureBuilder?.build(), captureCallback, null)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun drawStampsOnCanvas(bitmap : Bitmap, canvas: Canvas, person : Person) : Canvas {
        val sunglassBitmap : Bitmap = BitmapFactory.decodeResource(resources, R.drawable.sunglasses)
        val mustacheBitmap : Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mustache)

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // Draw `bitmap` and `person` in square canvas.
        val screenWidth: Int
        val screenHeight: Int
        val left: Int
        val right: Int
        val top: Int
        val bottom: Int
        if (canvas.height > canvas.width) {
            screenWidth = canvas.width
            screenHeight = canvas.width
            left = 0
            top = (canvas.height - canvas.width) / 2
        } else {
            screenWidth = canvas.height
            screenHeight = canvas.height
            left = (canvas.width - canvas.height) / 2
            top = 0
        }
        right = left + screenWidth
        bottom = top + screenHeight

        val widthRatio = screenWidth.toFloat() / 257
        val heightRatio = screenHeight.toFloat() / 257

        //EYES position
        val leftEyeX: Float = person.keyPoints[1].position.x.toFloat() * widthRatio + left
        val leftEyeY: Float = person.keyPoints[1].position.y.toFloat() * heightRatio + top
        val rightEyeX: Float = person.keyPoints[2].position.x.toFloat() * widthRatio + left
        val rightEyeY: Float = person.keyPoints[2].position.y.toFloat() * heightRatio + top

        //EARS position
        val leftEarX: Float = person.keyPoints[3].position.x.toFloat() * widthRatio + left
        val leftEarY: Float = person.keyPoints[3].position.y.toFloat() * heightRatio + top
        val rightEarX: Float = person.keyPoints[4].position.x.toFloat() * widthRatio + left
        val rightEarY: Float = person.keyPoints[4].position.y.toFloat() * heightRatio + top

        //NOSE position
        val noseX: Float = person.keyPoints[0].position.x.toFloat() * widthRatio + left
        val noseY: Float = person.keyPoints[0].position.y.toFloat() * heightRatio + top

        //Resizing bitmap - sunglasses
        val new_w : Int = (sunglassBitmap.width* (leftEarX-rightEarX) / sunglassBitmap.width).toInt()
        val new_h : Int = (sunglassBitmap.height * (leftEarX-rightEarX) / sunglassBitmap.height).toInt()
        val resizedBitmap = Bitmap.createScaledBitmap(sunglassBitmap, new_w, new_h, true)

        //Resizing bitmap - mustache
        val new2_w : Int = (mustacheBitmap.width * (leftEyeX-rightEyeX) / mustacheBitmap.width).toInt()
        val new2_h : Int = (mustacheBitmap.height * (leftEyeX-rightEyeX) / mustacheBitmap.height).toInt()
        val resized2Bitmap = Bitmap.createScaledBitmap(mustacheBitmap, new2_w, new2_h, true)

        canvas.drawBitmap(bitmap,0.0f,0.0f,null)

        Log.d("tag","isSunglassses : $isSunglasses, isMustache : $isMustache")
        if(isSunglasses){
            canvas.drawBitmap(resizedBitmap, rightEarX, rightEyeY - new_h/2, null)
        }
        if(isMustache){
            canvas.drawBitmap(resized2Bitmap, rightEyeX, noseY, null)
        }
        return canvas
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private fun unlockFocus() {
        lognyan()
        try {
            // Reset the auto-focus trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            setAutoFlash(previewRequestBuilder)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
            // After this, the camera will go back to the normal state of preview.
            state = STATE_PREVIEW
            captureSession?.setRepeatingRequest(previewRequest, captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    override fun onClick(view: View) {
        lognyan()
        when (view.id) {
            R.id.picture -> lockFocus()
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        lognyan()
        if (flashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
    }

    companion object {

        lateinit var textView : TextView

        /**
         * Conversion from screen rotation to JPEG orientation.
         */
        private val ORIENTATIONS = SparseIntArray()
        private val FRAGMENT_DIALOG = "dialog"

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        /**
         * Tag for the [Log].
         */
        private val TAG = "Camera2BasicFragment"

        /**
         * Camera state: Showing camera preview.
         */
        private val STATE_PREVIEW = 0

        /**
         * Camera state: Waiting for the focus to be locked.
         */
        private val STATE_WAITING_LOCK = 1

        /**
         * Camera state: Waiting for the exposure to be precapture state.
         */
        private val STATE_WAITING_PRECAPTURE = 2

        /**
         * Camera state: Waiting for the exposure state to be something other than precapture.
         */
        private val STATE_WAITING_NON_PRECAPTURE = 3

        /**
         * Camera state: Picture was taken.
         */
        private val STATE_PICTURE_TAKEN = 4

        /**
         * Max preview width that is guaranteed by Camera2 API
         */
        private val MAX_PREVIEW_WIDTH = 1920

        /**
         * Max preview height that is guaranteed by Camera2 API
         */
        private val MAX_PREVIEW_HEIGHT = 1080

        /**
         * Given `choices` of `Size`s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as
         * the respective max size, and whose aspect ratio matches with the specified value. If such
         * size doesn't exist, choose the largest one that is at most as large as the respective max
         * size, and whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended
         *                          output class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth          The maximum width that can be chosen
         * @param maxHeight         The maximum height that can be chosen
         * @param aspectRatio       The aspect ratio
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        @JvmStatic
        private fun chooseOptimalSize(
                choices: Array<Size>,
                textureViewWidth: Int,
                textureViewHeight: Int,
                maxWidth: Int,
                maxHeight: Int,
                aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                        option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            if (bigEnough.size > 0) {
                return Collections.min(bigEnough, CompareSizesByArea())
            } else if (notBigEnough.size > 0) {
                return Collections.max(notBigEnough, CompareSizesByArea())
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size")
                return choices[0]
            }
        }

        @JvmStatic
        fun newInstance(): Camera2BasicFragment = Camera2BasicFragment()
    }

    private fun lognyan() {
        Log.d("tag", Thread.currentThread().stackTrace[3].methodName)
    }

    private fun cropBitmap(bitmap: Bitmap): Bitmap {
        val bitmapRatio = bitmap.height.toFloat() / bitmap.width
        val modelInputRatio = 257.toFloat() / 257
        var croppedBitmap = bitmap

        // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
        val maxDifference = 1e-5

        // Checks if the bitmap has similar aspect ratio as the required model input.
        when {
            abs(modelInputRatio - bitmapRatio) < maxDifference -> return croppedBitmap
            modelInputRatio < bitmapRatio -> {
                // New image is taller so we are height constrained.
                val cropHeight = bitmap.height - (bitmap.width.toFloat() / modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                        bitmap,
                        0,
                        (cropHeight / 2).toInt(),
                        bitmap.width,
                        (bitmap.height - cropHeight).toInt()
                )
            }
            else -> {
                val cropWidth = bitmap.width - (bitmap.height.toFloat() * modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                        bitmap,
                        (cropWidth / 2).toInt(),
                        0,
                        (bitmap.width - cropWidth).toInt(),
                        bitmap.height
                )
            }
        }
        return croppedBitmap
    }

    //파일에서 프로세싱
    private fun processImage(bitmap: Bitmap) : Person {
        // Crop bitmap.
        val croppedBitmap = cropBitmap(bitmap)

        // Created scaled version of bitmap for model input.
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, 257, 257, true)

        val posenet = Posenet(this.context!!)
        // Perform inference.
        val person = posenet.estimateSinglePose(scaledBitmap)
        when(person.score){
            in 0.3..1.0 -> Log.d("file","" + // 좀 그럴듯하게 추측(0.3 이상)했다 싶으면
                    "코오 ${person.keyPoints[0].position.x},${person.keyPoints[0].position.y}\n"+
                    "좌눈 ${person.keyPoints[1].position.x},${person.keyPoints[1].position.y}\n"+
                    "우눈 ${person.keyPoints[2].position.x},${person.keyPoints[2].position.y}\n"+
                    "좌귀 ${person.keyPoints[3].position.x},${person.keyPoints[3].position.y}\n"+
                    "우귀 ${person.keyPoints[4].position.x},${person.keyPoints[4].position.y}\n") // 그럼 뭐가 나오겠지
            else -> Log.d("file","인식불가 : ${person.score}")
        }
        return person
    }

    //프리뷰에서 프로세싱
    private fun processImage(bitmap: Bitmap, isThread: Boolean) : Bitmap {
        val croppedBitmap = cropBitmap(bitmap)
        return Bitmap.createScaledBitmap(croppedBitmap, 257, 257, true)
    }

    fun file2Bitmap(): Bitmap? {
        // 샘플이 될 파일
        val path = file.path
        //파일이 있으면 bitmap화, 없으면 null 리턴
        if (file.exists()) {
            val bitmap: Bitmap
            val rotateMatrix = Matrix()
            rotateMatrix.postRotate(90.0f)
            try {
                bitmap = BitmapFactory.decodeFile(path)
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotateMatrix, true)
                return rotatedBitmap
            } catch (e: Exception) {
                Log.d("tag", "$e")
            }
        }
        return null
    }

    inner class ThreadClass : Thread() {
        override fun run(){
            Log.d("thread2","run(start)")
            while(isPIThreadWork){
                //프리뷰에서 이미지 갖고와서
                val sampleBitmap = textureView.getBitmap()
                try{
                    if(sampleBitmap!=null){ // 없음 말고
                        val processedImage = processImage(sampleBitmap, true) // 전처리 해주고
                        val posenet = Posenet(this@Camera2BasicFragment.context) // 모델 인스턴스 만들고
                        val person = posenet.estimateSinglePose(processedImage) // 추측을 해본다
                        previewPersonMessage =
                                "점수 ${person.score}\n"+
                                        "코오 ${person.keyPoints[0].position.x},${person.keyPoints[0].position.y}\n"+
                                        "좌눈 ${person.keyPoints[1].position.x},${person.keyPoints[1].position.y}\n"+
                                        "우눈 ${person.keyPoints[2].position.x},${person.keyPoints[2].position.y}\n"+
                                        "좌귀 ${person.keyPoints[3].position.x},${person.keyPoints[3].position.y}\n"+
                                        "우귀 ${person.keyPoints[4].position.x},${person.keyPoints[4].position.y}\n"
                        when(person.score){
                            in 0.3..1.0 -> {
                                Log.d("thread2",previewPersonMessage)
                            }
                            else -> Log.d("thread2","인식불가 : ${person.score}")
                        }
                        //텍뷰에 메시지 뽈롱
                        val msg = previewHandler.obtainMessage()
                        msg.obj = previewPersonMessage
                        previewHandler.sendMessage(msg)

                        //추정한 결과를 바탕으로 프리뷰 화면에 수염 선글라스 기타등등을 그려야함

                        // 캔버스 오브젝트 소환 : 그 위에다가 Drawable에서 가져온 것을 그리자

                    }
                }
                catch (e:Exception){
                    Log.d("thread2","에러발생 : $e")
                }
            }
        }
    }

}