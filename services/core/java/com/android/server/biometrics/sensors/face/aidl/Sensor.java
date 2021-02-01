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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.face.AuthenticationFrame;
import android.hardware.biometrics.face.EnrollmentFrame;
import android.hardware.biometrics.face.Error;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.ISessionCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.SensorServiceStateProto;
import com.android.server.biometrics.SensorStateProto;
import com.android.server.biometrics.UserStateProto;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.Interruptable;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Maintains the state of a single sensor within an instance of the {@link IFace} HAL.
 */
public class Sensor implements IBinder.DeathRecipient {

    private boolean mTestHalEnabled;

    @NonNull private final String mTag;
    @NonNull private final FaceProvider mProvider;
    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final FaceSensorPropertiesInternal mSensorProperties;
    @NonNull private final BiometricScheduler mScheduler;
    @NonNull private final LockoutCache mLockoutCache;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;
    @NonNull private final HalClientMonitor.LazyDaemon<ISession> mLazySession;
    @Nullable private Session mCurrentSession;

    static class Session {
        @NonNull final HalSessionCallback mHalSessionCallback;
        @NonNull private final String mTag;
        @NonNull private final ISession mSession;
        private final int mUserId;

        Session(@NonNull String tag, @NonNull ISession session, int userId,
                @NonNull HalSessionCallback halSessionCallback) {
            mTag = tag;
            mSession = session;
            mUserId = userId;
            mHalSessionCallback = halSessionCallback;
            Slog.d(mTag, "New session created for user: " + userId);
        }
    }

    static class HalSessionCallback extends ISessionCallback.Stub {
        /**
         * Interface to sends results to the HalSessionCallback's owner.
         */
        public interface Callback {
            /**
             * Invoked when the HAL sends ERROR_HW_UNAVAILABLE.
             */
            void onHardwareUnavailable();
        }

        @NonNull
        private final Context mContext;
        @NonNull
        private final Handler mHandler;
        @NonNull
        private final String mTag;
        @NonNull
        private final BiometricScheduler mScheduler;
        private final int mSensorId;
        private final int mUserId;
        @NonNull
        private final Callback mCallback;

        HalSessionCallback(@NonNull Context context, @NonNull Handler handler, @NonNull String tag,
                @NonNull BiometricScheduler scheduler, int sensorId, int userId,
                @NonNull Callback callback) {
            mContext = context;
            mHandler = handler;
            mTag = tag;
            mScheduler = scheduler;
            mSensorId = sensorId;
            mUserId = userId;
            mCallback = callback;
        }

        @Override
        public void onStateChanged(int cookie, byte state) {
            // TODO(b/162973174)
        }

        @Override
        public void onChallengeGenerated(long challenge) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceGenerateChallengeClient)) {
                    Slog.e(mTag, "onChallengeGenerated for wrong client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FaceGenerateChallengeClient generateChallengeClient =
                        (FaceGenerateChallengeClient) client;
                generateChallengeClient.onChallengeGenerated(mSensorId, mUserId, challenge);
            });
        }

        @Override
        public void onChallengeRevoked(long challenge) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceRevokeChallengeClient)) {
                    Slog.e(mTag, "onChallengeRevoked for wrong client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FaceRevokeChallengeClient revokeChallengeClient =
                        (FaceRevokeChallengeClient) client;
                revokeChallengeClient.onChallengeRevoked(mSensorId, mUserId, challenge);
            });
        }

        @Override
        public void onAuthenticationFrame(AuthenticationFrame frame) {
            // TODO(b/174619156): propagate the frame to an AuthenticationClient
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AcquisitionClient)) {
                    Slog.e(mTag, "onAcquired for non-acquisition client: "
                            + Utils.getClientName(client));
                    return;
                }

                final AcquisitionClient<?> acquisitionClient = (AcquisitionClient<?>) client;
                acquisitionClient.onAcquired(frame.data.acquiredInfo, frame.data.vendorCode);
            });
        }

        @Override
        public void onEnrollmentFrame(EnrollmentFrame frame) {
            // TODO(b/174619156): propagate the frame to an EnrollmentClient
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AcquisitionClient)) {
                    Slog.e(mTag, "onAcquired for non-acquisition client: "
                            + Utils.getClientName(client));
                    return;
                }

                final AcquisitionClient<?> acquisitionClient = (AcquisitionClient<?>) client;
                acquisitionClient.onAcquired(frame.data.acquiredInfo, frame.data.vendorCode);
            });
        }

        @Override
        public void onError(byte error, int vendorCode) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                Slog.d(mTag, "onError"
                        + ", client: " + Utils.getClientName(client)
                        + ", error: " + error
                        + ", vendorCode: " + vendorCode);
                if (!(client instanceof Interruptable)) {
                    Slog.e(mTag, "onError for non-error consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final Interruptable interruptable = (Interruptable) client;
                interruptable.onError(error, vendorCode);

                if (error == Error.HW_UNAVAILABLE) {
                    mCallback.onHardwareUnavailable();
                }
            });
        }

        @Override
        public void onEnrollmentProgress(int enrollmentId, int remaining) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceEnrollClient)) {
                    Slog.e(mTag, "onEnrollmentProgress for non-enroll client: "
                            + Utils.getClientName(client));
                    return;
                }

                final int currentUserId = client.getTargetUserId();
                final CharSequence name = FaceUtils.getInstance(mSensorId)
                        .getUniqueName(mContext, currentUserId);
                final Face face = new Face(name, enrollmentId, mSensorId);

                final FaceEnrollClient enrollClient = (FaceEnrollClient) client;
                enrollClient.onEnrollResult(face, remaining);
            });
        }

        @Override
        public void onAuthenticationSucceeded(int enrollmentId, HardwareAuthToken hat) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(mTag, "onAuthenticationSucceeded for non-authentication consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final AuthenticationConsumer authenticationConsumer =
                        (AuthenticationConsumer) client;
                final Face face = new Face("" /* name */, enrollmentId, mSensorId);
                final byte[] byteArray = HardwareAuthTokenUtils.toByteArray(hat);
                final ArrayList<Byte> byteList = new ArrayList<>();
                for (byte b : byteArray) {
                    byteList.add(b);
                }
                authenticationConsumer.onAuthenticated(face, true /* authenticated */, byteList);
            });
        }

        @Override
        public void onAuthenticationFailed() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(mTag, "onAuthenticationFailed for non-authentication consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final AuthenticationConsumer authenticationConsumer =
                        (AuthenticationConsumer) client;
                final Face face = new Face("" /* name */, 0 /* faceId */, mSensorId);
                authenticationConsumer.onAuthenticated(face, false /* authenticated */,
                        null /* hat */);
            });
        }

        @Override
        public void onLockoutTimed(long durationMillis) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof LockoutConsumer)) {
                    Slog.e(mTag, "onLockoutTimed for non-lockout consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final LockoutConsumer lockoutConsumer = (LockoutConsumer) client;
                lockoutConsumer.onLockoutTimed(durationMillis);
            });
        }

        @Override
        public void onLockoutPermanent() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof LockoutConsumer)) {
                    Slog.e(mTag, "onLockoutPermanent for non-lockout consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final LockoutConsumer lockoutConsumer = (LockoutConsumer) client;
                lockoutConsumer.onLockoutPermanent();
            });
        }

        @Override
        public void onLockoutCleared() {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceResetLockoutClient)) {
                    Slog.e(mTag, "onLockoutCleared for non-resetLockout client: "
                            + Utils.getClientName(client));
                    return;
                }

                final FaceResetLockoutClient resetLockoutClient = (FaceResetLockoutClient) client;
                resetLockoutClient.onLockoutCleared();
            });
        }

        @Override
        public void onInteractionDetected() {
            // no-op
        }

        @Override
        public void onEnrollmentsEnumerated(int[] enrollmentIds) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof EnumerateConsumer)) {
                    Slog.e(mTag, "onEnrollmentsEnumerated for non-enumerate consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final EnumerateConsumer enumerateConsumer =
                        (EnumerateConsumer) client;
                if (enrollmentIds.length > 0) {
                    for (int i = 0; i < enrollmentIds.length; ++i) {
                        final Face face = new Face("" /* name */, enrollmentIds[i], mSensorId);
                        enumerateConsumer.onEnumerationResult(face, enrollmentIds.length - i - 1);
                    }
                } else {
                    enumerateConsumer.onEnumerationResult(null /* identifier */, 0 /* remaining */);
                }
            });
        }

        @Override
        public void onFeaturesRetrieved(byte[] features, int enrollmentId) {

        }

        @Override
        public void onFeatureSet(int enrollmentId, byte feature) {

        }

        @Override
        public void onEnrollmentsRemoved(int[] enrollmentIds) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof RemovalConsumer)) {
                    Slog.e(mTag, "onRemoved for non-removal consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final RemovalConsumer removalConsumer = (RemovalConsumer) client;
                if (enrollmentIds.length > 0) {
                    for (int i = 0; i < enrollmentIds.length; i++) {
                        final Face face = new Face("" /* name */, enrollmentIds[i], mSensorId);
                        removalConsumer.onRemoved(face, enrollmentIds.length - i - 1);
                    }
                } else {
                    removalConsumer.onRemoved(null /* identifier */, 0 /* remaining */);
                }
            });
        }

        @Override
        public void onAuthenticatorIdRetrieved(long authenticatorId) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceGetAuthenticatorIdClient)) {
                    Slog.e(mTag, "onAuthenticatorIdRetrieved for wrong consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final FaceGetAuthenticatorIdClient getAuthenticatorIdClient =
                        (FaceGetAuthenticatorIdClient) client;
                getAuthenticatorIdClient.onAuthenticatorIdRetrieved(authenticatorId);
            });
        }

        @Override
        public void onAuthenticatorIdInvalidated(long newAuthenticatorId) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof FaceInvalidationClient)) {
                    Slog.e(mTag, "onAuthenticatorIdInvalidated for wrong consumer: "
                            + Utils.getClientName(client));
                    return;
                }

                final FaceInvalidationClient invalidationClient = (FaceInvalidationClient) client;
                invalidationClient.onAuthenticatorIdInvalidated(newAuthenticatorId);
            });
        }

    }

    Sensor(@NonNull String tag, @NonNull FaceProvider provider, @NonNull Context context,
            @NonNull Handler handler, @NonNull FaceSensorPropertiesInternal sensorProperties) {
        mTag = tag;
        mProvider = provider;
        mContext = context;
        mHandler = handler;
        mSensorProperties = sensorProperties;
        mScheduler = new BiometricScheduler(tag, null /* gestureAvailabilityDispatcher */);
        mLockoutCache = new LockoutCache();
        mAuthenticatorIds = new HashMap<>();
        mLazySession = () -> {
            if (mTestHalEnabled) {
                return new TestSession(mCurrentSession.mHalSessionCallback);
            } else {
                return mCurrentSession != null ? mCurrentSession.mSession : null;
            }
        };
    }

    @NonNull HalClientMonitor.LazyDaemon<ISession> getLazySession() {
        return mLazySession;
    }

    @NonNull FaceSensorPropertiesInternal getSensorProperties() {
        return mSensorProperties;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean hasSessionForUser(int userId) {
        return mCurrentSession != null && mCurrentSession.mUserId == userId;
    }

    @Nullable Session getSessionForUser(int userId) {
        if (mCurrentSession != null && mCurrentSession.mUserId == userId) {
            return mCurrentSession;
        } else {
            return null;
        }
    }

    @NonNull ITestSession createTestSession() {
        return new BiometricTestSessionImpl(mContext, mSensorProperties.sensorId, mProvider, this);
    }

    void createNewSession(@NonNull IFace daemon, int sensorId, int userId)
            throws RemoteException {

        final HalSessionCallback.Callback callback = () -> {
            Slog.e(mTag, "Got ERROR_HW_UNAVAILABLE");
            mCurrentSession = null;
        };
        final HalSessionCallback resultController = new HalSessionCallback(mContext, mHandler,
                mTag, mScheduler, sensorId, userId, callback);

        final ISession newSession = daemon.createSession(sensorId, userId, resultController);
        newSession.asBinder().linkToDeath(this, 0 /* flags */);
        mCurrentSession = new Session(mTag, newSession, userId, resultController);
    }

    @NonNull BiometricScheduler getScheduler() {
        return mScheduler;
    }

    @NonNull LockoutCache getLockoutCache() {
        return mLockoutCache;
    }

    @NonNull Map<Integer, Long> getAuthenticatorIds() {
        return mAuthenticatorIds;
    }

    void setTestHalEnabled(boolean enabled) {
        mTestHalEnabled = enabled;
    }

    void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
            boolean clearSchedulerBuffer) {
        final long sensorToken = proto.start(SensorServiceStateProto.SENSOR_STATES);

        proto.write(SensorStateProto.SENSOR_ID, mSensorProperties.sensorId);
        proto.write(SensorStateProto.MODALITY, SensorStateProto.FACE);
        proto.write(SensorStateProto.SCHEDULER, mScheduler.dumpProtoState(clearSchedulerBuffer));

        for (UserInfo user : UserManager.get(mContext).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(SensorStateProto.USER_STATES);
            proto.write(UserStateProto.USER_ID, userId);
            proto.write(UserStateProto.NUM_ENROLLED,
                    FaceUtils.getInstance(mSensorProperties.sensorId)
                            .getBiometricsForUser(mContext, userId).size());
            proto.end(userToken);
        }

        proto.end(sensorToken);
    }

    @Override
    public void binderDied() {
        Slog.e(mTag, "Binder died");
        mHandler.post(() -> {
            final BaseClientMonitor client = mScheduler.getCurrentClient();
            if (client instanceof Interruptable) {
                Slog.e(mTag, "Sending ERROR_HW_UNAVAILABLE for client: " + client);
                final Interruptable interruptable = (Interruptable) client;
                interruptable.onError(FaceManager.FACE_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);

                mScheduler.recordCrashState();

                FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                        BiometricsProtoEnums.MODALITY_FACE,
                        BiometricsProtoEnums.ISSUE_HAL_DEATH);
                mCurrentSession = null;
            }
        });
    }
}
