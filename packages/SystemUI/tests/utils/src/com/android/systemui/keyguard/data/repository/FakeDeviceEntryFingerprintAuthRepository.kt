/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeDeviceEntryFingerprintAuthRepository : DeviceEntryFingerprintAuthRepository {
    private val _isLockedOut = MutableStateFlow(false)
    override val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: Flow<Boolean>
        get() = _isRunning

    private var fpSensorType = MutableStateFlow<BiometricType?>(null)
    override val availableFpSensorType: Flow<BiometricType?>
        get() = fpSensorType

    fun setLockedOut(lockedOut: Boolean) {
        _isLockedOut.value = lockedOut
    }

    fun setIsRunning(value: Boolean) {
        _isRunning.value = value
    }

    fun setAvailableFpSensorType(value: BiometricType?) {
        fpSensorType.value = value
    }
}
