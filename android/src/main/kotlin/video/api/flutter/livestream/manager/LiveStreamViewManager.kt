package video.api.flutter.livestream.manager

import android.Manifest
import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.util.Size
import android.view.Surface
import io.flutter.view.TextureRegistry
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.streampack.ext.rtmp.configuration.mediadescriptor.RtmpMediaDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Owns the StreamPack [SingleStreamer] and adapts it to the Pigeon host API surface.
 *
 * StreamPack 3.x replaced the listener-based 2.x API with a coroutine/Flow one, and moved
 * the RTMP transport off the unmaintained `video.api:rtmpdroid` native library onto a pure
 * Kotlin implementation -- which is what makes the plugin 16 KB page-size clean (SWAN-3182).
 */
class LiveStreamViewManager(
    private val context: Context,
    textureRegistry: TextureRegistry,
    private val permissionsManager: PermissionsManager,
    private val onConnectionSucceeded: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onConnectionFailed: (String) -> Unit,
    private val onGenericError: (Exception) -> Unit,
    private val onVideoSizeChanged: (Size) -> Unit,
) {
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val flutterTexture = textureRegistry.createSurfaceTexture()

    val textureId: Long
        get() = flutterTexture.id()

    private var currentCameraId: String = context.defaultCameraId

    private val streamer: SingleStreamer = runBlocking {
        cameraSingleStreamer(context = context, cameraId = currentCameraId)
    }.also { observe(it) }

    private var _isPreviewing = false
    private var _isStreaming = false
    val isStreaming: Boolean
        get() = _isStreaming

    private fun observe(streamer: SingleStreamer) {
        eventScope.launch {
            streamer.throwableFlow
                .filterNotNull()
                .filter { !it.isClosedException }
                .collect { throwable ->
                    onGenericError(
                        throwable as? Exception
                            ?: Exception(throwable.message ?: throwable.javaClass.simpleName, throwable)
                    )
                }
        }
        eventScope.launch {
            streamer.throwableFlow
                .filterNotNull()
                .filter { it.isClosedException }
                .collect { throwable -> onConnectionFailed(throwable.message ?: "Connection lost") }
        }
        eventScope.launch {
            streamer.isOpenFlow.collect { isOpen ->
                if (!isOpen && _isStreaming) {
                    onDisconnected()
                }
            }
        }
        eventScope.launch {
            streamer.isStreamingFlow.collect { isStreaming ->
                _isStreaming = isStreaming
                if (isStreaming) {
                    onConnectionSucceeded()
                }
            }
        }
    }

    /** The active camera source, once the pipeline has one. */
    private val cameraSource: ICameraSource?
        get() = runBlocking {
            runCatching { streamer.videoInput?.sourceFlow?.first() as? ICameraSource }.getOrNull()
        }

    private var _videoConfig: VideoCodecConfig? = null
    val videoConfig: VideoCodecConfig
        get() = _videoConfig!!

    fun setVideoConfig(
        videoConfig: VideoCodecConfig,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isStreaming) {
            throw UnsupportedOperationException("You have to stop streaming first")
        }

        onVideoSizeChanged(videoConfig.resolution)

        val wasPreviewing = _isPreviewing
        if (wasPreviewing) {
            stopPreview()
        }
        try {
            runBlocking { streamer.setVideoConfig(videoConfig) }
            _videoConfig = videoConfig
            if (wasPreviewing) {
                startPreview(onSuccess, onError)
            } else {
                onSuccess()
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    private var _audioConfig: AudioCodecConfig? = null
    val audioConfig: AudioCodecConfig
        get() = _audioConfig!!

    /**
     * In StreamPack 2.x echo cancellation and noise suppression were part of the audio codec
     * config. In 3.x they are audio-source effects, so they are applied by rebuilding the
     * microphone source.
     */
    fun setAudioConfig(
        audioConfig: AudioCodecConfig,
        enableEchoCanceler: Boolean,
        enableNoiseSuppressor: Boolean,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isStreaming) {
            throw UnsupportedOperationException("You have to stop streaming first")
        }

        permissionsManager.requestPermission(
            Manifest.permission.RECORD_AUDIO,
            onGranted = {
                try {
                    val effects = mutableSetOf<UUID>()
                    if (enableEchoCanceler && AcousticEchoCanceler.isAvailable()) {
                        effects.add(android.media.audiofx.AudioEffect.EFFECT_TYPE_AEC)
                    }
                    if (enableNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                        effects.add(android.media.audiofx.AudioEffect.EFFECT_TYPE_NS)
                    }
                    runBlocking {
                        streamer.setAudioSource(MicrophoneSourceFactory(effects = effects))
                        streamer.setAudioConfig(audioConfig)
                    }
                    _audioConfig = audioConfig
                    onSuccess()
                } catch (e: Exception) {
                    onError(e)
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.RECORD_AUDIO"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.RECORD_AUDIO"))
            })
    }

    var isMuted: Boolean
        get() = runBlocking { streamer.audioInput?.isMuted ?: false }
        set(value) {
            runBlocking { streamer.audioInput?.isMuted = value }
        }

    val camera: String
        get() = currentCameraId

    fun setCamera(camera: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                try {
                    val wasPreviewing = _isPreviewing
                    if (wasPreviewing) {
                        stopPreview()
                    }
                    runBlocking { streamer.setCameraId(camera) }
                    currentCameraId = camera
                    if (wasPreviewing) {
                        startPreview(onSuccess, onError)
                    } else {
                        onSuccess()
                    }
                } catch (e: Exception) {
                    onError(e)
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    var zoomRatio: Float
        get() = runBlocking { requireCameraSource().settings.zoom.getZoomRatio() }
        set(value) {
            runBlocking { requireCameraSource().settings.zoom.setZoomRatio(value) }
        }

    private fun requireCameraSource(): ICameraSource =
        cameraSource ?: throw IllegalStateException("Camera source is not available")

    fun dispose() {
        stopStream()
        eventScope.cancel()
        runBlocking {
            runCatching { cameraSource?.stopPreview() }
            runCatching { streamer.release() }
        }
        flutterTexture.release()
    }

    fun startStream(url: String) {
        runBlocking {
            try {
                streamer.open(RtmpMediaDescriptor(Uri.parse(url)))
                streamer.startStream()
            } catch (e: Exception) {
                onConnectionFailed(e.message ?: "Failed to start stream")
                throw e
            }
        }
    }

    fun stopStream() {
        val wasStreaming = _isStreaming
        runBlocking {
            runCatching { streamer.stopStream() }
            runCatching { streamer.close() }
        }
        // Clear the flag before yielding to the event loop so the isOpenFlow collector
        // suppresses itself and the disconnect is reported exactly once.
        _isStreaming = false
        if (wasStreaming) {
            onDisconnected()
        }
    }

    fun startPreview(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                val videoConfig = _videoConfig
                if (videoConfig == null) {
                    onError(IllegalStateException("Video has not been configured!"))
                } else {
                    try {
                        val source = cameraSource
                            ?: throw IllegalStateException("Camera source is not available")
                        runBlocking { source.startPreview(getSurface(videoConfig.resolution)) }
                        _isPreviewing = true
                        onSuccess()
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    fun stopPreview() {
        runBlocking { runCatching { cameraSource?.stopPreview() } }
        _isPreviewing = false
    }

    private fun getSurface(resolution: Size): Surface {
        val surfaceTexture = flutterTexture.surfaceTexture().apply {
            setDefaultBufferSize(resolution.width, resolution.height)
        }
        return Surface(surfaceTexture)
    }
}
