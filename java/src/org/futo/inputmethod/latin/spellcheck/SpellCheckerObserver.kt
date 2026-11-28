package org.futo.inputmethod.latin.spellcheck

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.latin.ActiveSubtype
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.MultilingualBucketSetting
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.uix.getSettingFlow

/**
 * Monitors resource reload events and setting changes for AndroidSpellCheckerService.
 *
 * Listens for:
 * 1. Dictionary addition/deletion/replacement events emitted via GlobalIMEMessage (ReloadResources, ReloadPersonalDict).
 * 2. Language setting changes in DataStore (SubtypesSetting, ActiveSubtype, MultilingualBucketSetting).
 */
object SpellCheckerObserver {
    private const val TAG = "FUTO_SPELLCHECKER"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isSubscribed = false

    interface Listener {
        fun onDictionaryResourceChanged()
        fun onLanguageConfigurationChanged()
    }

    fun observe(context: Context, listener: Listener) {
        if (isSubscribed) return
        synchronized(this) {
            if (isSubscribed) return
            isSubscribed = true
        }

        // Observe dictionary file addition/deletion/replacement events
        scope.launch {
            try {
                GlobalIMEMessage.collect { message ->
                    if (message == IMEMessage.ReloadResources || message == IMEMessage.ReloadPersonalDict) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[SpellCheckerObserver] Dictionary resource event received: $message")
                        }
                        listener.onDictionaryResourceChanged()
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "[SpellCheckerObserver] Error collecting GlobalIMEMessage", e)
                }
            }
        }

        // Observe language preference changes in FUTO DataStore
        scope.launch {
            try {
                combine(
                    context.getSettingFlow(SubtypesSetting),
                    context.getSettingFlow(ActiveSubtype),
                    context.getSettingFlow(MultilingualBucketSetting)
                ) { _, _, _ ->
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[SpellCheckerObserver] Language settings change detected")
                    }
                    listener.onLanguageConfigurationChanged()
                }.collect {}
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "[SpellCheckerObserver] Error collecting language settings flow", e)
                }
            }
        }
    }
}
