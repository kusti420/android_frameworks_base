package com.android.systemui.scene.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.android.systemui.Flags.blackScreenOnSceneContainerStartFix
import com.android.systemui.keyguard.ui.viewmodel.AuthRippleScrimViewModel
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.scene.ui.composable.Scene
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.shade.TouchLogger
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.phone.ui.TintedIconManager

/** A root view of the main SysUI window that supports scenes. */
class SceneWindowRootView(context: Context, attrs: AttributeSet?) : WindowRootView(context, attrs) {

    private var motionEventHandler: SceneContainerViewModel.MotionEventHandler? = null

    /**
     * IchthysOS: a gesture that may take a touch stream over from the scenes (the status bar
     * brightness slide on the lock screen). It sees every event first and returns true while it
     * owns the stream; the scenes then get one ACTION_CANCEL and nothing more of that stream.
     */
    fun interface TouchInterceptor {
        fun onTouch(ev: MotionEvent): Boolean
    }

    var touchInterceptor: TouchInterceptor? = null
    private var intercepting = false
    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private val windowInsets: MutableState<WindowInsets?> = mutableStateOf(null)

    fun init(
        viewModelFactory: SceneContainerViewModel.Factory,
        containerConfig: SceneContainerConfig,
        sharedNotificationContainer: SharedNotificationContainer,
        scenes: Set<Scene>,
        overlays: Set<Overlay>,
        layoutInsetController: LayoutInsetsController,
        sceneDataSourceDelegator: SceneDataSourceDelegator,
        sceneJankMonitorFactory: SceneJankMonitor.Factory,
        sceneTransitionLatencyMonitor: SceneTransitionLatencyMonitor,
        windowRootViewKeyEventHandler: WindowRootViewKeyEventHandler,
        tintedIconManagerFactory: TintedIconManager.Factory,
        authRippleViewModelFactory: AuthRippleScrimViewModel.Factory,
    ) {
        if (blackScreenOnSceneContainerStartFix()) {
            super.setVisibility(View.INVISIBLE)
        }
        setLayoutInsetsController(layoutInsetController)
        SceneWindowRootViewBinder.bind(
            view = this@SceneWindowRootView,
            viewModelFactory = viewModelFactory,
            motionEventHandlerReceiver = { motionEventHandler ->
                this.motionEventHandler = motionEventHandler
            },
            windowInsets = windowInsets,
            containerConfig = containerConfig,
            sharedNotificationContainer = sharedNotificationContainer,
            scenes = scenes,
            overlays = overlays,
            onVisibilityChangedInternal = { isVisible ->
                super.setVisibility(if (isVisible) View.VISIBLE else View.INVISIBLE)
            },
            dataSourceDelegator = sceneDataSourceDelegator,
            sceneJankMonitorFactory = sceneJankMonitorFactory,
            sceneTransitionLatencyMonitor = sceneTransitionLatencyMonitor,
            tintedIconManagerFactory = tintedIconManagerFactory,
            authRippleViewModelFactory = authRippleViewModelFactory,
        )
        setWindowRootViewKeyEventHandler(windowRootViewKeyEventHandler)
    }

    override fun setVisibility(visibility: Int) {
        // Do nothing. We don't want external callers to invoke this. Instead, we drive our own
        // visibility from our view-binder.
    }

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    override fun onApplyWindowInsets(windowInsets: WindowInsets): WindowInsets {
        this.windowInsets.value = windowInsets
        return windowInsets
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            intercepting = false
        }
        if (touchInterceptor?.onTouch(ev) == true || intercepting) {
            if (!intercepting) {
                intercepting = true
                // The scenes saw the start of this gesture: end it there.
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                motionEventHandler?.onMotionEvent(cancel)
                super.dispatchTouchEvent(cancel)
                motionEventHandler?.onMotionEventComplete()
                cancel.recycle()
            }
            return true
        }
        motionEventHandler?.onMotionEvent(ev)
        return super.dispatchTouchEvent(ev).also {
            TouchLogger.logDispatchTouch(TAG, ev, it)
            motionEventHandler?.onMotionEventComplete()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { motionEventHandler?.onEmptySpaceMotionEvent(it) }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val TAG = "SceneWindowRootView"
    }
}
