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

package com.android.settingslib.spa.framework.common

import android.util.Log
import java.util.LinkedList

const val MAX_ENTRY_SIZE = 5000

/**
 * The repository to maintain all Settings entries
 */
class SettingsEntryRepository(sppRepository: SettingsPageProviderRepository) {
    // Map of entry unique Id to entry
    private val entryMap: Map<String, SettingsEntry>

    // Map of Settings page to its contained entries.
    private val pageToEntryListMap: Map<String, List<SettingsEntry>>

    init {
        logMsg("Initialize")
        entryMap = mutableMapOf()
        pageToEntryListMap = mutableMapOf()

        val entryQueue = LinkedList<SettingsEntry>()
        for (page in sppRepository.getAllRootPages()) {
            val rootEntry = SettingsEntryBuilder.createRoot(page).build()
            if (!entryMap.containsKey(rootEntry.id)) {
                entryQueue.push(rootEntry)
                entryMap.put(rootEntry.id, rootEntry)
            }
        }

        while (entryQueue.isNotEmpty() && entryMap.size < MAX_ENTRY_SIZE) {
            val entry = entryQueue.pop()
            val page = entry.toPage
            if (page == null || pageToEntryListMap.containsKey(page.toString())) continue
            val spp = sppRepository.getProviderOrNull(page.name) ?: continue
            val newEntries = spp.buildEntry(page.args)
            pageToEntryListMap[page.toString()] = newEntries
            for (newEntry in newEntries) {
                if (!entryMap.containsKey(newEntry.id)) {
                    entryQueue.push(newEntry)
                    entryMap.put(newEntry.id, newEntry)
                }
            }
        }
    }

    fun printAllPages() {
        for (entry in pageToEntryListMap.entries) {
            logMsg("page: ${entry.key} with ${entry.value.size} entries")
        }
    }

    fun printAllEntries() {
        for (entry in entryMap.values) {
            logMsg("entry: $entry")
        }
    }
}

private fun logMsg(message: String) {
    Log.d("EntryRepo", message)
}
