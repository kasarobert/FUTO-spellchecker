package org.futo.inputmethod.latin.spellcheck;

import android.text.TextUtils;

import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.utils.ScriptUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for analyzing word checkability and Unicode script compatibility.
 *
 * Filtering Responsibilities:
 * 1. Checks if words contain special non-spelling characters like '@' or '/'.
 * 2. Checks if words contain period separators.
 * 3. Analyzes Unicode script membership to ensure words written entirely in
 *    unsupported scripts (e.g. CJK when spellchecking Latin) are skipped rather
 *    than incorrectly flagged as typos.
 */
final class SpellCheckerScriptUtils {

    public static final int CHECKABILITY_CHECKABLE = 0;
    public static final int CHECKABILITY_TOO_MANY_NON_LETTERS = 1;
    public static final int CHECKABILITY_CONTAINS_PERIOD = 2;
    public static final int CHECKABILITY_EMAIL_OR_URL = 3;
    public static final int CHECKABILITY_FIRST_LETTER_UNCHECKABLE = 4;
    public static final int CHECKABILITY_TOO_SHORT = 5;
    public static final int CHECKABILITY_UNSUPPORTED_SCRIPT = 6;

    private SpellCheckerScriptUtils() {}

    public static Character.UnicodeScript getLocaleUnicodeScript(final Locale locale) {
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

    public static boolean isCombiningMark(final int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK ||
               type == Character.COMBINING_SPACING_MARK ||
               type == Character.ENCLOSING_MARK;
    }

    /**
     * Determines whether a word token is checkable given the active session locales.
     *
     * @param text The word token string to analyze.
     * @param locales The active locales configured for the session.
     * @return A CHECKABILITY_* code indicating whether the word should be checked or skipped.
     */
    public static int getCheckabilityInScript(final String text, final List<Locale> locales) {
        if (TextUtils.isEmpty(text) || text.length() <= 1) return CHECKABILITY_TOO_SHORT;

        final int firstCodePoint = text.codePointAt(0);
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
            if (Constants.CODE_COMMERCIAL_AT == codePoint || Constants.CODE_SLASH == codePoint) {
                return CHECKABILITY_EMAIL_OR_URL;
            }
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

        // If all letter characters in the word belong to an unsupported script (e.g. CJK when checking English),
        // skip spellchecking for this word rather than flagging it as a typo.
        if (hasUnsupportedScriptLetter && !hasSupportedScriptLetter) {
            return CHECKABILITY_UNSUPPORTED_SCRIPT;
        }

        return (letterCount * 4 < length * 3)
                ? CHECKABILITY_TOO_MANY_NON_LETTERS : CHECKABILITY_CHECKABLE;
    }
}
