package org.futo.inputmethod.latin.spellcheck

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.ActiveSubtype
import org.futo.inputmethod.latin.MultilingualBucketSetting
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.stripExtensionsIfNeeded
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.getSettingFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

object SpellCheckerLanguageManager {
    private const val TAG = "SpellCheckerLangManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cachedLocales = AtomicReference<List<Locale>>(emptyList())
    private var isInitialized = false

    /**
     * Normalizes a Locale object or locale string into a canonical Locale without extensions.
     */
    fun normalizeLocale(locale: Locale): Locale = locale.stripExtensionsIfNeeded()

    fun normalizeLocale(localeStr: String): Locale {
        if (localeStr.isBlank()) return Locale.ENGLISH
        return localeFromString(localeStr).stripExtensionsIfNeeded()
    }

    /**
     * Checks if two Locales match, accounting for language-only, region, and script specificities.
     */
    fun matches(l1: Locale, l2: Locale): Boolean {
        if (l1 == l2) return true
        if (l1.language.equalsIgnoreCase(l2.language)) {
            if (l1.country.isEmpty() || l2.country.isEmpty() || l1.country.equalsIgnoreCase(l2.country)) {
                if (l1.script.isEmpty() || l2.script.isEmpty() || l1.script.equalsIgnoreCase(l2.script)) {
                    return true
                }
            }
        }
        return false
    }

    private fun String.equalsIgnoreCase(other: String): Boolean =
        this.equals(other, ignoreCase = true)

    /**
     * Initializes setting observers to keep the active locale list updated.
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            isInitialized = true
        }

        updateCache(context)

        scope.launch {
            try {
                combine(
                    context.getSettingFlow(SubtypesSetting),
                    context.getSettingFlow(ActiveSubtype),
                    context.getSettingFlow(MultilingualBucketSetting)
                ) { _, _, _ ->
                    updateCache(context)
                }.collect {}
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting settings flow", e)
            }
        }
    }

    /**
     * Resolves the full list of active locales for spellchecking.
     * Guaranteed to return a deterministic, deduplicated list.
     */
    fun resolveLocales(context: Context, sessionLocaleStr: String?): List<Locale> {
        val sessionLocale = sessionLocaleStr?.takeIf { it.isNotBlank() }?.let { normalizeLocale(it) }

        val activeSubtypeStr = context.getSettingBlocking(ActiveSubtype)
        val activeSubtype = Subtypes.convertToSubtype(activeSubtypeStr)
        val activeLocale = Subtypes.getLocale(activeSubtype)

        val primaryLocale = sessionLocale ?: activeLocale

        // Get enabled subtypes from settings
        val enabledSubtypeStrings = context.getSettingBlocking(SubtypesSetting)
        val enabledLocales = enabledSubtypeStrings.map { Subtypes.getLocale(Subtypes.convertToSubtype(it)) }

        // Match primaryLocale against enabledLocales or multilingual bucket
        val matchedEnabledLocale = enabledLocales.find { matches(primaryLocale, it) } ?: primaryLocale

        // Get multilingual bucket locales for the matched primary locale
        val bucketLocales = Subtypes.getMultilingualBucket(context, matchedEnabledLocale)
            .map { normalizeLocale(it) }

        val resultList = mutableListOf<Locale>()
        resultList.add(normalizeLocale(matchedEnabledLocale))

        // Append bucket locales deterministically
        val sortedBucket = bucketLocales
            .filter { !matches(it, matchedEnabledLocale) }
            .distinctBy { it.toLanguageTag() }
            .sortedBy { it.toLanguageTag() }

        resultList.addAll(sortedBucket)
        return resultList.distinctBy { it.toLanguageTag() }
    }

    private fun updateCache(context: Context) {
        val newLocales = resolveLocales(context, null)
        val oldLocales = cachedLocales.getAndSet(newLocales)
        if (oldLocales != newLocales) {
            Log.i(TAG, "Active spellchecker locales updated: $oldLocales -> $newLocales")
        }
    }

    fun getCachedLocales(context: Context): List<Locale> {
        init(context)
        val list = cachedLocales.get()
        return if (list.isNotEmpty()) list else resolveLocales(context, null)
    }
}
