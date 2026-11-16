package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.text.InputType
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import org.json.JSONArray

internal enum class SpecialFieldKind(val storageKey: String) {
    Email("email"),
    Phone("phone"),
    PersonName("person_name"),
    PostalAddress("postal_address"),
}

internal interface SpecialFieldStore {
    fun values(kind: SpecialFieldKind): List<String>
    fun record(kind: SpecialFieldKind, rawValue: String)
}

internal class InMemorySpecialFieldStore : SpecialFieldStore {
    private val map = mutableMapOf<SpecialFieldKind, List<String>>()

    override fun values(kind: SpecialFieldKind): List<String> = map[kind].orEmpty()

    override fun record(kind: SpecialFieldKind, rawValue: String) {
        val updated = mergeSpecialFieldValue(kind, map[kind].orEmpty(), rawValue) ?: return
        map[kind] = updated
    }
}

internal class SharedPreferencesSpecialFieldStore(
    context: Context,
) : SpecialFieldStore {
    companion object {
        private const val PREFS_NAME = "special_field_values"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun values(kind: SpecialFieldKind): List<String> {
        val raw = prefs.getString(kind.storageKey, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optString(i, "")
                    if (item.isNotEmpty()) add(item)
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun record(kind: SpecialFieldKind, rawValue: String) {
        val updated = mergeSpecialFieldValue(kind, values(kind), rawValue) ?: return
        val array = JSONArray()
        updated.forEach(array::put)
        prefs.edit().putString(kind.storageKey, array.toString()).apply()
    }
}

private const val MAX_STORED_SPECIAL_FIELD_VALUES = 5

private val COMMON_EMAIL_DOMAINS = listOf(
    "gmail.com",
    "hotmail.com",
    "outlook.com",
    "icloud.com",
    "yahoo.com.hk",
    "yahoo.com",
)

internal fun specialFieldKindFor(inputType: Int?): SpecialFieldKind? {
    inputType ?: return null
    val cls = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (cls) {
        InputType.TYPE_CLASS_PHONE -> SpecialFieldKind.Phone
        InputType.TYPE_CLASS_TEXT -> when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> SpecialFieldKind.Email
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> SpecialFieldKind.PersonName
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> SpecialFieldKind.PostalAddress
            else -> null
        }
        else -> null
    }
}

internal fun normalizeValidSpecialFieldValue(
    kind: SpecialFieldKind,
    rawValue: String,
): String? {
    val trimmed = rawValue.trim()
    if (trimmed.isEmpty()) return null
    return when (kind) {
        SpecialFieldKind.Email -> {
            if (trimmed.length !in 5..128 || trimmed.any(Char::isWhitespace)) return null
            val atIndex = trimmed.indexOf('@')
            if (atIndex <= 0 || atIndex != trimmed.lastIndexOf('@')) return null
            val domain = trimmed.substring(atIndex + 1)
            if ('.' !in domain || domain.startsWith('.') || domain.endsWith('.')) return null
            if (domain.substringAfterLast('.').length < 2) return null
            trimmed
        }
        SpecialFieldKind.Phone -> {
            if (trimmed.length !in 7..24) return null
            val digits = trimmed.count { it in '0'..'9' }
            if (digits < 7) return null
            if (trimmed.any { it !in '0'..'9' && it !in "+- ().#" }) return null
            trimmed
        }
        SpecialFieldKind.PersonName -> {
            if (trimmed.length !in 2..80 || '\n' in trimmed || '\r' in trimmed) return null
            if (trimmed.none { it.isLetter() }) return null
            trimmed
        }
        SpecialFieldKind.PostalAddress -> {
            val singleLine = trimmed.replace(Regex("\\s+"), " ")
            if (singleLine.length !in 4..160) return null
            if (singleLine.count { it.isLetter() } < 2) return null
            singleLine
        }
    }
}

internal fun mergeSpecialFieldValue(
    kind: SpecialFieldKind,
    existing: List<String>,
    rawValue: String,
): List<String>? {
    val normalized = normalizeValidSpecialFieldValue(kind, rawValue) ?: return null
    val deduplicated = existing.filterNot { item ->
        when (kind) {
            SpecialFieldKind.Email -> item.equals(normalized, ignoreCase = true)
            SpecialFieldKind.Phone -> phoneDigits(item) == phoneDigits(normalized)
            SpecialFieldKind.PersonName,
            SpecialFieldKind.PostalAddress,
            -> item == normalized
        }
    }
    return (listOf(normalized) + deduplicated).take(MAX_STORED_SPECIAL_FIELD_VALUES)
}

private fun phoneDigits(value: String): String =
    value.filter { it in '0'..'9' || it == '+' }

internal fun specialFieldCandidates(
    kind: SpecialFieldKind,
    currentText: String,
    storedValues: List<String>,
): List<HandwritingCandidate> {
    val query = currentText.trim()
    val results = mutableListOf<String>()

    storedValues.forEach { stored ->
        val matches = query.isEmpty() ||
            (stored.startsWith(query, ignoreCase = true) && stored != query)
        if (matches) {
            results += stored
        }
    }

    if (kind == SpecialFieldKind.Email && '@' in query && !query.any(Char::isWhitespace)) {
        val local = query.substringBefore('@')
        val typedDomain = query.substringAfter('@')
        if (local.isNotEmpty() && '@' !in typedDomain) {
            COMMON_EMAIL_DOMAINS.forEach { domain ->
                if (domain.startsWith(typedDomain, ignoreCase = true) &&
                    !domain.equals(typedDomain, ignoreCase = true)
                ) {
                    val completion = "$local@$domain"
                    if (results.none { it.equals(completion, ignoreCase = true) }) {
                        results += completion
                    }
                }
            }
        }
    }

    return results.mapIndexed { index, text -> HandwritingCandidate(text, index) }
}
