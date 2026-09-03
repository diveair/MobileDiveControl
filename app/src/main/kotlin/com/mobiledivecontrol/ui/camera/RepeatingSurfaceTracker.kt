package com.mobiledivecontrol.ui.camera

/** CameraX may add/remove an encoder target without replacing its CameraCaptureSession. */
internal class RepeatingSurfaceTracker<S : Any, T : Any> {
    private var session: S? = null
    private var lastFrame = -1L
    private var targets = emptySet<T>()

    fun update(owner: S, frame: Long, cameraXRepeating: Boolean, surfaces: Collection<T>): Boolean {
        if (!cameraXRepeating || surfaces.isEmpty()) return false
        if (session === owner && frame <= lastFrame) return false
        val changed = session !== owner || targets != surfaces.toSet()
        session = owner
        lastFrame = frame
        targets = surfaces.toSet()
        return changed
    }
}
