package com.madeinhk.cantonesevoicekeyboard

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorActionResolverTest {
    @Test
    fun searchActionTakesPrecedenceOverNoEnterActionFlag() {
        val imeOptions =
            EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION

        assertEquals(EditorInfo.IME_ACTION_SEARCH, standardImeAction(imeOptions))
    }

    @Test
    fun noEnterActionFlagStillSuppressesNonSearchActions() {
        val imeOptions =
            EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION

        assertEquals(EditorInfo.IME_ACTION_NONE, standardImeAction(imeOptions))
    }

    @Test
    fun ordinarySearchActionIsPreserved() {
        assertEquals(
            EditorInfo.IME_ACTION_SEARCH,
            standardImeAction(EditorInfo.IME_ACTION_SEARCH),
        )
    }
}
