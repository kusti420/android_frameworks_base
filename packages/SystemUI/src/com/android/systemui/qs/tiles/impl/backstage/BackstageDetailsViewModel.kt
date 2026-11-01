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

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformButton
import com.android.compose.dialog.AlertDialogContent
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.dialog.colordisplay.IchthysSliderDialogDelegate
import com.android.systemui.qs.tiles.dialog.colordisplay.rememberIchthysDialogTokens
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

private const val TAG = "BackstageDetails"
private const val KEY_PACKAGES = "ichthys_backstage_pkgs"
/** IchthysBackstageService.MAX_APPS. */
private const val MAX_APPS = 3
/** How many recently used apps the popup offers to send. */
private const val RECENT_SHOWN = 6
/** Asked for from the task manager; home, SystemUI and the Backstage apps are dropped. */
private const val RECENT_FETCHED = 16

/**
 * IchthysOS: the Backstage tile's details popup (kusti420/ichthysos-tracker#38): the apps in
 * Backstage with a Remove control each, and the recently used apps with a Send control each. Dual
 * shade shows it as the tile's details view ([BackstageDetailsContent] through TileDetails.kt);
 * the single shade shows the same content in [BackstageDialogDelegate]. The only state is
 * Settings.Secure `ichthys_backstage_pkgs`, which IchthysBackstageService reconciles.
 */
@SysUISingleton
class BackstageDetailsViewModel
@Inject
constructor(
    private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : TileDetailsViewModel {

    /** One app row: its package, label and icon, all for the current user. */
    data class App(val pkg: String, val label: CharSequence, val icon: Drawable?)

    data class State(val inBackstage: List<App>, val recent: List<App>) {
        val full: Boolean
            get() = inBackstage.size >= MAX_APPS
    }

    /** The current lists, again whenever `ichthys_backstage_pkgs` changes. */
    fun state(): Flow<State> {
        val userId = userTracker.userId
        return merge(flowOf(Unit), secureSettings.observerFlow(userId, KEY_PACKAGES))
            .map { read(userId) }
            .flowOn(bgDispatcher)
    }

    /** Appends [pkg] to Backstage, unless it is full or already there. */
    fun send(pkg: String) {
        val userId = userTracker.userId
        val current = packagesOf(secureSettings.getStringForUser(KEY_PACKAGES, userId))
        if (pkg in current || current.size >= MAX_APPS) return
        secureSettings.putStringForUser(KEY_PACKAGES, (current + pkg).joinToString(","), userId)
        Log.i(TAG, "sent $pkg to Backstage")
    }

    /** Takes [pkg] out of Backstage (the service brings its task back, without opening it). */
    fun remove(pkg: String) {
        val userId = userTracker.userId
        val current = packagesOf(secureSettings.getStringForUser(KEY_PACKAGES, userId))
        if (pkg !in current) return
        val rest = current - pkg
        secureSettings.putStringForUser(
            KEY_PACKAGES,
            if (rest.isEmpty()) null else rest.joinToString(","),
            userId,
        )
        Log.i(TAG, "took $pkg out of Backstage")
    }

    override fun clickOnSettingsButton() {
        qsTileIntentUserActionHandler.handle(/* expandable= */ null, listIntent())
    }

    override val title: String = context.getString(R.string.ichthys_backstage_tile_label)

    override val subTitle: String = ""

    private fun read(userId: Int): State {
        val pm = context.createContextAsUser(UserHandle.of(userId), 0).packageManager
        val inBackstage = packagesOf(secureSettings.getStringForUser(KEY_PACKAGES, userId))
        val recent =
            recentPackages(userId)
                .filter { it !in inBackstage && pm.getLaunchIntentForPackage(it) != null }
                .take(RECENT_SHOWN)
        return State(inBackstage.map { app(pm, it) }, recent.map { app(pm, it) })
    }

    private fun app(pm: PackageManager, pkg: String): App =
        try {
            val info = pm.getApplicationInfo(pkg, 0)
            App(pkg, pm.getApplicationLabel(info), pm.getApplicationIcon(info))
        } catch (e: PackageManager.NameNotFoundException) {
            App(pkg, pkg, null)
        }

    /** Packages of the recent tasks, most recent first, without home and SystemUI. */
    private fun recentPackages(userId: Int): List<String> {
        val infos =
            try {
                ActivityTaskManager.getInstance()
                    .getRecentTasks(
                        RECENT_FETCHED,
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                        userId,
                    )
            } catch (e: RuntimeException) {
                Log.w(TAG, "getRecentTasks failed", e)
                return emptyList()
            }
        return infos
            .asSequence()
            .filter { it.baseIntent?.hasCategory(Intent.CATEGORY_HOME) != true }
            .mapNotNull { info ->
                (info.baseIntent?.component ?: info.baseActivity)?.packageName
            }
            .filter { it != context.packageName }
            .distinct()
            .toList()
    }

    companion object {
        const val ACTION_BACKSTAGE_SETTINGS = "org.ichthys.action.BACKSTAGE_SETTINGS"

        fun packagesOf(value: String?): List<String> =
            value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?: emptyList()

        fun listIntent(): Intent =
            Intent(ACTION_BACKSTAGE_SETTINGS)
                .setPackage("com.android.settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/**
 * IchthysOS: the single-shade home of [BackstageDetailsContent]: the details view only exists in
 * the dual shade (DetailsViewModel.onTileClicked), so like the Internet and Bluetooth tiles the
 * Backstage tile shows a SystemUIDialog there, with the details view's title row (title, settings
 * gear) on top. The dialog host is the shared compose dialog delegate of the Night Light dialog.
 */
@SysUISingleton
class BackstageDialogDelegate
@Inject
constructor(
    @Main mainCoroutineContext: CoroutineContext,
    sysuiDialogFactory: SystemUIDialogFactory,
    shadeDialogContextInteractor: ShadeDialogContextInteractor,
    dialogTransitionAnimator: DialogTransitionAnimator,
    private val viewModel: BackstageDetailsViewModel,
) :
    IchthysSliderDialogDelegate(
        mainCoroutineContext,
        sysuiDialogFactory,
        shadeDialogContextInteractor,
        dialogTransitionAnimator,
        jankTag = "backstage",
    ) {

    @Composable
    override fun Content(dialog: SystemUIDialog) {
        val tokens = rememberIchthysDialogTokens()
        AlertDialogContent(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_qs_ichthys_backstage),
                    contentDescription = null,
                    tint = tokens.accent,
                )
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = viewModel.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The details view's settings gear: the Backstage list in Settings.
                    IconButton(
                        onClick = {
                            dialog.dismiss()
                            viewModel.clickOnSettingsButton()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription =
                                stringResource(R.string.ichthys_backstage_tile_label),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            content = { BackstageDetailsContent(viewModel) },
            positiveButton = {
                PlatformButton(onClick = { dialog.dismiss() }) {
                    Text(stringResource(R.string.quick_settings_done))
                }
            },
            contentTopPadding = 8.dp,
            contentBottomPadding = 8.dp,
        )
    }
}
