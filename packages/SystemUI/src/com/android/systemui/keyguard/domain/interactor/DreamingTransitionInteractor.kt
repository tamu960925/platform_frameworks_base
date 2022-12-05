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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeStateModel.Companion.isDozeOff
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class DreamingTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(DreamingTransitionInteractor::class.simpleName!!) {

    private val canDreamFrom =
        setOf(KeyguardState.LOCKSCREEN, KeyguardState.GONE, KeyguardState.DOZING)

    override fun start() {
        listenForEntryToDreaming()
        listenForDreamingToLockscreen()
        listenForDreamingToGone()
        listenForDreamingToDozing()
    }

    private fun listenForEntryToDreaming() {
        scope.launch {
            keyguardInteractor.isDreaming
                .sample(
                    combine(
                        keyguardInteractor.dozeTransitionModel,
                        keyguardTransitionInteractor.finishedKeyguardState,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { triple ->
                    val (isDreaming, dozeTransitionModel, keyguardState) = triple
                    // Dozing/AOD and dreaming have overlapping events. If the state remains in
                    // FINISH, it means that doze mode is not running and DREAMING is ok to
                    // commence.
                    if (
                        isDozeOff(dozeTransitionModel.to) &&
                            isDreaming &&
                            canDreamFrom.contains(keyguardState)
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                keyguardState,
                                KeyguardState.DREAMING,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToLockscreen() {
        scope.launch {
            keyguardInteractor.isDreaming
                .sample(
                    combine(
                        keyguardInteractor.dozeTransitionModel,
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
                        ::Pair,
                    ),
                    ::toTriple
                )
                .collect { triple ->
                    val (isDreaming, dozeTransitionModel, lastStartedTransition) = triple
                    if (
                        isDozeOff(dozeTransitionModel.to) &&
                            !isDreaming &&
                            lastStartedTransition.to == KeyguardState.DREAMING
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DREAMING,
                                KeyguardState.LOCKSCREEN,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToGone() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(keyguardTransitionInteractor.finishedKeyguardState, ::Pair)
                .collect { pair ->
                    val (biometricUnlockState, keyguardState) = pair
                    if (
                        keyguardState == KeyguardState.DREAMING &&
                            isWakeAndUnlock(biometricUnlockState)
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DREAMING,
                                KeyguardState.GONE,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToDozing() {
        scope.launch {
            combine(
                    keyguardInteractor.dozeTransitionModel,
                    keyguardTransitionInteractor.finishedKeyguardState,
                    ::Pair
                )
                .collect { pair ->
                    val (dozeTransitionModel, keyguardState) = pair
                    if (
                        dozeTransitionModel.to == DozeStateModel.DOZE &&
                            keyguardState == KeyguardState.DREAMING
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DREAMING,
                                KeyguardState.DOZING,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun getAnimator(): ValueAnimator {
        return ValueAnimator().apply {
            setInterpolator(Interpolators.LINEAR)
            setDuration(TRANSITION_DURATION_MS)
        }
    }

    companion object {
        private const val TRANSITION_DURATION_MS = 500L
    }
}
