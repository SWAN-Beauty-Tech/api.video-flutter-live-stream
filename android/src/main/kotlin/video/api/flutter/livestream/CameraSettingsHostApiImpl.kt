package video.api.flutter.livestream

import video.api.flutter.livestream.generated.CameraSettingsHostApi
import video.api.flutter.livestream.manager.InstanceManager

class CameraSettingsHostApiImpl(
    private val instanceManager: InstanceManager
) : CameraSettingsHostApi {
    override fun setZoomRatio(zoomRatio: Double) {
        instanceManager.requireViewManager().zoomRatio = zoomRatio.toFloat()
    }

    override fun getZoomRatio(): Double {
        return instanceManager.requireViewManager().zoomRatio.toDouble()
    }
}
