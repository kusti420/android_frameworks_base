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

package com.android.systemui.qs.tiles.impl.backstage

import android.app.ActivityTaskManager
import android.app.StatusBarManager
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.UserHandle
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.widget.Toast
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.newTracingContext
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * IchthysOS: the Backstage tile (kusti420/ichthysos-tracker#38). Tap opens the Backstage popup
 * ([BackstageDetailsViewModel]: the apps in Backstage, the recent apps to send); on the lock
 * screen it still sends the app on screen to Backstage after unlocking, where the app keeps
 * running as if open. Long-press opens the Backstage list. The only state is Settings.Secure
 * `ichthys_backstage_pkgs`, which IchthysBackstageService reconciles, so the tile and the
 * Settings page always agree.
 */
data class BackstageTileModel(val apps: List<CharSequence>)

private const val TAG = "BackstageTile"
private const val KEY_PACKAGES = "ichthys_backstage_pkgs"
/** IchthysBackstageService.MAX_APPS. */
private const val MAX_APPS = 3

private fun packagesOf(value: String?): List<String> =
    value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

class BackstageTileDataInteractor
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val secureSettings: SecureSettings,
    private val context: Context,
) : QSTileDataInteractor<BackstageTileModel> {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<BackstageTileModel> =
        triggers
            .flatMapLatest {
                merge(flowOf(Unit), secureSettings.observerFlow(user.identifier, KEY_PACKAGES))
                    .map { read(user) }
            }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)

    private fun read(user: UserHandle): BackstageTileModel {
        val pm = context.createContextAsUser(user, 0).packageManager
        val labels =
            packagesOf(secureSettings.getStringForUser(KEY_PACKAGES, user.identifier)).map { pkg ->
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
                } catch (e: PackageManager.NameNotFoundException) {
                    pkg
                }
            }
        return BackstageTileModel(labels)
    }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}

class BackstageTileUserActionInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val secureSettings: SecureSettings,
    private val context: Context,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val activityStarter: ActivityStarter,
    private val keyguardController: KeyguardStateController,
    private val dialogDelegate: Lazy<BackstageDialogDelegate>,
) : QSTileUserActionInteractor<BackstageTileModel> {

    private val main = Handler(Looper.getMainLooper())

    override suspend fun handleInput(input: QSTileInput<BackstageTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    // IchthysOS: the popup. In the dual shade this is never reached (the click
                    // opens the details view, see BackstageTileModule); the single shade shows the
                    // same content in a dialog, like the Internet and Bluetooth tiles do there.
                    // The lock screen keeps the one-tap send: the popup has nothing to show over
                    // the keyguard, and the app on screen is only ours to move once unlocked.
                    if (keyguardController.isShowing()) {
                        activityStarter.postQSRunnableDismissingKeyguard {
                            CoroutineScope(
                                    applicationScope.coroutineContext +
                                        bgDispatcher +
                                        newTracingContext("BackstageTileScope")
                                )
                                .launch { sendTopApp(user) }
                        }
                    } else {
                        dialogDelegate.get().showDialog(action.expandable)
                    }
                }
                is QSTileUserAction.LongClick ->
                    qsTileIntentUserActionHandler.handle(action.expandable, listIntent())
                is QSTileUserAction.ToggleClick -> {}
            }
        }

    private fun sendTopApp(user: UserHandle) {
        val pkg = topApp(user)
        if (pkg == null) {
            // Nothing to send (the launcher is on screen): show the list instead.
            main.post {
                collapse()
                context.startActivityAsUser(listIntent(), user)
            }
            return
        }
        val current = packagesOf(secureSettings.getStringForUser(KEY_PACKAGES, user.identifier))
        if (pkg in current) return
        if (current.size >= MAX_APPS) {
            main.post {
                Toast.makeText(context, R.string.ichthys_backstage_tile_full, Toast.LENGTH_LONG)
                    .show()
            }
            return
        }
        secureSettings.putStringForUser(
            KEY_PACKAGES,
            (current + pkg).joinToString(","),
            user.identifier,
        )
        Log.i(TAG, "sent $pkg to Backstage")
        main.post { collapse() }
    }

    /** The package of the app filling the main screen, or null for launcher / system UI. */
    private fun topApp(user: UserHandle): String? {
        val task =
            try {
                ActivityTaskManager.getService()
                    .getTasks(1, false, false, Display.DEFAULT_DISPLAY)
                    .firstOrNull()
            } catch (e: RemoteException) {
                null
            } ?: return null
        if (task.userId != user.identifier) return null
        val type = task.getActivityType()
        if (
            type == WindowConfiguration.ACTIVITY_TYPE_HOME ||
                type == WindowConfiguration.ACTIVITY_TYPE_RECENTS ||
                type == WindowConfiguration.ACTIVITY_TYPE_DREAM
        ) {
            return null
        }
        val pkg = (task.baseActivity ?: task.topActivity)?.packageName ?: return null
        if (pkg == context.packageName) return null
        // Backstage starts and restores apps through their launcher entry.
        val pm = context.createContextAsUser(user, 0).packageManager
        return if (pm.getLaunchIntentForPackage(pkg) != null) pkg else null
    }

    private fun collapse() {
        context.getSystemService(StatusBarManager::class.java)?.collapsePanels()
    }

    private fun listIntent(): Intent =
        Intent(ACTION_BACKSTAGE_SETTINGS)
            .setPackage("com.android.settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private companion object {
        const val ACTION_BACKSTAGE_SETTINGS = "org.ichthys.action.BACKSTAGE_SETTINGS"
    }
}

class BackstageTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<BackstageTileModel> {

    override fun map(config: QSTileConfig, data: BackstageTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            val iconRes = R.drawable.ic_qs_ichthys_backstage
            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)
            label = resources.getString(R.string.ichthys_backstage_tile_label)
            if (data.apps.isEmpty()) {
                activationState = QSTileState.ActivationState.INACTIVE
                secondaryLabel = resources.getString(R.string.ichthys_backstage_tile_send)
            } else {
                activationState = QSTileState.ActivationState.ACTIVE
                secondaryLabel =
                    if (data.apps.size == 1) data.apps[0]
                    else resources.getString(R.string.ichthys_backstage_tile_count, data.apps.size)
            }
            contentDescription =
                if (TextUtils.isEmpty(secondaryLabel)) label
                else TextUtils.concat(label, ", ", secondaryLabel)
            sideViewIcon = QSTileState.SideViewIcon.Chevron
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
        }
}
