package com.madeinhk.cantonesevoicekeyboard.hwr

// Prompted sample collection waits briefly after pen-up before previewing candidates.
internal const val HandwritingInferenceIdleDelayMillis = 1_000L

// Keyboard handwriting runs live per-stroke recognition immediately on pen-up;
// only surface a busy indicator if model loading or inference actually takes noticeable time.
internal const val HandwritingLiveRecognitionDelayMillis = 0L
internal const val HandwritingBusyIndicatorDelayMillis = 150L
