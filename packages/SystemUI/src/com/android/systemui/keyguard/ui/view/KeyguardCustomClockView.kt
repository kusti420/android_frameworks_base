/*
 * Copyright (C) 2026 IchthysOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.text.InputFilter
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.android.systemui.Dependency
import com.android.settingslib.ichthys.clock.DepthClockView
import com.android.settingslib.ichthys.clock.IchthysClockFace
import com.android.systemui.plugins.statusbar.StatusBarStateController
import java.util.Calendar

/**
 * IchthysOS custom lockscreen clock.
 *
 * Renders a distinct clock face selected by [Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE] and
 * tuned live by the auxiliary secure settings written by the crDroid Settings "Custom clock style"
 * picker (color mode, custom color, size scale, opacity, top margin).
 *
 * Faces are inflated **from the picker's own layout resources**, cross-package, out of
 * `com.android.settings` (crDroidSettings' `res/layout/keyguard_clock_*.xml`). That is the exact
 * same resource the picker preview inflates, so what the user picks is what the lockscreen shows -
 * all 67 faces, not a hand-written subset. Resolution is by NAME (see [LAYOUT_NAMES], mirroring
 * crDroidSettings `ClockUtils.CLOCK_LAYOUTS`), and every resource the layouts reference (fonts,
 * drawables, raw Lottie JSON) is resolved out of the Settings resource table too, so nothing has to
 * be duplicated into SystemUI - notably no new SystemUI `dimen` is added, which is what previously
 * shifted the resource-ID table and crash-looped SystemUI at app-bind.
 *
 * If the Settings package or a given layout cannot be resolved, [legacyBuildClock] falls back to the
 * original hand-written builders so the previously working faces never regress.
 *
 * When the style is 0 ("Default Clock") this view stays [GONE] and the stock AOSP keyguard clock is
 * shown instead (the picker leaves the stock clock/smartspace overlays enabled for style 0).
 */
class KeyguardCustomClockView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())

    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // IchthysOS: the lock screen editor's Apply writes several keys in a row; one
                // rebuild (a cross-package inflate) for all of them, not one per key. The writes
                // arrive as separate notifications, so wait a moment for the rest.
                changedKeys.add(uri?.lastPathSegment ?: "")
                handler.removeCallbacks(rebuildRunnable)
                handler.postDelayed(rebuildRunnable, 50L)
            }
        }

    private val rebuildRunnable = Runnable { onSettingsChanged() }

    /** IchthysOS polish: the keys changed since the last [onSettingsChanged] (main thread). */
    private val changedKeys = HashSet<String>()
    private var currentStyle = -1

    /** IchthysOS polish: SystemUI's doze amount, handed to the Depth face (also when rebuilt). */
    private var dozeAmount = 0f

    /**
     * IchthysOS polish: only size, place, turn or opacity changed (the editor's Apply, a slider):
     * the face stays and glides there, its Shape refits as it goes. Anything else builds it again.
     */
    private fun onSettingsChanged() {
        val keys = HashSet(changedKeys)
        changedKeys.clear()
        val clock = currentClock
        val style = secureInt(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, STYLE_DEFAULT)
        if (clock != null && style == currentStyle && keys.isNotEmpty() &&
            GEOMETRY_KEYS.containsAll(keys)) {
            applyContainerAttributes(clock, animate = true)
        } else {
            rebuild()
        }
    }

    /**
     * IchthysOS polish: the Depth face cross-fades between its awake and AOD looks with SystemUI's
     * doze amount (0 awake .. 1 dozing) instead of switching at the start of the animation.
     */
    fun setDozeAmount(amount: Float) {
        if (amount == dozeAmount) return
        dozeAmount = amount
        currentClock?.let { forEachFace(it) { f -> (f as? DepthClockView)?.setDozeAmount(amount) } }
    }

    private var currentClock: View? = null

    /**
     * IchthysOS [KEY_AOD_ANIM]: whether the face's Lottie animation keeps running on AOD. Faces are
     * inflated with `setRepeatCount(INFINITE)` + `playAnimation()`, so without this they animate
     * forever while dozing.
     *
     * The controller is fetched through [Dependency] rather than injected because this view is
     * constructed bare from Compose (`AndroidView { KeyguardCustomClockView(it) }`) and has no
     * Dagger entry point. Null if SysUI's dependency graph is not up, in which case the animation
     * simply keeps its current behaviour.
     */
    private val statusBarStateController: StatusBarStateController? =
        try {
            Dependency.get(StatusBarStateController::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "no StatusBarStateController; AOD animation control disabled", e)
            null
        }

    private var isDozing: Boolean = statusBarStateController?.isDozing ?: false

    /** Whether [applyAodAnimation] currently has the face's animations paused. */
    private var pausedForAod = false

    private val dozeListener =
        object : StatusBarStateController.StateListener {
            override fun onDozingChanged(dozing: Boolean) {
                if (isDozing == dozing) return
                isDozing = dozing
                applyAodAnimation()
                currentClock?.let { forEachFace(it) { face -> face.setDozing(dozing) } }
            }
        }

    init {
        isClickable = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val cr = context.contentResolver
        WATCHED_KEYS.forEach { key ->
            cr.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }
        statusBarStateController?.let {
            isDozing = it.isDozing
            it.addCallback(dozeListener)
        }
        rebuild()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(rebuildRunnable)
        stopWordTicker()
        statusBarStateController?.removeCallback(dozeListener)
        context.contentResolver.unregisterContentObserver(observer)
    }

    private fun secureInt(key: String, def: Int): Int =
        Settings.Secure.getIntForUser(context.contentResolver, key, def, UserHandle.USER_CURRENT)

    private fun secureString(key: String, def: String): String =
        Settings.Secure.getStringForUser(context.contentResolver, key, UserHandle.USER_CURRENT)
            ?: def

    private fun rebuild() {
        stopWordTicker()
        currentClock?.let { removeView(it) }
        currentClock = null

        val style =
            secureInt(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, STYLE_DEFAULT)
        currentStyle = style
        if (style == STYLE_DEFAULT) {
            visibility = GONE
            return
        }
        visibility = VISIBLE

        val inflated = inflateFace(style)
        val clock = inflated ?: legacyBuildClock(style)
        Log.i(TAG, "rebuild: style=$style inflated=${inflated != null}")
        applyTheme(clock)
        applyFaceOptions(clock)
        applyContainerAttributes(clock)

        val lp: LayoutParams
        if (inflated != null) {
            // The layout root already carries its authored layout_width / layout_gravity; only the
            // height is forced, because five faces declare match_parent height (authored for the
            // full-screen picker preview) and would otherwise eat the whole Compose column.
            lp =
                (clock.layoutParams as? LayoutParams)
                    ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.height = LayoutParams.WRAP_CONTENT
            val g = lp.gravity
            lp.gravity =
                if (g == LayoutParams.UNSPECIFIED_GRAVITY) Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else (g and HORIZONTAL_GRAVITY_BITS) or Gravity.TOP
        } else {
            lp =
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = clockGravity(style)
                }
        }
        addView(clock, lp)
        currentClock = clock
        // The freshly inflated face is playing, whatever the previous one was doing.
        pausedForAod = false
        applyAodAnimation()
    }

    /**
     * IchthysOS [KEY_AOD_ANIM]: pause the face's Lottie animations while dozing when the user has
     * turned the AOD animation off. Defaults to on, which is the behaviour the faces shipped with.
     */
    private fun applyAodAnimation() {
        val clock = currentClock ?: return
        val allowOnAod = secureInt(KEY_AOD_ANIM, 1) != 0
        val shouldPause = isDozing && !allowOnAod
        // Only touch the views on an actual transition. A freshly inflated face is already playing
        // (LottieFactory calls playAnimation), and its composition may still be parsing, so an
        // unconditional resume here would fight that.
        if (shouldPause == pausedForAod) return
        pausedForAod = shouldPause
        forEachLottieView(clock) { view ->
            if (shouldPause) view.pauseAnimation() else view.resumeAnimation()
        }
    }

    /**
     * IchthysOS: options and doze state for the drawn faces ([IchthysClockFace], SettingsLib).
     */
    private fun applyFaceOptions(clock: View) {
        val showDate = secureInt(KEY_SHOW_DATE, 1) != 0
        val dotShape = secureInt(KEY_DOT_SHAPE, IchthysClockFace.DOT_ROUND)
        val showUnlit = secureInt(KEY_UNLIT_DOTS, 1) != 0
        forEachFace(clock) { face ->
            face.setClockOptions(showDate, dotShape, showUnlit)
            face.setDozing(isDozing)
            (face as? DepthClockView)?.setDozeAmount(dozeAmount)
        }
    }

    private fun forEachFace(view: View, action: (IchthysClockFace) -> Unit) {
        if (view is IchthysClockFace) action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) forEachFace(view.getChildAt(i), action)
        }
    }

    private fun forEachLottieView(view: View, action: (LottieAnimationView) -> Unit) {
        when (view) {
            is LottieAnimationView -> action(view)
            is ViewGroup -> for (i in 0 until view.childCount) forEachLottieView(view.getChildAt(i), action)
        }
    }

    private fun applyContainerAttributes(clock: View, animate: Boolean = false) {
        val opacity = secureInt(KEY_OPACITY, 100).coerceIn(0, 100)
        val scale = secureInt(KEY_SIZE_SCALE, 100).coerceIn(40, 180) / 100f
        val marginTopPx = secureInt(KEY_MARGIN_TOP, DEFAULT_MARGIN_TOP)

        // IchthysOS lock screen editor (tracker#58): moved sideways and turned freely too.
        val offsetX = secureInt(KEY_OFFSET_X, 0)
        val rotation = secureInt(KEY_ROTATION, 0).toFloat()
        val oldTop = paddingTop
        setPadding(dp(24), dp(BASE_TOP_DP) + marginTopPx, dp(24), 0)
        target = floatArrayOf(scale, offsetX.toFloat(), rotation, opacity / 100f)
        // IchthysOS: on the Depth face the opacity is its digits' only (DepthClockView.faceOpacity):
        // on the face's root it also faded the date line and the weather in it (the moon icon).
        var depthFace: DepthClockView? = null
        forEachFace(clock) { f -> if (f is DepthClockView) depthFace = f }
        val depth = depthFace
        val rootAlpha = if (depth != null) 1f else opacity / 100f
        // IchthysOS polish: scaled about its top (as the editor and the weather line expect) and
        // turned about its middle, for every angle: the pivot is always the middle and a
        // translation puts the scaled top back. So turning a scaled clock never makes it jump (it
        // did at 0°, where the pivot used to switch between the top and the middle).
        if (animate && clock.isLaidOut && isAttachedToWindow && !isDozing) {
            // A new margin moves the box at once: start from where the face was.
            clock.translationY += (oldTop - paddingTop).toFloat()
            clock.pivotX = scalePivotX(clock)
            clock.pivotY = clock.height / 2f
            gliding = true
            clock.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationX(offsetX.toFloat())
                .translationY(topCompensation(clock, scale))
                .rotation(rotation)
                .alpha(rootAlpha)
                .setDuration(GLIDE_MS)
                .setInterpolator(GLIDE)
                .apply {
                    // The animator is the view's own and keeps its listener: set it every glide.
                    val from = depth?.faceOpacity ?: 1f
                    val to = opacity / 100f
                    setUpdateListener(
                        if (depth == null || from == to) null
                        else android.animation.ValueAnimator.AnimatorUpdateListener { a ->
                            depth.faceOpacity = from + (to - from) * a.animatedFraction
                        }
                    )
                }
                .withEndAction {
                    gliding = false
                    depth?.faceOpacity = opacity / 100f
                }
                .start()
        } else {
            clock.animate().cancel()
            gliding = false
            clock.alpha = rootAlpha
            depth?.faceOpacity = opacity / 100f
            clock.scaleX = scale
            clock.scaleY = scale
            clock.translationX = offsetX.toFloat()
            clock.rotation = rotation
            placePivot(clock)
            // Inflated roots are usually match_parent wide, so scaling has to pivot on the edge
            // the face is actually aligned to, otherwise a start-aligned face grows off the
            // padding at >100%.
            clock.post { placePivot(clock) }
        }
        if (pivotListenerOn !== clock) {
            // The face's height changes (Shape's stretch): keep the top where it is.
            pivotListenerOn = clock
            clock.addOnLayoutChangeListener(
                OnLayoutChangeListener { v, _, top, _, bottom, _, oldTop2, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop2 && !gliding) placePivot(v)
                })
        }
    }

    /** The face the height listener is on (each rebuild makes a new one). */
    private var pivotListenerOn: View? = null

    /** The scale / offset / turn / opacity last applied (what a glide ends at). */
    private var target = floatArrayOf(1f, 0f, 0f, 1f)
    private var gliding = false

    private fun placePivot(clock: View) {
        clock.pivotX = scalePivotX(clock)
        clock.pivotY = clock.height / 2f
        clock.translationY = topCompensation(clock, target[0])
    }

    /** Scaled about the middle, the top moves by (1 - scale) x half the height: back up by that. */
    private fun topCompensation(clock: View, scale: Float): Float = -(1f - scale) * clock.height / 2f

    private fun scalePivotX(clock: View): Float {
        val g = (clock as? LinearLayout)?.gravity ?: return clock.width / 2f
        val abs = Gravity.getAbsoluteGravity(g, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        return when (abs) {
            Gravity.LEFT -> 0f
            Gravity.RIGHT -> clock.width.toFloat()
            else -> clock.width / 2f
        }
    }

    /**
     * Walks the built clock and applies the selected color mode. Views are tagged with role tokens
     * (e.g. "text1", "accent1", "nolineheight") joined by '|'.
     */
    private fun applyTheme(root: View) {
        val mode = secureString(KEY_COLOR_MODE, MODE_DEFAULT)
        val customColor = secureInt(KEY_CUSTOM_COLOR, Color.WHITE)
        val accent = accentColor()
        themeRecursive(root, mode, customColor, accent)
    }

    private fun themeRecursive(view: View, mode: String, customColor: Int, accent: Int) {
        if (view is IchthysClockFace) {
            when (mode) {
                MODE_ACCENT -> view.setClockColors(accent, white())
                MODE_CUSTOM -> view.setClockColors(customColor, accent)
                else -> view.setClockColors(white(), accent)
            }
        }
        if (view is TextView) {
            val roles = (view.tag as? String)?.split("|") ?: emptyList()
            val isAccent = roles.any { it.startsWith("accent") }
            val isText = roles.any { it.startsWith("text") }
            if (isAccent || isText) {
                when (mode) {
                    MODE_ACCENT -> view.setTextColor(accent)
                    MODE_CUSTOM -> if (isText) view.setTextColor(customColor)
                    // MODE_DEFAULT keeps the face's designed colors.
                }
            } else if (mode != MODE_DEFAULT && isRecolorable(view.currentTextColor)) {
                // Roughly half of the crDroid layouts carry no role tags at all. Recolour those by
                // heuristic so the colour modes still apply, but only for colours that are clearly
                // "the default clock colour" (white / a system palette tone) - never the faces'
                // deliberately designed colours (the IDE syntax highlighting, the OOS red hour, the
                // transparent alignment spacers) and never near-black text, which on these layouts
                // always sits on a light chip and would become unreadable.
                view.setTextColor(if (mode == MODE_ACCENT) accent else customColor)
            }
            if (roles.contains("nolineheight")) {
                view.includeFontPadding = false
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                themeRecursive(view.getChildAt(i), mode, customColor, accent)
            }
        }
    }

    /** True for "generic" clock colours that a colour mode may safely override. */
    private fun isRecolorable(color: Int): Boolean {
        if (Color.alpha(color) < 0x40) return false // transparent alignment spacers
        val lum = (0.2126f * Color.red(color) + 0.7152f * Color.green(color) +
            0.0722f * Color.blue(color)) / 255f
        if (lum < 0.15f) return false // black-on-white chips (stylish2 / stylish5 / stylish6)
        return lum > 0.85f || paletteColors.contains(color or COLOR_ALPHA_MASK)
    }

    /** Opaque forms of the dynamic-colour tones the crDroid clock layouts actually reference. */
    private val paletteColors: Set<Int> by lazy {
        val out = HashSet<Int>()
        for (palette in listOf("accent1", "accent2", "accent3")) {
            for (shade in listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)) {
                val id = resources.getIdentifier("system_${palette}_$shade", "color", "android")
                if (id != 0) out.add(context.getColor(id) or COLOR_ALPHA_MASK)
            }
        }
        out
    }

    private fun accentColor(): Int =
        context.getColor(android.R.color.system_accent1_100)

    // ---------------------------------------------------------------------------------------------
    // Cross-package inflation of the picker's own layouts (primary render path).
    // ---------------------------------------------------------------------------------------------

    /**
     * Inflates the crDroidSettings layout that the picker preview shows for [style], resolving every
     * nested resource (font / drawable / raw) out of the Settings resource table.
     *
     * Returns null - so the caller can fall back to the hand-written builders - if the package or
     * the layout cannot be resolved, or inflation throws.
     */
    private fun inflateFace(style: Int): View? {
        if (style in PLACEHOLDER_STYLES) return null
        val name = LAYOUT_NAMES.getOrNull(style) ?: return null
        return try {
            val res = context.packageManager.getResourcesForApplication(SETTINGS_PKG)
            val layoutId = res.getIdentifier(name, "layout", SETTINGS_PKG)
            if (layoutId == 0) {
                Log.w(TAG, "layout $name not found in $SETTINGS_PKG")
                return null
            }
            val inflater =
                LayoutInflater.from(context).cloneInContext(ClockResContext(context, res))
            inflater.setFactory2(LottieFactory(res, LOTTIE_RAW[style]))
            val view = inflater.inflate(layoutId, this, false)
            bindDynamicContent(view, res)
            view
        } catch (e: Exception) {
            Log.w(TAG, "failed to inflate clock face $name", e)
            null
        }
    }

    /**
     * A context that resolves *resources* against the Settings package while keeping SystemUI's
     * class loader, main looper and everything else. This is what makes the compiled `@font/…`,
     * `@drawable/…` and `@raw/…` ids inside the crDroid layouts resolve correctly: those ids belong
     * to the Settings resource table, so the [Resources] (and the [Resources.Theme] that
     * [Context.obtainStyledAttributes] goes through) must be the Settings ones.
     *
     * The class loader deliberately stays SystemUI's, so `com.airbnb.lottie.LottieAnimationView`
     * still resolves out of SystemUI's own bundled Lottie.
     */
    private class ClockResContext(base: Context, private val res: Resources) : ContextWrapper(base) {
        private val clockTheme: Resources.Theme =
            res.newTheme().apply { applyStyle(android.R.style.Theme_DeviceDefault, true) }

        override fun getResources(): Resources = res

        override fun getAssets(): AssetManager = res.assets

        override fun getTheme(): Resources.Theme = clockTheme
    }

    /**
     * Constructs Lottie views without their [AttributeSet].
     *
     * `app:lottie_*` are library attributes: the ids baked into the Settings layout belong to
     * Settings' copy of Lottie, while [LottieAnimationView] reads SystemUI's copy's ids. Letting the
     * view parse those attributes would read unrelated entries out of the Settings table and can
     * hand Lottie a bogus raw-resource id, whose default failure listener *throws*. So the animation
     * is wired up explicitly here instead, and a swallowing failure listener is installed.
     */
    private class LottieFactory(private val res: Resources, private val rawName: String?) :
        LayoutInflater.Factory2 {

        override fun onCreateView(
            parent: View?,
            name: String,
            context: Context,
            attrs: AttributeSet,
        ): View? {
            if (name != KeyguardCustomClockView.LOTTIE_CLASS) return null
            return LottieAnimationView(context).apply {
                setFailureListener { t ->
                    Log.w(KeyguardCustomClockView.TAG, "lottie composition failed", t)
                }
                val rawId =
                    if (rawName == null) 0
                    else res.getIdentifier(rawName, "raw", KeyguardCustomClockView.SETTINGS_PKG)
                if (rawId != 0) {
                    // Lottie auto-closes the stream once the async parse completes.
                    setAnimation(res.openRawResource(rawId), rawName)
                    setRepeatCount(LottieDrawable.INFINITE)
                    playAnimation()
                }
            }
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)
    }

    /**
     * Fills in the placeholders the crDroid layouts ship with ("Username", "Device Name", "Eight
     * Seventeen"), matched by the layouts' own role tags first and by id as a fallback.
     */
    @Suppress("DEPRECATION") // android.widget.AnalogClock: the "Simple Analog" face is built on it.
    private fun bindDynamicContent(root: View, res: Resources) {
        val idUserName = res.getIdentifier("user_name", "id", SETTINGS_PKG)
        val idDeviceName = res.getIdentifier("device_name", "id", SETTINGS_PKG)
        val idProfileIcon = res.getIdentifier("user_profile_icon", "id", SETTINGS_PKG)
        val idWordHour = res.getIdentifier("word_clock", "id", SETTINGS_PKG)
        val idWordMinute = res.getIdentifier("word_min", "id", SETTINGS_PKG)

        forEachView(root) { v ->
            val roles = (v.tag as? String)?.split("|") ?: emptyList()
            when {
                // "Simple Analog" declares wrap_content and the framework's default dial is a 380dp
                // vector, so unconstrained it measures to the full column width. Clamp it.
                v is android.widget.AnalogClock -> {
                    val lp = v.layoutParams
                    if (lp != null && lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                        lp.width = dp(ANALOG_CLOCK_DP)
                        lp.height = dp(ANALOG_CLOCK_DP)
                        v.layoutParams = lp
                    }
                }
                v is TextView &&
                    (roles.contains("username") || (idUserName != 0 && v.id == idUserName)) ->
                    userName()?.let { v.text = it }
                v is TextView &&
                    (roles.contains("device_name") || (idDeviceName != 0 && v.id == idDeviceName)) ->
                    v.text = deviceName()
                v is ImageView &&
                    (roles.contains("profile_picture") ||
                        (idProfileIcon != 0 && v.id == idProfileIcon)) ->
                    userIcon()?.let { v.setImageDrawable(it) }
            }
        }

        val hourWord = if (idWordHour == 0) null else root.findViewById<TextView>(idWordHour)
        val minuteWord = if (idWordMinute == 0) null else root.findViewById<TextView>(idWordMinute)
        if (hourWord != null || minuteWord != null) {
            startWordTicker(hourWord, minuteWord)
        }
    }

    private fun forEachView(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) forEachView(view.getChildAt(i), action)
        }
    }

    private fun userName(): String? =
        try {
            context.getSystemService(UserManager::class.java)?.userName?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotEmpty() } ?: Build.MODEL

    private fun userIcon(): Drawable? =
        try {
            context.getSystemService(UserManager::class.java)?.getUserIcon(context.userId)?.let {
                BitmapDrawable(resources, it)
            }
        } catch (e: Exception) {
            null
        }

    // ---------------------------------------------------------------------------------------------
    // Word clock ("Text Clock", picker index 18). The layout ships static placeholder words; unlike
    // every other face it has no TextClock to tick itself, so it is driven from here.
    // ---------------------------------------------------------------------------------------------

    private var wordTicker: Runnable? = null

    private fun startWordTicker(hourWord: TextView?, minuteWord: TextView?) {
        val tick =
            object : Runnable {
                override fun run() {
                    updateWordClock(hourWord, minuteWord)
                    val now = System.currentTimeMillis()
                    handler.postDelayed(this, MINUTE_MS - (now % MINUTE_MS) + 100L)
                }
            }
        wordTicker = tick
        tick.run()
    }

    private fun stopWordTicker() {
        wordTicker?.let { handler.removeCallbacks(it) }
        wordTicker = null
    }

    private fun updateWordClock(hourWord: TextView?, minuteWord: TextView?) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val minute = cal.get(Calendar.MINUTE)
        hourWord?.text = numberToWord(hour)
        minuteWord?.text = if (minute == 0) "O'Clock" else numberToWord(minute)
    }

    private fun numberToWord(n: Int): String =
        when {
            n < 20 -> WORD_UNITS[n]
            n % 10 == 0 -> WORD_TENS[n / 10]
            else -> WORD_TENS[n / 10] + "-" + WORD_UNITS[n % 10].lowercase()
        }

    // ---------------------------------------------------------------------------------------------
    // Clock faces. Each maps to a picker index in crDroidSettings ClockUtils.CLOCK_LAYOUTS.
    // Retained as the fallback render path when cross-package inflation is unavailable.
    // ---------------------------------------------------------------------------------------------

    private fun clockGravity(style: Int): Int =
        when (style) {
            STYLE_STACKED,
            STYLE_SAMMY,
            STYLE_OOS,
            STYLE_OOS2,
            STYLE_MIUI,
            STYLE_IDE,
            STYLE_STYLISH6,
            STYLE_LABEL -> Gravity.TOP or Gravity.START
            else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

    private fun legacyBuildClock(style: Int): View =
        when (style) {
            STYLE_OOS -> buildOos()
            STYLE_OOS2 -> buildOos2()
            STYLE_CENTER -> buildCenter()
            STYLE_SIMPLE -> buildMinimal()
            STYLE_MIUI -> buildMiui()
            STYLE_IDE -> buildIde()
            STYLE_MOTO -> buildMoto()
            STYLE_STYLISH6 -> buildStylish6()
            STYLE_A9 -> buildA9()
            STYLE_STACKED -> buildStacked()
            STYLE_ACCENT -> buildAccent()
            STYLE_LABEL -> buildLabel()
            STYLE_SCAPULA -> buildScapula()
            STYLE_BIG -> buildBig()
            STYLE_SAMMY -> buildSammy()
            STYLE_TALL -> buildTall()
            else -> buildBig() // graceful fallback for any not-yet-ported style
        }

    /** idx 3 "Center Clock" - clean centered HH:MM with accent date. */
    private fun buildCenter(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh:mm", "HH:mm", 60f, "sans-serif", white(), "text1|nolineheight")
            )
            addView(
                textClock("EEE, d MMM", "EEE, d MMM", 14f, "sans-serif-medium", accentColor(),
                    "accent1")
            )
        }

    /** idx 4 "Simple Clock" - thin minimal HH:MM with muted date. */
    private fun buildMinimal(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh:mm", "HH:mm", 56f, "sans-serif-thin", white(), "text1|nolineheight")
            )
            addView(
                textClock("MMM d", "MMM d", 13f, "sans-serif-light", white70(), "text2")
            )
        }

    /** idx 23 "Stacked Clock" - bold hour over thin accent minute, left aligned. */
    private fun buildStacked(): View =
        column(Gravity.START).apply {
            addView(
                textClock("hh", "HH", 78f, "sans-serif-black", white(), "text1|nolineheight")
            )
            addView(
                textClock("mm", "mm", 78f, "sans-serif-thin", accentColor(), "accent1|nolineheight")
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(-20) }
            )
            addView(
                textClock("EEE, MMM d", "EEE, MMM d", 14f, "sans-serif-medium", white70(), "text2")
            )
        }

    /** idx 24 "X Factor" - accent hour, white minute split by a divider. */
    private fun buildAccent(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                textClock("hh", "HH", 60f, "sans-serif-medium", accentColor(),
                    "accent1|nolineheight")
            )
            row.addView(
                staticText(":", 60f, "sans-serif-thin", white(), "text1|nolineheight")
                    .also {
                        (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(2)
                        (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(2)
                    }
            )
            row.addView(
                textClock("mm", "mm", 60f, "sans-serif-medium", white(), "text1|nolineheight")
            )
            addView(row)
            addView(
                textClock("EEEE, MMM d", "EEEE, MMM d", 13f, "sans-serif", white70(), "text2")
            )
        }

    /** idx 59 "Big 1" - huge bold single-line time. Also the fallback face. */
    private fun buildBig(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh:mm", "HH:mm", 64f, "sans-serif-black", white(), "text1|nolineheight")
            )
            addView(
                textClock("EEEE, MMMM d", "EEEE, MMMM d", 13f, "sans-serif", white(), "text2")
            )
        }

    /** idx 60 "Big 2" - Samsung-inspired left stack: accent day, thin time, date. */
    private fun buildSammy(): View =
        column(Gravity.START).apply {
            addView(
                textClock("EEEE", "EEEE", 18f, "sans-serif-medium", accentColor(),
                    "accent1|nolineheight")
            )
            addView(
                textClock("hh:mm", "HH:mm", 72f, "sans-serif-thin", white(), "text1|nolineheight")
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(-4) }
            )
            addView(
                textClock("d MMMM", "d MMMM", 14f, "sans-serif", white70(), "text2")
            )
        }

    /** idx 67 "Tall clock" - very tall thin hour over accent minute. */
    private fun buildTall(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh", "HH", 120f, "sans-serif-thin", white(), "text1|nolineheight")
            )
            addView(
                textClock("mm", "mm", 120f, "sans-serif-thin", accentColor(), "accent1|nolineheight")
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(-40) }
            )
        }

    // ---------------------------------------------------------------------------------------------
    // Ported crDroid faces (branch 15.0, layouts from crDroidSettings res/layout).
    // ---------------------------------------------------------------------------------------------

    /** idx 1 "OnePlus Clock" - weekday, "NEVER SETTLE" tagline, small time w/ accent first digit. */
    private fun buildOos(): View =
        column(Gravity.START).apply {
            addView(tc("EEEE", "EEEE", 18f, white(), "text1"))
            addView(tv("𝗡𝗘𝗩𝗘𝗥 𝘚𝘌𝘛𝘛𝘓𝘌", 15f, white(), "text2"))
            val base = tc("hh:mm", "HH:mm", 20f, white(), "text1")
            val top = tc("hh:mm", "HH:mm", 20f, accent600(), "accent1").apply {
                filters = arrayOf(InputFilter.LengthFilter(1))
            }
            addView(overlay(base, top))
            addView(tc("MMMM dd", "MMMM dd", 10f, white(), "text2"))
        }

    /** idx 2 "OnePlus Clock 2" - weekday over big time with red hour, date below. */
    private fun buildOos2(): View =
        column(Gravity.START).apply {
            addView(tc("EEEE", "EEEE", 17f, white(), "text1"))
            val base = tc("h:mm", "H:mm", 54f, white(), "text1")
            val top = tc("h", "H", 54f, holoRed(), "accent1")
            addView(overlay(base, top))
            val row = rowH()
            row.addView(tc("MMM dd", "MMM dd", 15f, white(), "text1"))
            addView(row)
        }

    /** idx 5 "MIUI Clock" - big time, all-caps weekday, MM/dd date, tightly stacked. */
    private fun buildMiui(): View =
        column(Gravity.START).apply {
            addView(tc("hh:mm", "HH:mm", 50f, white(), "text1"))
            addView(
                tc("EEE", "EEE", 30f, white(), "text1", allCaps = true)
                    .also { lp(it).topMargin = dp(-8) }
            )
            addView(
                tc("MM/dd", "MM/dd", 30f, white(), "text2")
                    .also { lp(it).topMargin = dp(-5) }
            )
        }

    /** idx 6 "IDE Clock" - a C++ snippet whose string values are live TextClocks. */
    private fun buildIde(): View =
        column(Gravity.START).apply {
            val w = white()
            val green = Color.parseColor("#3DDB86")
            val blue = Color.parseColor("#0040FF")
            val red = Color.parseColor("#FF0000")
            val sz = 8f
            rowH().let { r ->
                r.addView(tv("#include  ", sz, w, "text1", bold = true))
                r.addView(tv("<string>", sz, green, "accent1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("using namespace  ", sz, w, "text1", bold = true))
                r.addView(tv("std;", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("int main  ", sz, w, "text1", bold = true))
                r.addView(tv("()", sz, w, "text1", bold = true))
                addView(r)
            }
            addView(tv("{", sz, w, "text1", bold = true))
            addView(tv("\tstring time, date, day, month;", sz, w, "text1", bold = true))
            rowH().let { r ->
                r.addView(tv("\ttime =  \"", sz, w, "text1", bold = true))
                r.addView(tc("hh:mm", "kk:mm", sz, green, "accent1", letterSpacing = 0.03f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("\tday =  \"", sz, w, "text1", bold = true))
                r.addView(tc("EEEE", "EEEE", sz, green, "accent1", allCaps = true,
                    letterSpacing = 0.3f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("\tdate =  \"", sz, w, "text1", bold = true))
                r.addView(tc("yyyy.MM.dd", "yyyy.MM.dd", sz, blue, "accent1",
                    letterSpacing = 0.03f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("\tmonth =  \"", sz, w, "text1", bold = true))
                r.addView(tc("MMMM", "MMMM", sz, red, "accent1", allCaps = true,
                    letterSpacing = 0.3f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            addView(tv("\treturn 0;", sz, w, "text1", bold = true))
            addView(tv("}", sz, w, "text1", bold = true))
        }

    /** idx 7 "Moto Clock" - centered bold time with small centered date. */
    private fun buildMoto(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                tc("hh:mm", "HH:mm", 50f, white(), "text1", bold = true, alpha = 0.9f)
                    .apply { gravity = Gravity.CENTER }
                    .also { lp(it).bottomMargin = dp(4) }
            )
            addView(
                tc("EEE, MMM d", "EEE, d MMM", 10f, white(), "text2|nolineheight")
                    .apply { gravity = Gravity.CENTER }
            )
        }

    /** idx 13 "Stylish Clock 6" - greeting, date, boxed weekday chip, divider line + time. */
    private fun buildStylish6(): View =
        column(Gravity.START).apply {
            val inner = column(Gravity.START).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            inner.addView(tv("Hello !", 11f, white(), "text1|nolineheight", bold = true))
            inner.addView(
                tc("MMMM • dd • yyyy", "dd • MMMM • yyyy", 7f, white(), "text1", bold = true)
            )
            val boxOuter =
                FrameLayout(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(100), dp(29)).also {
                            it.topMargin = dp(4)
                            it.gravity = Gravity.CENTER_HORIZONTAL
                        }
                }
            val shadow =
                View(context).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.END or Gravity.BOTTOM,
                        ).also {
                            it.marginStart = dp(2)
                            it.topMargin = dp(2)
                        }
                    setBackgroundColor(accent600())
                    tag = "accent1"
                }
            val whiteBox =
                FrameLayout(context).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        ).also {
                            it.marginEnd = dp(2)
                            it.bottomMargin = dp(2)
                        }
                    setBackgroundColor(Color.WHITE)
                }
            whiteBox.addView(
                tc("EEEE", "EEEE", 12f, Color.BLACK, null, bold = true).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        )
                }
            )
            boxOuter.addView(shadow)
            boxOuter.addView(whiteBox)
            inner.addView(boxOuter)
            val lineRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            dp(100),
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).also {
                            it.topMargin = dp(4)
                            it.gravity = Gravity.CENTER_HORIZONTAL
                        }
                    setPadding(dp(2), 0, 0, 0)
                }
            lineRow.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    setBackgroundColor(Color.WHITE)
                    tag = "text1"
                }
            )
            lineRow.addView(
                tc("hh • mm", "HH • mm", 7f, white(), "text1|nolineheight", bold = true)
                    .also { lp(it).marginStart = dp(8) }
            )
            inner.addView(lineRow)
            addView(inner)
        }

    /** idx 20 "Android 9 Vibe" - centered medium time over centered long date. */
    private fun buildA9(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                tc("hh:mm", "HH:mm", 35f, white(), "accent1|nolineheight")
                    .apply { gravity = Gravity.CENTER }
                    .also { lp(it).topMargin = dp(6) }
            )
            addView(
                tc("EEEE, MMMM dd", "EEEE, dd MMMM", 10f, white(), "text1")
                    .apply { gravity = Gravity.CENTER }
            )
        }

    /** idx 28 "Label Clock" - weekday, time w/ accent first digit, date. */
    private fun buildLabel(): View =
        column(Gravity.START).apply {
            addView(tc("EEEE", "EEEE", 25f, white(), "text1"))
            val base = tc("hh:mm", "HH:mm", 25f, white(), "text1")
            val top = tc("hh:mm", "HH:mm", 25f, accent300(), "accent1").apply {
                filters = arrayOf(InputFilter.LengthFilter(1))
            }
            addView(overlay(base, top))
            addView(
                tc("MMMM dd", "MMMM dd", 10f, white(), "text2")
                    .also { lp(it).marginStart = dp(4) }
            )
        }

    /** idx 41 "Anci Scapula" - staggered accent hour/minute, date row, weekday. */
    private fun buildScapula(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            val a1 = accentColor()
            val clear = Color.TRANSPARENT
            val row1 = rowH(Gravity.CENTER).also { lp(it).bottomMargin = dp(-10) }
            row1.addView(tc("hh", "HH", 45f, a1, "accent1").apply { gravity = Gravity.CENTER })
            row1.addView(tc("    ", "    ", 25f, clear, null))
            addView(row1)
            val row2 = rowH(Gravity.CENTER).also { lp(it).bottomMargin = dp(-10) }
            row2.addView(tc("    ", "    ", 25f, clear, null))
            row2.addView(tc("mm", "mm", 45f, a1, "accent1").apply { gravity = Gravity.CENTER })
            addView(row2)
            val row3 = rowH(Gravity.CENTER)
            row3.addView(tc("MMMM dd", "MMMM dd", 10f, a1, "accent1"))
            row3.addView(tc(", yyy", ", yyy", 10f, a1, "accent1"))
            addView(row3)
            addView(tc("EEEE", "EEEE", 10f, a1, "accent1").apply { gravity = Gravity.CENTER })
        }

    // ---------------------------------------------------------------------------------------------
    // View builder helpers.
    // ---------------------------------------------------------------------------------------------

    private fun column(horizontalGravity: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or horizontalGravity
            clipChildren = false
            clipToPadding = false
        }

    private fun textClock(
        format12: String,
        format24: String,
        sizeSp: Float,
        family: String,
        color: Int,
        tag: String,
    ): TextClock =
        TextClock(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            format12Hour = format12
            format24Hour = format24
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(family, Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setSingleLine(true)
            setTag(tag)
        }

    private fun staticText(
        text: String,
        sizeSp: Float,
        family: String,
        color: Int,
        tag: String,
    ): TextView =
        TextView(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(family, Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setTag(tag)
        }

    /** Flexible TextClock builder used by the ported crDroid faces. */
    private fun tc(
        format12: String,
        format24: String,
        sizeSp: Float,
        color: Int,
        tag: String?,
        bold: Boolean = false,
        allCaps: Boolean = false,
        letterSpacing: Float = 0f,
        alpha: Float = 1f,
    ): TextClock =
        TextClock(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            format12Hour = format12
            format24Hour = format24
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(clockFamily(), if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setSingleLine(true)
            setAllCaps(allCaps)
            setLetterSpacing(letterSpacing)
            this.alpha = alpha
            tag?.let { setTag(it) }
        }

    /** Flexible static TextView builder used by the ported crDroid faces. */
    private fun tv(
        text: String,
        sizeSp: Float,
        color: Int,
        tag: String?,
        bold: Boolean = false,
        allCaps: Boolean = false,
    ): TextView =
        TextView(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(clockFamily(), if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setAllCaps(allCaps)
            tag?.let { setTag(it) }
        }

    private fun rowH(gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            clipChildren = false
            clipToPadding = false
        }

    /** Stacks [top] over [base], both start/top aligned (crDroid's first-digit accent trick). */
    private fun overlay(base: View, top: View): FrameLayout =
        FrameLayout(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            clipChildren = false
            addView(
                base,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.TOP,
                ),
            )
            addView(
                top,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.TOP,
                ),
            )
        }

    private fun lp(v: View): LinearLayout.LayoutParams =
        v.layoutParams as LinearLayout.LayoutParams

    /** Resolves the framework lockscreen clock font family, falling back to sans-serif. */
    private fun clockFamily(): String =
        try {
            val id = resources.getIdentifier("config_clockFontFamily", "string", "android")
            if (id != 0) resources.getString(id).ifEmpty { "sans-serif" } else "sans-serif"
        } catch (e: Exception) {
            "sans-serif"
        }

    private fun accent600(): Int = context.getColor(android.R.color.system_accent1_600)

    private fun accent300(): Int = context.getColor(android.R.color.system_accent1_300)

    private fun holoRed(): Int = context.getColor(android.R.color.holo_red_light)

    private fun white(): Int = Color.WHITE

    private fun white70(): Int = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val VIEW_TAG = "ichthys_custom_clock"

        private const val TAG = "IchthysClock"

        /** crDroidSettings sources + res are compiled into the Settings apk. */
        private const val SETTINGS_PKG = "com.android.settings"

        private const val LOTTIE_CLASS = "com.airbnb.lottie.LottieAnimationView"

        private val COLOR_ALPHA_MASK = 0xFF000000.toInt()

        /** START | END | HORIZONTAL_GRAVITY_MASK - the horizontal bits of a gravity, RTL included. */
        private val HORIZONTAL_GRAVITY_BITS =
            Gravity.HORIZONTAL_GRAVITY_MASK or Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK

        private const val MINUTE_MS = 60_000L

        /** Rendered size of the "Simple Analog" face (its layout declares no size at all). */
        private const val ANALOG_CLOCK_DP = 200

        private val WORD_UNITS =
            arrayOf(
                "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
                "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
                "Seventeen", "Eighteen", "Nineteen",
            )

        private val WORD_TENS =
            arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty")

        /**
         * Picker index -> crDroidSettings layout resource name. Mirrors
         * `crDroidSettings/src/com/crdroid/settings/fragments/lockscreen/ClockUtils.kt`
         * `CLOCK_LAYOUTS`; index 0 ("Default Clock") is never inflated - style 0 hands the lockscreen
         * back to the stock AOSP clock.
         */
        private val LAYOUT_NAMES =
            arrayOf(
                "keyguard_clock_default", // 0  Default Clock (unused, stock clock is shown)
                "keyguard_clock_oos", // 1  OnePlus Clock
                "keyguard_clock_oos2", // 2  OnePlus Clock 2
                "keyguard_clock_center", // 3  Center Clock
                "keyguard_clock_simple", // 4  Simple Clock
                "keyguard_clock_miui", // 5  MIUI Clock
                "keyguard_clock_ide", // 6  IDE Clock
                "keyguard_clock_moto", // 7  Moto Clock
                "keyguard_clock_stylish", // 8  Stylish Clock
                "keyguard_clock_stylish2", // 9  Stylish Clock 2
                "keyguard_clock_stylish3", // 10 Stylish Clock 3
                "keyguard_clock_stylish4", // 11 Stylish Clock 4
                "keyguard_clock_stylish5", // 12 Stylish Clock 5
                "keyguard_clock_stylish6", // 13 Stylish Clock 6
                "keyguard_clock_stylish7", // 14 Stylish Clock 7
                "keyguard_clock_stylish8", // 15 Stylish Clock 8
                "keyguard_clock_stylish9", // 16 Stylish Clock 9
                "keyguard_clock_stylish10", // 17 Stylish Clock 10
                "keyguard_clock_word", // 18 Text Clock
                "keyguard_clock_life", // 19 LifeStyle Clock
                "keyguard_clock_a9", // 20 Android 9 Vibe
                "keyguard_clock_nos1", // 21 NothingOS 1 Clock
                "keyguard_clock_nos2", // 22 NothingOS 2 Clock
                "keyguard_clock_num", // 23 Stacked Clock
                "keyguard_clock_accent", // 24 X Factor
                "keyguard_clock_analog", // 25 Simple Analog
                "keyguard_clock_block", // 26 Block
                "keyguard_clock_bubble", // 27 Bubble
                "keyguard_clock_label", // 28 Label Clock
                "keyguard_clock_ios", // 29 iOS Clock
                "keyguard_clock_taden", // 30 Taden Clock
                "keyguard_clock_mont", // 31 Mont Clock
                "keyguard_clock_encode", // 32 Encode Clock
                "keyguard_clock_nos3", // 33 NOS Clock 3
                "keyguard_anci_clock_outline", // 34 Anci Outline
                "keyguard_anci_clock_ovalium", // 35 Anci Ovalium
                "keyguard_anci_clock_rectangle", // 36 Anci Rectangle
                "keyguard_anci_clock_wallet", // 37 Anci Wallet
                "keyguard_anci_clockdate_clavicula", // 38 Anci Clavicula
                "keyguard_anci_clockdate_kln", // 39 Anci KLN
                "keyguard_anci_clockdate_miring", // 40 Anci Miring
                "keyguard_anci_clockdate_scapula", // 41 Anci Scapula
                "keyguard_anci_clockdate_sternum", // 42 Anci Sternum
                "keyguard_sparkCircle", // 43 Spark Circle
                "keyguard_sparkList", // 44 Spark List
                "keyguard_clock_miui2", // 45 HyperOS Clock
                "keyguard_clock_ios2", // 46 iOS 2
                "keyguard_clock_ios3", // 47 iOS 3
                "keyguard_clock_ios4", // 48 iOS 4
                "keyguard_clock_ios5", // 49 iOS 5
                "keyguard_clock_ios6", // 50 iOS 6
                "keyguard_clock_ios7", // 51 iOS 7
                "keyguard_clock_ios8", // 52 iOS 8
                "keyguard_clock_ios9", // 53 iOS 9
                "keyguard_clock_ios10", // 54 iOS 10
                "keyguard_clock_ios11", // 55 iOS 11
                "keyguard_clock_ios12", // 56 iOS 12
                "keyguard_clock_ios13", // 57 iOS 13
                "keyguard_clock_ios14", // 58 iOS 14
                "keyguard_clock_big1", // 59 Big 1
                "keyguard_clock_big2", // 60 Big 2
                "keyguard_clock_big3", // 61 Big 3
                "keyguard_clock_big4", // 62 Big 4
                "keyguard_clock_sweet", // 63 Sweet
                "keyguard_clock_pixel", // 64 Pixel
                "keyguard_clock_samurai", // 65 Samurai
                "keyguard_clock_gateway", // 66 Gateway
                "keyguard_clock_tall", // 67 Tall clock
                // IchthysOS "Shoal" faces (68-75 drawn by SettingsLib IchthysClockFace views).
                "keyguard_clock_ichthys_shoal", // 68 Shoal Lattice
                "keyguard_clock_ichthys_dotmatrix", // 69 Dot Matrix
                "keyguard_clock_ichthys_binary", // 70 Binary
                "keyguard_clock_ichthys_ring", // 71 Analog Ring
                "keyguard_clock_ichthys_radar", // 72 Radar
                "keyguard_clock_ichthys_sonar", // 73 Sonar
                "keyguard_clock_ichthys_dayprogress", // 74 Day Progress
                "keyguard_clock_ichthys_wordgrid", // 75 Word Grid
                "keyguard_clock_ichthys_stacked", // 76 Stacked Bold
                "keyguard_clock_ichthys_split", // 77 Vertical Split
                "keyguard_clock_ichthys_typo", // 78 Typographic
                "keyguard_clock_ichthys_oneui", // 79 One UI Stacked
                "keyguard_clock_ichthys_depth", // 80 Depth
            )

        /** Picker index -> `@raw` Lottie animation the face's LottieAnimationView plays. */
        private val LOTTIE_RAW: Map<Int, String> =
            mapOf(35 to "ovalium", 36 to "rectangle", 37 to "wallet")

        /**
         * Indices whose crDroid layout is a stub rather than a clock, so inflating it would leave the
         * lockscreen with no time at all. 33 "NOS Clock 3" is a single TextView reading "Nothing
         * analog clock (No preview)" - it was never implemented upstream. These fall through to
         * [legacyBuildClock] so the user always gets a readable clock.
         */
        private val PLACEHOLDER_STYLES = setOf(33)

        private const val STYLE_DEFAULT = 0
        private const val STYLE_OOS = 1
        private const val STYLE_OOS2 = 2
        private const val STYLE_CENTER = 3
        private const val STYLE_SIMPLE = 4
        private const val STYLE_MIUI = 5
        private const val STYLE_IDE = 6
        private const val STYLE_MOTO = 7
        private const val STYLE_STYLISH6 = 13
        private const val STYLE_A9 = 20
        private const val STYLE_STACKED = 23
        private const val STYLE_ACCENT = 24
        private const val STYLE_LABEL = 28
        private const val STYLE_SCAPULA = 41
        private const val STYLE_BIG = 59
        private const val STYLE_SAMMY = 60
        private const val STYLE_TALL = 67

        private const val BASE_TOP_DP = 72
        private const val DEFAULT_MARGIN_TOP = 15

        /** IchthysOS polish: how a changed size / place / turn glides in. */
        private const val GLIDE_MS = 500L
        private val GLIDE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

        // Auxiliary secure setting keys (raw keys written by crDroidSettings, not Settings.java
        // constants).
        private const val KEY_COLOR_MODE = "lock_screen_custom_clock_color_mode"
        private const val KEY_SIZE_SCALE = "lock_screen_custom_clock_size_scale"
        private const val KEY_CUSTOM_COLOR = "lock_screen_custom_clock_custom_color"
        private const val KEY_OPACITY = "lock_screen_custom_clock_opacity"
        private const val KEY_MARGIN_TOP = "lock_screen_custom_clock_margin_top"
        private const val KEY_AOD_ANIM = "lock_screen_custom_clock_aod_anim"
        // IchthysOS options for the drawn faces.
        private const val KEY_SHOW_DATE = "ichthys_clock_show_date"
        private const val KEY_DOT_SHAPE = "ichthys_clock_dot_shape"
        private const val KEY_UNLIT_DOTS = "ichthys_clock_unlit_dots"
        private const val KEY_OFFSET_X = "ichthys_clock_offset_x"
        private const val KEY_ROTATION = "ichthys_clock_rotation"

        private const val MODE_DEFAULT = "default"
        private const val MODE_ACCENT = "accent"
        private const val MODE_CUSTOM = "custom"

        /** IchthysOS polish: what [onSettingsChanged] can apply without building the face again. */
        private val GEOMETRY_KEYS =
            setOf(KEY_SIZE_SCALE, KEY_OPACITY, KEY_MARGIN_TOP, KEY_OFFSET_X, KEY_ROTATION)

        private val WATCHED_KEYS =
            listOf(
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                KEY_COLOR_MODE,
                KEY_SIZE_SCALE,
                KEY_CUSTOM_COLOR,
                KEY_OPACITY,
                KEY_MARGIN_TOP,
                KEY_AOD_ANIM,
                KEY_SHOW_DATE,
                KEY_DOT_SHAPE,
                KEY_UNLIT_DOTS,
                KEY_OFFSET_X,
                KEY_ROTATION,
            )
    }
}
