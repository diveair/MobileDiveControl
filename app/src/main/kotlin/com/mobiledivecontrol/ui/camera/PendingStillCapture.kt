package com.mobiledivecontrol.ui.camera

/** A still request belongs to bind completion, never to bindCamera's synchronous return. */
internal class PendingStillCapture {
    private var expectedFormat: Int? = null
    private var ready: (() -> Unit)? = null
    private var failed: ((String) -> Unit)? = null

    fun begin(format: Int, onReady: () -> Unit, onFailure: (String) -> Unit) {
        check(ready == null) { "A still surface is already pending" }
        expectedFormat = format
        ready = onReady
        failed = onFailure
    }

    fun bound(format: Int?) {
        if (ready == null) return
        if (format != expectedFormat) {
            fail("Camera did not bind the requested still output")
            return
        }
        val action = ready
        clear()
        action?.invoke()
    }

    fun fail(message: String) {
        val action = failed
        clear()
        action?.invoke(message)
    }

    fun clear() {
        expectedFormat = null
        ready = null
        failed = null
    }
}
