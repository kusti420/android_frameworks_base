/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.Context;
import android.content.res.Configuration;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.ContextComponentHelper;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;

import java.io.PrintWriter;

/**
 * Dagger injection module for {@link RecentsImplementation}
 */
@Module
public abstract class RecentsModule {

    /** Start Recents.  */
    @Binds
    @IntoMap
    @ClassKey(Recents.class)
    abstract CoreStartable bindRecentsStartable(Recents impl);

    /** Listen to config changes for Recents.  */
    @Binds
    @IntoSet
    abstract ConfigurationListener bindRecentsConfigChanges(Recents impl);

    /** Start ScreenPinningRequest.  */
    @Binds
    @IntoMap
    @ClassKey(ScreenPinningRequest.class)
    abstract CoreStartable bindScreenPinningRequestStartable(ScreenPinningRequest impl);

    /** Listen to config changes for ScreenPinningRequest.  */
    @Binds
    @IntoSet
    abstract ConfigurationListener bindScreenPinningRequestConfigChanges(ScreenPinningRequest impl);

    /**
     * @return The {@link RecentsImplementation} from the config.
     */
    // Custom Options: user-selectable recents implementation.
    // recents_type: 0 = Stock (Launcher overview), 1 = Classic (revived vertical recents),
    // 2 = Slim (imported Slim Recents), 3 = Grace UX cascade (Samsung 7.1.1), 4 = Grid,
    // 5 = Stack, 6 = List, 7 = Slim list. Read on every recents request (see
    // SelectableRecentsImpl), so changing it applies at once.
    private static final String RECENTS_TYPE = "recents_type";
    private static final int RECENTS_STOCK = 0;
    private static final int RECENTS_CLASSIC = 1;
    private static final int RECENTS_SLIM = 2;

    @Provides
    public static RecentsImplementation provideRecentsImpl(Context context,
            ContextComponentHelper componentHelper) {
        // recents_type 1 (Classic) is served by LegacyRecentsImpl; 2 (Slim) and 3-7 (the
        // card-list styles) are driven entirely from the launcher's Quickstep
        // (OverviewCommandHelper -> slimrecent.RecentController / OneUiRecentsActivity), which
        // the stock component below forwards to.
        return new SelectableRecentsImpl(provideConfiguredRecentsImpl(context, componentHelper));
    }

    /**
     * Routes each recents request to Classic or to the configured (launcher) recents according
     * to the current recents_type, so the setting does not need a SystemUI restart.
     */
    private static final class SelectableRecentsImpl implements RecentsImplementation {
        private final RecentsImplementation mConfigured;
        private final RecentsImplementation mClassic = new LegacyRecentsImpl();
        private Context mContext;

        SelectableRecentsImpl(RecentsImplementation configured) {
            mConfigured = configured;
        }

        private RecentsImplementation current() {
            if (mContext != null && Settings.System.getIntForUser(mContext.getContentResolver(),
                    RECENTS_TYPE, RECENTS_STOCK, UserHandle.USER_CURRENT) == RECENTS_CLASSIC) {
                return mClassic;
            }
            return mConfigured;
        }

        @Override
        public void onStart(Context context) {
            mContext = context;
            mConfigured.onStart(context);
            mClassic.onStart(context);
        }

        @Override
        public void onBootCompleted() {
            mConfigured.onBootCompleted();
            mClassic.onBootCompleted();
        }

        @Override
        public void onAppTransitionFinished() {
            mConfigured.onAppTransitionFinished();
            mClassic.onAppTransitionFinished();
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            mConfigured.onConfigurationChanged(newConfig);
            mClassic.onConfigurationChanged(newConfig);
        }

        @Override
        public void preloadRecentApps() {
            current().preloadRecentApps();
        }

        @Override
        public void cancelPreloadRecentApps() {
            current().cancelPreloadRecentApps();
        }

        @Override
        public void showRecentApps(boolean triggeredFromAltTab) {
            current().showRecentApps(triggeredFromAltTab);
        }

        @Override
        public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
            current().hideRecentApps(triggeredFromAltTab, triggeredFromHomeKey);
        }

        @Override
        public void toggleRecentApps() {
            current().toggleRecentApps();
        }

        @Override
        public void dump(PrintWriter pw) {
            mConfigured.dump(pw);
        }
    }

    /** The {@link RecentsImplementation} named by {@code config_recentsComponent}. */
    private static RecentsImplementation provideConfiguredRecentsImpl(Context context,
            ContextComponentHelper componentHelper) {
        final String clsName = context.getString(R.string.config_recentsComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No recents component configured", null);
        }
        RecentsImplementation impl = componentHelper.resolveRecents(clsName);

        if (impl == null) {
            Class<?> cls = null;
            try {
                cls = context.getClassLoader().loadClass(clsName);
            } catch (Throwable t) {
                throw new RuntimeException("Error loading recents component: " + clsName, t);
            }
            try {
                impl = (RecentsImplementation) cls.newInstance();
            } catch (Throwable t) {
                throw new RuntimeException("Error creating recents component: " + clsName, t);
            }
        }

        return impl;
    }

    /** */
    @Binds
    @IntoMap
    @ClassKey(OverviewProxyRecentsImpl.class)
    public abstract RecentsImplementation bindOverviewProxyRecentsImpl(
            OverviewProxyRecentsImpl impl);
}
