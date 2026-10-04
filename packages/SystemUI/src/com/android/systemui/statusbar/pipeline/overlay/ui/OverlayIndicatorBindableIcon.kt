/*
 * Copyright (C) 2026 IchthysOS
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

package com.android.systemui.statusbar.pipeline.overlay.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.icons.shared.model.BindableIcon
import com.android.systemui.statusbar.pipeline.icons.shared.model.ModernStatusBarViewCreator
import com.android.systemui.statusbar.pipeline.overlay.data.repository.OverlayWindowsRepository
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.android.systemui.statusbar.pipeline.shared.ui.view.SingleBindableStatusBarIconView
import dagger.Lazy
import javax.inject.Inject

/**
 * IchthysOS: status bar icon shown while any non-system app draws over other apps
 * (SYSTEM_ALERT_WINDOW), W8b decision 33. Tapping it opens Settings' "Display over other apps"
 * page for that app (the list when several apps overlay at once). While hidden it isn't
 * clickable, so the status bar under it still pulls the shade.
 */
@SysUISingleton
class OverlayIndicatorBindableIcon
@Inject
constructor(
    private val repository: OverlayWindowsRepository,
    // Lazy: built with the status bar icon controller, early in SystemUI's graph.
    private val activityStarter: Lazy<ActivityStarter>,
) : BindableIcon {
    override val slot: String = SLOT

    override val initializer = ModernStatusBarViewCreator { context ->
        SingleBindableStatusBarIconView.createView(context).also { view ->
            view.initView(slot) { bind(view) }
        }
    }

    override val shouldBindIcon: Boolean = true

    private fun bind(view: SingleBindableStatusBarIconView): ModernStatusBarViewBinding {
        view.setOnClickListener { openOverlaySettings(repository.overlayPackages.value) }
        view.isClickable = false
        return SingleBindableStatusBarIconView.withDefaultBinding(
            view = view,
            shouldBeVisible = { repository.overlayPackages.value.isNotEmpty() },
        ) {
            // Runs once attached; view.iconView only exists after initView() has bound us.
            view.iconView.setImageResource(R.drawable.stat_sys_ichthys_overlay)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    repository.overlayPackages.collect { packages ->
                        view.isClickable = packages.isNotEmpty()
                        view.contentDescription = describe(view.context, packages)
                        // StatusIconContainer re-reads isIconVisible() on its next layout pass.
                        view.requestLayout()
                    }
                }
            }
        }
    }

    private fun openOverlaySettings(packages: List<String>) {
        if (packages.isEmpty()) return
        val intent =
            if (packages.size == 1) {
                Intent(
                    Settings.ACTION_MANAGE_APP_OVERLAY_PERMISSION,
                    Uri.fromParts("package", packages[0], null),
                )
            } else {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activityStarter.get().postStartActivityDismissingKeyguard(intent, 0)
    }

    private fun describe(context: Context, packages: List<String>): CharSequence? =
        when (packages.size) {
            0 -> null
            1 ->
                context.getString(
                    R.string.ichthys_overlay_indicator_one,
                    label(context, packages[0]),
                )
            else -> context.getString(R.string.ichthys_overlay_indicator_many, packages.size)
        }

    private fun label(context: Context, packageName: String): CharSequence {
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    companion object {
        /**
         * Status bar slot. Not in config_statusBarIcons; CentralSurfacesDependenciesModule puts it
         * before the camera slot when it builds the StatusBarIconList.
         */
        const val SLOT = "ichthys_overlay"
    }
}
