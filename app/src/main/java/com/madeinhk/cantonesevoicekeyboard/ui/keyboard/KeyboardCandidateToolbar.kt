package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme

internal data class TextToolbarKeys(
    val settings: KeyModel,
    val voice: KeyModel,
    val clipboard: KeyModel,
    val candidatePrevious: KeyModel,
    val candidateNext: KeyModel,
    val candidates: List<KeyModel>,
)

/**
 * Creates stable visual models only. A toolbar action becomes clickable only when its
 * [KeyVisual] is composed and mounts it in [KeyboardKeyRegistry].
 */
internal fun registerTextToolbarKeys(
    keyRegistry: KeyboardKeyRegistry,
    candidates: List<HandwritingCandidate> = emptyList(),
): TextToolbarKeys =
    TextToolbarKeys(
        settings = keyRegistry.key(
            id = "toolbar.settings",
            data = KeyData("toolbar.settings", KeyboardKeyKind.Settings, text = "Settings"),
            rowId = "toolbar",
        ),
        voice = keyRegistry.key(
            id = "toolbar.voice",
            data = KeyData("toolbar.voice", KeyboardKeyKind.Record, text = "Voice input"),
            rowId = "toolbar",
        ),
        clipboard = keyRegistry.key(
            id = "toolbar.clipboard",
            data = KeyData("toolbar.clipboard", KeyboardKeyKind.Clipboard, text = "Paste"),
            rowId = "toolbar",
        ),
        candidatePrevious = keyRegistry.key(
            id = "toolbar.candidate.previous",
            data = KeyData(
                "toolbar.candidate.previous",
                KeyboardKeyKind.CandidatePrevious,
                text = "Previous candidates",
            ),
            rowId = "toolbar",
        ),
        candidateNext = keyRegistry.key(
            id = "toolbar.candidate.next",
            data = KeyData(
                "toolbar.candidate.next",
                KeyboardKeyKind.CandidateNext,
                text = "Next candidates",
            ),
            rowId = "toolbar",
        ),
        candidates = candidates.mapIndexed { index, candidate ->
            keyRegistry.key(
                id = "toolbar.candidate.$index",
                data = KeyData(
                    keyId = "toolbar.candidate.$index",
                    kind = KeyboardKeyKind.Candidate,
                    text = candidate.text,
                ),
                rowId = "toolbar",
            )
        },
    )

@Composable
internal fun HandwritingCandidateStrip(
    candidates: List<HandwritingCandidate>,
    keys: TextToolbarKeys,
    keyRegistry: KeyboardKeyRegistry,
    page: Int,
    onPageChanged: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pages = handwritingCandidatePages(candidates.size, maxWidth)
        val safePage = page.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        val candidatePage = pages.getOrNull(safePage)
        val firstCandidate = candidatePage?.firstCandidate ?: 0
        val pageCapacity = candidatePage?.capacity ?: 1
        val pageCandidates = candidates.drop(firstCandidate).take(pageCapacity)
        val hasPrevious = candidatePage?.hasPrevious == true
        val hasNext = candidatePage?.hasNext == true

        LaunchedEffect(safePage, page) {
            if (safePage != page) onPageChanged(safePage)
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasPrevious) {
                CandidatePageArrow(
                    key = keys.candidatePrevious,
                    keyRegistry = keyRegistry,
                    pointsForward = false,
                    visible = true,
                    accessibilityDescription = stringResource(
                        R.string.keyboard_handwriting_candidates_previous,
                    ),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCapacity) { slot ->
                    val candidate = pageCandidates.getOrNull(slot)
                    if (slot > 0 && candidate != null) CandidateDivider()
                    if (candidate != null) {
                        ToolbarCandidateVisual(
                            key = keys.candidates[firstCandidate + slot],
                            keyRegistry = keyRegistry,
                            candidate = candidate,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
            CandidatePageArrow(
                key = keys.candidateNext,
                keyRegistry = keyRegistry,
                pointsForward = true,
                visible = hasNext,
                accessibilityDescription = stringResource(
                    R.string.keyboard_handwriting_candidates_next,
                ),
            )
        }
    }
}

internal data class HandwritingCandidatePage(
    val firstCandidate: Int,
    val capacity: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

internal fun handwritingCandidateCapacity(availableWidth: Dp, arrowCount: Int): Int =
    ((availableWidth.value -
        KeyboardUiDefaults.ToolbarIconTouchSize.value * arrowCount.coerceIn(0, 2)) / 62f)
        .toInt()
        .coerceAtLeast(1)

internal fun handwritingCandidatePages(
    candidateCount: Int,
    availableWidth: Dp,
): List<HandwritingCandidatePage> {
    if (candidateCount <= 0) return emptyList()

    val noArrowCapacity = handwritingCandidateCapacity(availableWidth, arrowCount = 0)
    if (candidateCount <= noArrowCapacity) {
        return listOf(
            HandwritingCandidatePage(
                firstCandidate = 0,
                capacity = noArrowCapacity,
                hasPrevious = false,
                hasNext = false,
            ),
        )
    }

    val oneArrowCapacity = handwritingCandidateCapacity(availableWidth, arrowCount = 1)
    val twoArrowCapacity = handwritingCandidateCapacity(availableWidth, arrowCount = 2)
    val pages = mutableListOf(
        HandwritingCandidatePage(
            firstCandidate = 0,
            capacity = oneArrowCapacity,
            hasPrevious = false,
            hasNext = true,
        ),
    )
    var firstCandidate = oneArrowCapacity

    while (firstCandidate < candidateCount) {
        val remaining = candidateCount - firstCandidate
        val isLastPage = remaining <= oneArrowCapacity
        val capacity = if (isLastPage) oneArrowCapacity else twoArrowCapacity
        pages += HandwritingCandidatePage(
            firstCandidate = firstCandidate,
            capacity = capacity,
            hasPrevious = true,
            hasNext = !isLastPage,
        )
        firstCandidate += capacity
    }

    return pages
}

@Composable
private fun CandidatePageArrow(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    pointsForward: Boolean,
    visible: Boolean,
    accessibilityDescription: String,
) {
    Box(
        modifier = Modifier.size(KeyboardUiDefaults.ToolbarIconTouchSize),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            ToolbarIconVisual(
                key = key,
                keyRegistry = keyRegistry,
                accessibilityDescription = accessibilityDescription,
            ) {
                CandidateChevronIcon(
                    pointsForward = pointsForward,
                    modifier = Modifier.size(18.dp),
                    color = BrandTheme.colors.keyboardKeyOnSurface,
                )
            }
        }
    }
}

@Composable
private fun CandidateDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(30.dp)
            .background(BrandTheme.colors.keyboardKeyOnSurface.copy(alpha = 0.28f)),
    )
}

@Composable
private fun ToolbarCandidateVisual(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    candidate: HandwritingCandidate,
    modifier: Modifier = Modifier,
) {
    KeyVisual(
        modifier = modifier.height(KeyboardUiDefaults.ToolbarHeight),
        key = key,
        keyRegistry = keyRegistry,
        horizontalInset = 0.dp,
        verticalInset = 0.dp,
        accessibilityDescription = candidate.text,
        backgroundColor = Color.Transparent,
        shape = RectangleShape,
        restingElevation = 0.dp,
        pressedScale = 0.96f,
    ) {
        Text(
            text = candidate.text,
            color = BrandTheme.colors.keyboardKeyOnSurface,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ToolbarIconVisual(
    modifier: Modifier = Modifier,
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    accessibilityDescription: String,
    content: @Composable BoxScope.() -> Unit,
) {
    KeyVisual(
        modifier = modifier
            .size(KeyboardUiDefaults.ToolbarIconTouchSize)
            .semantics { contentDescription = accessibilityDescription },
        key = key,
        keyRegistry = keyRegistry,
        horizontalInset = 0.dp,
        verticalInset = 0.dp,
        accessibilityDescription = accessibilityDescription,
        backgroundColor = Color.Transparent,
        shape = CircleShape,
        restingElevation = 0.dp,
        pressedScale = 0.9f,
        borderColor = Color.Transparent,
        content = content,
    )
}
