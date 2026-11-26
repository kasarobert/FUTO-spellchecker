/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Binder;
import android.provider.UserDictionary.Words;
import android.service.textservice.SpellCheckerService.Session;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import org.futo.inputmethod.compat.SuggestionsInfoCompatUtils;
import org.futo.inputmethod.keyboard.Keyboard;
import org.futo.inputmethod.latin.NgramContext;
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import org.futo.inputmethod.latin.WordComposer;
import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.common.LocaleUtils;
import org.futo.inputmethod.latin.common.StringUtils;
import org.futo.inputmethod.latin.define.DebugFlags;
import org.futo.inputmethod.latin.utils.BinaryDictionaryUtils;
import org.futo.inputmethod.latin.utils.ScriptUtils;
import org.futo.inputmethod.latin.utils.StatsUtils;
import org.futo.inputmethod.latin.utils.SuggestionResults;
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutElement;
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind;
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public abstract class AndroidWordLevelSpellCheckerSession extends Session {
    private static final String TAG = AndroidWordLevelSpellCheckerSession.class.getSimpleName();

    public final static String[] EMPTY_STRING_ARRAY = new String[0];

    // Immutable, but not available in the constructor.
    private Locale mLocale;
    // Cache this for performance
    private List<Locale> mLocales = new ArrayList<>();
    private final AndroidSpellCheckerService mService;
    protected final SuggestionsCache mSuggestionsCache = new SuggestionsCache();
    private final ContentObserver mObserver;

    private static final String quotesRegexp =
            "(\\u0022|\\u0027|\\u0060|\\u00B4|\\u2018|\\u2018|\\u201C|\\u201D)";

    private static final class SuggestionsParams {
        public final String[] mSuggestions;
        public final int mFlags;
        public SuggestionsParams(String[] suggestions, int flags) {
            mSuggestions = suggestions;
            mFlags = flags;
        }
    }

    protected static final class SuggestionsCache {
        private static final int MAX_CACHE_SIZE = 50;
        private final LruCache<String, SuggestionsParams> mUnigramSuggestionsInfoCache =
                new LruCache<>(MAX_CACHE_SIZE);

        private static String generateKey(final String query) {
            return query + "";
        }

        public SuggestionsParams getSuggestionsFromCache(final String query) {
            return mUnigramSuggestionsInfoCache.get(query);
        }

        public void putSuggestionsToCache(
                final String query, final String[] suggestions, final int flags) {
            if (suggestions == null || TextUtils.isEmpty(query)) {
                return;
            }
            mUnigramSuggestionsInfoCache.put(
                    generateKey(query),
                    new SuggestionsParams(suggestions, flags));
        }

        public void clearCache() {
            mUnigramSuggestionsInfoCache.evictAll();
        }
    }

    AndroidWordLevelSpellCheckerSession(final AndroidSpellCheckerService service) {
        Log.i("FUTO_SPELLCHECKER", "AndroidWordLevelSpellCheckerSession.<init>");
        mService = service;
        final ContentResolver cres = service.getContentResolver();

        mObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean self) {
                mSuggestionsCache.clearCache();
            }
        };
        cres.registerContentObserver(Words.CONTENT_URI, true, mObserver);
    }

    @Override
    public void onCreate() {
        final String localeString = getLocale();
        mLocales = SpellCheckerLanguageManager.INSTANCE.resolveLocales(mService, localeString);
        mLocale = mLocales.isEmpty() ? Locale.ENGLISH : mLocales.get(0);
        Log.i("FUTO_SPELLCHECKER", "AndroidWordLevelSpellCheckerSession.onCreate() session locale: " + localeString + ", resolved: " + mLocales);
    }

    @Override
    public void onClose() {
        Log.i("FUTO_SPELLCHECKER", "AndroidWordLevelSpellCheckerSession.onClose()");
        final ContentResolver cres = mService.getContentResolver();
        cres.unregisterContentObserver(mObserver);
    }

    private static final int CHECKABILITY_CHECKABLE = 0;
    private static final int CHECKABILITY_TOO_MANY_NON_LETTERS = 1;
    private static final int CHECKABILITY_CONTAINS_PERIOD = 2;
    private static final int CHECKABILITY_EMAIL_OR_URL = 3;
    private static final int CHECKABILITY_FIRST_LETTER_UNCHECKABLE = 4;
    private static final int CHECKABILITY_TOO_SHORT = 5;
    private static final int CHECKABILITY_UNSUPPORTED_SCRIPT = 6;



    private static Character.UnicodeScript getLocaleUnicodeScript(final Locale locale) {
        String script = locale.getScript();
        if (!TextUtils.isEmpty(script)) {
            try {
                return Character.UnicodeScript.forName(script);
            } catch (Exception ignored) {}
        }
        int scriptId = ScriptUtils.getScriptFromSpellCheckerLocale(locale);
        switch (scriptId) {
            case ScriptUtils.SCRIPT_CYRILLIC: return Character.UnicodeScript.CYRILLIC;
            case ScriptUtils.SCRIPT_GREEK: return Character.UnicodeScript.GREEK;
            case ScriptUtils.SCRIPT_HEBREW: return Character.UnicodeScript.HEBREW;
            case ScriptUtils.SCRIPT_ARABIC: return Character.UnicodeScript.ARABIC;
            case ScriptUtils.SCRIPT_ARMENIAN: return Character.UnicodeScript.ARMENIAN;
            case ScriptUtils.SCRIPT_BENGALI: return Character.UnicodeScript.BENGALI;
            case ScriptUtils.SCRIPT_DEVANAGARI: return Character.UnicodeScript.DEVANAGARI;
            case ScriptUtils.SCRIPT_GEORGIAN: return Character.UnicodeScript.GEORGIAN;
            case ScriptUtils.SCRIPT_THAI: return Character.UnicodeScript.THAI;
            default: return Character.UnicodeScript.LATIN;
        }
    }

    private static boolean isCombiningMark(final int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK ||
               type == Character.COMBINING_SPACING_MARK ||
               type == Character.ENCLOSING_MARK;
    }

    private static int getCheckabilityInScript(final String text, final List<Locale> locales) {
        if (TextUtils.isEmpty(text) || text.length() <= 1) return CHECKABILITY_TOO_SHORT;

        // TODO: check if an equivalent processing can't be done more quickly with a
        // compiled regexp.
        // Filter by first letter
        final int firstCodePoint = text.codePointAt(0);
        // Filter out words that don't start with a letter or an apostrophe
        if ('\'' == firstCodePoint || '\u2019' == firstCodePoint) {
            // Apostrophe prefix
        } else if (!Character.isLetter(firstCodePoint)) {
            return CHECKABILITY_FIRST_LETTER_UNCHECKABLE;
        }

        List<Character.UnicodeScript> supportedScripts = new ArrayList<>();
        for (Locale loc : locales) {
            supportedScripts.add(getLocaleUnicodeScript(loc));
        }

        final int length = text.length();
        int letterCount = 0;
        boolean hasSupportedScriptLetter = false;
        boolean hasUnsupportedScriptLetter = false;

        for (int i = 0; i < length; i = text.offsetByCodePoints(i, 1)) {
            final int codePoint = text.codePointAt(i);
            // Any word containing a COMMERCIAL_AT is probably an e-mail address
            // Any word containing a SLASH is probably either an ad-hoc combination of two
            // words or a URI - in either case we don't want to spell check that
            if (Constants.CODE_COMMERCIAL_AT == codePoint || Constants.CODE_SLASH == codePoint) {
                return CHECKABILITY_EMAIL_OR_URL;
            }
            // If the string contains a period, native returns strange suggestions (it seems
            // to return suggestions for everything up to the period only and to ignore the
            // rest), so we suppress lookup if there is a period.
            // TODO: investigate why native returns these suggestions and remove this code.
            if (Constants.CODE_PERIOD == codePoint) {
                return CHECKABILITY_CONTAINS_PERIOD;
            }
            if (Character.isLetter(codePoint)) {
                letterCount++;
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) {
                    if (supportedScripts.contains(script)) {
                        hasSupportedScriptLetter = true;
                    } else {
                        hasUnsupportedScriptLetter = true;
                    }
                }
            } else if (isCombiningMark(codePoint)) {
                letterCount++;
            }
        }
        // Guestimate heuristic: perform spell checking if at least 3/4 of the characters
        // in this word are letters
        return (letterCount * 4 < length * 3)
                ? CHECKABILITY_TOO_MANY_NON_LETTERS : CHECKABILITY_CHECKABLE;
    }

    /**
     * Helper method to test valid capitalizations of a word.
     *
     * If the "text" is lower-case, we test only the exact string.
     * If the "Text" is capitalized, we test the exact string "Text" and the lower-cased
     *  version of it "text".
     * If the "TEXT" is fully upper case, we test the exact string "TEXT", the lower-cased
     *  version of it "text" and the capitalized version of it "Text".
     */
    private boolean isInDictForAnyCapitalization(final String text, final int capitalizeType) {
        // If the word is in there as is, then it's in the dictionary. If not, we'll test lower
        // case versions, but only if the word is not already all-lower case or mixed case.
        if (mService.isValidWord(mLocales, text)) return true;
        if (StringUtils.CAPITALIZE_NONE == capitalizeType) return false;

        for (Locale loc : mLocales) {
        // If we come here, we have a capitalized word (either First- or All-).
        // Downcase the word and look it up again. If the word is only capitalized, we
        // tested all possibilities, so if it's still negative we can return false.
            final String lowerCaseText = text.toLowerCase(loc);
            if (mService.isValidWord(mLocales, lowerCaseText)) return true;
            if (StringUtils.CAPITALIZE_FIRST != capitalizeType) {
                if (mService.isValidWord(mLocales,
                        StringUtils.capitalizeFirstAndDowncaseRest(lowerCaseText, loc))) return true;
            }
        }

        // If the lower case version is not in the dictionary, it's still possible
        // that we have an all-caps version of a word that needs to be capitalized
        // according to the dictionary. E.g. "GERMANS" only exists in the dictionary as "Germans".
        return false;
    }

    protected SuggestionsInfo onGetSuggestionsInternal(
            final TextInfo textInfo, final int suggestionsLimit) {
        return onGetSuggestionsInternal(textInfo, null, suggestionsLimit);
    }

    protected SuggestionsInfo onGetSuggestionsInternal(
            final TextInfo textInfo, final NgramContext ngramContext, final int suggestionsLimit) {
        try {
            final String text = textInfo.getText().
                    replaceAll(AndroidSpellCheckerService.APOSTROPHE,
                            AndroidSpellCheckerService.SINGLE_QUOTE).
                    replaceAll("^" + quotesRegexp, "").
                    replaceAll(quotesRegexp + "$", "");

            Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal entry limit=" + suggestionsLimit + " locales=" + mLocales);

            if (!mService.hasMainDictionaryForLocales(mLocales)) {
                Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal: No main dictionary for locales: " + mLocales + ", attempting fallback to English");
                final List<Locale> fallbackLocales = java.util.Collections.singletonList(Locale.ENGLISH);
                if (mService.hasMainDictionaryForLocales(fallbackLocales)) {
                    mLocales = fallbackLocales;
                    mLocale = Locale.ENGLISH;
                } else {
                    Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal early return: no main dictionary available for " + mLocales);
                    return AndroidSpellCheckerService.getNotInDictEmptySuggestions(
                            false /* reportAsTypo */);
                }
            }

            // Handle special patterns like email, URI, telephone number.
            final int checkability = getCheckabilityInScript(text, mLocales);
            if (CHECKABILITY_CHECKABLE != checkability) {
                Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal early return: uncheckable code=" + checkability);
                if (CHECKABILITY_CONTAINS_PERIOD == checkability) {
                    final String[] splitText = text.split(Constants.REGEXP_PERIOD);
                    boolean allWordsAreValid = true;
                    for (final String word : splitText) {
                        if (!mService.isValidWord(mLocales, word)) {
                            allWordsAreValid = false;
                            break;
                        }
                    }
                    if (allWordsAreValid) {
                        return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                                | SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
                                new String[] {
                                        TextUtils.join(Constants.STRING_SPACE, splitText) });
                    }
                }
                return (checkability == CHECKABILITY_UNSUPPORTED_SCRIPT) ?
                        AndroidSpellCheckerService.getNotInDictEmptySuggestions(false /* reportAsTypo */) :
                        (mService.isValidWord(mLocales, text) ?
                                AndroidSpellCheckerService.getInDictEmptySuggestions() :
                                AndroidSpellCheckerService.getNotInDictEmptySuggestions(
                                        CHECKABILITY_CONTAINS_PERIOD == checkability /* reportAsTypo */));
            }

            // Handle normal words.
            final int capitalizeType = StringUtils.getCapitalizationType(text);

            if (isInDictForAnyCapitalization(text, capitalizeType)) {
                Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal return: in-dictionary (valid word)");
                return AndroidSpellCheckerService.getInDictEmptySuggestions();
            }
            Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal: word is NOT in dictionary (typo)");

            final Keyboard keyboard = mService.getKeyboardForLocale(mLocale);
            if (null == keyboard) {
                Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal early return: no keyboard for locale: " + mLocale);
                // If there is no keyboard for this locale, don't do any spell-checking.
                return AndroidSpellCheckerService.getNotInDictEmptySuggestions(
                        false /* reportAsTypo */);
            }

            final WordComposer composer = new WordComposer();
            final int[] codePoints = StringUtils.toCodePointArray(text);
            final int[] coordinates;
            coordinates = keyboard.getCoordinates(codePoints);
            composer.setComposingWord(codePoints, coordinates);
            // TODO: Don't gather suggestions if the limit is <= 0 unless necessary
            final SuggestionResults suggestionResults = mService.getSuggestionResults(
                    mLocales, composer.getComposedDataSnapshot(), ngramContext, keyboard);
            final Result result = getResult(capitalizeType, mLocale, suggestionsLimit,
                    mService.getRecommendedThreshold(), text, suggestionResults);
            if (DebugFlags.DEBUG_ENABLED) {
                if (result.mSuggestions != null && result.mSuggestions.length > 0) {
                    final StringBuilder builder = new StringBuilder();
                    for (String suggestion : result.mSuggestions) {
                        builder.append(" [");
                        builder.append(suggestion);
                        builder.append("]");
                    }
                    Log.i(TAG, "onGetSuggestionsInternal() : Suggestions =" + builder);
                }
            }
            // Handle word not in dictionary.
            // This is called only once per unique word, so entering multiple
            // instances of the same word does not result in more than one call
            // to this method.
            // Also, upon changing the orientation of the device, this is called
            // again for every unique invalid word in the text box.
            StatsUtils.onInvalidWordIdentification(text);

            final int flags =
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                    | (result.mHasRecommendedSuggestions
                            ? SuggestionsInfoCompatUtils
                                    .getValueOf_RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS()
                            : 0);
            final SuggestionsInfo retval = new SuggestionsInfo(flags, result.mSuggestions);
            mSuggestionsCache.putSuggestionsToCache(text, result.mSuggestions, flags);
            Log.i("FUTO_SPELLCHECKER", "onGetSuggestionsInternal exit: returned SuggestionsInfo flags=" + flags + ", count=" + (result.mSuggestions != null ? result.mSuggestions.length : 0));
            return retval;
        } catch (RuntimeException e) {
            // Don't kill the keyboard if there is a bug in the spell checker
            Log.e("FUTO_SPELLCHECKER", "Exception while spellchecking", e);
            Log.e(TAG, "Exception while spellchecking", e);
            return AndroidSpellCheckerService.getNotInDictEmptySuggestions(
                    false /* reportAsTypo */);
        }
    }

    private static final class Result {
        public final String[] mSuggestions;
        public final boolean mHasRecommendedSuggestions;
        public Result(final String[] gatheredSuggestions, final boolean hasRecommendedSuggestions) {
            mSuggestions = gatheredSuggestions;
            mHasRecommendedSuggestions = hasRecommendedSuggestions;
        }
    }

    private static Result getResult(final int capitalizeType, final Locale locale,
            final int suggestionsLimit, final float recommendedThreshold, final String originalText,
            final SuggestionResults suggestionResults) {
        if (suggestionResults.isEmpty() || suggestionsLimit <= 0) {
            return new Result(null /* gatheredSuggestions */,
                    false /* hasRecommendedSuggestions */);
        }
        final ArrayList<String> suggestions = new ArrayList<>();
        for (final SuggestedWordInfo suggestedWordInfo : suggestionResults) {
            final String suggestion;
            if (StringUtils.CAPITALIZE_ALL == capitalizeType) {
                suggestion = suggestedWordInfo.mWord.toUpperCase(locale);
            } else if (StringUtils.CAPITALIZE_FIRST == capitalizeType) {
                suggestion = StringUtils.capitalizeFirstCodePoint(
                        suggestedWordInfo.mWord, locale);
            } else {
                suggestion = suggestedWordInfo.mWord;
            }
            suggestions.add(suggestion);
        }
        StringUtils.removeDupes(suggestions);
        // This returns a String[], while toArray() returns an Object[] which cannot be cast
        // into a String[].
        final List<String> gatheredSuggestionsList =
                suggestions.subList(0, Math.min(suggestions.size(), suggestionsLimit));
        final String[] gatheredSuggestions =
                gatheredSuggestionsList.toArray(new String[gatheredSuggestionsList.size()]);

        final int bestScore = suggestionResults.first().mScore;
        final String bestSuggestion = suggestions.get(0);
        final float normalizedScore = BinaryDictionaryUtils.calcNormalizedScore(
                originalText, bestSuggestion, bestScore);
        final boolean hasRecommendedSuggestions = (normalizedScore > recommendedThreshold);
        return new Result(gatheredSuggestions, hasRecommendedSuggestions);
    }

    /*
     * The spell checker acts on its own behalf. That is needed, in particular, to be able to
     * access the dictionary files, which the provider restricts to the identity of Latin IME.
     * Since it's called externally by the application, the spell checker is using the identity
     * of the application by default unless we clearCallingIdentity.
     * That's what the following method does.
     */
    @Override
    public SuggestionsInfo onGetSuggestions(final TextInfo textInfo, final int suggestionsLimit) {
        Log.i("FUTO_SPELLCHECKER", "AndroidWordLevelSpellCheckerSession.onGetSuggestions() entry limit=" + suggestionsLimit);
        long ident = Binder.clearCallingIdentity();
        try {
            SuggestionsInfo retval = onGetSuggestionsInternal(textInfo, suggestionsLimit);
            Log.i("FUTO_SPELLCHECKER", "AndroidWordLevelSpellCheckerSession.onGetSuggestions() exit flags=" + (retval != null ? retval.getSuggestionsAttributes() : -1));
            return retval;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
