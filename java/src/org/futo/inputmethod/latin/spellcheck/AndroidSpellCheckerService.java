/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.latin.spellcheck;

import android.content.Intent;
import android.content.SharedPreferences;
import android.service.textservice.SpellCheckerService;
import android.util.Log;
import android.view.textservice.SuggestionsInfo;

import org.futo.inputmethod.keyboard.Keyboard;
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutElement;
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind;
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutPage;
import org.futo.inputmethod.latin.DictionaryFacilitator;
import org.futo.inputmethod.latin.DictionaryFacilitatorLruCache;
import org.futo.inputmethod.latin.NgramContext;
import org.futo.inputmethod.latin.R;
import org.futo.inputmethod.latin.SuggestedWords;
import org.futo.inputmethod.latin.common.ComposedData;
import org.futo.inputmethod.latin.define.DebugFlags;
import org.futo.inputmethod.latin.settings.SettingsValuesForSuggestion;
import org.futo.inputmethod.latin.uix.PreferenceUtils;
import org.futo.inputmethod.latin.utils.ScriptUtils;
import org.futo.inputmethod.latin.utils.SuggestionResults;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import javax.annotation.Nonnull;

/**
 * Service for spell checking, using LatinIME's dictionaries and mechanisms.
 */

/**
 * System-level Android {@link SpellCheckerService} implementation for FUTO Keyboard.
 *
 * Why this extends Android's SpellCheckerService:
 * Android's TextServicesManager framework binds to services declaring the action
 * `android.service.textservice.SpellCheckerService` in AndroidManifest.xml.
 * When the user selects FUTO as the system spellchecker in Android Settings, Android OS
 * manages binding, IPC dispatching, and session creation through this service.
 *
 * Reusing FUTO's DictionaryEngine:
 * Rather than creating a separate dictionary infrastructure, this service reuses FUTO's
 * {@link DictionaryFacilitatorLruCache} and {@link DictionaryFacilitator} instances.
 */
public final class AndroidSpellCheckerService extends SpellCheckerService
        implements SharedPreferences.OnSharedPreferenceChangeListener,
        SpellCheckerObserver.Listener {
    private static final String TAG = AndroidSpellCheckerService.class.getSimpleName();

    public static final String PREF_USE_CONTACTS_KEY = "pref_spellcheck_use_contacts";

    private static final int SPELLCHECKER_DUMMY_KEYBOARD_WIDTH = 480;
    private static final int SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT = 301;

    private static final String DICTIONARY_NAME_PREFIX = "spellcheck_";

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final int MAX_NUM_OF_THREADS_READ_DICTIONARY = 2;
    private final Semaphore mSemaphore = new Semaphore(MAX_NUM_OF_THREADS_READ_DICTIONARY,
            true /* fair */);
    // TODO: Make each spell checker session has its own session id.
    private final ConcurrentLinkedQueue<Integer> mSessionIdPool = new ConcurrentLinkedQueue<>();

    private final DictionaryFacilitatorLruCache mDictionaryFacilitatorCache =
            new DictionaryFacilitatorLruCache(this /* context */, DICTIONARY_NAME_PREFIX);
    private final ConcurrentHashMap<Locale, Keyboard> mKeyboardCache = new ConcurrentHashMap<>();
    private final Set<AndroidWordLevelSpellCheckerSession> mActiveSessions =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // The threshold for a suggestion to be considered "recommended".
    private float mRecommendedThreshold;
    // TODO: make a spell checker option to block offensive words or not
    private final SettingsValuesForSuggestion mSettingsValuesForSuggestion =
            new SettingsValuesForSuggestion(true /* blockPotentiallyOffensive */, false);

    public static final String SINGLE_QUOTE = "\u0027";
    public static final String APOSTROPHE = "\u2019";

    public AndroidSpellCheckerService() {
        super();
        for (int i = 0; i < MAX_NUM_OF_THREADS_READ_DICTIONARY; i++) {
            mSessionIdPool.add(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i("FUTO_SPELLCHECKER", "AndroidSpellCheckerService.onCreate()");
        }
        // Initialize FUTO Keyboard's LayoutManager so dummy spellchecker keyboards can be built.
        org.futo.inputmethod.v2keyboard.LayoutManager.INSTANCE.init(this);
        mRecommendedThreshold = Float.parseFloat(
                getString(R.string.spellchecker_recommended_threshold_value));
        final SharedPreferences prefs = PreferenceUtils.INSTANCE.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(prefs, PREF_USE_CONTACTS_KEY);

        SpellCheckerObserver.INSTANCE.observe(this, this);
    }

    public void registerSession(AndroidWordLevelSpellCheckerSession session) {
        mActiveSessions.add(session);
    }

    public void unregisterSession(AndroidWordLevelSpellCheckerSession session) {
        mActiveSessions.remove(session);
    }

    @Override
    public void onDictionaryResourceChanged() {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i("FUTO_SPELLCHECKER", "[AndroidSpellCheckerService] Dictionary resource changed event received. Reloading dictionaries and clearing caches.");
        }
        mSemaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        try {
            mDictionaryFacilitatorCache.reloadDictionaries();
        } finally {
            mSemaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        }
        mKeyboardCache.clear();
        for (AndroidWordLevelSpellCheckerSession session : mActiveSessions) {
            session.clearSuggestionsCache();
        }
    }

    @Override
    public void onLanguageConfigurationChanged() {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i("FUTO_SPELLCHECKER", "[AndroidSpellCheckerService] Language configuration changed event received. Updating active session locales.");
        }
        mDictionaryFacilitatorCache.invalidateLocales();
        mKeyboardCache.clear();
        for (AndroidWordLevelSpellCheckerSession session : mActiveSessions) {
            session.refreshLocales();
        }
    }

    public float getRecommendedThreshold() {
        return mRecommendedThreshold;
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (!PREF_USE_CONTACTS_KEY.equals(key)) return;
        final boolean useContactsDictionary = prefs.getBoolean(PREF_USE_CONTACTS_KEY, true);
        mDictionaryFacilitatorCache.setUseContactsDictionary(useContactsDictionary);
    }

    /**
     * Factory entry point invoked by Android OS when a client application (e.g. TextView / Editor)
     * requests a new spellchecker session.
     */
    @Override
    public Session createSession() {
        // Should not refer to AndroidSpellCheckerSession directly considering
        // that AndroidSpellCheckerSession may be overlaid.
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i("FUTO_SPELLCHECKER", "AndroidSpellCheckerService.createSession()");
        }
        return AndroidSpellCheckerSessionFactory.newInstance(this);
    }

    /**
     * Returns an empty SuggestionsInfo with flags signaling the word is not in the dictionary.
     * @param reportAsTypo whether this should include the flag LOOKS_LIKE_TYPO, for red underline.
     * @return the empty SuggestionsInfo with the appropriate flags set.
     */
    public static SuggestionsInfo getNotInDictEmptySuggestions(final boolean reportAsTypo) {
        return new SuggestionsInfo(reportAsTypo ? SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO : 0,
                EMPTY_STRING_ARRAY);
    }

    /**
     * Returns an empty SuggestionsInfo with flags signaling the word is in the dictionary.
     * @return the empty SuggestionsInfo with the appropriate flags set.
     */
    public static SuggestionsInfo getInDictEmptySuggestions() {
        return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
                EMPTY_STRING_ARRAY);
    }

    public boolean isValidWord(final Locale locale, final String word) {
        return isValidWord(Collections.singletonList(locale), word);
    }

    public boolean isValidWord(final java.util.List<Locale> locales, final String word) {
        mSemaphore.acquireUninterruptibly();
        try {
            DictionaryFacilitator dictionaryFacilitatorForLocale =
                    mDictionaryFacilitatorCache.get(locales);
            return dictionaryFacilitatorForLocale.isValidSpellingWord(word);
        } finally {
            mSemaphore.release();
        }
    }

    public SuggestionResults getSuggestionResults(final Locale locale,
            final ComposedData composedData, final NgramContext ngramContext,
            @Nonnull final Keyboard keyboard) {
        return getSuggestionResults(Collections.singletonList(locale), composedData, ngramContext, keyboard);
    }

    public SuggestionResults getSuggestionResults(final java.util.List<Locale> locales,
            final ComposedData composedData, final NgramContext ngramContext,
            @Nonnull final Keyboard keyboard) {
        Integer sessionId = null;
        mSemaphore.acquireUninterruptibly();
        try {
            sessionId = mSessionIdPool.poll();
            DictionaryFacilitator dictionaryFacilitatorForLocale =
                    mDictionaryFacilitatorCache.get(locales);
            return dictionaryFacilitatorForLocale.getSuggestionResults(composedData, ngramContext,
                    keyboard, mSettingsValuesForSuggestion,
                    sessionId, SuggestedWords.INPUT_STYLE_TYPING);
        } finally {
            if (sessionId != null) {
                mSessionIdPool.add(sessionId);
            }
            mSemaphore.release();
        }
    }

    public boolean hasMainDictionaryForLocale(final Locale locale) {
        return hasMainDictionaryForLocales(Collections.singletonList(locale));
    }

    public boolean hasMainDictionaryForLocales(final java.util.List<Locale> locales) {
        mSemaphore.acquireUninterruptibly();
        try {
            final DictionaryFacilitator dictionaryFacilitator =
                    mDictionaryFacilitatorCache.get(locales);
            return dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary() &&
                    !dictionaryFacilitator.hasAtLeastOneUninitializedMainDictionary();
        } finally {
            mSemaphore.release();
        }
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i("FUTO_SPELLCHECKER", "AndroidSpellCheckerService.onUnbind()");
        }
        mSemaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        try {
            mDictionaryFacilitatorCache.closeDictionaries();
        } finally {
            mSemaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        }
        mKeyboardCache.clear();
        return false;
    }

    public Keyboard getKeyboardForLocale(final Locale locale) {
        Keyboard keyboard = mKeyboardCache.get(locale);
        if (keyboard == null) {
            keyboard = createKeyboardForLocale(locale);
            if (keyboard != null) {
                mKeyboardCache.put(locale, keyboard);
            }
        }
        return keyboard;
    }

    private Keyboard createKeyboardForLocale(final Locale locale) {
        String layoutName = "qwerty";
        try {
            java.util.List<String> layouts = org.futo.inputmethod.latin.Subtypes.INSTANCE.findClosestLocaleLayouts(this, locale);
            if (layouts != null && !layouts.isEmpty()) {
                layoutName = layouts.get(0);
            }
        } catch (Exception ignored) {}

        final android.view.inputmethod.EditorInfo editorInfo = new android.view.inputmethod.EditorInfo();
        editorInfo.inputType = android.text.InputType.TYPE_CLASS_TEXT;

        final org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2Params params =
                new org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2Params(
                        new org.futo.inputmethod.v2keyboard.RegularKeyboardSize(
                                SPELLCHECKER_DUMMY_KEYBOARD_WIDTH, SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT,
                                new android.graphics.Rect(0, 0, 0, 0),
                                SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT / 4
                        ),
                        layoutName,
                        locale,
                        null,
                        editorInfo,
                        false, 0, true, false, false, 4.0f, null, null
                );

        final org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2 layoutSet =
                new org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2(this, params);

        return layoutSet.getKeyboard(
                new KeyboardLayoutElement(
                        KeyboardLayoutKind.Alphabet0,
                        KeyboardLayoutPage.Base
                )
        );
    }
}
