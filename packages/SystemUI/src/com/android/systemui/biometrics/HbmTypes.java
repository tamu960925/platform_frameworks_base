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

package com.android.systemui.biometrics;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Different high-brightness mode (HBM) types that are relevant to this package.
 */
public final class HbmTypes {
    /** HBM that applies to the whole screen. */
    public static final int GLOBAL_HBM = 0;

    /** HBM that only applies to a portion of the screen. */
    public static final int LOCAL_HBM = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({GLOBAL_HBM, LOCAL_HBM})
    public @interface HbmType {
    }
}
