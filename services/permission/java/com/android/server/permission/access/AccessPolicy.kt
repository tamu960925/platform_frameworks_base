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

package com.android.server.permission.access

import android.util.Log
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.appop.PackageAppOpPolicy
import com.android.server.permission.access.appop.UidAppOpPolicy
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.UidPermissionPolicy
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName
import com.android.server.pm.permission.PermissionAllowlist
import com.android.server.pm.pkg.PackageState

class AccessPolicy private constructor(
    private val schemePolicies: IndexedMap<String, IndexedMap<String, SchemePolicy>>
) {
    constructor() : this(
        IndexedMap<String, IndexedMap<String, SchemePolicy>>().apply {
            fun addPolicy(policy: SchemePolicy) =
                getOrPut(policy.subjectScheme) { IndexedMap() }.put(policy.objectScheme, policy)
            addPolicy(UidPermissionPolicy())
            addPolicy(UidAppOpPolicy())
            addPolicy(PackageAppOpPolicy())
        }
    )

    fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        checkNotNull(schemePolicies[subjectScheme]?.get(objectScheme)) {
            "Scheme policy for $subjectScheme and $objectScheme does not exist"
        }

    fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int =
        with(getSchemePolicy(subject, `object`)){ getDecision(subject, `object`) }

    fun MutateStateScope.setDecision(subject: AccessUri, `object`: AccessUri, decision: Int) {
        with(getSchemePolicy(subject, `object`)) { setDecision(subject, `object`, decision) }
    }

    fun initialize(
        state: AccessState,
        userIds: IntSet,
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        permissionAllowlist: PermissionAllowlist
    ) {
        state.systemState.apply {
            this.userIds += userIds
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            packageStates.forEach { (_, packageState) ->
                appIds.getOrPut(packageState.appId) { IndexedListSet() }
                    .add(packageState.packageName)
            }
            this.permissionAllowlist = permissionAllowlist
        }
    }

    fun MutateStateScope.onUserAdded(userId: Int) {
        newState.systemState.userIds += userId
        newState.userStates[userId] = UserState()
        forEachSchemePolicy {
            with(it) { onUserAdded(userId) }
        }
    }

    fun MutateStateScope.onUserRemoved(userId: Int) {
        newState.systemState.userIds -= userId
        newState.userStates -= userId
        forEachSchemePolicy {
            with(it) { onUserRemoved(userId) }
        }
    }

    fun MutateStateScope.onStorageVolumeMounted(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        volumeUuid: String?,
        isSystemUpdated: Boolean
    ) {
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
        }
        forEachSchemePolicy {
            with(it) { onStorageVolumeMounted(volumeUuid, isSystemUpdated) }
        }
    }

    fun MutateStateScope.onPackageAdded(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        packageName: String
    ) {
        val packageState = packageStates[packageName]
        // TODO(zhanghai): STOPSHIP: Remove check before feature enable.
        checkNotNull(packageState) {
            "Added package $packageName isn't found in packageStates in onPackageAdded()"
        }
        val appId = packageState.appId
        var isAppIdAdded = false
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            appIds.getOrPut(appId) {
                isAppIdAdded = true
                IndexedListSet()
            }.add(packageName)
        }
        if (isAppIdAdded) {
            forEachSchemePolicy {
                with(it) { onAppIdAdded(appId) }
            }
        }
        forEachSchemePolicy {
            with(it) { onPackageAdded(packageState) }
        }
    }

    fun MutateStateScope.onPackageRemoved(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        packageName: String,
        appId: Int
    ) {
        // TODO(zhanghai): STOPSHIP: Remove check before feature enable.
        check(packageName !in packageStates) {
            "Removed package $packageName is still in packageStates in onPackageRemoved()"
        }
        var isAppIdRemoved = false
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            appIds[appId]?.apply {
                this -= packageName
                if (isEmpty()) {
                    appIds -= appId
                    isAppIdRemoved = true
                }
            }
        }
        forEachSchemePolicy {
            with(it) { onPackageRemoved(packageName, appId) }
        }
        if (isAppIdRemoved) {
            forEachSchemePolicy {
                with(it) { onAppIdRemoved(appId) }
            }
        }
    }

    fun MutateStateScope.onPackageInstalled(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        packageName: String,
        userId: Int
    ) {
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
        }
        val packageState = packageStates[packageName]
        // TODO(zhanghai): STOPSHIP: Remove check before feature enable.
        checkNotNull(packageState) {
            "Installed package $packageName isn't found in packageStates in onPackageInstalled()"
        }
        forEachSchemePolicy {
            with(it) { onPackageInstalled(packageState, userId) }
        }
    }

    fun MutateStateScope.onPackageUninstalled(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        packageName: String,
        appId: Int,
        userId: Int
    ) {
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
        }
        forEachSchemePolicy {
            with(it) { onPackageUninstalled(packageName, appId, userId) }
        }
    }

    fun BinaryXmlPullParser.parseSystemState(systemState: SystemState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        forEachSchemePolicy {
                            with(it) { parseSystemState(systemState) }
                        }
                    }
                }
                else -> Log.w(LOG_TAG, "Ignoring unknown tag $tagName when parsing system state")
            }
        }
    }

    fun BinaryXmlSerializer.serializeSystemState(systemState: SystemState) {
        tag(TAG_ACCESS) {
            forEachSchemePolicy {
                with(it) { serializeSystemState(systemState) }
            }
        }
    }

    fun BinaryXmlPullParser.parseUserState(userId: Int, userState: UserState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        forEachSchemePolicy {
                            with(it) { parseUserState(userId, userState) }
                        }
                    }
                }
                else -> {
                    Log.w(
                        LOG_TAG,
                        "Ignoring unknown tag $tagName when parsing user state for user $userId"
                    )
                }
            }
        }
    }

    fun BinaryXmlSerializer.serializeUserState(userId: Int, userState: UserState) {
        tag(TAG_ACCESS) {
            forEachSchemePolicy {
                with(it) { serializeUserState(userId, userState) }
            }
        }
    }

    private fun getSchemePolicy(subject: AccessUri, `object`: AccessUri): SchemePolicy =
        getSchemePolicy(subject.scheme, `object`.scheme)

    private inline fun forEachSchemePolicy(action: (SchemePolicy) -> Unit) {
        schemePolicies.forEachValueIndexed { _, objectSchemePolicies ->
            objectSchemePolicies.forEachValueIndexed { _, schemePolicy ->
                action(schemePolicy)
            }
        }
    }

    companion object {
        private val LOG_TAG = AccessPolicy::class.java.simpleName

        private const val TAG_ACCESS = "access"
    }
}

abstract class SchemePolicy {
    abstract val subjectScheme: String

    abstract val objectScheme: String

    abstract fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int

    abstract fun MutateStateScope.setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int
    )

    open fun MutateStateScope.onUserAdded(userId: Int) {}

    open fun MutateStateScope.onUserRemoved(userId: Int) {}

    open fun MutateStateScope.onAppIdAdded(appId: Int) {}

    open fun MutateStateScope.onAppIdRemoved(appId: Int) {}

    open fun MutateStateScope.onStorageVolumeMounted(
        volumeUuid: String?,
        isSystemUpdated: Boolean
    ) {}

    open fun MutateStateScope.onPackageAdded(packageState: PackageState) {}

    open fun MutateStateScope.onPackageRemoved(packageName: String, appId: Int) {}

    open fun MutateStateScope.onPackageInstalled(packageState: PackageState, userId: Int) {}

    open fun MutateStateScope.onPackageUninstalled(packageName: String, appId: Int, userId: Int) {}

    open fun BinaryXmlPullParser.parseSystemState(systemState: SystemState) {}

    open fun BinaryXmlSerializer.serializeSystemState(systemState: SystemState) {}

    open fun BinaryXmlPullParser.parseUserState(userId: Int, userState: UserState) {}

    open fun BinaryXmlSerializer.serializeUserState(userId: Int, userState: UserState) {}
}
