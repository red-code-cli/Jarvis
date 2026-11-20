package com.jarvis.assistant.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.FragmentScanBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import com.jarvis.assistant.vision.ScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val captureManager get() = ScreenCaptureManager.getInstance()

    private var previewJob: Job? = null
    @Volatile private var isFragmentVisible = false
    @Volatile private var cameraReady = false
    private var cameraStarting = false
    private var cameraPermissionDenied = false
    private var autoCameraRequested = false
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var latestState: ScanState? = null
    private var lastCameraFrameAt = 0L
    private var isSharingNow = false

    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionDenied = !granted
        if (granted) {
            startCamera()
        } else {
            updateVisionHeader()
            toast("Camera permission chahiye — Settings se allow karo.")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActions()
        observeState()
    }

    private fun setupActions() {
        binding.btnScanSession.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            when {
                !JarvisConversationService.isSessionOn.value -> activity.handleMicOrOrbClick()
                viewModel.isMicMuted.value -> activity.handleMicOrOrbClick()
                else -> activity.handleTurnOff()
            }
        }

        binding.btnScanShare.setOnClickListener {
            (activity as? MainActivity)?.toggleScreenShare()
        }

        binding.btnScanSnap.setOnClickListener {
            when {
                cameraReady -> captureCameraPhoto()
                captureManager.isSharing.value && captureManager.hasReceivedFrame.value -> sendScreenSnapshot()
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED -> requestCameraPermission()
                else -> toast("Camera abhi ready nahi hai. Thoda wait karo.")
            }
        }

        binding.visionReticle.setOnClickListener {
            if (!cameraReady) requestCameraPermission()
        }
    }

    // ------------------------------------------------------------------
    // CameraX: live preview + throttled vision frames for Jarvis
    // ------------------------------------------------------------------

    private fun maybeStartCamera() {
        if (_binding == null || !isFragmentVisible) return
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!cameraPermissionDenied && !autoCameraRequested) {
                autoCameraRequested = true
                requestCameraPermission()
            } else {
                updateVisionHeader()
            }
            return
        }
        startCamera()
    }

    private fun requestCameraPermission() {
        autoCameraRequested = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (cameraReady || cameraStarting || _binding == null || !isFragmentVisible) return
        cameraStarting = true
        updateVisionHeader()
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val binding = _binding
            if (binding == null || !isAdded) {
                cameraStarting = false
                return@addListener
            }
            if (!isFragmentVisible) {
                cameraStarting = false
                return@addListener
            }
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                        CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> throw IllegalStateException("No camera available")
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image -> processAnalysisFrame(image) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    selector,
                    preview,
                    capture,
                    analysis
                )

                imageCapture = capture
                cameraReady = true
                cameraStarting = false
                cameraPermissionDenied = false
                binding.cameraPreview.animate().alpha(1f).setDuration(260L).start()
                updateVisionHeader()
            } catch (t: Throwable) {
                cameraStarting = false
                cameraReady = false
                Log.w(TAG, "Camera start failed: ${t.message}", t)
                toast("Camera start nahi hua: ${t.message ?: "unknown error"}")
                updateVisionHeader()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCamera() {
        cameraReady = false
        cameraStarting = false
        imageCapture = null
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "Camera unbind failed: ${t.message}")
        }
        _binding?.cameraPreview?.animate()?.alpha(0f)?.setDuration(160L)?.start()
        updateVisionHeader()
    }

    private fun processAnalysisFrame(image: ImageProxy) {
        try {
            if (!cameraReady || !isFragmentVisible) return
            if (!JarvisConversationService.isSessionOn.value) return
            // Screen sharing takes over the video stream to keep one visual source at a time.
            if (captureManager.isSharing.value) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastCameraFrameAt < 1000L) return
            val jpeg = rgbaToJpeg(image) ?: return
            lastCameraFrameAt = now
            JarvisConversationService.sendCameraFrame(jpeg)
        } catch (t: Throwable) {
            Log.w(TAG, "Camera frame processing failed: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun rgbaToJpeg(image: ImageProxy): ByteArray? {
        if (image.format != PixelFormat.RGBA_8888) return null
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4 || rowStride < width * 4) return null

        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        val cropped = if (paddedWidth != width) {
            Bitmap.createBitmap(padded, 0, 0, width, height)
        } else padded

        val rotation = image.imageInfo.rotationDegrees
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            cropped, 0, 0, cropped.width, cropped.height, matrix, true
        )

        val maxSide = maxOf(rotated.width, rotated.height)
        val scaled = if (maxSide > 768) {
            val scale = 768f / maxSide
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1),
                (rotated.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else rotated

        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== rotated) scaled.recycle()
            if (rotated !== cropped) rotated.recycle()
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
        }
    }

    private fun captureCameraPhoto() {
        if (!JarvisConversationService.isSessionOn.value) {
            toast("Pehle Jarvis session ON karo, phir camera dikhega.")
            return
        }
        val capture = imageCapture
        if (capture == null) {
            toast("Camera abhi ready nahi hai. Thoda wait karo.")
            return
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val jpeg = imageProxyToJpeg(image)
                        if (jpeg != null && jpeg.isNotEmpty()) {
                            JarvisConversationService.sendCameraFrame(jpeg)
                            toast("Photo Jarvis ko bhej di.")
                        } else {
                            toast("Photo capture fail ho gaya.")
                        }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Camera capture failed: ${exception.message}")
                    toast("Photo capture fail: ${exception.message}")
                }
            }
        )
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.JPEG) return rgbaToJpeg(image)
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun sendScreenSnapshot() {
        val jpeg = captureManager.captureSnapshotJpeg(quality = 80)
        if (jpeg == null) {
            toast("Snapshot nahi ban paya. Dobara try karo.")
            return
        }
        JarvisConversationService.sendImmediateVideoFrame(jpeg)
        toast("Snapshot Jarvis ko bhej diya.")
    }

    // ------------------------------------------------------------------
    // Screen-share preview + HUD state
    // ------------------------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        captureManager.isSharing,
                        captureManager.hasReceivedFrame,
                        viewModel.isSessionOn,
                        viewModel.isMicMuted,
                        viewModel.conversationState
                    ) { sharing, hasFrame, sessionOn, muted, state ->
                        ScanState(sharing, hasFrame, sessionOn, muted, state)
                    }.collect { state ->
                        renderState(state)
                    }
                }
            }
        }
    }

    private fun renderState(state: ScanState) {
        if (_binding == null) return
        latestState = state
        val accent = ContextCompat.getColor(requireContext(), R.color.ref_blue_light)
        val muted = ContextCompat.getColor(requireContext(), R.color.ref_muted)

        binding.tvVisionSession.text = when {
            !state.sessionOn -> "SESSION: OFF"
            state.muted -> "SESSION: MUTED"
            else -> "SESSION: ${state.conversationState.name}"
        }
        binding.tvVisionSession.setTextColor(if (state.sessionOn && !state.muted) accent else muted)

        binding.frameScanSession.setBackgroundResource(
            if (state.sessionOn && !state.muted) R.drawable.bg_ref_scan_circle_active else R.drawable.bg_ref_scan_circle
        )
        binding.ivScanSession.setImageResource(
            if (state.muted) R.drawable.ic_mic_mute_jarvis else R.drawable.ic_mic_jarvis
        )
        binding.ivScanSession.imageTintList = android.content.res.ColorStateList.valueOf(
            if (state.sessionOn && !state.muted) android.graphics.Color.WHITE else accent
        )
        binding.tvScanSession.text = when {
            !state.sessionOn -> "SESSION"
            state.muted -> "UNMUTE"
            else -> "STOP"
        }

        binding.frameScanShare.setBackgroundResource(
            if (state.sharing) R.drawable.bg_ref_scan_circle_active else R.drawable.bg_ref_scan_circle
        )
        binding.ivScanShare.imageTintList = android.content.res.ColorStateList.valueOf(
            if (state.sharing) android.graphics.Color.WHITE else accent
        )
        binding.tvScanShare.text = if (state.sharing) "STOP" else "SHARE"
        binding.tvScanShare.setTextColor(if (state.sharing) android.graphics.Color.WHITE else accent)

        isSharingNow = state.sharing
        updateVisionHeader()
        updatePreview()
    }

    private fun updateVisionHeader() {
        val binding = _binding ?: return
        val state = latestState
        val green = ContextCompat.getColor(requireContext(), R.color.status_green)
        val accent = ContextCompat.getColor(requireContext(), R.color.ref_blue_light)

        val sharing = state?.sharing ?: captureManager.isSharing.value
        val hasFrame = state?.hasFrame ?: captureManager.hasReceivedFrame.value
        val sessionOn = state?.sessionOn ?: JarvisConversationService.isSessionOn.value

        binding.tvVisionMode.text = when {
            sharing -> "SCREEN SHARE"
            cameraReady -> "CAMERA LIVE"
            cameraStarting -> "CAMERA"
            cameraPermissionDenied -> "CAMERA OFF"
            else -> "CAMERA"
        }

        binding.tvVisionStatus.text = when {
            sharing && hasFrame -> "LIVE — Jarvis screen dekh raha hai"
            sharing -> "STARTING — pehla frame aa raha hai"
            cameraReady && sessionOn -> "LIVE — Jarvis camera se dekh raha hai"
            cameraReady -> "CAMERA LIVE — session shuru karo, phir Jarvis dekhega"
            cameraPermissionDenied -> "CAMERA OFF — permission chahiye. Tap karke allow karo."
            cameraStarting -> "STARTING — camera khul raha hai…"
            else -> "STANDBY — session shuru karo, Jarvis ko aankhein do"
        }
        binding.tvVisionStatus.setTextColor(
            when {
                (sharing && hasFrame) || (cameraReady && sessionOn) -> green
                sharing || cameraReady || cameraStarting ->
                    ContextCompat.getColor(requireContext(), R.color.status_amber)
                else -> accent
            }
        )

        binding.tvVisionFeed.text = when {
            sharing && hasFrame -> "FEED: LIVE"
            sharing -> "FEED: WAITING"
            cameraReady -> "FEED: CAMERA"
            else -> "FEED: OFFLINE"
        }
        binding.tvVisionFeed.setTextColor(
            if ((sharing && hasFrame) || cameraReady) green else accent
        )

        val snapReady = sessionOn && (cameraReady || (sharing && hasFrame))
        binding.btnScanSnap.alpha = if (snapReady) 1f else 0.45f
        binding.btnScanSnap.isEnabled = true
        binding.tvScanSnap.text = if (snapReady) "SNAP" else "WAIT"
    }

    private fun updatePreview() {
        if (_binding == null) return
        if (isSharingNow && isFragmentVisible) {
            startPreviewLoop()
        } else {
            stopPreviewLoop()
            if (!isSharingNow) binding.ivVisionPreview.alpha = 0f
        }
    }

    private fun startPreviewLoop() {
        if (previewJob?.isActive == true) return
        if (!isSharingNow || !isFragmentVisible) return
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && _binding != null && isSharingNow && isFragmentVisible) {
                val jpeg = withContext(Dispatchers.Default) {
                    captureManager.captureSnapshotJpeg(quality = 45)
                }
                if (jpeg != null) {
                    val bitmap = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    }
                    if (bitmap != null && _binding != null) {
                        binding.ivVisionPreview.setImageBitmap(bitmap)
                        binding.ivVisionPreview.animate().alpha(0.52f).setDuration(220L).start()
                    }
                }
                delay(2000L)
            }
        }
    }

    private fun stopPreviewLoop() {
        previewJob?.cancel()
        previewJob = null
    }

    override fun onResume() {
        super.onResume()
        isFragmentVisible = !isHidden
        updatePreview()
        maybeStartCamera()
    }

    override fun onPause() {
        isFragmentVisible = false
        stopPreviewLoop()
        stopCamera()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        isFragmentVisible = !hidden
        if (hidden) {
            stopPreviewLoop()
            stopCamera()
        } else {
            updatePreview()
            maybeStartCamera()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        stopPreviewLoop()
        stopCamera()
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScanFragment"
    }
}

private data class ScanState(
    val sharing: Boolean,
    val hasFrame: Boolean,
    val sessionOn: Boolean,
    val muted: Boolean,
    val conversationState: ConversationState
)
