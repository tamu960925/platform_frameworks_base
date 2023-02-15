/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.power;

import static android.os.PowerManager.LOW_POWER_STANDBY_ALLOWED_REASON_TEMP_POWER_SAVE_ALLOWLIST;
import static android.os.PowerManager.LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION;
import static android.os.PowerManager.LOW_POWER_STANDBY_FEATURE_WAKE_ON_LAN;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.PowerManager.LowPowerStandbyPolicy;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.PowerAllowlistInternal;
import com.android.server.PowerAllowlistInternal.TempAllowlistChangeListener;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.power.LowPowerStandbyController.DeviceConfigWrapper;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link com.android.server.power.LowPowerStandbyController}.
 *
 * Build/Install/Run:
 * atest LowPowerStandbyControllerTest
 */
public class LowPowerStandbyControllerTest {
    private static final int STANDBY_TIMEOUT = 5000;
    private static final String TEST_PKG1 = "PKG1";
    private static final String TEST_PKG2 = "PKG2";
    private static final int TEST_PKG1_APP_ID = 123;
    private static final int TEST_PKG2_APP_ID = 456;
    private static final int USER_ID_1 = 0;
    private static final int USER_ID_2 = 10;
    private static final LowPowerStandbyPolicy EMPTY_POLICY = new LowPowerStandbyPolicy(
            "Test policy", Collections.emptySet(), 0, Collections.emptySet());

    private LowPowerStandbyController mController;
    private BroadcastInterceptingContext mContextSpy;
    private Resources mResourcesSpy;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private File mTestPolicyFile;

    @Mock
    private DeviceConfigWrapper mDeviceConfigWrapperMock;
    @Mock
    private AlarmManager mAlarmManagerMock;
    @Mock
    private PackageManager mPackageManagerMock;
    @Mock
    private UserManager mUserManagerMock;
    @Mock
    private IPowerManager mIPowerManagerMock;
    @Mock
    private PowerManagerInternal mPowerManagerInternalMock;
    @Mock
    private NetworkPolicyManagerInternal mNetworkPolicyManagerInternalMock;
    @Mock
    private PowerAllowlistInternal mPowerAllowlistInternalMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new BroadcastInterceptingContext(InstrumentationRegistry.getContext()));
        when(mContextSpy.getPackageManager()).thenReturn(mPackageManagerMock);
        when(mContextSpy.getSystemService(AlarmManager.class)).thenReturn(mAlarmManagerMock);
        when(mContextSpy.getSystemService(UserManager.class)).thenReturn(mUserManagerMock);
        PowerManager powerManager = new PowerManager(mContextSpy, mIPowerManagerMock, null, null);
        when(mContextSpy.getSystemService(PowerManager.class)).thenReturn(powerManager);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
        addLocalServiceMock(NetworkPolicyManagerInternal.class, mNetworkPolicyManagerInternalMock);
        addLocalServiceMock(PowerAllowlistInternal.class, mPowerAllowlistInternalMock);

        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        when(mDeviceConfigWrapperMock.enableCustomPolicy()).thenReturn(true);
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_lowPowerStandbySupported))
                .thenReturn(true);
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer.config_lowPowerStandbyNonInteractiveTimeout))
                .thenReturn(STANDBY_TIMEOUT);
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_lowPowerStandbyEnabledByDefault))
                .thenReturn(false);

        FakeSettingsProvider.clearSettingsProvider();
        MockContentResolver cr = new MockContentResolver(mContextSpy);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContextSpy.getContentResolver()).thenReturn(cr);

        when(mUserManagerMock.getUserHandles(true)).thenReturn(List.of(
                UserHandle.of(USER_ID_1), UserHandle.of(USER_ID_2)));
        when(mPackageManagerMock.getPackageUid(eq(TEST_PKG1), any())).thenReturn(TEST_PKG1_APP_ID);
        when(mPackageManagerMock.getPackageUid(eq(TEST_PKG2), any())).thenReturn(TEST_PKG2_APP_ID);

        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);

        mTestPolicyFile = new File(mContextSpy.getCacheDir(), "lps_policy.xml");
        mController = new LowPowerStandbyController(mContextSpy, mTestLooper.getLooper(),
                () -> mClock.now(), mDeviceConfigWrapperMock, mTestPolicyFile);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(LowPowerStandbyControllerInternal.class);
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
        LocalServices.removeServiceForTest(PowerAllowlistInternal.class);
        mTestPolicyFile.delete();
    }

    @Test
    public void testOnSystemReady_isInactivate() {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
    }

    @Test
    public void testActivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();
        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternalMock, times(1)).setLowPowerStandbyActive(true);
    }

    private void awaitStandbyTimeoutAlarm() {
        ArgumentCaptor<Long> timeArg = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<AlarmManager.OnAlarmListener> listenerArg =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);
        verify(mAlarmManagerMock).setExact(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                timeArg.capture(), anyString(),
                listenerArg.capture(), any());
        mClock.reset();
        mClock.fastForward(timeArg.getValue());
        listenerArg.getValue().onAlarm();
        mTestLooper.dispatchAll();
    }

    @Test
    public void testOnNonInteractive_notImmediatelyActive() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);

        setNonInteractive();
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
    }

    @Test
    public void testOnNonInteractive_activateAfterStandbyTimeout() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);

        setNonInteractive();
        awaitStandbyTimeoutAlarm();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternalMock, times(1)).setLowPowerStandbyActive(true);
    }

    @Test
    public void testOnNonInteractive_doesNotActivateWhenBecomingInteractive() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);

        setNonInteractive();
        advanceTime(STANDBY_TIMEOUT / 2);
        setInteractive();
        verifyStandbyAlarmCancelled();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
    }

    private void verifyStandbyAlarmCancelled() {
        InOrder inOrder = inOrder(mAlarmManagerMock);
        inOrder.verify(mAlarmManagerMock, atLeast(0)).setExact(anyInt(), anyLong(), anyString(),
                any(), any());
        inOrder.verify(mAlarmManagerMock).cancel((AlarmManager.OnAlarmListener) any());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testOnInteractive_deactivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setInteractive();
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternalMock, times(1)).setLowPowerStandbyActive(false);
    }

    @Test
    public void testOnDozeMaintenance_deactivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        mController.setActiveDuringMaintenance(false);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setDeviceIdleMode(false);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternalMock, times(1)).setLowPowerStandbyActive(false);
    }

    @Test
    public void testOnDozeMaintenance_activeDuringMaintenance_staysActive() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        mController.setActiveDuringMaintenance(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setDeviceIdleMode(false);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternalMock, never()).setLowPowerStandbyActive(false);
    }

    @Test
    public void testOnDozeMaintenanceEnds_activate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setDeviceIdleMode(false);
        advanceTime(1000);
        setDeviceIdleMode(true);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, times(2)).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternalMock, times(2)).setLowPowerStandbyActive(true);
    }

    @Test
    public void testLowPowerStandbyDisabled_doesNotActivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(false);
        setNonInteractive();

        assertThat(mController.isActive()).isFalse();
        verify(mAlarmManagerMock, never()).setExact(anyInt(), anyLong(), anyString(), any(), any());
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
    }

    @Test
    public void testLowPowerStandbyEnabled_EnabledChangedBroadcastsAreSent() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();

        BroadcastInterceptingContext.FutureIntent futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
        mController.setEnabled(false);
        futureIntent.assertNotReceived();

        futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
        mController.setEnabled(true);
        assertThat(futureIntent.get(1, TimeUnit.SECONDS)).isNotNull();

        futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
        mController.setEnabled(true);
        futureIntent.assertNotReceived();

        futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);

        mController.setEnabled(false);
        assertThat(futureIntent.get(1, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    public void testSetEnabled_WhenNotSupported_DoesNotEnable() throws Exception {
        setLowPowerStandbySupportedConfig(false);
        mController.systemReady();

        mController.setEnabled(true);

        assertThat(mController.isEnabled()).isFalse();
    }

    @Test
    public void testIsSupported_WhenSupported() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();

        assertThat(mController.isSupported()).isTrue();
    }

    @Test
    public void testIsSupported_WhenNotSupported() throws Exception {
        setLowPowerStandbySupportedConfig(false);
        mController.systemReady();

        assertThat(mController.isSupported()).isFalse();
    }

    @Test
    public void testForceActive() throws Exception {
        setLowPowerStandbySupportedConfig(false);
        mController.systemReady();

        mController.forceActive(true);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyActive(true);

        mController.forceActive(false);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyActive(false);
    }

    private void setLowPowerStandbySupportedConfig(boolean supported) {
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_lowPowerStandbySupported))
                .thenReturn(supported);
    }

    @Test
    public void testSetPolicy() throws Exception {
        mController.systemReady();
        mController.setPolicy(EMPTY_POLICY);
        assertThat(mController.getPolicy()).isEqualTo(EMPTY_POLICY);
    }

    @Test
    public void testSetDefaultPolicy() throws Exception {
        mController.systemReady();
        mController.setPolicy(EMPTY_POLICY);
        mController.setPolicy(null);
        assertThat(mController.getPolicy()).isNotNull();
        assertThat(mController.getPolicy()).isEqualTo(LowPowerStandbyController.DEFAULT_POLICY);
    }

    @Test
    public void testAddToAllowlist_ReasonIsAllowed_servicesAreNotified() throws Exception {
        mController.systemReady();
        mController.setPolicy(
                policyWithAllowedReasons(LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION));

        LowPowerStandbyControllerInternal service = LocalServices.getService(
                LowPowerStandbyControllerInternal.class);
        service.addToAllowlist(10, LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{10});
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{10});

        service.removeFromAllowlist(10, LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{});
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{});
    }

    @Test
    public void testRemoveFromAllowlist_ReasonIsAllowed_servicesAreNotified() throws Exception {
        mController.systemReady();
        mController.setPolicy(
                policyWithAllowedReasons(LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION));

        LowPowerStandbyControllerInternal service = LocalServices.getService(
                LowPowerStandbyControllerInternal.class);
        service.addToAllowlist(10, LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION);
        mTestLooper.dispatchAll();

        service.removeFromAllowlist(10, LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{});
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{});
    }

    @Test
    public void testSetAllowReasons_ActiveExemptionsNoLongerAllowed_servicesAreNotified() {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(
                policyWithAllowedReasons(LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION));

        LowPowerStandbyControllerInternal service = LocalServices.getService(
                LowPowerStandbyControllerInternal.class);
        service.addToAllowlist(10, LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION);
        mTestLooper.dispatchAll();

        mController.setPolicy(EMPTY_POLICY);
        mTestLooper.dispatchAll();

        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{});
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{});
    }

    @Test
    public void testSetAllowReasons_ReasonBecomesAllowed_servicesAreNotified() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(EMPTY_POLICY);

        LowPowerStandbyControllerInternal service = LocalServices.getService(
                LowPowerStandbyControllerInternal.class);
        service.addToAllowlist(10, LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION);
        mTestLooper.dispatchAll();

        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyAllowlist(any());
        verify(mNetworkPolicyManagerInternalMock, never()).setLowPowerStandbyAllowlist(any());

        mController.setPolicy(
                policyWithAllowedReasons(LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION));
        mTestLooper.dispatchAll();

        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{10});
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{10});
    }

    @Test
    public void testSetAllowReasons_NoActiveExemptions_servicesAreNotNotified() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(
                policyWithAllowedReasons(LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION));
        mController.setPolicy(EMPTY_POLICY);
        mTestLooper.dispatchAll();

        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyAllowlist(any());
        verify(mNetworkPolicyManagerInternalMock, never()).setLowPowerStandbyAllowlist(any());
    }

    @Test
    public void testSetAllowedFeatures_isAllowedIfDisabled() throws Exception {
        mController.systemReady();
        mController.setEnabled(false);
        mTestLooper.dispatchAll();

        assertTrue(mController.isAllowed(LOW_POWER_STANDBY_FEATURE_WAKE_ON_LAN));
    }

    @Test
    public void testSetAllowedFeatures_isAllowedWhenEnabled() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(policyWithAllowedFeatures(LOW_POWER_STANDBY_FEATURE_WAKE_ON_LAN));
        mTestLooper.dispatchAll();

        assertTrue(mController.isAllowed(LOW_POWER_STANDBY_FEATURE_WAKE_ON_LAN));
    }

    @Test
    public void testSetAllowedFeatures_isNotAllowed() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mTestLooper.dispatchAll();

        assertFalse(mController.isAllowed(LOW_POWER_STANDBY_FEATURE_WAKE_ON_LAN));
    }

    @Test
    public void testSetExemptPackages_uidPerUserIsExempt() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(policyWithExemptPackages(TEST_PKG1, TEST_PKG2));
        mTestLooper.dispatchAll();

        int[] expectedUidAllowlist = {
                UserHandle.getUid(USER_ID_1, TEST_PKG1_APP_ID),
                UserHandle.getUid(USER_ID_1, TEST_PKG2_APP_ID),
                UserHandle.getUid(USER_ID_2, TEST_PKG1_APP_ID),
                UserHandle.getUid(USER_ID_2, TEST_PKG2_APP_ID)
        };
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(expectedUidAllowlist);
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(expectedUidAllowlist);
    }

    @Test
    public void testExemptPackageIsRemoved_servicesAreNotified() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(policyWithExemptPackages(TEST_PKG1));
        mTestLooper.dispatchAll();

        int[] expectedUidAllowlist = {
                UserHandle.getUid(USER_ID_1, TEST_PKG1_APP_ID),
                UserHandle.getUid(USER_ID_2, TEST_PKG1_APP_ID),
        };
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(expectedUidAllowlist);
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(expectedUidAllowlist);
        verifyNoMoreInteractions(mPowerManagerInternalMock, mNetworkPolicyManagerInternalMock);

        reset(mPackageManagerMock);
        when(mPackageManagerMock.getPackageUid(eq(TEST_PKG1), any()))
                .thenThrow(PackageManager.NameNotFoundException.class);

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.fromParts(IntentFilter.SCHEME_PACKAGE, TEST_PKG1, null));
        intent.putExtra(Intent.EXTRA_REPLACING, false);
        mContextSpy.sendBroadcast(intent);
        mTestLooper.dispatchAll();

        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[0]);
        verify(mNetworkPolicyManagerInternalMock).setLowPowerStandbyAllowlist(new int[0]);
    }

    @Test
    public void testUsersChanged_packagesExemptForNewUser() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(policyWithExemptPackages(TEST_PKG1));
        mTestLooper.dispatchAll();

        InOrder inOrder = inOrder(mPowerManagerInternalMock);

        inOrder.verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{
                UserHandle.getUid(USER_ID_1, TEST_PKG1_APP_ID),
                UserHandle.getUid(USER_ID_2, TEST_PKG1_APP_ID),
        });
        inOrder.verifyNoMoreInteractions();

        when(mUserManagerMock.getUserHandles(true)).thenReturn(List.of(UserHandle.of(USER_ID_1)));
        Intent intent = new Intent(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(USER_ID_2));
        mContextSpy.sendBroadcast(intent);
        mTestLooper.dispatchAll();

        inOrder.verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{
                UserHandle.getUid(USER_ID_1, TEST_PKG1_APP_ID)
        });
        inOrder.verifyNoMoreInteractions();

        when(mUserManagerMock.getUserHandles(true)).thenReturn(
                List.of(UserHandle.of(USER_ID_1), UserHandle.of(USER_ID_2)));
        intent = new Intent(Intent.ACTION_USER_ADDED);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(USER_ID_2));
        mContextSpy.sendBroadcast(intent);
        mTestLooper.dispatchAll();

        inOrder.verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{
                UserHandle.getUid(USER_ID_1, TEST_PKG1_APP_ID),
                UserHandle.getUid(USER_ID_2, TEST_PKG1_APP_ID)
        });
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testIsExempt_exemptIfDisabled() throws Exception {
        mController.systemReady();
        mController.setEnabled(false);
        mTestLooper.dispatchAll();

        assertTrue(mController.isPackageExempt(TEST_PKG1_APP_ID));
    }

    @Test
    public void testIsExempt_notExemptIfEnabled() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mTestLooper.dispatchAll();

        assertFalse(mController.isPackageExempt(TEST_PKG1_APP_ID));
    }

    @Test
    public void testAllowReason_tempPowerSaveAllowlist() throws Exception {
        mController.systemReady();
        mController.setEnabled(true);
        mController.setPolicy(policyWithAllowedReasons(
                LOW_POWER_STANDBY_ALLOWED_REASON_TEMP_POWER_SAVE_ALLOWLIST));
        mTestLooper.dispatchAll();

        ArgumentCaptor<TempAllowlistChangeListener> tempAllowlistChangeListenerArgumentCaptor =
                ArgumentCaptor.forClass(TempAllowlistChangeListener.class);
        verify(mPowerAllowlistInternalMock).registerTempAllowlistChangeListener(
                tempAllowlistChangeListenerArgumentCaptor.capture());
        TempAllowlistChangeListener tempAllowlistChangeListener =
                tempAllowlistChangeListenerArgumentCaptor.getValue();

        tempAllowlistChangeListener.onAppAdded(TEST_PKG1_APP_ID);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{TEST_PKG1_APP_ID});

        tempAllowlistChangeListener.onAppAdded(TEST_PKG2_APP_ID);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(
                new int[]{TEST_PKG1_APP_ID, TEST_PKG2_APP_ID});

        tempAllowlistChangeListener.onAppRemoved(TEST_PKG1_APP_ID);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[]{TEST_PKG2_APP_ID});

        mController.setPolicy(EMPTY_POLICY);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[0]);
    }

    private void setInteractive() throws Exception {
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);
        mContextSpy.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
    }

    private void setNonInteractive() throws Exception {
        when(mIPowerManagerMock.isInteractive()).thenReturn(false);
        mContextSpy.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
    }

    private void setDeviceIdleMode(boolean idle) throws Exception {
        when(mIPowerManagerMock.isDeviceIdleMode()).thenReturn(idle);
        mContextSpy.sendBroadcast(new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
    }

    private LowPowerStandbyPolicy policyWithAllowedReasons(int allowedReasons) {
        return new LowPowerStandbyPolicy(
                "Test policy",
                Collections.emptySet(),
                allowedReasons,
                Collections.emptySet()
        );
    }

    private LowPowerStandbyPolicy policyWithAllowedFeatures(String... allowedFeatures) {
        return new LowPowerStandbyPolicy(
                "Test policy",
                Collections.emptySet(),
                0,
                new ArraySet<>(allowedFeatures)
        );
    }

    private LowPowerStandbyPolicy policyWithExemptPackages(String... exemptPackages) {
        return new LowPowerStandbyPolicy(
                "Test policy",
                new ArraySet<>(exemptPackages),
                0,
                Collections.emptySet()
        );
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }
}
