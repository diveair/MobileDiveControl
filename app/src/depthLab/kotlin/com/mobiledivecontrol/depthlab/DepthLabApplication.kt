package com.mobiledivecontrol.depthlab

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.DiveControlApp
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.platform.ble.HousingLinkEvent
import com.mobiledivecontrol.viewmodel.DiveViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

class DepthLabApplication : DiveControlApp() {
    @Volatile private var usbAdb = false
    @Volatile private var screenState: StateFlow<AppState>? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
        fun update(intent: Intent?) {
            usbAdb = intent?.getBooleanExtra("connected", false) == true &&
                intent.getBooleanExtra("configured", false) && intent.getBooleanExtra("adb", false)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update(intent)
        }
        update(if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, filter))
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is MainActivity) return
                screenState = ViewModelProvider(activity)[DiveViewModel::class.java].state
            }
            override fun onActivityStarted(activity: Activity) {
                if (activity !is MainActivity) return
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                if (content.findViewWithTag<TextView>("depth-lab-marker") != null) return
                val marker = TextView(activity).apply {
                    tag = "depth-lab-marker"
                    text = " USB DEPTH LAB "
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(22, 63, 91))
                }
                activity.addContentView(marker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) screenState = null
            }
        })
    }

    override fun housingEventsForDisplay(): Flow<HousingLinkEvent> {
        val router = LabDepthRouter()
        val controller = UsbDepthConnection(
            wiredAdb = { usbAdb && Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 0 },
            state = { screenState?.value }, router = router,
        )
        return merge(super.housingEventsForDisplay().map { LabInput.Physical(it) }, controller.inputs())
            .mapNotNull(router::route)
    }
}
