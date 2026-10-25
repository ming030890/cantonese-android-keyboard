package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import java.util.Locale

/**
 * Infers default keyboard layouts by inspecting existing enabled input methods
 * and system locales on the user's device.
 */
object KeyboardLayoutInference {

    data class SubtypeInfo(
        val locale: String = "",
        val languageTag: String = "",
        val mode: String = "",
        val extraValue: String = "",
    )

    data class ImeInfo(
        val packageName: String = "",
        val id: String = "",
        val subtypes: List<SubtypeInfo> = emptyList(),
    )

    /**
     * Inspects existing IMEs and system locales to infer an optimal default layout set.
     */
    fun inferLayouts(
        existingImes: List<ImeInfo>,
        systemLocales: List<Locale>,
    ): Set<String> {
        val inferred = mutableSetOf<String>()

        var hasQuickOrCangjie = false
        var hasHandwriting = false
        var hasCantoneseOrHk = false
        var hasEnglish = false

        for (ime in existingImes) {
            for (sub in ime.subtypes) {
                val extra = sub.extraValue.lowercase(Locale.ROOT)
                val mode = sub.mode.lowercase(Locale.ROOT)
                val loc = sub.locale.lowercase(Locale.ROOT)
                val tag = sub.languageTag.lowercase(Locale.ROOT)

                // Quick / Cangjie patterns (e.g. Gboard, Samsung keyboard)
                if (extra.contains("quick") || extra.contains("cangjie") || extra.contains("sucheng") ||
                    extra.contains("changjei") || tag.contains("yue") || tag.contains("zh-hk") ||
                    loc.contains("zh_hk") || loc.contains("yue")
                ) {
                    hasCantoneseOrHk = true
                }

                if (extra.contains("quick") || extra.contains("cangjie") || extra.contains("sucheng") ||
                    extra.contains("changjei")
                ) {
                    hasQuickOrCangjie = true
                }

                if (mode.contains("handwriting") || extra.contains("handwriting")) {
                    hasHandwriting = true
                }

                if (loc.startsWith("en") || tag.startsWith("en") || mode == "keyboard") {
                    hasEnglish = true
                }
            }
        }

        val isHkOrYueSystem = systemLocales.any { locale ->
            locale.country.equals("HK", ignoreCase = true) ||
            locale.language.equals("yue", ignoreCase = true) ||
            locale.toLanguageTag().contains("HK", ignoreCase = true) ||
            locale.toLanguageTag().contains("yue", ignoreCase = true)
        }

        // Quick (with English) is our primary Cantonese layout
        if (hasQuickOrCangjie || isHkOrYueSystem || hasCantoneseOrHk) {
            inferred.add(KeyboardSettings.LAYOUT_QUICK)
        }

        // Handwriting layout
        if (hasHandwriting) {
            inferred.add(KeyboardSettings.LAYOUT_HANDWRITING)
        }

        // English layout
        if (hasEnglish || isHkOrYueSystem || inferred.isEmpty() ||
            systemLocales.any { it.language.equals("en", ignoreCase = true) }
        ) {
            inferred.add(KeyboardSettings.LAYOUT_ENGLISH)
        }

        // Default fallback: Quick + English
        if (inferred.isEmpty()) {
            inferred.add(KeyboardSettings.LAYOUT_QUICK)
            inferred.add(KeyboardSettings.LAYOUT_ENGLISH)
        }

        return inferred
    }

    /**
     * Reads active system IMEs and locales from Android context to infer layouts.
     */
    fun inferFromSystem(context: Context): Set<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return KeyboardSettings.DEFAULT_LAYOUTS

        val enabledImes = try {
            imm.enabledInputMethodList.filter { it.packageName != context.packageName }
        } catch (e: Exception) {
            emptyList()
        }

        val imeInfos = enabledImes.map { imi ->
            val subtypes = try {
                imm.getEnabledInputMethodSubtypeList(imi, true)
            } catch (e: Exception) {
                emptyList()
            }
            ImeInfo(
                packageName = imi.packageName,
                id = imi.id,
                subtypes = subtypes.map { sub ->
                    @Suppress("DEPRECATION")
                    val loc = sub.locale
                    val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) sub.languageTag else ""
                    SubtypeInfo(
                        locale = loc,
                        languageTag = tag,
                        mode = sub.mode ?: "",
                        extraValue = sub.extraValue ?: "",
                    )
                }
            )
        }

        val systemLocales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = context.resources.configuration.locales
            (0 until list.size()).map { list.get(it) }
        } else {
            @Suppress("DEPRECATION")
            listOf(context.resources.configuration.locale)
        }

        return inferLayouts(imeInfos, systemLocales)
    }
}
