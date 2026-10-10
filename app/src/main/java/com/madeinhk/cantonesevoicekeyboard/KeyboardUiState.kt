package com.madeinhk.cantonesevoicekeyboard

sealed interface KeyboardUiState {
    data object Idle : KeyboardUiState
    data object LoadingModel : KeyboardUiState
    data object DownloadingModel : KeyboardUiState
    data object Recording : KeyboardUiState
    data object Transcribing : KeyboardUiState
    data class Error(val message: String, val canRetryModelDownload: Boolean = false) : KeyboardUiState
}
