package video.api.flutter.livestream.manager

import android.content.Context

/**
 * Holds the currently active [LiveStreamViewManager].
 *
 * StreamPack 3.x builds its streamer around a camera id and owns the whole capture pipeline,
 * so the streamer can no longer be created eagerly and shared. The view manager owns it and
 * this class just brokers access for the other host APIs (camera settings, zoom).
 */
class InstanceManager(var context: Context? = null) {
    var viewManager: LiveStreamViewManager? = null

    fun requireViewManager(): LiveStreamViewManager =
        viewManager ?: throw IllegalStateException("Live stream view has not been created yet")

    fun dispose() {
        viewManager = null
    }
}
