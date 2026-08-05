/*
 * IchthysOS – One UI volume panel expand/collapse state.
 *
 * A top-level singleton that bridges the per-slider tap handler (VolumeDialogSliderScope) and the
 * dialog-wide stream filter (VolumeDialogScope). When [expanded] is true the interactor shows ALL
 * audio streams instead of only the active one, and the sliders binder renders them as side-by-side
 * One UI capsules.
 */

package com.android.systemui.volume.dialog.sliders.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object OneUiExpandState {
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded

    /** Toggle between collapsed (single capsule) and expanded (all streams). */
    fun toggle() {
        _expanded.value = !_expanded.value
    }

    /** Force-collapse (called when the dialog is re-bound / shown fresh). */
    fun collapse() {
        _expanded.value = false
    }
}
