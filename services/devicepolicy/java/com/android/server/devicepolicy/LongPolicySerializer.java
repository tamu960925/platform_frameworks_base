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

package com.android.server.devicepolicy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.LongPolicyValue;
import android.app.admin.PolicyKey;
import android.util.Log;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

final class LongPolicySerializer extends PolicySerializer<Long> {

    @Override
    void saveToXml(PolicyKey policyKey, TypedXmlSerializer serializer, String attributeName,
            @NonNull Long value) throws IOException {
        Objects.requireNonNull(value);
        serializer.attributeLong(/* namespace= */ null, attributeName, value);
    }

    @Nullable
    @Override
    LongPolicyValue readFromXml(TypedXmlPullParser parser, String attributeName) {
        try {
            return new LongPolicyValue(
                    parser.getAttributeLong(/* namespace= */ null, attributeName));
        } catch (XmlPullParserException e) {
            Log.e(DevicePolicyEngine.TAG, "Error parsing Long policy value", e);
            return null;
        }
    }
}
