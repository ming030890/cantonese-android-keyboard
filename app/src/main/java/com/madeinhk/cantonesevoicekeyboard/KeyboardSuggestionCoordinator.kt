package com.madeinhk.cantonesevoicekeyboard

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
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
        internal const val MAX_INLINE_SUGGESTIONS = 6
        private const val INLINE_SUGGESTION_MIN_WIDTH_DP = 40
        private const val INLINE_SUGGESTION_HEIGHT_DP = 44
        // Settings icon (48dp) + mic icon (48dp) + icon spacing (16dp) + horizontal padding (8dp).
        private const val INLINE_TOOLBAR_RESERVED_WIDTH_DP = 120
        private const val INLINE_CHIP_SPACING_DP = 6
    }

    private val _autofillSuggestionView = MutableStateFlow<View?>(null)
    override val autofillSuggestionView = _autofillSuggestionView.asStateFlow()
    private val clipboard = ClipboardSession()
    override val clipboardSuggestion = clipboard.suggestion

    private var inputSessionActive = false
    private var autofillGeneration = 0L
    private var clipboardManager: ClipboardManager? = null
    private var clipboardGeneration = 0L
    private var lastObservedFingerprint: ClipboardFingerprint? = null
    private var observingClipboard = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        refreshClipboardSuggestion(forceGeneration = true)
    }

    override fun onInputStarted() {
        // Do not clear in-flight or newly inflated autofill suggestions here:
        // Android's AutofillManager can invoke onCreateInlineSuggestionsRequest
        // slightly before onStartInput, or between onStartInput and onStartInputView,
        // and web/Compose editors frequently call onStartInput(restarting = true).
        // Leaving the previous field already ran onFinishInput -> onInputFinished().
        inputSessionActive = true
        clipboard.start()
        startClipboardObservation()
        refreshClipboardSuggestion()
    }

    override fun onInputFinished() {
        inputSessionActive = false
        clipboard.finish()
        clearAutofill()
        stopClipboardObservation()
    }

    override fun onUserTyped() {
        clipboard.onUserTyped()
        clearAutofill()
    }

    override fun onSelectionChanged() {
        // Initial focus in EditText/WebView/Compose reports selection (-1 -> cursor)
        // immediately after autofill suggestions arrive; do not wipe autofill here.
    }

    @SuppressLint("RestrictedApi")
    override fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        inputSessionActive = true
        clearAutofill()
        val minWidth = dpToPx(INLINE_SUGGESTION_MIN_WIDTH_DP)
        val height = dpToPx(INLINE_SUGGESTION_HEIGHT_DP)
        val availableSurfaceWidth = service.maxWidth.takeIf { it > 0 }
            ?: service.resources.displayMetrics.widthPixels
        val maxWidth = (availableSurfaceWidth - dpToPx(INLINE_TOOLBAR_RESERVED_WIDTH_DP))
            .coerceAtLeast(minWidth)
        val stylesBundle = createInlineSuggestionStylesBundle()
        val spec = InlinePresentationSpec.Builder(
            Size(minWidth, height),
            Size(maxWidth, height),
        ).setStyle(stylesBundle).build()
        val requestExtras = Bundle(uiExtras).apply { putAll(stylesBundle) }
        return InlineSuggestionsRequest.Builder(List(MAX_INLINE_SUGGESTIONS) { spec })
            .setMaxSuggestionCount(MAX_INLINE_SUGGESTIONS)
            .setExtras(requestExtras)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions.take(MAX_INLINE_SUGGESTIONS)
        if (suggestions.isEmpty()) {
            clearAutofill()
            return true
        }
        inputSessionActive = true
        val generation = ++autofillGeneration
        val count = suggestions.size
        val inflatedViews = arrayOfNulls<InlineContentView>(count)
        val pinnedFlags = BooleanArray(count) { index -> suggestions[index].info.isPinned }
        var remaining = count
        val wrapSize = Size(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        suggestions.forEachIndexed { index, suggestion ->
            inflate(suggestion, wrapSize) { view ->
                if (generation != autofillGeneration || !inputSessionActive) return@inflate
                if (view != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    val isPinned = pinnedFlags[index]
                    view.setOnClickListener {
                        onAutofillSuggestionClicked(isPinned)
                    }
                    inflatedViews[index] = view
                }
                remaining--
                if (remaining == 0) {
                    val chips = inflatedViews.mapIndexedNotNull { i, chipView ->
                        chipView?.let { InflatedAutofillChip(it, pinnedFlags[i]) }
                    }
                    _autofillSuggestionView.value = if (chips.isEmpty()) {
                        null
                    } else {
                        buildInlineAutofillStripView(
                            context = service,
                            chips = chips,
                            chipSpacingPx = dpToPx(INLINE_CHIP_SPACING_DP),
                        )
                    }
                }
            }
        }
        return true
    }

    override fun onAutofillTapped() = onAutofillSuggestionClicked(isPinned = false)

    internal fun onAutofillSuggestionClicked(isPinned: Boolean) {
        acceptHandwriting()
        if (!isPinned) {
            clearAutofill()
        }
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
        if (clip == null || description == null) {
            refreshClipboardSuggestion()
            return
        }
        if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            val text = (0 until clip.itemCount)
                .asSequence()
                .mapNotNull { clip.getItemAt(it).text?.toString() }
                .firstOrNull(String::isNotEmpty)
            clearAutofill()
            if (text != null) editor.commitText(text)
            refreshClipboardSuggestion()
            return
        }
        val hasImage = (0 until description.mimeTypeCount).any {
            description.getMimeType(it).startsWith("image/")
        }
        if (hasImage) {
            val uri = (0 until clip.itemCount)
                .asSequence()
                .mapNotNull { clip.getItemAt(it).uri }
                .firstOrNull()
            if (uri != null) {
                clearAutofill()
                editor.commitContent(uri, description)
            }
            refreshClipboardSuggestion()
            return
        }
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
                val item = clip.getItemAt(it)
                ClipboardItemSnapshot(
                    mimeTypes = mimeTypes,
                    text = if (sensitive) null else item.text?.toString(),
                    uri = if (sensitive) null else item.uri?.toString(),
                )
            }
        }
        clipboard.update(ClipboardSnapshot(clipboardGeneration, items, sensitive))
    }

    @SuppressLint("RestrictedApi")
    private fun createInlineSuggestionStylesBundle(): Bundle {
        val darkTheme =
            (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val chipDrawableRes = if (darkTheme) {
            R.drawable.bg_inline_autofill_chip_dark
        } else {
            R.drawable.bg_inline_autofill_chip_light
        }
        val titleColor = if (darkTheme) Color.WHITE else Color.parseColor("#FF202820")
        val subtitleColor = if (darkTheme) {
            Color.parseColor("#FF8E8E9F")
        } else {
            Color.parseColor("#FF5A5A6A")
        }
        val chipBackground = Icon.createWithResource(service, chipDrawableRes)
        val iconSizePx = dpToPx(20)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBackground)
                    .setPadding(dpToPx(10), 0, dpToPx(10), 0)
                    .setLayoutMargin(0, 0, 0, 0)
                    .build(),
            )
            .setSingleIconChipIconStyle(
                ImageViewStyle.Builder()
                    .setMaxWidth(iconSizePx)
                    .setMaxHeight(iconSizePx)
                    .build(),
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBackground)
                    .setPadding(dpToPx(12), 0, dpToPx(12), 0)
                    .setLayoutMargin(0, 0, 0, 0)
                    .build(),
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setLayoutMargin(0, 0, dpToPx(4), 0)
                    .setMaxWidth(iconSizePx)
                    .setMaxHeight(iconSizePx)
                    .build(),
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(titleColor)
                    .setTextSize(14f)
                    .setLayoutMargin(dpToPx(2), 0, dpToPx(2), 0)
                    .build(),
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(subtitleColor)
                    .setTextSize(12f)
                    .setLayoutMargin(dpToPx(2), 0, dpToPx(2), 0)
                    .build(),
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setLayoutMargin(dpToPx(4), 0, 0, 0)
                    .setMaxWidth(iconSizePx)
                    .setMaxHeight(iconSizePx)
                    .build(),
            )
            .build()
        return UiVersions.newStylesBuilder().addStyle(style).build()
    }

    private fun inflate(
        suggestion: InlineSuggestion,
        size: Size,
        onInflated: (InlineContentView?) -> Unit,
    ) {
        try {
            suggestion.inflate(service, size, service.mainExecutor) { view ->
                onInflated(view)
            }
        } catch (error: Throwable) {
            android.util.Log.w("CantoneseIME", "Unable to inflate inline suggestion", error)
            onInflated(null)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * service.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private data class ClipboardFingerprint(val timestamp: Long, val itemCount: Int)
}

internal data class InflatedAutofillChip(
    val view: View,
    val isPinned: Boolean,
)

internal data class InlineChipClip(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Computes the local clip rect for an [InlineContentView] inside a horizontally scrolling strip
 * so its z-ordered remote surface never bleeds outside the scroll viewport over toolbar icons.
 * Returns `null` when the entire chip fits inside `[scrollX, scrollX + viewportWidth]`.
 */
internal fun computeInlineChipClipBounds(
    scrollX: Int,
    viewportWidth: Int,
    chipLeft: Int,
    chipWidth: Int,
    chipHeight: Int,
): InlineChipClip? {
    if (viewportWidth <= 0 || chipWidth <= 0 || chipHeight <= 0) return null
    val visibleLeft = (scrollX - chipLeft).coerceIn(0, chipWidth)
    val visibleRight = (scrollX + viewportWidth - chipLeft).coerceIn(0, chipWidth)
    if (visibleLeft == 0 && visibleRight == chipWidth) return null
    return InlineChipClip(visibleLeft, 0, visibleRight, chipHeight)
}

internal fun buildInlineAutofillStripView(
    context: Context,
    chips: List<InflatedAutofillChip>,
    chipSpacingPx: Int,
): View {
    val scrollableChips = chips.filterNot { it.isPinned }
    val pinnedChips = chips.filter { it.isPinned }
    val outer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = if (scrollableChips.isEmpty()) Gravity.CENTER else Gravity.CENTER_VERTICAL
    }
    if (scrollableChips.isNotEmpty()) {
        val scrollRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        scrollableChips.forEachIndexed { index, chip ->
            (chip.view.parent as? ViewGroup)?.removeView(chip.view)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index < scrollableChips.lastIndex) {
                    marginEnd = chipSpacingPx
                }
            }
            scrollRow.addView(chip.view, lp)
        }
        val scrollView = object : HorizontalScrollView(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                updateInlineChipClips()
            }

            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                updateInlineChipClips()
            }

            private fun updateInlineChipClips() {
                val viewportWidth = width
                for (i in 0 until scrollRow.childCount) {
                    val child = scrollRow.getChildAt(i)
                    child.clipBounds = computeInlineChipClipBounds(
                        scrollX = scrollX,
                        viewportWidth = viewportWidth,
                        chipLeft = scrollRow.left + child.left,
                        chipWidth = child.width,
                        chipHeight = child.height,
                    )?.let { Rect(it.left, it.top, it.right, it.bottom) }
                }
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(
                scrollRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        outer.addView(
            scrollView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
    }
    pinnedChips.forEachIndexed { index, chip ->
        (chip.view.parent as? ViewGroup)?.removeView(chip.view)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (scrollableChips.isNotEmpty() || index > 0) {
                marginStart = chipSpacingPx
            }
        }
        outer.addView(chip.view, lp)
    }
    return outer
}
