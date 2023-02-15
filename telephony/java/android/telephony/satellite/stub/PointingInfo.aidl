/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telephony.satellite.stub;

/**
 * @hide
 */
parcelable PointingInfo {
    /**
     * Satellite azimuth in degrees.
     */
    float satelliteAzimuth;

    /**
     * Satellite elevation in degrees.
     */
    float satelliteElevation;

    /** Antenna azimuth in degrees */
    float mAntennaAzimuthDegrees;

    /**
     * Angle of rotation about the x axis. This value represents the angle between a plane
     * parallel to the device's screen and a plane parallel to the ground.
     */
    float mAntennaPitchDegrees;

    /**
     * Angle of rotation about the y axis. This value represents the angle between a plane
     * perpendicular to the device's screen and a plane parallel to the ground.
     */
    float mAntennaRollDegrees;
}
