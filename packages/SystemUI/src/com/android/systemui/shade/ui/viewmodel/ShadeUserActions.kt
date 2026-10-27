/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade.ui.viewmodel

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeSource
import com.android.compose.animation.scene.SwipeSourceDetector
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.scene.ui.viewmodel.SceneContainerArea
import com.android.systemui.shade.domain.interactor.IchthysPulldownInteractor

private val singleShadeUserActionResult = UserActionResult(Scenes.Shade)
private val quickSettingsSceneUserActionResult = UserActionResult(Scenes.QuickSettings)
private val splitShadeUserActionResult = UserActionResult(Scenes.Shade, ToSplitShade)

/**
 * Returns collection of [UserAction] to [UserActionResult] pairs for opening the single shade.
 *
 * IchthysOS: [quickPulldown] is the crDroid `qs_quick_pulldown` mode
 * ([IchthysPulldownInteractor.quickPulldownMode]) and [openQsDirectly] says a one-finger pull should
 * go straight to Quick Settings (quick pulldown "always" or an active smart pulldown). Callers that
 * don't pass them keep the stock behaviour. The [QuickPulldownCorner] sources only ever match while
 * [QuickPulldownSwipeDetector] is in use; they mirror the [Edge.Top] results unless the corner is
 * the quick pulldown one.
 */
fun singleShadeActions(
    isDownFromTopEdgeEnabled: Boolean = true,
    requireTwoPointersForTopEdgeForQs: Boolean = false,
    quickPulldown: Int = IchthysPulldownInteractor.QUICK_PULLDOWN_OFF,
    openQsDirectly: Boolean = false,
): Array<Pair<UserAction, UserActionResult>> {

    return buildList {
            // Swiping down, not from the edge, always goes to shade.
            add(
                Swipe.Down to
                    if (openQsDirectly) {
                        quickSettingsSceneUserActionResult
                    } else {
                        singleShadeUserActionResult
                    }
            )
            add(Swipe.Down(pointerCount = 2) to singleShadeUserActionResult)
            if (isDownFromTopEdgeEnabled) {
                val topEdgeResult =
                    if (requireTwoPointersForTopEdgeForQs && !openQsDirectly) {
                        singleShadeUserActionResult
                    } else {
                        quickSettingsSceneUserActionResult
                    }
                add(swipeDownFromTopEdge() to topEdgeResult)
                add(swipeDownFromTopEdge(pointerCount = 2) to quickSettingsSceneUserActionResult)

                val quickCorner =
                    when (quickPulldown) {
                        IchthysPulldownInteractor.QUICK_PULLDOWN_END -> QuickPulldownCorner.End
                        IchthysPulldownInteractor.QUICK_PULLDOWN_START -> QuickPulldownCorner.Start
                        else -> null
                    }
                for (corner in listOf(QuickPulldownCorner.Start, QuickPulldownCorner.End)) {
                    add(
                        Swipe.Down(fromSource = corner) to
                            if (corner == quickCorner) {
                                quickSettingsSceneUserActionResult
                            } else {
                                topEdgeResult
                            }
                    )
                    add(
                        Swipe.Down(fromSource = corner, pointerCount = 2) to
                            quickSettingsSceneUserActionResult
                    )
                }
            }
        }
        .toTypedArray()
}

/**
 * IchthysOS: the outer quarters of the top edge, where crDroid quick pulldown (`qs_quick_pulldown`
 * 1 = end/right, 2 = start/left) opens Quick Settings. Resolved by [QuickPulldownSwipeDetector].
 */
sealed class QuickPulldownCorner(private val resolveArea: (LayoutDirection) -> Resolved) :
    SwipeSource {
    data object Start :
        QuickPulldownCorner(
            resolveArea = { if (it == LayoutDirection.Ltr) Resolved.Left else Resolved.Right }
        )

    data object End :
        QuickPulldownCorner(
            resolveArea = { if (it == LayoutDirection.Ltr) Resolved.Right else Resolved.Left }
        )

    override fun resolve(layoutDirection: LayoutDirection): Resolved = resolveArea(layoutDirection)

    sealed interface Resolved : SwipeSource.Resolved {
        data object Left : Resolved

        data object Right : Resolved
    }
}

/**
 * IchthysOS: a [SwipeSourceDetector] that behaves like [edgeDetector], except that a swipe starting
 * on the top edge within the outer quarter of the width resolves to a [QuickPulldownCorner]. Only
 * used in the single shade while quick pulldown is set to the left or right edge.
 */
class QuickPulldownSwipeDetector(private val edgeDetector: SwipeSourceDetector) :
    SwipeSourceDetector {
    override fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SwipeSource.Resolved? {
        val source = edgeDetector.source(layoutSize, position, density, orientation)
        if (source != Edge.Resolved.Top) {
            return source
        }
        val region = layoutSize.width * QUICK_PULLDOWN_REGION
        return when {
            position.x < region -> QuickPulldownCorner.Resolved.Left
            position.x > layoutSize.width - region -> QuickPulldownCorner.Resolved.Right
            else -> source
        }
    }

    private companion object {
        /** Same share of the width as the legacy QuickSettingsControllerImpl region. */
        const val QUICK_PULLDOWN_REGION = 0.25f
    }
}

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the split shade. */
fun splitShadeActions(): Array<Pair<UserAction, UserActionResult>> {
    return arrayOf(
        // Swiping down, not from the edge, always goes to shade.
        Swipe.Down to splitShadeUserActionResult,
        Swipe.Down(pointerCount = 2) to splitShadeUserActionResult,
        // Swiping down from the top edge goes to QS.
        swipeDownFromTopEdge() to splitShadeUserActionResult,
        swipeDownFromTopEdge(pointerCount = 2) to splitShadeUserActionResult,
    )
}

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the dual shade. */
fun dualShadeActions(
    twoFingerSwipeEnabled: Boolean = true
): Array<Pair<UserAction, UserActionResult>> {
    return buildList {
            allPointerTypesButMouse {
                // Don't add the "single pointer swipe down" actions for PointerType.Mouse because
                // it is easy to accidentally trigger them when trying to dragging windows.
                add(Swipe.Down(pointerType = it) to Overlays.NotificationsShade)
                add(
                    Swipe.Down(fromSource = SceneContainerArea.TopEdgeEndHalf, pointerType = it) to
                        Overlays.QuickSettingsShade
                )
            }
            if (twoFingerSwipeEnabled) {
                add(Swipe.Down(pointerCount = 2) to Overlays.QuickSettingsShade)
            }
        }
        .toTypedArray()
}

private fun swipeDownFromTopEdge(pointerCount: Int = 1): Swipe {
    return Swipe.Down(fromSource = Edge.Top, pointerCount = pointerCount)
}

/** Invokes [block] for all PointerTypes except MOUSE (which means Mouse / Touchpad). */
private fun allPointerTypesButMouse(block: (pointerType: PointerType) -> Unit) {
    block(PointerType.Eraser)
    block(PointerType.Stylus)
    block(PointerType.Touch)
}
