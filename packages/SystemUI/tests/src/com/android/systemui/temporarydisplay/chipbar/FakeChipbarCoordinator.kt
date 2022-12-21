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

package com.android.systemui.temporarydisplay.chipbar

import android.content.Context
import android.os.PowerManager
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLock

/** A fake implementation of [ChipbarCoordinator] for testing. */
class FakeChipbarCoordinator(
    context: Context,
    logger: ChipbarLogger,
    windowManager: WindowManager,
    mainExecutor: DelayableExecutor,
    accessibilityManager: AccessibilityManager,
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    powerManager: PowerManager,
    falsingManager: FalsingManager,
    falsingCollector: FalsingCollector,
    viewUtil: ViewUtil,
    vibratorHelper: VibratorHelper,
    wakeLockBuilder: WakeLock.Builder,
    systemClock: SystemClock,
) :
    ChipbarCoordinator(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        dumpManager,
        powerManager,
        falsingManager,
        falsingCollector,
        viewUtil,
        vibratorHelper,
        wakeLockBuilder,
        systemClock,
    ) {
    override fun animateViewOut(view: ViewGroup, onAnimationEnd: Runnable) {
        // Just bypass the animation in tests
        onAnimationEnd.run()
    }
}
