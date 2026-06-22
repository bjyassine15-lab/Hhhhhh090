package com.example.data.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class VoiceAssistantManager private constructor(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _statusText = MutableStateFlow("مستعد لبدء الاستماع...")
    val statusText: StateFlow<String> = _statusText

    private var onSpeechResultReceived: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVoiceActive = false

    init {
        mainHandler.post {
            initializeRecognizer()
            initializeTts()
        }
    }

    private fun initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("VoiceAssistant", "onReadyForSpeech")
                        _statusText.value = "جاري الاستماع..."
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d("VoiceAssistant", "onBeginningOfSpeech")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d("VoiceAssistant", "onEndOfSpeech")
                        _statusText.value = "جاري تجميع الكلمات..."
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "خطأ في تسجيل الصوت"
                            SpeechRecognizer.ERROR_CLIENT -> "خطأ في عميل التعرف"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "صلاحيات الميكروفون غير كافية"
                            SpeechRecognizer.ERROR_NETWORK -> "خطأ في الشبكة"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "انتهت مهلة الميقاتية"
                            SpeechRecognizer.ERROR_NO_MATCH -> "لم يتم التعرف على كلمات"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "المعالج مشغول حالياً"
                            SpeechRecognizer.ERROR_SERVER -> "خطأ من الخادم"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "لم يتم سماع صوت"
                            else -> "خطأ غير معروف"
                        }
                        Log.e("VoiceAssistant", "onError: $message ($error)")
                        _isListening.value = false
                        
                        // Continuous listening pattern: restart if still active
                        if (isVoiceActive && !_isSpeaking.value) {
                            mainHandler.postDelayed({
                                restartListeningQuietly()
                            }, 500)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val speechText = matches[0].trim()
                            Log.d("VoiceAssistant", "onResults: $speechText")
                            _statusText.value = "التعرف بنجاح: '$speechText'"
                            if (speechText.isNotBlank()) {
                                onSpeechResultReceived?.invoke(speechText)
                            }
                        }
                        
                        // Continuous listening: restart listening after processing
                        if (isVoiceActive && !_isSpeaking.value) {
                            mainHandler.postDelayed({
                                restartListeningQuietly()
                            }, 1000)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            _statusText.value = "التعرف على الصوت غير متاح بهذا الجهاز"
        }
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Set language to Arabic for beautiful spoken responses!
                val locale = Locale("ar")
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("VoiceAssistant", "Arabic is not supported for TTS")
                    tts?.setLanguage(Locale.getDefault())
                }
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        _statusText.value = "جاري التحدث..."
                        // During speaking, stop speech recognizer to avoid feedback loop
                        mainHandler.post {
                            speechRecognizer?.stopListening()
                            _isListening.value = false
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        _statusText.value = "أنهيت الكلام، أستمع لك..."
                        // Resume local listening once speaking is completed!
                        mainHandler.post {
                            if (isVoiceActive) {
                                restartListeningQuietly()
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java", ReplaceWith("Log.e(\"VoiceAssistant\", \"TTS error\")"))
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        mainHandler.post {
                            if (isVoiceActive) {
                                restartListeningQuietly()
                            }
                        }
                    }
                    
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _isSpeaking.value = false
                        mainHandler.post {
                            if (isVoiceActive) {
                                restartListeningQuietly()
                            }
                        }
                    }
                })
            } else {
                Log.e("VoiceAssistant", "TTS Initialization failed")
            }
        }
    }

    fun startListeningFlow(onResult: (String) -> Unit) {
        this.onSpeechResultReceived = onResult
        isVoiceActive = true
        _isListening.value = true
        _statusText.value = "جاري تشغيل الميكروفون..."
        
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ar")
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                _statusText.value = "خطأ تشغيل الميكروفون: ${e.message}"
            }
        }
    }

    fun stopVoiceAssistant() {
        isVoiceActive = false
        _isListening.value = false
        _isSpeaking.value = false
        _statusText.value = "موقوف"
        
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                tts?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun restartListeningQuietly() {
        if (!isVoiceActive || _isSpeaking.value) return
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
                }
                speechRecognizer?.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun speak(text: String) {
        if (tts == null) return
        mainHandler.post {
            try {
                _isSpeaking.value = true
                speechRecognizer?.stopListening()
                _isListening.value = false
                
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_id_1")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_id_1")
            } catch (e: Exception) {
                e.printStackTrace()
                _isSpeaking.value = false
                if (isVoiceActive) restartListeningQuietly()
            }
        }
    }

    fun triggerVibration() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(150)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                tts?.shutdown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: VoiceAssistantManager? = null

        fun getInstance(context: Context): VoiceAssistantManager {
            return INSTANCE ?: synchronized(this) {
                val instance = VoiceAssistantManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
