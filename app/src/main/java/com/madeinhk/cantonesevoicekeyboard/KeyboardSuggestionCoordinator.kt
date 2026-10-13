package com.madeinhk.cantonesevoicekeyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.ClipboardItemSnapshot
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.ClipboardSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class KeyboardSuggestionCoordinator(
    private val service: InputMethodService,
    private val editor: KeyboardEditor,
    private val isInputViewVisible: () -> Boolean,
    private val acceptHandwriting: () -> Unit,
) : SuggestionSession {
    companion object {
        private const val INLINE_SUGGESTION_MIN_WIDTH_DP = 72
        private const val INLINE_SUGGESTION_HEIGHT_DP = 48
    }

    private val _autofillSuggestionView = MutableStateFlow<View?>(null)
    override val autofillSuggestionView = _autofillSuggestionView.asStateFlow()
    private val clipboard = ClipboardSession()
    override val clipboardSuggestion = clipboard.suggestion

    private var autofillGeneration = 0L
    private var clipboardManager: ClipboardManager? = null
    private var clipboardGeneration = 0L
    private var lastObservedFingerprint: ClipboardFingerprint? = null
    private var observingClipboard = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        refreshClipboardSuggestion(forceGeneration = true)
    }

    override fun onInputStarted() {
        clipboard.start()
        clearAutofill()
        startClipboardObservation()
        refreshClipboardSuggestion()
    }

    override fun onInputFinished() {
        clipboard.finish()
        clearAutofill()
        stopClipboardObservation()
    }

    override fun onUserTyped() {
        clipboard.onUserTyped()
        clearAutofill()
    }

    override fun onSelectionChanged() = clearAutofill()

    override fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        clearAutofill()
        val minWidth = dpToPx(INLINE_SUGGESTION_MIN_WIDTH_DP)
        val height = dpToPx(INLINE_SUGGESTION_HEIGHT_DP)
        val spec = InlinePresentationSpec.Builder(
            Size(minWidth, height),
            Size(service.maxWidth.coerceAtLeast(minWidth), height),
        ).setStyle(uiExtras).build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(1)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val suggestion = response.inlineSuggestions.firstOrNull()
        if (suggestion == null) {
            clearAutofill()
            return true
        }
        val generation = ++autofillGeneration
        _autofillSuggestionView.value = null
        val size = Size(
            service.maxWidth.coerceAtLeast(dpToPx(INLINE_SUGGESTION_MIN_WIDTH_DP)),
            dpToPx(INLINE_SUGGESTION_HEIGHT_DP),
        )
        inflate(suggestion, size) { view ->
            if (generation != autofillGeneration || !isInputViewVisible()) return@inflate
            (view.parent as? ViewGroup)?.removeView(view)
            _autofillSuggestionView.value = view
        }
        return true
    }

    override fun onAutofillTapped() {
        acceptHandwriting()
        clearAutofill()
    }

    fun clearAutofill() {
        autofillGeneration++
        _autofillSuggestionView.value = null
    }

    override fun pasteClipboard() {
        acceptHandwriting()
        clipboard.onPaste()
        val manager = clipboardManager ?: return
        val clip = manager.primaryClip
        val description = clip?.description
        if (
            clip == null ||
            description == null ||
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
        ) {
            refreshClipboardSuggestion()
            return
        }
        val text = (0 until clip.itemCount)
            .asSequence()
            .mapNotNull { clip.getItemAt(it).text?.toString() }
            .firstOrNull(String::isNotEmpty)
        clearAutofill()
        if (text != null) editor.commitText(text)
        refreshClipboardSuggestion()
    }

    private fun startClipboardObservation() {
        if (observingClipboard) return
        val manager = service.getSystemService(ClipboardManager::class.java) ?: return
        clipboardManager = manager
        manager.addPrimaryClipChangedListener(clipboardListener)
        observingClipboard = true
        refreshClipboardSuggestion()
    }

    private fun stopClipboardObservation() {
        if (!observingClipboard) return
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        observingClipboard = false
        clipboard.finish()
    }

    private fun refreshClipboardSuggestion(forceGeneration: Boolean = false) {
        if (!observingClipboard) return
        val clip = clipboardManager?.primaryClip
        val fingerprint = clip?.let {
            ClipboardFingerprint(it.description.timestamp, it.itemCount)
        }
        if (forceGeneration || fingerprint != lastObservedFingerprint) {
            clipboardGeneration++
            lastObservedFingerprint = fingerprint
        }
        val description = clip?.description
        val sensitive =
            description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        val mimeTypes = if (description == null) {
            emptySet()
        } else {
            (0 until description.mimeTypeCount)
                .mapTo(mutableSetOf(), description::getMimeType)
        }
        val items = if (clip == null) {
            emptyList()
        } else {
            (0 until clip.itemCount).map {
                ClipboardItemSnapshot(
                    mimeTypes = mimeTypes,
                    text = if (sensitive) null else clip.getItemAt(it).text?.toString(),
                )
            }
        }
        clipboard.update(ClipboardSnapshot(clipboardGeneration, items, sensitive))
    }

    private fun inflate(
        suggestion: InlineSuggestion,
        size: Size,
        onInflated: (InlineContentView) -> Unit,
    ) {
        try {
            suggestion.inflate(service, size, service.mainExecutor, onInflated)
        } catch (error: Throwable) {
            android.util.Log.w("CantoneseIME", "Unable to inflate inline suggestion", error)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * service.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private data class ClipboardFingerprint(val timestamp: Long, val itemCount: Int)
}
