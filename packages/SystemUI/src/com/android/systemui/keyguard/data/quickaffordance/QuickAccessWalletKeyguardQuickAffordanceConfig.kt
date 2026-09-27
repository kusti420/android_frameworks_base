/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.service.quickaccesswallet.GetWalletCardsError
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.service.quickaccesswallet.WalletCard
import android.util.Log
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.wallet.controller.QuickAccessWalletController
import com.android.systemui.wallet.util.getPaymentCards
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Quick access wallet quick affordance data source. */
@SysUISingleton
class QuickAccessWalletKeyguardQuickAffordanceConfig
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val walletController: QuickAccessWalletController,
    private val activityStarter: ActivityStarter,
) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET

    override fun pickerName(): String = context.getString(R.string.wallet_title)

    override val pickerIconResourceId = R.drawable.ic_wallet_lockscreen

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        conflatedCallbackFlow {
                val callback =
                    object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                        override fun onWalletCardsRetrieved(response: GetWalletCardsResponse) {
                            val hasCards =
                                getPaymentCards(response.walletCards)?.isNotEmpty() == true
                            trySendWithFailureLogging(hasCards, TAG)
                        }

                        override fun onWalletCardRetrievalError(error: GetWalletCardsError) {
                            Log.e(
                                TAG,
                                "Wallet card retrieval error, message: \"${error?.message}\"",
                            )
                            trySendWithFailureLogging(null, TAG)
                        }
                    }

                walletController.setupWalletChangeObservers(
                    callback,
                    QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE,
                    QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE,
                    QuickAccessWalletController.WalletChangeEvent.DEFAULT_WALLET_APP_CHANGE,
                )

                withContext(backgroundDispatcher) {
                    // Both must be called on background thread
                    walletController.updateWalletPreference()
                    walletController.queryWalletCards(callback)
                }

                awaitClose {
                    walletController.unregisterWalletChangeObservers(
                        QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE,
                        QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE,
                        QuickAccessWalletController.WalletChangeEvent.DEFAULT_WALLET_APP_CHANGE,
                    )
                }
            }
            .flatMapLatest { hasCards ->
                // If hasCards is null, this indicates an error occurred upon card retrieval
                val state =
                    if (hasCards == null) {
                        // IchthysOS: If wallet service failed but Curve Pay is installed,
                        // still show the affordance so the user can launch Curve Pay.
                        curvePayFallbackState()
                    } else {
                        state(isWalletAvailable(), hasCards, walletController.walletClient.tileIcon)
                    }
                flowOf(state)
            }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        // IchthysOS: Allow picking the wallet affordance when Curve Pay is installed,
        // even if no QuickAccessWalletService is available.
        return when {
            walletController.walletClient.isWalletServiceAvailable && isWalletAvailable() -> {
                if (queryCards().isEmpty()) {
                    KeyguardQuickAffordanceConfig.PickerScreenState.Disabled(
                        explanation =
                            context.getString(
                                R.string.wallet_quick_affordance_unavailable_configure_the_app
                            )
                    )
                } else {
                    KeyguardQuickAffordanceConfig.PickerScreenState.Default()
                }
            }

            isCurvePayInstalled() ->
                KeyguardQuickAffordanceConfig.PickerScreenState.Default()

            !walletController.walletClient.isWalletServiceAvailable ->
                KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice

            else ->
                KeyguardQuickAffordanceConfig.PickerScreenState.Disabled(
                    explanation =
                        context.getString(
                            R.string.wallet_quick_affordance_unavailable_install_the_app
                        )
                )
        }
    }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        if (walletController.walletClient.isWalletServiceAvailable) {
            walletController.startQuickAccessUiIntent(
                activityStarter,
                expandable?.activityTransitionController(),
                /* hasCard= */ true,
            )
        } else {
            // IchthysOS: Wallet service not available (e.g. Curve Pay doesn't implement
            // QuickAccessWalletService). Launch Curve Pay directly.
            val curveIntent = context.packageManager
                .getLaunchIntentForPackage(CURVE_PAY_PACKAGE)
            if (curveIntent != null) {
                activityStarter.postStartActivityDismissingKeyguard(
                    curveIntent,
                    /* delay= */ 0,
                    expandable?.activityTransitionController(),
                )
            }
        }
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled(true)
    }

    private suspend fun queryCards(): List<WalletCard> {
        return withContext(backgroundDispatcher) {
            suspendCancellableCoroutine { continuation ->
                val callback =
                    object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                        override fun onWalletCardsRetrieved(response: GetWalletCardsResponse) {
                            continuation.resumeWith(
                                Result.success(getPaymentCards(response.walletCards))
                            )
                        }

                        override fun onWalletCardRetrievalError(error: GetWalletCardsError) {
                            continuation.resumeWith(Result.success(emptyList()))
                        }
                    }
                // Must be called on background thread
                walletController.queryWalletCards(callback)
            }
        }
    }

    private suspend fun isWalletAvailable() =
        withContext(backgroundDispatcher) {
            with(walletController.walletClient) {
                // Must be called on background thread
                isWalletServiceAvailable && isWalletFeatureAvailable
            }
        }

    private fun state(
        isFeatureEnabled: Boolean,
        hasCard: Boolean,
        tileIcon: Drawable?,
    ): KeyguardQuickAffordanceConfig.LockScreenState {
        return if (isFeatureEnabled && hasCard && tileIcon != null) {
            val drawable = tileIcon.constantState?.newDrawable()
            val icon =
                if (drawable != null) {
                    Icon.Loaded(
                        drawable = drawable,
                        contentDescription =
                            ContentDescription.Resource(res = R.string.accessibility_wallet_button),
                    )
                } else {
                    Icon.Resource(
                        resId = R.drawable.ic_wallet_lockscreen,
                        contentDescription =
                            ContentDescription.Resource(res = R.string.accessibility_wallet_button),
                    )
                }
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = icon)
        } else if (isCurvePayInstalled()) {
            // IchthysOS: Wallet service not available but Curve Pay is installed.
            // Show the lockscreen affordance with the wallet icon so the user can
            // launch Curve Pay from the lockscreen.
            curvePayFallbackState()
        } else {
            KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        }
    }

    /**
     * IchthysOS: Check if Curve Pay is installed.
     */
    private fun isCurvePayInstalled(): Boolean {
        return try {
            context.packageManager.getApplicationInfo(CURVE_PAY_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * IchthysOS: Return a Visible lockscreen state using the Curve Pay app icon,
     * or the default wallet icon if loading fails.
     */
    private fun curvePayFallbackState(): KeyguardQuickAffordanceConfig.LockScreenState {
        return try {
            val ai = context.packageManager.getApplicationInfo(CURVE_PAY_PACKAGE, 0)
            val appIcon = context.packageManager.getApplicationIcon(ai)
            val drawable = appIcon.constantState?.newDrawable() ?: appIcon
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                icon = Icon.Loaded(
                    drawable = drawable,
                    contentDescription =
                        ContentDescription.Resource(res = R.string.accessibility_wallet_button),
                )
            )
        } catch (e: PackageManager.NameNotFoundException) {
            KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        }
    }

    companion object {
        private const val TAG = "QuickAccessWalletKeyguardQuickAffordanceConfig"
        // IchthysOS: Curve Pay as preferred wallet app (replaces Google Wallet)
        private const val CURVE_PAY_PACKAGE = "com.imaginecurve.curve.prd"
    }
}
