/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.flags.Flags.MODERN_BOUNCER;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowOnBackInvokedDispatcher;

import androidx.test.filters.SmallTest;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.data.BouncerView;
import com.android.systemui.keyguard.data.BouncerViewDelegate;
import com.android.systemui.keyguard.domain.interactor.BouncerCallbackInteractor;
import com.android.systemui.keyguard.domain.interactor.BouncerInteractor;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionChangeEvent;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class StatusBarKeyguardViewManagerTest extends SysuiTestCase {

    private static final PanelExpansionChangeEvent EXPANSION_EVENT =
            expansionEvent(/* fraction= */ 0.5f, /* expanded= */ false, /* tracking= */ true);

    @Mock private ViewMediatorCallback mViewMediatorCallback;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private ViewGroup mContainer;
    @Mock private NotificationPanelViewController mNotificationPanelView;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private View mNotificationContainer;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private KeyguardBouncer.Factory mKeyguardBouncerFactory;
    @Mock private KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    @Mock private KeyguardMessageAreaController mKeyguardMessageAreaController;
    @Mock private KeyguardBouncer mBouncer;
    @Mock private StatusBarKeyguardViewManager.AlternateAuthInterceptor mAlternateAuthInterceptor;
    @Mock private KeyguardMessageArea mKeyguardMessageArea;
    @Mock private ShadeController mShadeController;
    @Mock private SysUIUnfoldComponent mSysUiUnfoldComponent;
    @Mock private DreamOverlayStateController mDreamOverlayStateController;
    @Mock private LatencyTracker mLatencyTracker;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private KeyguardSecurityModel mKeyguardSecurityModel;
    @Mock private BouncerCallbackInteractor mBouncerCallbackInteractor;
    @Mock private BouncerInteractor mBouncerInteractor;
    @Mock private BouncerView mBouncerView;
//    @Mock private WeakReference<BouncerViewDelegate> mBouncerViewDelegateWeakReference;
    @Mock private BouncerViewDelegate mBouncerViewDelegate;

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private KeyguardBouncer.BouncerExpansionCallback mBouncerExpansionCallback;
    private FakeKeyguardStateController mKeyguardStateController =
            spy(new FakeKeyguardStateController());

    @Mock private ViewRootImpl mViewRootImpl;
    @Mock private WindowOnBackInvokedDispatcher mOnBackInvokedDispatcher;
    @Captor
    private ArgumentCaptor<OnBackInvokedCallback> mOnBackInvokedCallback;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mKeyguardBouncerFactory.create(
                        any(ViewGroup.class), any(KeyguardBouncer.BouncerExpansionCallback.class)))
                .thenReturn(mBouncer);
        when(mCentralSurfaces.getBouncerContainer()).thenReturn(mContainer);
        when(mContainer.findViewById(anyInt())).thenReturn(mKeyguardMessageArea);
        when(mKeyguardMessageAreaFactory.create(any(KeyguardMessageArea.class)))
                .thenReturn(mKeyguardMessageAreaController);
        when(mBouncerView.getDelegate()).thenReturn(mBouncerViewDelegate);

        mStatusBarKeyguardViewManager =
                new StatusBarKeyguardViewManager(
                        getContext(),
                        mViewMediatorCallback,
                        mLockPatternUtils,
                        mStatusBarStateController,
                        mock(ConfigurationController.class),
                        mKeyguardUpdateMonitor,
                        mDreamOverlayStateController,
                        mock(NavigationModeController.class),
                        mock(DockManager.class),
                        mock(NotificationShadeWindowController.class),
                        mKeyguardStateController,
                        mock(NotificationMediaManager.class),
                        mKeyguardBouncerFactory,
                        mKeyguardMessageAreaFactory,
                        Optional.of(mSysUiUnfoldComponent),
                        () -> mShadeController,
                        mLatencyTracker,
                        mKeyguardSecurityModel,
                        mFeatureFlags,
                        mBouncerCallbackInteractor,
                        mBouncerInteractor,
                        mBouncerView) {
                    @Override
                    public ViewRootImpl getViewRootImpl() {
                        return mViewRootImpl;
                    }
                };
        when(mViewRootImpl.getOnBackInvokedDispatcher())
                .thenReturn(mOnBackInvokedDispatcher);
        mStatusBarKeyguardViewManager.registerCentralSurfaces(
                mCentralSurfaces,
                mNotificationPanelView,
                new PanelExpansionStateManager(),
                mBiometricUnlockController,
                mNotificationContainer,
                mBypassController);
        mStatusBarKeyguardViewManager.show(null);
        ArgumentCaptor<KeyguardBouncer.BouncerExpansionCallback> callbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardBouncer.BouncerExpansionCallback.class);
        verify(mKeyguardBouncerFactory).create(any(ViewGroup.class),
                callbackArgumentCaptor.capture());
        mBouncerExpansionCallback = callbackArgumentCaptor.getValue();
    }

    @Test
    public void dismissWithAction_AfterKeyguardGoneSetToFalse() {
        OnDismissAction action = () -> false;
        Runnable cancelAction = () -> {};
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, false /* afterKeyguardGone */);
        verify(mBouncer).showWithDismissAction(eq(action), eq(cancelAction));
    }

    @Test
    public void showBouncer_onlyWhenShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_notWhenBouncerAlreadyShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        when(mBouncer.isSecure()).thenReturn(true);
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_showsTheBouncer() {
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer).show(anyBoolean(), eq(true));
    }

    @Test
    public void onPanelExpansionChanged_neverHidesScrimmedBouncer() {
        when(mBouncer.isShowing()).thenReturn(true);
        when(mBouncer.isScrimmed()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_VISIBLE));
    }

    @Test
    public void onPanelExpansionChanged_neverShowsDuringHintAnimation() {
        when(mNotificationPanelView.isUnlockHintRunning()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_HIDDEN));
    }

    @Test
    public void onPanelExpansionChanged_propagatesToBouncer() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer).setExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_hideBouncer_afterKeyguardHidden() {
        mStatusBarKeyguardViewManager.hide(0, 0);
        when(mBouncer.inTransit()).thenReturn(true);

        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_HIDDEN));
    }

    @Test
    public void onPanelExpansionChanged_showsBouncerWhenSwiping() {
        mKeyguardStateController.setCanDismissLockScreen(false);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer).show(eq(false), eq(false));

        // But not when it's already visible
        reset(mBouncer);
        when(mBouncer.isShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer, never()).show(eq(false), eq(false));

        // Or animating away
        reset(mBouncer);
        when(mBouncer.isAnimatingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer, never()).show(eq(false), eq(false));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenOccluded() {
        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animate */);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mBouncer, never()).setExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenWakeAndUnlock() {
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ KeyguardBouncer.EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenDismissBouncer() {
        // Since KeyguardBouncer.EXPANSION_VISIBLE = 0 panel expansion, if the unlock is dismissing
        // the bouncer, there may be an onPanelExpansionChanged(0) call to collapse the panel
        // which would mistakenly cause the bouncer to show briefly before its visibility
        // is set to hide. Therefore, we don't want to propagate panelExpansionChanged to the
        // bouncer if the bouncer is dismissing as a result of a biometric unlock.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_DISMISS_BOUNCER);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ KeyguardBouncer.EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenLaunchingApp() {
        when(mCentralSurfaces.isInLaunchTransition()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ KeyguardBouncer.EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenShadeLocked() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ KeyguardBouncer.EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void setOccluded_animatesPanelExpansion_onlyIfBouncerHidden() {
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mCentralSurfaces).animateKeyguardUnoccluding();

        when(mBouncer.isShowing()).thenReturn(true);
        clearInvocations(mCentralSurfaces);
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mCentralSurfaces, never()).animateKeyguardUnoccluding();
    }

    @Test
    public void setOccluded_onKeyguardOccludedChangedCalled() {
        clearInvocations(mKeyguardStateController);
        clearInvocations(mKeyguardUpdateMonitor);

        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, false);

        clearInvocations(mKeyguardUpdateMonitor);
        clearInvocations(mKeyguardStateController);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);

        clearInvocations(mKeyguardUpdateMonitor);
        clearInvocations(mKeyguardStateController);

        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, false);
    }

    @Test
    public void setOccluded_isInLaunchTransition_onKeyguardOccludedChangedCalled() {
        when(mCentralSurfaces.isInLaunchTransition()).thenReturn(true);
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);
    }

    @Test
    public void setOccluded_isLaunchingActivityOverLockscreen_onKeyguardOccludedChangedCalled() {
        when(mCentralSurfaces.isLaunchingActivityOverLockscreen()).thenReturn(true);
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);
    }

    @Test
    public void testHiding_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        when(mBouncer.isShowing()).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(true);
        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    public void testHidingBouncer_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        when(mBouncer.isShowing()).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(true);

        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    public void testHiding_doesntCancelWhenShowing() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action).onDismiss();
        verify(cancelAction, never()).run();
    }

    @Test
    public void testShowing_whenAlternateAuthShowing() {
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mAlternateAuthInterceptor.isShowingAlternateAuthBouncer()).thenReturn(true);
        assertTrue(
                "Is showing not accurate when alternative auth showing",
                mStatusBarKeyguardViewManager.isBouncerShowing());
    }

    @Test
    public void testWillBeShowing_whenAlternateAuthShowing() {
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mAlternateAuthInterceptor.isShowingAlternateAuthBouncer()).thenReturn(true);
        assertTrue(
                "Is or will be showing not accurate when alternative auth showing",
                mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing());
    }

    @Test
    public void testHideAltAuth_onShowBouncer() {
        // GIVEN alt auth is showing
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mAlternateAuthInterceptor.isShowingAlternateAuthBouncer()).thenReturn(true);
        reset(mAlternateAuthInterceptor);

        // WHEN showBouncer is called
        mStatusBarKeyguardViewManager.showBouncer(true);

        // THEN alt bouncer should be hidden
        verify(mAlternateAuthInterceptor).hideAlternateAuthBouncer();
    }

    @Test
    public void testBouncerIsOrWillBeShowing_whenBouncerIsInTransit() {
        when(mBouncer.isShowing()).thenReturn(false);
        when(mBouncer.inTransit()).thenReturn(true);

        assertTrue(
                "Is or will be showing should be true when bouncer is in transit",
                mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing());
    }

    @Test
    public void testShowAltAuth_unlockingWithBiometricNotAllowed() {
        // GIVEN alt auth exists, unlocking with biometric isn't allowed
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(false);

        // WHEN showGenericBouncer is called
        final boolean scrimmed = true;
        mStatusBarKeyguardViewManager.showGenericBouncer(scrimmed);

        // THEN regular bouncer is shown
        verify(mBouncer).show(anyBoolean(), eq(scrimmed));
        verify(mAlternateAuthInterceptor, never()).showAlternateAuthBouncer();
    }

    @Test
    public void testShowAltAuth_unlockingWithBiometricAllowed() {
        // GIVEN alt auth exists, unlocking with biometric is allowed
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);

        // WHEN showGenericBouncer is called
        mStatusBarKeyguardViewManager.showGenericBouncer(true);

        // THEN alt auth bouncer is shown
        verify(mAlternateAuthInterceptor).showAlternateAuthBouncer();
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
    }

    @Test
    public void testUpdateResources_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateResources();

        verify(mBouncer).updateResources();
    }

    @Test
    public void updateKeyguardPosition_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateKeyguardPosition(1.0f);

        verify(mBouncer).updateKeyguardPosition(1.0f);
    }

    @Test
    public void testIsBouncerInTransit() {
        when(mBouncer.inTransit()).thenReturn(true);
        Truth.assertThat(mStatusBarKeyguardViewManager.isBouncerInTransit()).isTrue();
        when(mBouncer.inTransit()).thenReturn(false);
        Truth.assertThat(mStatusBarKeyguardViewManager.isBouncerInTransit()).isFalse();
        mBouncer = null;
        Truth.assertThat(mStatusBarKeyguardViewManager.isBouncerInTransit()).isFalse();
    }

    private static PanelExpansionChangeEvent expansionEvent(
            float fraction, boolean expanded, boolean tracking) {
        return new PanelExpansionChangeEvent(
                fraction, expanded, tracking, /* dragDownPxAmount= */ 0f);
    }

    @Test
    public void testPredictiveBackCallback_registration() {
        /* verify that a predictive back callback is registered when the bouncer becomes visible */
        mBouncerExpansionCallback.onVisibilityChanged(true);
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                mOnBackInvokedCallback.capture());

        /* verify that the same callback is unregistered when the bouncer becomes invisible */
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mOnBackInvokedDispatcher).unregisterOnBackInvokedCallback(
                eq(mOnBackInvokedCallback.getValue()));
    }

    @Test
    public void testPredictiveBackCallback_invocationHidesBouncer() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                mOnBackInvokedCallback.capture());

        when(mBouncer.isShowing()).thenReturn(true);
        when(mCentralSurfaces.shouldKeyguardHideImmediately()).thenReturn(true);
        /* invoke the back callback directly */
        mOnBackInvokedCallback.getValue().onBackInvoked();

        /* verify that the bouncer will be hidden as a result of the invocation */
        verify(mCentralSurfaces).setBouncerShowing(eq(false));
    }

    @Test
    public void testReportBouncerOnDreamWhenVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        verify(mCentralSurfaces).setBouncerShowingOverDream(false);
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(true);
        verify(mCentralSurfaces).setBouncerShowingOverDream(true);
    }

    @Test
    public void testReportBouncerOnDreamWhenNotVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mCentralSurfaces).setBouncerShowingOverDream(false);
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mCentralSurfaces).setBouncerShowingOverDream(false);
    }

    @Test
    public void flag_off_DoesNotCallBouncerInteractor() {
        when(mFeatureFlags.isEnabled(MODERN_BOUNCER)).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(false);
        verify(mBouncerInteractor, never()).hide();
    }
}
