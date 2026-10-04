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

package com.android.systemui.qs.tiles.impl.guard.data.repository

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.util.Log
import com.android.internal.ichthys.guard.IIchthysGuard
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * IchthysOS: SystemUI's side of Ichthys Guard, for the Lockdown and Pause QS tiles.
 *
 * Pause is owned by the platform: IchthysGuardService ("ichthys_guard", hidden AIDL
 * [IIchthysGuard]) keeps the deadline and lifts the firewall itself, so this talks to it directly
 * (SystemUI holds org.ichthys.permission.MANAGE_ICHTHYS_GUARD; its platform_app domains may find
 * the service).
 *
 * Lockdown is not a platform state: the allowlist and the per-app policies it overrides live in
 * Settings' Guard (credential-protected storage), which pushes the resulting uid table to the
 * service. So the tile asks Settings over a permission-protected ordered broadcast:
 * - [ACTION_LOCKDOWN] to [SETTINGS_PACKAGE], with [EXTRA_ENABLED] to switch lockdown, without it to
 *   only ask. Settings' receiver answers with result code [RESULT_ON] / [RESULT_OFF]; no answer
 *   (receiver missing, user still locked) leaves [RESULT_UNKNOWN].
 * - [ACTION_LOCKDOWN_CHANGED] from Settings to SystemUI whenever lockdown changes, with the new
 *   state in [EXTRA_ENABLED].
 * Both directions require MANAGE_ICHTHYS_GUARD, so no app can drive or spoof them.
 *
 * Asking starts the Settings process when it isn't running, so the last answer is kept per user
 * and [ACTION_LOCKDOWN_CHANGED] is watched for as long as SystemUI lives: Settings is only asked
 * while the state is unknown, not every time the shade opens.
 */
@SysUISingleton
class IchthysGuardRepository
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Background private val bgHandler: Handler,
    private val broadcastDispatcher: BroadcastDispatcher,
) {
    @Volatile private var service: IIchthysGuard? = null

    /** userId -> lockdown state Settings last reported; no entry while it is unknown. */
    private val lockdownCache = ConcurrentHashMap<Int, Boolean>()
    /** The userId whose [lockdownCache] entry just changed. */
    private val lockdownCacheChanges =
        MutableSharedFlow<Int>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val watchingLockdown = AtomicBoolean(false)
    private val lockdownChangedReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val userId = sendingUserId
                if (intent.hasExtra(EXTRA_ENABLED)) {
                    lockdownCache[userId] = intent.getBooleanExtra(EXTRA_ENABLED, false)
                } else {
                    // Changed, but not to what: ask again.
                    lockdownCache.remove(userId)
                }
                lockdownCacheChanges.tryEmit(userId)
            }
        }

    /** Whether the platform service exists (the tiles are offered only then). */
    fun isAvailable(): Boolean = ServiceManager.checkService(SERVICE_NAME) != null

    /**
     * When the current pause ends (wall clock ms), 0 while Guard enforces, null if the service
     * can't be reached.
     */
    suspend fun pausedUntil(): Long? = withContext(bgDispatcher) { call { it.pausedUntil } }

    /** Pauses enforcement for [minutes]; 0 resumes right away. @return false if unreachable. */
    suspend fun pause(minutes: Int): Boolean =
        withContext(bgDispatcher) { call { it.pauseEnforcement(minutes) } != null }

    /**
     * The lockdown state of [user]: the last one Settings reported, or, while that is unknown,
     * asked from Settings now.
     *
     * @return null when Settings didn't answer.
     */
    suspend fun lockdownState(user: UserHandle): Boolean? {
        watchLockdownChanges()
        return lockdownCache[user.identifier] ?: lockdown(user)
    }

    /**
     * Asks Settings for the lockdown state of [user], switching it first when [enable] is set.
     *
     * @return the state after the call, or null when Settings didn't answer.
     */
    suspend fun lockdown(user: UserHandle, enable: Boolean? = null): Boolean? =
        askLockdown(user, enable)?.also { state ->
            if (lockdownCache.put(user.identifier, state) != state) {
                lockdownCacheChanges.tryEmit(user.identifier)
            }
        }

    private suspend fun askLockdown(user: UserHandle, enable: Boolean?): Boolean? =
        withTimeoutOrNull(LOCKDOWN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean?> { cont ->
                val intent =
                    Intent(ACTION_LOCKDOWN)
                        .setPackage(SETTINGS_PACKAGE)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                if (enable != null) intent.putExtra(EXTRA_ENABLED, enable)
                val resultReceiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val state =
                                when (resultCode) {
                                    RESULT_ON -> true
                                    RESULT_OFF -> false
                                    else -> null
                                }
                            if (cont.isActive) cont.resume(state)
                        }
                    }
                try {
                    context.sendOrderedBroadcastAsUser(
                        intent,
                        user,
                        PERMISSION,
                        resultReceiver,
                        bgHandler,
                        RESULT_UNKNOWN,
                        null,
                        null,
                    )
                } catch (e: RuntimeException) {
                    Log.w(TAG, "lockdown broadcast failed", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /** Emits whenever the known lockdown state of [user] changes. */
    fun lockdownChanges(user: UserHandle): Flow<Unit> =
        lockdownCacheChanges
            .onStart { watchLockdownChanges() }
            .filter { it == user.identifier }
            .map {}

    /** Registered once and kept: the cache is only right while every change is seen. */
    private fun watchLockdownChanges() {
        if (!watchingLockdown.compareAndSet(false, true)) return
        broadcastDispatcher.registerReceiverWithHandler(
            lockdownChangedReceiver,
            IntentFilter(ACTION_LOCKDOWN_CHANGED),
            bgHandler,
            UserHandle.ALL,
            Context.RECEIVER_EXPORTED,
            PERMISSION,
        )
    }

    private inline fun <T> call(block: (IIchthysGuard) -> T): T? {
        val guard = guard() ?: return null
        return try {
            block(guard)
        } catch (e: RemoteException) {
            Log.w(TAG, "ichthys_guard call failed", e)
            service = null
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "ichthys_guard denied the call", e)
            null
        }
    }

    private fun guard(): IIchthysGuard? {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        val binder = ServiceManager.checkService(SERVICE_NAME) ?: return null
        return IIchthysGuard.Stub.asInterface(binder).also { service = it }
    }

    companion object {
        private const val TAG = "IchthysGuardRepository"

        const val SERVICE_NAME = "ichthys_guard"
        const val PERMISSION = "org.ichthys.permission.MANAGE_ICHTHYS_GUARD"
        const val SETTINGS_PACKAGE = "com.android.settings"

        /** Settings' Guard screen. Not exported; SystemUI holds START_ANY_ACTIVITY. */
        val GUARD_ACTIVITY =
            ComponentName(SETTINGS_PACKAGE, "com.android.settings.ichthys.guard.ui.GuardActivity")

        /** Opens Guard in Settings (tile long-press), for ActivityStarter. */
        fun guardScreenIntent(): Intent =
            Intent()
                .setComponent(GUARD_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        const val ACTION_LOCKDOWN = "com.android.settings.ichthys.guard.action.LOCKDOWN"
        const val ACTION_LOCKDOWN_CHANGED =
            "com.android.settings.ichthys.guard.action.LOCKDOWN_CHANGED"
        const val EXTRA_ENABLED = "enabled"
        const val RESULT_UNKNOWN = -1
        const val RESULT_OFF = 0
        const val RESULT_ON = 1

        /** Pause length of one tap on the Pause tile. */
        const val PAUSE_MINUTES = 5

        private const val LOCKDOWN_TIMEOUT_MS = 5_000L
    }
}
