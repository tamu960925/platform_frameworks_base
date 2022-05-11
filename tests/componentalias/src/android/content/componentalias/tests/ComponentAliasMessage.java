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
package android.content.componentalias.tests;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.DataClass;

/**
 * Parcelabe containing a "message" that's meant to be delivered via BroadcastMessenger.
 *
 * To add a new field, just add a private member field, and run:
 * codegen $ANDROID_BUILD_TOP/frameworks/base/tests/componentalias/src/android/content/componentalias/tests/ComponentAliasMessage.java
 */
@DataClass(
        genConstructor = false,
        genSetters = true,
        genToString = true,
        genAidl = false)
public final class ComponentAliasMessage implements Parcelable {
    public ComponentAliasMessage() {
    }

    @Nullable
    private String mMessage;

    @Nullable
    private String mMethodName;

    @Nullable
    private String mSenderIdentity;

    @Nullable
    private Intent mIntent;

    @Nullable
    private ComponentName mComponent;



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/tests/componentalias/src/android/content/componentalias/tests/ComponentAliasMessage.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public @Nullable String getMessage() {
        return mMessage;
    }

    @DataClass.Generated.Member
    public @Nullable String getMethodName() {
        return mMethodName;
    }

    @DataClass.Generated.Member
    public @Nullable String getSenderIdentity() {
        return mSenderIdentity;
    }

    @DataClass.Generated.Member
    public @Nullable Intent getIntent() {
        return mIntent;
    }

    @DataClass.Generated.Member
    public @Nullable ComponentName getComponent() {
        return mComponent;
    }

    @DataClass.Generated.Member
    public @NonNull ComponentAliasMessage setMessage(@NonNull String value) {
        mMessage = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ComponentAliasMessage setMethodName(@NonNull String value) {
        mMethodName = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ComponentAliasMessage setSenderIdentity(@NonNull String value) {
        mSenderIdentity = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ComponentAliasMessage setIntent(@NonNull Intent value) {
        mIntent = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ComponentAliasMessage setComponent(@NonNull ComponentName value) {
        mComponent = value;
        return this;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "ComponentAliasMessage { " +
                "message = " + mMessage + ", " +
                "methodName = " + mMethodName + ", " +
                "senderIdentity = " + mSenderIdentity + ", " +
                "intent = " + mIntent + ", " +
                "component = " + mComponent +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mMessage != null) flg |= 0x1;
        if (mMethodName != null) flg |= 0x2;
        if (mSenderIdentity != null) flg |= 0x4;
        if (mIntent != null) flg |= 0x8;
        if (mComponent != null) flg |= 0x10;
        dest.writeByte(flg);
        if (mMessage != null) dest.writeString(mMessage);
        if (mMethodName != null) dest.writeString(mMethodName);
        if (mSenderIdentity != null) dest.writeString(mSenderIdentity);
        if (mIntent != null) dest.writeTypedObject(mIntent, flags);
        if (mComponent != null) dest.writeTypedObject(mComponent, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ ComponentAliasMessage(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        String message = (flg & 0x1) == 0 ? null : in.readString();
        String methodName = (flg & 0x2) == 0 ? null : in.readString();
        String senderIdentity = (flg & 0x4) == 0 ? null : in.readString();
        Intent intent = (flg & 0x8) == 0 ? null : (Intent) in.readTypedObject(Intent.CREATOR);
        ComponentName component = (flg & 0x10) == 0 ? null : (ComponentName) in.readTypedObject(ComponentName.CREATOR);

        this.mMessage = message;
        this.mMethodName = methodName;
        this.mSenderIdentity = senderIdentity;
        this.mIntent = intent;
        this.mComponent = component;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<ComponentAliasMessage> CREATOR
            = new Parcelable.Creator<ComponentAliasMessage>() {
        @Override
        public ComponentAliasMessage[] newArray(int size) {
            return new ComponentAliasMessage[size];
        }

        @Override
        public ComponentAliasMessage createFromParcel(@NonNull Parcel in) {
            return new ComponentAliasMessage(in);
        }
    };

    @DataClass.Generated(
            time = 1630098801203L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/tests/componentalias/src/android/content/componentalias/tests/ComponentAliasMessage.java",
            inputSignatures = "private @android.annotation.Nullable java.lang.String mMessage\nprivate @android.annotation.Nullable java.lang.String mMethodName\nprivate @android.annotation.Nullable java.lang.String mSenderIdentity\nprivate @android.annotation.Nullable android.content.Intent mIntent\nprivate @android.annotation.Nullable android.content.ComponentName mComponent\nclass ComponentAliasMessage extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstructor=false, genSetters=true, genToString=true, genAidl=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
