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

package com.android.server.permission.access.appop

import android.app.AppOpsManager
import com.android.server.permission.access.AccessUri
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.PackageUri
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports

class PackageAppOpPolicy : BaseAppOpPolicy(PackageAppOpPersistence()) {
    @Volatile
    private var onAppOpModeChangedListeners = IndexedListSet<OnAppOpModeChangedListener>()
    private val onAppOpModeChangedListenersLock = Any()

    override val subjectScheme: String
        get() = PackageUri.SCHEME

    override fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int {
        subject as PackageUri
        `object` as AppOpUri
        return getAppOpMode(subject.packageName, subject.userId, `object`.appOpName)
    }

    override fun MutateStateScope.setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int
    ) {
        subject as PackageUri
        `object` as AppOpUri
        setAppOpMode(subject.packageName, subject.userId, `object`.appOpName, decision)
    }

    override fun MutateStateScope.onPackageRemoved(packageName: String, appId: Int) {
        newState.userStates.forEachIndexed { _, _, userState ->
            userState.packageAppOpModes -= packageName
            userState.requestWrite()
            // Skip notifying the change listeners since the package no longer exists.
        }
    }

    fun MutateStateScope.removeAppOpModes(packageName: String, userId: Int): Boolean =
        newState.userStates[userId].packageAppOpModes.remove(packageName) != null

    fun GetStateScope.getAppOpMode(packageName: String, userId: Int, appOpName: String): Int =
        state.userStates[userId].packageAppOpModes[packageName]
            .getWithDefault(appOpName, AppOpsManager.opToDefaultMode(appOpName))

    fun MutateStateScope.setAppOpMode(
        packageName: String,
        userId: Int,
        appOpName: String,
        mode: Int
    ): Boolean {
        val userState = newState.userStates[userId]
        val packageAppOpModes = userState.packageAppOpModes
        var appOpModes = packageAppOpModes[packageName]
        val defaultMode = AppOpsManager.opToDefaultMode(appOpName)
        val oldMode = appOpModes.getWithDefault(appOpName, defaultMode)
        if (oldMode == mode) {
            return false
        }
        if (appOpModes == null) {
            appOpModes = IndexedMap()
            packageAppOpModes[packageName] = appOpModes
        }
        appOpModes.putWithDefault(appOpName, mode, defaultMode)
        if (appOpModes.isEmpty()) {
            packageAppOpModes -= packageName
        }
        userState.requestWrite()
        onAppOpModeChangedListeners.forEachIndexed { _, it ->
            it.onAppOpModeChanged(packageName, userId, appOpName, oldMode, mode)
        }
        return true
    }

    fun addOnAppOpModeChangedListener(listener: OnAppOpModeChangedListener) {
        synchronized(onAppOpModeChangedListenersLock) {
            onAppOpModeChangedListeners = onAppOpModeChangedListeners + listener
        }
    }

    fun removeOnAppOpModeChangedListener(listener: OnAppOpModeChangedListener) {
        synchronized(onAppOpModeChangedListenersLock) {
            onAppOpModeChangedListeners = onAppOpModeChangedListeners - listener
        }
    }

    fun interface OnAppOpModeChangedListener {
        fun onAppOpModeChanged(
            packageName: String,
            userId: Int,
            appOpName: String,
            oldMode: Int,
            newMode: Int
        )
    }
}
