package com.madeinhk.cantonesevoicekeyboard

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardScreen

class CantoneseInputMethodService :
    android.inputmethodservice.InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "CantoneseIME"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val controller by lazy { KeyboardSessionController(this) }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore
        get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        val startedAt = SystemClock.elapsedRealtime()
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Log.i(TAG, "onCreate completed in ${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    override fun onCreateInputView(): View {
        val startedAt = SystemClock.elapsedRealtime()
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        applyNavigationBarAppearance()
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CantoneseInputMethodService)
            setViewTreeViewModelStoreOwner(this@CantoneseInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@CantoneseInputMethodService)
            setContent {
                CantoneseVoiceKeyboardTheme {
                    KeyboardScreen(
                        uiState = controller.uiState,
                        volumeLevel = controller.volumeLevel,
                        keyboardAction = controller.keyboardAction,
                        keyboardInputMode = controller.keyboardInputMode,
                        handwritingCandidates = controller.handwritingCandidates,
                        handwritingRecognizing = controller.handwritingRecognizing,
                        handwritingError = controller.handwritingError,
                        handwritingSession = controller.handwritingSession,
                        autofillSuggestionView = controller.autofillSuggestionView,
                        clipboardSuggestion = controller.clipboardSuggestion,
                        onToggleRecording = controller::toggleRecording,
                        onDeleteSurroundingText = controller::deleteSurroundingText,
                        onCommitText = controller::commitText,
                        onSendSpace = controller::sendSpace,
                        onSendEnter = controller::sendEnter,
                        onOpenSettings = ::openSettingsApp,
                        onPasteClipboard = controller::pasteClipboard,
                        onHandwritingChanged = controller::recognizeHandwriting,
                        onHandwritingStrokeStarted = controller::onHandwritingStrokeStarted,
                        onHandwritingCandidateSelected = controller::selectHandwritingCandidate,
                        onKeyboardInputModeSelected = controller::selectKeyboardInputMode,
                        onAutofillTapped = controller::onAutofillTapped,
                        onEmojiPicked = controller::commitText,
                    )
                }
            }
            Log.i(TAG, "onCreateInputView completed in ${SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        controller.onStartInput(info, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        applyNavigationBarAppearance()
        controller.onStartInputView(info)
    }

    override fun onFinishInput() {
        controller.onFinishInput()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller.onFinishInputView()
        super.onFinishInputView(finishingInput)
        stopComposeLifecycle()
    }

    override fun onWindowHidden() {
        controller.onWindowHidden()
        super.onWindowHidden()
        stopComposeLifecycle()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (
            oldSelStart != newSelStart ||
            oldSelEnd != newSelEnd ||
            candidatesStart != candidatesEnd
        ) controller.onSelectionChanged()
    }

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        controller.createInlineSuggestionsRequest(uiExtras)

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean =
        controller.onInlineSuggestionsResponse(response)

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        controller.destroy()
        super.onDestroy()
    }

    private fun stopComposeLifecycle() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    private fun applyNavigationBarAppearance() {
        val targetWindow = window?.window ?: return
        val nightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(
            targetWindow,
            targetWindow.decorView,
        ).isAppearanceLightNavigationBars = !nightMode
    }

    private fun openSettingsApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_SETTINGS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
