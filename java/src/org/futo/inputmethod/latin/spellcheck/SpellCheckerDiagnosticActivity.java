package org.futo.inputmethod.latin.spellcheck;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;
import android.view.textservice.SpellCheckerSubtype;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class SpellCheckerDiagnosticActivity extends Activity implements SpellCheckerSessionListener {
    private static final String TAG = "FUTO_SPELLCHECKER";
    private static final int TIMEOUT_MS = 5000;

    private EditText mTestEditText;
    private TextView mLogTextView;
    private SpellCheckerSession mSpellCheckerSession;
    private boolean mCallbackReceived = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 32, 32, 32);

        TextView headerView = new TextView(this);
        headerView.setText("Native EditText Integration Test Field:");
        headerView.setTextSize(16f);
        mainLayout.addView(headerView);

        // 1. Create native EditText configured explicitly for auto-correct / spellcheck
        mTestEditText = new EditText(this);
        mTestEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        mTestEditText.setText("Thiss is a sentense.");
        mTestEditText.setSelection(mTestEditText.getText().length());
        mainLayout.addView(mTestEditText);

        Button runManualBtn = new Button(this);
        runManualBtn.setText("Run Manual TextServicesManager Session Test");
        runManualBtn.setOnClickListener(v -> runManualDiagnostics());
        mainLayout.addView(runManualBtn);

        TextView logHeader = new TextView(this);
        logHeader.setText("\nDiagnostic Log Output:");
        logHeader.setTextSize(16f);
        mainLayout.addView(logHeader);

        mLogTextView = new TextView(this);
        mLogTextView.setPadding(16, 16, 16, 16);
        mLogTextView.setTextIsSelectable(true);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(mLogTextView);
        mainLayout.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(mainLayout);

        logAndDisplay("=== FUTO Spell Checker Integration Diagnostics ===");
        logEditTextConfiguration();

        // Add listeners to observe focus and text edits in EditText
        mTestEditText.setOnFocusChangeListener((v, hasFocus) -> {
            logAndDisplay("\n[EditText Event] Focus Changed: hasFocus=" + hasFocus);
            logEditTextConfiguration();
        });

        mTestEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                logAndDisplay("\n[EditText Event] Text Changed: \"" + s + "\" (start=" + start + ", before=" + before + ", count=" + count + ")");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Run manual session test automatically on startup as well
        runManualDiagnostics();
    }

    private void logEditTextConfiguration() {
        if (mTestEditText == null) return;
        int inputType = mTestEditText.getInputType();
        boolean suggestionsEnabled = mTestEditText.isSuggestionsEnabled();
        boolean isFocused = mTestEditText.isFocused();
        boolean isEnabled = mTestEditText.isEnabled();
        boolean isEditable = mTestEditText.getKeyListener() != null;

        logAndDisplay("\n--- EditText Properties ---");
        logAndDisplay("editText.getInputType(): 0x" + Integer.toHexString(inputType) + " (TYPE_CLASS_TEXT=" + ((inputType & InputType.TYPE_CLASS_TEXT) != 0) + ", AUTO_CORRECT=" + ((inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0) + ", NO_SUGGESTIONS=" + ((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) + ")");
        logAndDisplay("editText.isSuggestionsEnabled(): " + suggestionsEnabled);
        logAndDisplay("editText.isFocused(): " + isFocused);
        logAndDisplay("editText.isEnabled(): " + isEnabled + ", isEditable=" + isEditable);

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolean suppressing = imm.isInputMethodSuppressingSpellChecker();
                logAndDisplay("InputMethodManager.isInputMethodSuppressingSpellChecker(): " + suppressing);
            } else {
                logAndDisplay("InputMethodManager.isInputMethodSuppressingSpellChecker(): (API < 31)");
            }
        }
    }

    private void logAndDisplay(String message) {
        Log.i(TAG, "[DIAGNOSTIC_SCREEN] " + message);
        mHandler.post(() -> {
            if (mLogTextView != null) {
                mLogTextView.append(message + "\n");
            }
        });
    }

    private void runManualDiagnostics() {
        TextServicesManager tsm = (TextServicesManager) getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        if (tsm == null) {
            logAndDisplay("ERROR: TextServicesManager is null!");
            return;
        }

        logAndDisplay("\n--- Android Spell Checker Configuration ---");
        String selSc = Settings.Secure.getString(getContentResolver(), "selected_spell_checker");
        String scEnabled = Settings.Secure.getString(getContentResolver(), "spell_checker_enabled");
        logAndDisplay("Settings.Secure.selected_spell_checker: " + selSc);
        logAndDisplay("Settings.Secure.spell_checker_enabled: " + scEnabled);

        if (Build.VERSION.SDK_INT >= 31) {
            boolean isEnabled = tsm.isSpellCheckerEnabled();
            logAndDisplay("TextServicesManager.isSpellCheckerEnabled(): " + isEnabled);

            SpellCheckerInfo currentInfo = tsm.getCurrentSpellCheckerInfo();
            if (currentInfo != null) {
                logAndDisplay("Current SpellChecker Package: " + currentInfo.getPackageName());
                logAndDisplay("Current SpellChecker ID: " + currentInfo.getId());
                logAndDisplay("Current SpellChecker Component: " + currentInfo.getComponent().flattenToString());

                int subtypeCount = currentInfo.getSubtypeCount();
                logAndDisplay("Supported Subtypes Count: " + subtypeCount);
                for (int i = 0; i < subtypeCount; i++) {
                    SpellCheckerSubtype subtype = currentInfo.getSubtypeAt(i);
                    String localeStr = subtype.getLocale();
                    String langTag = subtype.getLanguageTag();
                    logAndDisplay("  Subtype #" + i + ": locale=" + localeStr + ", langTag=" + langTag + ", extra=" + subtype.getExtraValue());
                }
            } else {
                logAndDisplay("Current SpellCheckerInfo: NULL (no spellchecker active in settings)");
            }

            List<SpellCheckerInfo> enabledInfos = tsm.getEnabledSpellCheckerInfos();
            logAndDisplay("Enabled SpellCheckerInfos Count: " + (enabledInfos != null ? enabledInfos.size() : 0));
            if (enabledInfos != null) {
                for (SpellCheckerInfo info : enabledInfos) {
                    logAndDisplay("  Enabled SpellChecker: " + info.getId());
                }
            }
        } else {
            logAndDisplay("API < 31: Skipping TextServicesManager.isSpellCheckerEnabled() & getCurrentSpellCheckerInfo()");
        }

        try {
            java.lang.reflect.Method method = TextServicesManager.class.getMethod("getCurrentSpellCheckerSubtype", boolean.class);
            SpellCheckerSubtype currentSubtype = (SpellCheckerSubtype) method.invoke(tsm, true);
            if (currentSubtype != null) {
                logAndDisplay("TextServicesManager.getCurrentSpellCheckerSubtype(true): locale=" + currentSubtype.getLocale() + ", extra=" + currentSubtype.getExtraValue());
            } else {
                logAndDisplay("TextServicesManager.getCurrentSpellCheckerSubtype(true): NULL");
            }
        } catch (Exception e) {
            logAndDisplay("TextServicesManager.getCurrentSpellCheckerSubtype(true): (Reflection unavailable - " + e.getMessage() + ")");
        }

        logAndDisplay("\n--- Manual Request: TextServicesManager.newSpellCheckerSession() ---");
        mCallbackReceived = false;
        mSpellCheckerSession = tsm.newSpellCheckerSession(
                null, // Bundle
                null, // Locale
                this, // Listener
                true // referToSpellCheckerLanguageSettings
        );

        if (mSpellCheckerSession == null) {
            logAndDisplay("Result: TextServicesManager.newSpellCheckerSession() returned NULL!");
            logAndDisplay("CONCLUSION: FAILED BINDING - Android OS failed to create or bind to the spellchecker service.");
            return;
        }

        logAndDisplay("Result: SpellCheckerSession created successfully.");
        SpellCheckerInfo sessionInfo = mSpellCheckerSession.getSpellChecker();
        if (sessionInfo != null) {
            logAndDisplay("session.getSpellChecker(): " + sessionInfo.getId());
        } else {
            logAndDisplay("session.getSpellChecker(): NULL");
        }

        TextInfo[] queryTextInfos = new TextInfo[]{
                new TextInfo("apple"),
                new TextInfo("alma"),
                new TextInfo("applle"),
                new TextInfo("allma"),
                new TextInfo("Thiss is a sentense.")
        };

        logAndDisplay("Submitting manual query: \"apple\", \"alma\", \"applle\", \"allma\", \"Thiss is a sentense.\" via getSentenceSuggestions()");
        mSpellCheckerSession.getSentenceSuggestions(queryTextInfos, 5);

        mHandler.postDelayed(() -> {
            if (!mCallbackReceived) {
                logAndDisplay("\n[TIMEOUT] No manual callback received within " + TIMEOUT_MS + " ms!");
            }
        }, TIMEOUT_MS);
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        mCallbackReceived = true;
        logAndDisplay("\n--- Manual Callback Received: onGetSuggestions() ---");
        if (results == null) {
            logAndDisplay("Results array is NULL");
            return;
        }
        for (int i = 0; i < results.length; i++) {
            SuggestionsInfo si = results[i];
            logSuggestionsInfo("Item " + i, si);
        }
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        mCallbackReceived = true;
        logAndDisplay("\n--- Manual Callback Received: onGetSentenceSuggestions() ---");
        if (results == null) {
            logAndDisplay("SentenceSuggestionsInfo array is NULL");
            return;
        }
        for (int i = 0; i < results.length; i++) {
            SentenceSuggestionsInfo ssi = results[i];
            if (ssi == null) {
                logAndDisplay("SentenceSuggestionsInfo #" + i + " is NULL");
                continue;
            }
            int count = ssi.getSuggestionsCount();
            logAndDisplay("SentenceSuggestionsInfo #" + i + ": count=" + count);
            for (int j = 0; j < count; j++) {
                int offset = ssi.getOffsetAt(j);
                int length = ssi.getLengthAt(j);
                SuggestionsInfo si = ssi.getSuggestionsInfoAt(j);
                logAndDisplay("  Word #" + j + ": offset=" + offset + ", length=" + length);
                logSuggestionsInfo("    SuggestionsInfo", si);
            }
        }
    }

    private void logSuggestionsInfo(String prefix, SuggestionsInfo si) {
        if (si == null) {
            logAndDisplay(prefix + ": NULL");
            return;
        }
        int flags = si.getSuggestionsAttributes();
        boolean isTypo = (flags & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0;
        boolean inDict = (flags & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0;
        boolean hasRec = (flags & SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0;

        String msg = prefix + ": flags=" + flags
                + " [typo=" + isTypo
                + ", inDict=" + inDict
                + ", recommended=" + hasRec + "]"
                + ", suggestionCount=" + si.getSuggestionsCount();

        logAndDisplay(msg);

        for (int k = 0; k < si.getSuggestionsCount(); k++) {
            logAndDisplay(prefix + " -> suggestion[" + k + "]: " + si.getSuggestionAt(k));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSpellCheckerSession != null) {
            mSpellCheckerSession.close();
        }
    }
}
