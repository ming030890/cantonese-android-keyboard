package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.view.inputmethod.InputMethodManager
import java.util.Locale

/**
 * Selects Quick only when an existing enabled input method identifies Quick.
 * Otherwise starts with handwriting; English is always opt-in.
 */
object KeyboardLayoutInference {

    data class SubtypeInfo(
        val locale: String = "",
        val languageTag: String = "",
        val mode: String = "",
        val extraValue: String = "",
        val label: String = "",
    )

    data class ImeInfo(
        val packageName: String = "",
        val id: String = "",
        val subtypes: List<SubtypeInfo> = emptyList(),
        val label: String = "",
    )

    private fun identifiesQuick(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        return normalized.contains("quick") || normalized.contains("sucheng") ||
            normalized.contains("速成") || normalized.contains("简易仓颉") ||
            normalized.contains("簡易倉頡")
    }

    fun inferLayouts(existingImes: List<ImeInfo>): Set<String> {
        val hasQuick = existingImes.any { ime ->
            identifiesQuick(ime.label) || identifiesQuick(ime.packageName) ||
                identifiesQuick(ime.id) || ime.subtypes.any { subtype ->
                    identifiesQuick(subtype.extraValue) || identifiesQuick(subtype.mode) ||
                        identifiesQuick(subtype.label)
                }
        }
        return if (hasQuick) setOf(KeyboardSettings.LAYOUT_QUICK)
        else KeyboardSettings.DEFAULT_LAYOUTS
    }

    /** Reads enabled third-party keyboards; locale alone never implies Quick. */
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
                label = try { imi.loadLabel(context.packageManager).toString() }
                    catch (_: Exception) { "" },
                subtypes = subtypes.map { sub ->
                    @Suppress("DEPRECATION")
                    val loc = sub.locale
                    val tag = sub.languageTag
                    SubtypeInfo(
                        locale = loc,
                        languageTag = tag,
                        mode = sub.mode ?: "",
                        extraValue = sub.extraValue ?: "",
                        label = try {
                            sub.getDisplayName(context, imi.packageName, imi.serviceInfo.applicationInfo).toString()
                        } catch (_: Exception) { "" },
                    )
                }
            )
        }

        return inferLayouts(imeInfos)
    }
}
