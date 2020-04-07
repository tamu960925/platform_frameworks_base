/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.metrics.LogMaker;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.media.KeyguardMediaController;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link NotificationStackScrollLayoutController}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class NotificationStackScrollerControllerTest extends SysuiTestCase {

    @Mock
    private NotificationGutsManager mNotificationGutsManager;
    @Mock
    private HeadsUpManagerPhone mHeadsUpManager;
    @Mock
    private NotificationRoundnessManager mNotificationRoundnessManager;
    @Mock
    private TunerService mTunerService;
    @Mock
    private DynamicPrivacyController mDynamicPrivacyController;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock
    private ZenModeController mZenModeController;
    @Mock
    private KeyguardMediaController mKeyguardMediaController;
    @Mock
    private SysuiStatusBarStateController mSysuiStatusBarStateController;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private SysuiColorExtractor mColorExtractor;
    @Mock
    private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock
    private MetricsLogger mMetricsLogger;

    private NotificationStackScrollLayoutController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mController = new NotificationStackScrollLayoutController(
                true,
                mNotificationGutsManager,
                mHeadsUpManager,
                mNotificationRoundnessManager,
                mTunerService,
                mDynamicPrivacyController,
                mConfigurationController,
                mSysuiStatusBarStateController,
                mKeyguardMediaController,
                mKeyguardBypassController,
                mZenModeController,
                mColorExtractor,
                mNotificationLockscreenUserManager,
                mMetricsLogger
        );

        when(mNotificationStackScrollLayout.isAttachedToWindow()).thenReturn(true);
    }


    @Test
    public void testAttach_viewAlreadyAttached() {
        mController.attach(mNotificationStackScrollLayout);

        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }
    @Test
    public void testAttach_viewAttachedAfterInit() {
        when(mNotificationStackScrollLayout.isAttachedToWindow()).thenReturn(false);

        mController.attach(mNotificationStackScrollLayout);

        verify(mConfigurationController, never()).addCallback(
                any(ConfigurationController.ConfigurationListener.class));

        mController.mOnAttachStateChangeListener.onViewAttachedToWindow(
                mNotificationStackScrollLayout);

        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }

    @Test
    public void testOnDensityOrFontScaleChanged_reInflatesFooterViews() {
        mController.attach(mNotificationStackScrollLayout);
        mController.mConfigurationListener.onDensityOrFontScaleChanged();
        verify(mNotificationStackScrollLayout).reinflateViews();
    }

    @Test
    public void testUpdateEmptyShadeView_notificationsVisible() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(true);
        mController.attach(mNotificationStackScrollLayout);

        mController.updateEmptyShadeView(true /* visible */);
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                true /* visible */,
                true /* notifVisibleInShade */);
        reset(mNotificationStackScrollLayout);
        mController.updateEmptyShadeView(false /* visible */);
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                false /* visible */,
                true /* notifVisibleInShade */);
    }

    @Test
    public void testUpdateEmptyShadeView_notificationsHidden() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        mController.attach(mNotificationStackScrollLayout);

        mController.updateEmptyShadeView(true /* visible */);
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                true /* visible */,
                false /* notifVisibleInShade */);
        reset(mNotificationStackScrollLayout);
        mController.updateEmptyShadeView(false /* visible */);
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                false /* visible */,
                false /* notifVisibleInShade */);
    }

    @Test
    public void testOnUserChange_verifySensitiveProfile() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);

        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        mController.attach(mNotificationStackScrollLayout);
        verify(mNotificationLockscreenUserManager)
                .addUserChangedListener(userChangedCaptor.capture());
        reset(mNotificationStackScrollLayout);

        UserChangedListener changedListener = userChangedCaptor.getValue();
        changedListener.onUserChanged(0);
        verify(mNotificationStackScrollLayout).setCurrentUserid(0);
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    public void testOnStatePostChange_verifyIfProfileIsPublic() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);

        ArgumentCaptor<StatusBarStateController.StateListener> stateListenerArgumentCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);

        mController.attach(mNotificationStackScrollLayout);
        verify(mSysuiStatusBarStateController).addCallback(
                stateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                stateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }


    @Test
    public void testOnMenuShownLogging() { ;

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class, RETURNS_DEEP_STUBS);
        when(row.getEntry().getSbn().getLogMaker()).thenReturn(new LogMaker(
                MetricsProto.MetricsEvent.VIEW_UNKNOWN));


        ArgumentCaptor<OnMenuEventListener> onMenuEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(OnMenuEventListener.class);

        mController.attach(mNotificationStackScrollLayout);
        verify(mNotificationStackScrollLayout).setMenuEventListener(
                onMenuEventListenerArgumentCaptor.capture());

        OnMenuEventListener onMenuEventListener = onMenuEventListenerArgumentCaptor.getValue();

        onMenuEventListener.onMenuShown(row);
        verify(row.getEntry().getSbn()).getLogMaker();  // This writes most of the log data
        verify(mMetricsLogger).write(logMatcher(MetricsProto.MetricsEvent.ACTION_REVEAL_GEAR,
                MetricsProto.MetricsEvent.TYPE_ACTION));
    }

    @Test
    public void testOnMenuClickedLogging() {
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class, RETURNS_DEEP_STUBS);
        when(row.getEntry().getSbn().getLogMaker()).thenReturn(new LogMaker(
                MetricsProto.MetricsEvent.VIEW_UNKNOWN));


        ArgumentCaptor<OnMenuEventListener> onMenuEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(OnMenuEventListener.class);

        mController.attach(mNotificationStackScrollLayout);
        verify(mNotificationStackScrollLayout).setMenuEventListener(
                onMenuEventListenerArgumentCaptor.capture());

        OnMenuEventListener onMenuEventListener = onMenuEventListenerArgumentCaptor.getValue();

        onMenuEventListener.onMenuClicked(row, 0, 0, mock(
                NotificationMenuRowPlugin.MenuItem.class));
        verify(row.getEntry().getSbn()).getLogMaker();  // This writes most of the log data
        verify(mMetricsLogger).write(logMatcher(MetricsProto.MetricsEvent.ACTION_TOUCH_GEAR,
                MetricsProto.MetricsEvent.TYPE_ACTION));
    }

    private LogMaker logMatcher(int category, int type) {
        return argThat(new LogMatcher(category, type));
    }

    static class LogMatcher implements ArgumentMatcher<LogMaker> {
        private int mCategory, mType;

        LogMatcher(int category, int type) {
            mCategory = category;
            mType = type;
        }
        public boolean matches(LogMaker l) {
            return (l.getCategory() == mCategory)
                    && (l.getType() == mType);
        }

        public String toString() {
            return String.format("LogMaker(%d, %d)", mCategory, mType);
        }
    }
}
