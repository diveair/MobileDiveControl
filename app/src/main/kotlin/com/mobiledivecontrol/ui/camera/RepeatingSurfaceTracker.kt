package com.mobiledivecontrol.ui.camera

/** CameraX may add/remove an encoder target without replacing its CameraCaptureSession. */
internal class RepeatingSurfaceTracker<S : Any, T : Any> {
    private var session: S? = null
    private var lastRequest: Any? = null
    private var lastFrame = -1L
    private var targets = emptySet<T>()

    /** CaptureRequest is immutable. A new request can change targets within the same session. */
    inline fun changedTargets(owner: S, frame: Long, request: Any, readTargets: () -> List<T>): List<T>? {
        if (session === owner) {
            if (frame <= lastFrame) return null
            if (lastRequest === request) {
                lastFrame = frame
                return null
            }
        }
        val surfaces = readTargets()
        if (surfaces.isEmpty()) return null
        val nextTargets = surfaces.toSet()
        val changed = session !== owner || targets != nextTargets
        session = owner
        lastRequest = request
        lastFrame = frame
        targets = nextTargets
        return if (changed) surfaces else null
    }
}
