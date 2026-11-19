package com.mobiledivecontrol.ui.camera

/** Debug-only surface host for instrumentation; never included in release builds. */
class NativeLogProbeActivity : android.app.Activity() {
    lateinit var preview: androidx.camera.view.PreviewView
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = androidx.camera.view.PreviewView(this)
        setContentView(preview)
    }
}
