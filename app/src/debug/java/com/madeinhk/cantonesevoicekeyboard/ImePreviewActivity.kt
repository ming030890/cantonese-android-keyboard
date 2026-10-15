package com.madeinhk.cantonesevoicekeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Debug-only host for checking the real IME against system navigation. */
class ImePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val showIme=intent.getBooleanExtra("showIme",true)
        // Instrumentation can exercise a real InputConnection without a second
        // IME concurrently finishing or replacing its composing spans.
        if(!showIme) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val editor = EditText(this).apply { hint = "Keyboard inset test" }
        setContentView(editor)
        editor.requestFocus()
        if(showIme) editor.postDelayed({
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }, 500)
    }
}
