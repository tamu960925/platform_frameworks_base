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
 */
package com.android.systemui.screenshot

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.phone.CentralSurfaces
import java.util.Optional
import javax.inject.Inject

/**
 * Provides state from the main SystemUI process on behalf of the Screenshot process.
 */
internal class ScreenshotProxyService @Inject constructor(
    private val mExpansionMgr: ShadeExpansionStateManager,
    private val mCentralSurfacesOptional: Optional<CentralSurfaces>,
) : Service() {

    private val mBinder: IBinder = object : IScreenshotProxy.Stub() {
        /**
         * @return true when the notification shade is partially or fully expanded.
         */
        override fun isNotificationShadeExpanded(): Boolean {
            val expanded = !mExpansionMgr.isClosed()
            Log.d(TAG, "isNotificationShadeExpanded(): $expanded")
            return expanded
        }

        override fun dismissKeyguard(callback: IOnDoneCallback) {
            if (mCentralSurfacesOptional.isPresent) {
                mCentralSurfacesOptional.get().executeRunnableDismissingKeyguard(
                    Runnable {
                        callback.onDone(true)
                    }, null,
                    true /* dismissShade */, true /* afterKeyguardGone */,
                    true /* deferred */
                )
            } else {
                callback.onDone(false)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind: $intent")
        return mBinder
    }

    companion object {
        const val TAG = "ScreenshotProxyService"
    }
}
