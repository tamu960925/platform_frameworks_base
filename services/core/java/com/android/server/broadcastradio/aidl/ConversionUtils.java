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

package com.android.server.broadcastradio.aidl;

import android.annotation.Nullable;
import android.hardware.broadcastradio.AmFmRegionConfig;
import android.hardware.broadcastradio.Announcement;
import android.hardware.broadcastradio.DabTableEntry;
import android.hardware.broadcastradio.IdentifierType;
import android.hardware.broadcastradio.Metadata;
import android.hardware.broadcastradio.ProgramFilter;
import android.hardware.broadcastradio.ProgramIdentifier;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.broadcastradio.Properties;
import android.hardware.broadcastradio.Result;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.RadioTuner;
import android.os.Build;
import android.os.ParcelableException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;

import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A utils class converting data types between AIDL broadcast radio HAL and
 * {@link android.hardware.radio}
 */
final class ConversionUtils {
    // TODO(b/241118988): Add unit test for ConversionUtils class
    private static final String TAG = "BcRadioAidlSrv.convert";

    private ConversionUtils() {
        throw new UnsupportedOperationException("ConversionUtils class is noninstantiable");
    }

    static boolean isAtLeastU(int targetSdkVersion) {
        // TODO(b/261770108): Use version code for U.
        return targetSdkVersion >= Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    static RuntimeException throwOnError(RuntimeException halException, String action) {
        if (!(halException instanceof ServiceSpecificException)) {
            return new ParcelableException(new RuntimeException(
                    action + ": unknown error"));
        }
        int result = ((ServiceSpecificException) halException).errorCode;
        switch (result) {
            case Result.UNKNOWN_ERROR:
                return new ParcelableException(new RuntimeException(action
                        + ": UNKNOWN_ERROR"));
            case Result.INTERNAL_ERROR:
                return new ParcelableException(new RuntimeException(action
                        + ": INTERNAL_ERROR"));
            case Result.INVALID_ARGUMENTS:
                return new IllegalArgumentException(action + ": INVALID_ARGUMENTS");
            case Result.INVALID_STATE:
                return new IllegalStateException(action + ": INVALID_STATE");
            case Result.NOT_SUPPORTED:
                return new UnsupportedOperationException(action + ": NOT_SUPPORTED");
            case Result.TIMEOUT:
                return new ParcelableException(new RuntimeException(action + ": TIMEOUT"));
            default:
                return new ParcelableException(new RuntimeException(
                        action + ": unknown error (" + result + ")"));
        }
    }

    @RadioTuner.TunerResultType
    static int halResultToTunerResult(int result) {
        switch (result) {
            case Result.OK:
                return RadioTuner.TUNER_RESULT_OK;
            case Result.INTERNAL_ERROR:
                return RadioTuner.TUNER_RESULT_INTERNAL_ERROR;
            case Result.INVALID_ARGUMENTS:
                return RadioTuner.TUNER_RESULT_INVALID_ARGUMENTS;
            case Result.INVALID_STATE:
                return RadioTuner.TUNER_RESULT_INVALID_STATE;
            case Result.NOT_SUPPORTED:
                return RadioTuner.TUNER_RESULT_NOT_SUPPORTED;
            case Result.TIMEOUT:
                return RadioTuner.TUNER_RESULT_TIMEOUT;
            case Result.UNKNOWN_ERROR:
            default:
                return RadioTuner.TUNER_RESULT_UNKNOWN_ERROR;
        }
    }

    static VendorKeyValue[] vendorInfoToHalVendorKeyValues(@Nullable Map<String, String> info) {
        if (info == null) {
            return new VendorKeyValue[]{};
        }

        ArrayList<VendorKeyValue> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : info.entrySet()) {
            VendorKeyValue elem = new VendorKeyValue();
            elem.key = entry.getKey();
            elem.value = entry.getValue();
            if (elem.key == null || elem.value == null) {
                Slogf.w(TAG, "VendorKeyValue contains invalid entry: key = %s, value = %s",
                        elem.key, elem.value);
                continue;
            }
            list.add(elem);
        }

        return list.toArray(VendorKeyValue[]::new);
    }

    static Map<String, String> vendorInfoFromHalVendorKeyValues(@Nullable VendorKeyValue[] info) {
        if (info == null) {
            return Collections.emptyMap();
        }

        Map<String, String> map = new ArrayMap<>();
        for (VendorKeyValue kvp : info) {
            if (kvp.key == null || kvp.value == null) {
                Slogf.w(TAG, "VendorKeyValue contains invalid entry: key = %s, value = %s",
                        kvp.key, kvp.value);
                continue;
            }
            map.put(kvp.key, kvp.value);
        }

        return map;
    }

    @ProgramSelector.ProgramType
    private static int identifierTypeToProgramType(
            @ProgramSelector.IdentifierType int idType) {
        switch (idType) {
            case ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY:
            case ProgramSelector.IDENTIFIER_TYPE_RDS_PI:
                // TODO(b/69958423): verify AM/FM with frequency range
                return ProgramSelector.PROGRAM_TYPE_FM;
            case ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT:
                // TODO(b/69958423): verify AM/FM with frequency range
                return ProgramSelector.PROGRAM_TYPE_FM_HD;
            case ProgramSelector.IDENTIFIER_TYPE_DAB_SIDECC:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_SCID:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT:
                return ProgramSelector.PROGRAM_TYPE_DAB;
            case ProgramSelector.IDENTIFIER_TYPE_DRMO_SERVICE_ID:
            case ProgramSelector.IDENTIFIER_TYPE_DRMO_FREQUENCY:
                return ProgramSelector.PROGRAM_TYPE_DRMO;
            case ProgramSelector.IDENTIFIER_TYPE_SXM_SERVICE_ID:
            case ProgramSelector.IDENTIFIER_TYPE_SXM_CHANNEL:
                return ProgramSelector.PROGRAM_TYPE_SXM;
        }
        if (idType >= ProgramSelector.IDENTIFIER_TYPE_VENDOR_PRIMARY_START
                && idType <= ProgramSelector.IDENTIFIER_TYPE_VENDOR_PRIMARY_END) {
            return idType;
        }
        return ProgramSelector.PROGRAM_TYPE_INVALID;
    }

    private static int[] identifierTypesToProgramTypes(int[] idTypes) {
        Set<Integer> programTypes = new ArraySet<>();

        for (int i = 0; i < idTypes.length; i++) {
            int pType = identifierTypeToProgramType(idTypes[i]);

            if (pType == ProgramSelector.PROGRAM_TYPE_INVALID) continue;

            programTypes.add(pType);
            if (pType == ProgramSelector.PROGRAM_TYPE_FM) {
                // TODO(b/69958423): verify AM/FM with region info
                programTypes.add(ProgramSelector.PROGRAM_TYPE_AM);
            }
            if (pType == ProgramSelector.PROGRAM_TYPE_FM_HD) {
                // TODO(b/69958423): verify AM/FM with region info
                programTypes.add(ProgramSelector.PROGRAM_TYPE_AM_HD);
            }
        }

        int[] programTypesArray = new int[programTypes.size()];
        int i = 0;
        for (int programType : programTypes) {
            programTypesArray[i++] = programType;
        }
        return programTypesArray;
    }

    private static RadioManager.BandDescriptor[] amfmConfigToBands(
            @Nullable AmFmRegionConfig config) {
        if (config == null) {
            return new RadioManager.BandDescriptor[0];
        }

        int len = config.ranges.length;
        List<RadioManager.BandDescriptor> bands = new ArrayList<>();

        // Just a placeholder value.
        int region = RadioManager.REGION_ITU_1;

        for (int i = 0; i < len; i++) {
            Utils.FrequencyBand bandType = Utils.getBand(config.ranges[i].lowerBound);
            if (bandType == Utils.FrequencyBand.UNKNOWN) {
                Slogf.e(TAG, "Unknown frequency band at %d kHz", config.ranges[i].lowerBound);
                continue;
            }
            if (bandType == Utils.FrequencyBand.FM) {
                bands.add(new RadioManager.FmBandDescriptor(region, RadioManager.BAND_FM,
                        config.ranges[i].lowerBound, config.ranges[i].upperBound,
                        config.ranges[i].spacing,

                        // TODO(b/69958777): stereo, rds, ta, af, ea
                        /* stereo= */ true, /* rds= */ true, /* ta= */ true, /* af= */ true,
                        /* ea= */ true
                ));
            } else {  // AM
                bands.add(new RadioManager.AmBandDescriptor(region, RadioManager.BAND_AM,
                        config.ranges[i].lowerBound, config.ranges[i].upperBound,
                        config.ranges[i].spacing,

                        // TODO(b/69958777): stereo
                        /* stereo= */ true
                ));
            }
        }

        return bands.toArray(RadioManager.BandDescriptor[]::new);
    }

    @Nullable
    private static Map<String, Integer> dabConfigFromHalDabTableEntries(
            @Nullable DabTableEntry[] config) {
        if (config == null) {
            return null;
        }
        Map<String, Integer> dabConfig = new ArrayMap<>();
        for (int i = 0; i < config.length; i++) {
            dabConfig.put(config[i].label, config[i].frequencyKhz);
        }
        return dabConfig;
    }

    static RadioManager.ModuleProperties propertiesFromHalProperties(int id,
            String serviceName, Properties prop,
            @Nullable AmFmRegionConfig amfmConfig, @Nullable DabTableEntry[] dabConfig) {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(prop);

        int[] supportedProgramTypes = identifierTypesToProgramTypes(prop.supportedIdentifierTypes);

        return new RadioManager.ModuleProperties(
                id,
                serviceName,

                // There is no Class concept in HAL AIDL.
                RadioManager.CLASS_AM_FM,

                prop.maker,
                prop.product,
                prop.version,
                prop.serial,

                // HAL AIDL only supports single tuner and audio source per
                // HAL implementation instance.
                /* numTuners= */ 1,
                /* numAudioSources= */ 1,
                /* isInitializationRequired= */ false,
                /* isCaptureSupported= */ false,

                amfmConfigToBands(amfmConfig),
                /* isBgScanSupported= */ true,
                supportedProgramTypes,
                prop.supportedIdentifierTypes,
                dabConfigFromHalDabTableEntries(dabConfig),
                vendorInfoFromHalVendorKeyValues(prop.vendorInfo)
        );
    }

    static ProgramIdentifier identifierToHalProgramIdentifier(ProgramSelector.Identifier id) {
        ProgramIdentifier hwId = new ProgramIdentifier();
        hwId.type = id.getType();
        hwId.value = id.getValue();
        return hwId;
    }

    @Nullable
    static ProgramSelector.Identifier identifierFromHalProgramIdentifier(
            ProgramIdentifier id) {
        if (id.type == IdentifierType.INVALID) {
            return null;
        }
        return new ProgramSelector.Identifier(id.type, id.value);
    }

    static android.hardware.broadcastradio.ProgramSelector programSelectorToHalProgramSelector(
            ProgramSelector sel) {
        android.hardware.broadcastradio.ProgramSelector hwSel =
                new android.hardware.broadcastradio.ProgramSelector();

        hwSel.primaryId = identifierToHalProgramIdentifier(sel.getPrimaryId());
        ProgramSelector.Identifier[] secondaryIds = sel.getSecondaryIds();
        ArrayList<ProgramIdentifier> secondaryIdList = new ArrayList<>(secondaryIds.length);
        for (int i = 0; i < secondaryIds.length; i++) {
            secondaryIdList.add(identifierToHalProgramIdentifier(secondaryIds[i]));
        }
        hwSel.secondaryIds = secondaryIdList.toArray(ProgramIdentifier[]::new);
        return hwSel;
    }

    private static boolean isEmpty(
            android.hardware.broadcastradio.ProgramSelector sel) {
        return sel.primaryId.type == IdentifierType.INVALID && sel.primaryId.value == 0
                && sel.secondaryIds.length == 0;
    }

    @Nullable
    static ProgramSelector programSelectorFromHalProgramSelector(
            android.hardware.broadcastradio.ProgramSelector sel) {
        if (isEmpty(sel)) {
            return null;
        }

        List<ProgramSelector.Identifier> secondaryIdList = new ArrayList<>();
        for (int i = 0; i < sel.secondaryIds.length; i++) {
            if (sel.secondaryIds[i] != null) {
                secondaryIdList.add(identifierFromHalProgramIdentifier(sel.secondaryIds[i]));
            }
        }

        return new ProgramSelector(
                identifierTypeToProgramType(sel.primaryId.type),
                Objects.requireNonNull(identifierFromHalProgramIdentifier(sel.primaryId)),
                secondaryIdList.toArray(new ProgramSelector.Identifier[0]),
                /* vendorIds= */ null);
    }

    private static RadioMetadata radioMetadataFromHalMetadata(Metadata[] meta) {
        RadioMetadata.Builder builder = new RadioMetadata.Builder();

        for (int i = 0; i < meta.length; i++) {
            switch (meta[i].getTag()) {
                case Metadata.rdsPs:
                    builder.putString(RadioMetadata.METADATA_KEY_RDS_PS, meta[i].getRdsPs());
                    break;
                case Metadata.rdsPty:
                    builder.putInt(RadioMetadata.METADATA_KEY_RDS_PTY, meta[i].getRdsPty());
                    break;
                case Metadata.rbdsPty:
                    builder.putInt(RadioMetadata.METADATA_KEY_RBDS_PTY, meta[i].getRbdsPty());
                    break;
                case Metadata.rdsRt:
                    builder.putString(RadioMetadata.METADATA_KEY_RDS_RT, meta[i].getRdsRt());
                    break;
                case Metadata.songTitle:
                    builder.putString(RadioMetadata.METADATA_KEY_TITLE, meta[i].getSongTitle());
                    break;
                case Metadata.songArtist:
                    builder.putString(RadioMetadata.METADATA_KEY_ARTIST, meta[i].getSongArtist());
                    break;
                case Metadata.songAlbum:
                    builder.putString(RadioMetadata.METADATA_KEY_ALBUM, meta[i].getSongAlbum());
                    break;
                case Metadata.stationIcon:
                    builder.putInt(RadioMetadata.METADATA_KEY_ICON, meta[i].getStationIcon());
                    break;
                case Metadata.albumArt:
                    builder.putInt(RadioMetadata.METADATA_KEY_ART, meta[i].getAlbumArt());
                    break;
                case Metadata.programName:
                    builder.putString(RadioMetadata.METADATA_KEY_PROGRAM_NAME,
                            meta[i].getProgramName());
                    break;
                case Metadata.dabEnsembleName:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_ENSEMBLE_NAME,
                            meta[i].getDabEnsembleName());
                    break;
                case Metadata.dabEnsembleNameShort:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_ENSEMBLE_NAME_SHORT,
                            meta[i].getDabEnsembleNameShort());
                    break;
                case Metadata.dabServiceName:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_SERVICE_NAME,
                            meta[i].getDabServiceName());
                    break;
                case Metadata.dabServiceNameShort:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_SERVICE_NAME_SHORT,
                            meta[i].getDabServiceNameShort());
                    break;
                case Metadata.dabComponentName:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_COMPONENT_NAME,
                            meta[i].getDabComponentName());
                    break;
                case Metadata.dabComponentNameShort:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_COMPONENT_NAME_SHORT,
                            meta[i].getDabComponentNameShort());
                    break;
                default:
                    Slogf.w(TAG, "Ignored unknown metadata entry: %s", meta[i]);
                    break;
            }

        }

        return builder.build();
    }

    static RadioManager.ProgramInfo programInfoFromHalProgramInfo(ProgramInfo info) {
        Collection<ProgramSelector.Identifier> relatedContent = new ArrayList<>();
        if (info.relatedContent != null) {
            for (int i = 0; i < info.relatedContent.length; i++) {
                ProgramSelector.Identifier relatedContentId =
                        identifierFromHalProgramIdentifier(info.relatedContent[i]);
                if (relatedContentId != null) {
                    relatedContent.add(relatedContentId);
                }
            }
        }

        return new RadioManager.ProgramInfo(
                Objects.requireNonNull(programSelectorFromHalProgramSelector(info.selector)),
                identifierFromHalProgramIdentifier(info.logicallyTunedTo),
                identifierFromHalProgramIdentifier(info.physicallyTunedTo),
                relatedContent,
                info.infoFlags,
                info.signalQuality,
                radioMetadataFromHalMetadata(info.metadata),
                vendorInfoFromHalVendorKeyValues(info.vendorInfo)
        );
    }

    static ProgramFilter filterToHalProgramFilter(@Nullable ProgramList.Filter filter) {
        if (filter == null) {
            filter = new ProgramList.Filter();
        }

        ProgramFilter hwFilter = new ProgramFilter();

        IntArray identifierTypeList = new IntArray(filter.getIdentifierTypes().size());
        ArrayList<ProgramIdentifier> identifiersList = new ArrayList<>();
        Iterator<Integer> typeIterator = filter.getIdentifierTypes().iterator();
        while (typeIterator.hasNext()) {
            identifierTypeList.add(typeIterator.next());
        }
        Iterator<ProgramSelector.Identifier> idIterator = filter.getIdentifiers().iterator();
        while (idIterator.hasNext()) {
            identifiersList.add(identifierToHalProgramIdentifier(idIterator.next()));
        }

        hwFilter.identifierTypes = identifierTypeList.toArray();
        hwFilter.identifiers = identifiersList.toArray(ProgramIdentifier[]::new);
        hwFilter.includeCategories = filter.areCategoriesIncluded();
        hwFilter.excludeModifications = filter.areModificationsExcluded();

        return hwFilter;
    }

    static ProgramList.Chunk chunkFromHalProgramListChunk(ProgramListChunk chunk) {
        Set<RadioManager.ProgramInfo> modified = new ArraySet<>(chunk.modified.length);
        for (int i = 0; i < chunk.modified.length; i++) {
            modified.add(programInfoFromHalProgramInfo(chunk.modified[i]));
        }
        Set<ProgramSelector.Identifier> removed = new ArraySet<>();
        if (chunk.removed != null) {
            for (int i = 0; i < chunk.removed.length; i++) {
                ProgramSelector.Identifier removedId =
                        identifierFromHalProgramIdentifier(chunk.removed[i]);
                if (removedId != null) {
                    removed.add(removedId);
                }
            }
        }
        return new ProgramList.Chunk(chunk.purge, chunk.complete, modified, removed);
    }

    private static boolean isNewIdentifierInU(ProgramSelector.Identifier id) {
        return id.getType() == ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT;
    }

    static boolean programSelectorMeetsSdkVersionRequirement(ProgramSelector sel,
            int targetSdkVersion) {
        if (isAtLeastU(targetSdkVersion)) {
            return true;
        }
        if (sel.getPrimaryId().getType() == ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT) {
            return false;
        }
        ProgramSelector.Identifier[] secondaryIds = sel.getSecondaryIds();
        for (int i = 0; i < secondaryIds.length; i++) {
            if (isNewIdentifierInU(secondaryIds[i])) {
                return false;
            }
        }
        return true;
    }

    static boolean programInfoMeetsSdkVersionRequirement(RadioManager.ProgramInfo info,
            int targetSdkVersion) {
        if (isAtLeastU(targetSdkVersion)) {
            return true;
        }
        if (!programSelectorMeetsSdkVersionRequirement(info.getSelector(), targetSdkVersion)) {
            return false;
        }
        if (isNewIdentifierInU(info.getLogicallyTunedTo())
                || isNewIdentifierInU(info.getPhysicallyTunedTo())) {
            return false;
        }
        Iterator<ProgramSelector.Identifier> relatedContentIt = info.getRelatedContent().iterator();
        while (relatedContentIt.hasNext()) {
            if (isNewIdentifierInU(relatedContentIt.next())) {
                return false;
            }
        }
        return true;
    }

    static ProgramList.Chunk convertChunkToTargetSdkVersion(ProgramList.Chunk chunk,
            int targetSdkVersion) {
        if (isAtLeastU(targetSdkVersion)) {
            return chunk;
        }
        Set<RadioManager.ProgramInfo> modified = chunk.getModified();
        modified.removeIf(info -> !programInfoMeetsSdkVersionRequirement(info, targetSdkVersion));
        Set<ProgramSelector.Identifier> removed = chunk.getRemoved();
        removed.removeIf(id -> isNewIdentifierInU(id));
        return new ProgramList.Chunk(chunk.isPurge(), chunk.isComplete(), modified, removed);
    }

    public static android.hardware.radio.Announcement announcementFromHalAnnouncement(
            Announcement hwAnnouncement) {
        return new android.hardware.radio.Announcement(
                Objects.requireNonNull(programSelectorFromHalProgramSelector(
                        hwAnnouncement.selector)),
                hwAnnouncement.type,
                vendorInfoFromHalVendorKeyValues(hwAnnouncement.vendorInfo)
        );
    }
}
