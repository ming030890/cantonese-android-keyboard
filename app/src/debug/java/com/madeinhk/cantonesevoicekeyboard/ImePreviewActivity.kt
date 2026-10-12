package com.madeinhk.cantonesevoicekeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Debug-only host for checking the real IME against system navigation. */
class ImePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor = EditText(this).apply { hint = "Keyboard inset test" }
        setContentView(editor)
        editor.requestFocus()
        editor.postDelayed({
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }, 500)
    }
}
