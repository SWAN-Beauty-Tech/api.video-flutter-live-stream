package video.api.flutter.livestream

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import video.api.flutter.livestream.generated.CameraProviderHostApi

class CameraProviderHostApiImpl(
    var context: Context
) : CameraProviderHostApi {
    override fun getAvailableCameraIds(): List<String> {
        return context.cameraManager.cameraIdList.toList()
    }
}
