package com.smartcess.app.features.onboarding

import androidx.lifecycle.ViewModel
import com.smartcess.app.core.ai.Speaker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Onboarding helpers: spoken voice sample for the voice-setup step. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val speaker: Speaker,
) : ViewModel() {
    fun playSample(text: String) = speaker.speak(text)

    fun stop() = speaker.stop()
}
