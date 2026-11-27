package org.futo.inputmethod.latin.spellcheck

import android.content.Context
import android.util.Log
import org.futo.inputmethod.latin.ActiveSubtype
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.MultilingualBucketSetting
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.matchesLocale
import org.futo.inputmethod.latin.stripExtensionsIfNeeded
import org.futo.inputmethod.latin.uix.getSettingBlocking
import java.util.Locale

/**
 * Manages language resolution for the FUTO SpellCheckerService.
 *
 * Responsibilities:
 * Maps the Android OS spellchecker session locale to FUTO's active keyboard language
 * configuration (ActiveSubtype, SubtypesSetting, and MultilingualBucketSetting).
 *
 * Reuses FUTO's existing DataStore settings as the single source of truth without
 * creating duplicate language configurations or state.
 */
object SpellCheckerLanguageManager {
    private const val TAG = "FUTO_SPELLCHECKER"

    /**
     * Normalizes a Locale object into a canonical Locale without extensions.
     */
    fun normalizeLocale(locale: Locale): Locale = locale.stripExtensionsIfNeeded()

    fun normalizeLocale(localeStr: String): Locale {
        if (localeStr.isBlank()) return Locale.ENGLISH
        return localeFromString(localeStr).stripExtensionsIfNeeded()
    }

    /**
     * Resolves the full list of active locales for a spellchecking session.
     * Reuses FUTO's DataStore settings to determine the primary active language
     * and any secondary languages configured in FUTO's multilingual language bucket.
     *
     * @param context The application context.
     * @param sessionLocaleStr The locale string requested by Android OS for this session.
     * @return A list of Locales to be checked against FUTO's DictionaryFacilitator.
     */
    fun resolveLocales(context: Context, sessionLocaleStr: String?): List<Locale> {
        val sessionLocale = sessionLocaleStr?.takeIf { it.isNotBlank() }?.let { normalizeLocale(it) }

        val activeSubtypeStr = context.getSettingBlocking(ActiveSubtype)
        val activeSubtype = Subtypes.convertToSubtype(activeSubtypeStr)
        val activeLocale = Subtypes.getLocale(activeSubtype)

        // Get enabled subtypes from settings
        val enabledSubtypeStrings = context.getSettingBlocking(SubtypesSetting)
        val enabledLocales = enabledSubtypeStrings.map { Subtypes.getLocale(Subtypes.convertToSubtype(it)) }

        // Get raw multilingual bucket setting
        val rawBucketStrings = context.getSettingBlocking(MultilingualBucketSetting)
        val bucketLocales = rawBucketStrings.map { normalizeLocale(Subtypes.getLocale(it)) }

        // Determine primary locale and matching strategy
        val primaryLocale = sessionLocale ?: activeLocale

        // Match primaryLocale against enabledLocales or bucketLocales or fallback to activeLocale
        val matchedEnabledLocale = enabledLocales.find { matchesLocale(primaryLocale, it) }
            ?: bucketLocales.find { matchesLocale(primaryLocale, it) }
            ?: activeLocale

        // Get multilingual bucket locales for the matched primary locale
        val resolvedBucket = Subtypes.getMultilingualBucket(context, matchedEnabledLocale)
            .map { normalizeLocale(it) }

        val resultList = mutableListOf<Locale>()
        resultList.add(normalizeLocale(matchedEnabledLocale))

        // Append bucket locales deterministically
        val sortedBucket = resolvedBucket
            .filter { !matchesLocale(it, matchedEnabledLocale) }
            .distinctBy { it.toLanguageTag() }
            .sortedBy { it.toLanguageTag() }

        resultList.addAll(sortedBucket)
        val resolvedList = resultList.distinctBy { it.toLanguageTag() }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[SpellCheckerLanguageManager] resolveLocales:" +
                    "\n  sessionLocaleStr: $sessionLocaleStr" +
                    "\n  rawActiveSubtype: $activeSubtypeStr" +
                    "\n  parsedActiveLocale: $activeLocale" +
                    "\n  rawSubtypesSetting: $enabledSubtypeStrings" +
                    "\n  parsedEnabledLocales: $enabledLocales" +
                    "\n  rawMultilingualBucketSetting: $rawBucketStrings" +
                    "\n  parsedBucketLocales: $bucketLocales" +
                    "\n  primaryLocale: $primaryLocale" +
                    "\n  matchedEnabledLocale: $matchedEnabledLocale" +
                    "\n  finalResolvedLocales: $resolvedList")
        }

        return resolvedList
    }
}
