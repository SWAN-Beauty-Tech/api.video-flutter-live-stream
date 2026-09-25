package video.api.flutter.livestream

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Range
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.getCameraCharacteristics
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isBackCamera
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isExternalCamera
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrontCamera
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.scalerMaxZoom
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.zoomRatioRange
import video.api.flutter.livestream.generated.CameraInfoHostApi
import video.api.flutter.livestream.generated.NativeCameraLensDirection

class CameraInfoHostApiImpl(
    var context: Context
) : CameraInfoHostApi {
    override fun getSensorRotationDegrees(cameraId: String): Long {
        val characteristics = context.getCameraCharacteristics(cameraId)
        return (characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0).toLong()
    }

    override fun getLensDirection(cameraId: String): NativeCameraLensDirection {
        val characteristics = context.getCameraCharacteristics(cameraId)
        return when {
            characteristics.isFrontCamera -> NativeCameraLensDirection.FRONT
            characteristics.isBackCamera -> NativeCameraLensDirection.BACK
            characteristics.isExternalCamera -> NativeCameraLensDirection.OTHER
            else -> throw IllegalArgumentException("Invalid camera position for camera $cameraId")
        }
    }

    private fun getZoomRange(cameraId: String): Range<Float> {
        val characteristics = context.getCameraCharacteristics(cameraId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.zoomRatioRange ?: Range(DEFAULT_ZOOM_RATIO, characteristics.scalerMaxZoom)
        } else {
            Range(DEFAULT_ZOOM_RATIO, characteristics.scalerMaxZoom)
        }
    }

    override fun getMinZoomRatio(cameraId: String) = getZoomRange(cameraId).lower.toDouble()

    override fun getMaxZoomRatio(cameraId: String) = getZoomRange(cameraId).upper.toDouble()

    private companion object {
        const val DEFAULT_ZOOM_RATIO = 1f
    }
}
