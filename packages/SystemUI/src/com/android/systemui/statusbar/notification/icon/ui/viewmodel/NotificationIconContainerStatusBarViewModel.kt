/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.content.res.Resources
import android.graphics.Rect
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.statusbar.ichthys.indicator.data.IchthysIndicatorSettingsRepository
import com.android.systemui.statusbar.notification.icon.domain.interactor.StatusBarNotificationIconsInteractor
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** View-model for the row of notification icons displayed in the status bar, */
class NotificationIconContainerStatusBarViewModel
@Inject
constructor(
    @Background private val bgContext: CoroutineContext,
    private val darkIconInteractor: DarkIconInteractor,
    dumpManager: DumpManager,
    iconsInteractor: StatusBarNotificationIconsInteractor,
    keyguardInteractor: KeyguardInteractor,
    @Main resources: Resources,
    shadeInteractor: ShadeInteractor,
    systemSettings: SystemSettingsRepository,
) : FlowDumperImpl(dumpManager) {

    private val defaultMaxIcons = resources.getInteger(R.integer.max_notif_static_icons)

    /**
     * IchthysOS: Settings.System "ichthys_statusbar_notif_icons" caps the notification icons shown
     * in the status bar (1-12). Unset keeps the device default.
     */
    private val maxIcons: Flow<Int> =
        systemSettings
            .intSetting(ICON_LIMIT_KEY, defaultMaxIcons)
            .map { if (it <= 0) defaultMaxIcons else it.coerceAtMost(12) }
            .distinctUntilChanged()

    /**
     * IchthysOS: statusBar.notificationDot.replaceIcons. The notification dot stands in for the
     * status bar notification icons, so none are shown. Only while the dot itself is enabled.
     */
    private val dotReplacesIcons: Flow<Boolean> =
        combine(
                systemSettings.boolSetting(
                    IchthysIndicatorSettingsRepository.KEY_NOTIF_DOT_ENABLED,
                    true,
                ),
                systemSettings.boolSetting(
                    IchthysIndicatorSettingsRepository.KEY_NOTIF_DOT_REPLACE_ICONS,
                    false,
                ),
            ) { dotEnabled, replaceIcons ->
                dotEnabled && replaceIcons
            }
            .distinctUntilChanged()

    /** Are changes to the icon container animated? */
    val animationsEnabled: Flow<Boolean> =
        combine(shadeInteractor.isShadeTouchable, keyguardInteractor.isKeyguardShowing) {
                panelTouchesEnabled,
                isKeyguardShowing ->
                panelTouchesEnabled && !isKeyguardShowing
            }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()

    /** The colors with which to display the notification icons. */
    fun iconColors(displayId: Int): Flow<NotificationIconColors> {
        return darkIconInteractor
            .darkState(displayId)
            .map { (areas: Collection<Rect>, tint: Int) -> IconColorsImpl(tint, areas) }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()
    }

    /** [NotificationIconsViewData] indicating which icons to display in the view. */
    val icons: Flow<NotificationIconsViewData> =
        combine(iconsInteractor.statusBarNotifs, maxIcons, dotReplacesIcons) {
                entries,
                limit,
                replacedByDot ->
                NotificationIconsViewData(
                    visibleIcons =
                        if (replacedByDot) emptyList()
                        else entries.mapNotNull { it.toIconInfo(it.statusBarIcon) },
                    iconLimit = limit,
                )
            }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()
            .dumpWhileCollecting("icons")

    private companion object {
        const val ICON_LIMIT_KEY = "ichthys_statusbar_notif_icons"
    }

    private class IconColorsImpl(override val tint: Int, private val areas: Collection<Rect>) :
        NotificationIconColors {
        override fun staticDrawableColor(viewBounds: Rect): Int {
            return if (DarkIconDispatcher.isInAreas(areas, viewBounds)) {
                tint
            } else {
                DarkIconDispatcher.DEFAULT_ICON_TINT
            }
        }
    }
}
