package org.futo.inputmethod.latin.spellcheck

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.ActiveSubtype
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.MultilingualBucketSetting
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.matchesLocale
import org.futo.inputmethod.latin.stripExtensionsIfNeeded
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.getSettingFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages language resolution for the FUTO SpellCheckerService.
 *
 * Responsibilities:
 * 1. Resolves the requested Android spellchecker session locale against FUTO's active
 *    subtype and multilingual bucket configuration stored in DataStore.
 * 2. Provides a thread-safe cached locale list that updates whenever user language
 *    settings change in FUTO Keyboard.
 */
object SpellCheckerLanguageManager {
    private const val TAG = "FUTO_SPELLCHECKER"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cachedLocales = AtomicReference<List<Locale>>(emptyList())
    private var isInitialized = false

    /**
     * Normalizes a Locale object into a canonical Locale without extensions.
     */
    fun normalizeLocale(locale: Locale): Locale = locale.stripExtensionsIfNeeded()

    fun normalizeLocale(localeStr: String): Locale {
        if (localeStr.isBlank()) return Locale.ENGLISH
        return localeFromString(localeStr).stripExtensionsIfNeeded()
    }

    /**
     * Initializes DataStore setting observers to keep the active locale list updated.
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
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Error collecting settings flow in SpellCheckerLanguageManager", e)
                }
            }
        }
    }

    /**
     * Resolves the full list of active locales for spellchecking.
     * Maps the session locale requested by Android OS to FUTO's configured
     * active subtype and multilingual language bucket.
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

    private fun updateCache(context: Context) {
        val newLocales = resolveLocales(context, null)
        val oldLocales = cachedLocales.getAndSet(newLocales)
        if (oldLocales != newLocales && BuildConfig.DEBUG) {
            Log.i(TAG, "[SpellCheckerLanguageManager] Active spellchecker locales updated: $oldLocales -> $newLocales")
        }
    }

    fun getCachedLocales(context: Context): List<Locale> {
        init(context)
        val list = cachedLocales.get()
        return if (list.isNotEmpty()) list else resolveLocales(context, null)
    }
}
