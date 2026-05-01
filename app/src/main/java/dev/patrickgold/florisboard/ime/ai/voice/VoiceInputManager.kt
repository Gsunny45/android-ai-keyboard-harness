package dev.patrickgold.florisboard.ime.ai.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.patrickgold.florisboard.ime.ai.bridges.AppProfileManager
import dev.patrickgold.florisboard.ime.ai.providers.KeyVault
import dev.patrickgold.florisboard.ime.ai.trigger.TriggerParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.vosk.Model as VoskModel
import org.vosk.Recognizer as VoskRecognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/**
 * Manages voice input for the FlorisBoard AI keyboard.
 *
 * Path priority:
 *   1. Whisper API  — if OPENAI_KEY is in KeyVault (best quality, needs network)
 *   2. Vosk offline — if model is downloaded to filesDir/vosk/
 *   3. SpeechRecognizer — last resort (needs Google + internet)
 *
 * Vosk model: vosk-model-en-us-0.22-lgraph (~128 MB)
 * Trigger download via ensureVoskModelAsync() or VoiceInputManager.downloadModel().
 * Observe VoiceInputManager.sharedVoskState from any context.
 */
class VoiceInputManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val normalizer: SpokenTriggerNormalizer,
    private val triggerParser: TriggerParser,
    private val appProfileManager: AppProfileManager,
) {
    private val SAMPLE_RATE         = 16_000
    private val CHANNEL_CONFIG      = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_ENCODING      = AudioFormat.ENCODING_PCM_16BIT
    private val SILENCE_THRESHOLD   = 400
    private val SILENCE_DURATION_MS = 1_500L
    private val MIN_SPEECH_MS       = 400L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _processedOutput = MutableStateFlow<String?>(null)
    val processedOutput: StateFlow<String?> = _processedOutput

    val voskState: StateFlow<VoskState> get() = sharedVoskState

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var voskModel: VoskModel? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        if (isVoskModelReady(context) && _sharedVoskState.value !is VoskState.Ready) {
            _sharedVoskState.value = VoskState.Ready
        }
    }

    fun startRecording() {
        if (_isRecording.value) return
        _processedOutput.value = null
        _isRecording.value = true
        val apiKey = KeyVault.getInstance(context).getKey(OPENAI_KEY_REF)
        when {
            apiKey != null        -> { Log.d(TAG, "Voice: Whisper"); startWhisperRecording(apiKey) }
            isVoskModelReady(context) -> { Log.d(TAG, "Voice: Vosk"); startVoskRecording() }
            else -> { Log.i(TAG, "Voice: SpeechRecognizer fallback"); startFallbackRecognition() }
        }
    }

    fun stopRecording() {
        audioRecord?.stop()
        speechRecognizer?.stopListening()
        _isRecording.value = false
    }

    fun reset() {
        recordingJob?.cancel()
        audioRecord?.stop()
        speechRecognizer?.cancel()
        _isRecording.value = false
        _processedOutput.value = null
    }

    fun destroy() {
        recordingJob?.cancel()
        audioRecord?.release()
        audioRecord = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        voskModel?.close()
        voskModel = null
    }

    fun ensureVoskModelAsync() = downloadModel(context, scope)

    // ── Whisper ───────────────────────────────────────────────────────────

    private fun startWhisperRecording(apiKey: String) {
        recordingJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING).coerceAtLeast(4096)
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING, minBuf * 4)
            audioRecord = recorder
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) { _isRecording.value = false }; return@launch
            }
            val buf = mutableListOf<Short>()
            val chunk = ShortArray(minBuf / 2)
            var silMs = 0L; var spMs = 0L
            val chunkMs = (chunk.size.toLong() * 1000L) / SAMPLE_RATE
            recorder.startRecording()
            while (_isRecording.value) {
                val n = recorder.read(chunk, 0, chunk.size); if (n <= 0) continue
                buf.addAll(chunk.take(n).toList())
                if (rms(chunk, n) < SILENCE_THRESHOLD) {
                    silMs += chunkMs
                    if (spMs >= MIN_SPEECH_MS && silMs >= SILENCE_DURATION_MS) break
                } else { spMs += chunkMs; silMs = 0 }
            }
            recorder.stop(); recorder.release(); audioRecord = null
            if (spMs < MIN_SPEECH_MS) { withContext(Dispatchers.Main) { _isRecording.value = false }; return@launch }
            val transcript = transcribeWithWhisper(apiKey, pcmToWav(buf.toShortArray()))
            withContext(Dispatchers.Main) {
                _isRecording.value = false
                if (transcript != null) _processedOutput.value = processTranscript(transcript)
            }
        }
    }

    private suspend fun transcribeWithWhisper(apiKey: String, wav: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val resp = http.newCall(Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey").post(body).build()).execute()
            if (!resp.isSuccessful) { Log.e(TAG, "Whisper ${resp.code}"); return@withContext null }
            JSONObject(resp.body!!.string()).getString("text").trim()
        } catch (e: Exception) { Log.e(TAG, "Whisper failed", e); null }
    }

    // ── Vosk ─────────────────────────────────────────────────────────────

    private fun startVoskRecording() {
        recordingJob = scope.launch(Dispatchers.IO) {
            if (voskModel == null) {
                try {
                    voskModel = VoskModel(voskModelDir(context).absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Vosk model load failed", e)
                    withContext(Dispatchers.Main) { _isRecording.value = false; startFallbackRecognition() }
                    return@launch
                }
            }
            val voskRec = try {
                VoskRecognizer(voskModel, SAMPLE_RATE.toFloat())
            } catch (e: Exception) {
                Log.e(TAG, "VoskRecognizer init failed", e)
                withContext(Dispatchers.Main) { _isRecording.value = false }; return@launch
            }
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING).coerceAtLeast(4096)
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING, minBuf * 4)
            audioRecord = recorder
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                voskRec.close(); withContext(Dispatchers.Main) { _isRecording.value = false }; return@launch
            }
            val chunk = ShortArray(minBuf / 2)
            var silMs = 0L; var spMs = 0L
            val chunkMs = (chunk.size.toLong() * 1000L) / SAMPLE_RATE
            recorder.startRecording()
            while (_isRecording.value) {
                val n = recorder.read(chunk, 0, chunk.size); if (n <= 0) continue
                voskRec.acceptWaveForm(chunk, n)
                if (rms(chunk, n) < SILENCE_THRESHOLD) {
                    silMs += chunkMs
                    if (spMs >= MIN_SPEECH_MS && silMs >= SILENCE_DURATION_MS) break
                } else { spMs += chunkMs; silMs = 0 }
            }
            recorder.stop(); recorder.release(); audioRecord = null
            val transcript: String? = if (spMs < MIN_SPEECH_MS) null else try {
                JSONObject(voskRec.finalResult).getString("text").trim().takeIf { it.isNotBlank() }
            } catch (e: Exception) { null }
            voskRec.close()
            withContext(Dispatchers.Main) {
                _isRecording.value = false
                if (transcript != null) _processedOutput.value = processTranscript(transcript)
            }
        }
    }

    // ── SpeechRecognizer fallback ─────────────────────────────────────────

    private fun startFallbackRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { _isRecording.value = false; return }
        scope.launch(Dispatchers.Main) {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: return
                        _isRecording.value = false; _processedOutput.value = processTranscript(text)
                    }
                    override fun onError(error: Int) { _isRecording.value = false }
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onEndOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onPartialResults(r: Bundle?) {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                sr.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
        }
    }

    // ── WAV / helpers ─────────────────────────────────────────────────────

    private fun pcmToWav(pcm: ShortArray): ByteArray {
        val d = pcm.size * 2
        val b = ByteBuffer.allocate(44 + d).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + d); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(SAMPLE_RATE); b.putInt(SAMPLE_RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(d)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    private fun rms(s: ShortArray, n: Int): Int {
        if (n == 0) return 0
        return sqrt(s.take(n).sumOf { it.toLong() * it.toLong() }.toDouble() / n).toInt()
    }

    private fun processTranscript(transcript: String): String {
        val norm = normalizer.normalize(transcript)
        val input = if (norm.hasTrigger) "${norm.triggerId} ${norm.args}" else transcript
        val parsed = triggerParser.parse(input)
        return if (parsed.hasTrigger) {
            val args = parsed.args.joinToString(" ")
            "${parsed.triggerId}${if (args.isNotBlank()) " $args" else ""}"
        } else input
    }

    // ── Companion — shared state + static download ────────────────────────

    companion object {
        private const val TAG = "VoiceInputManager"
        const val OPENAI_KEY_REF  = "OPENAI_KEY"
        const val VOSK_MODEL_NAME = "vosk-model-en-us-0.22-lgraph"
        const val VOSK_MODEL_URL  = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"

        val _sharedVoskState = MutableStateFlow<VoskState>(VoskState.Idle)
        val sharedVoskState: StateFlow<VoskState> = _sharedVoskState

        fun voskModelDir(context: Context): File = File(context.filesDir, "vosk/$VOSK_MODEL_NAME")

        fun isVoskModelReady(context: Context): Boolean {
            val d = voskModelDir(context)
            return d.exists() && d.isDirectory && d.listFiles()?.isNotEmpty() == true
        }

        fun downloadModel(context: Context, scope: CoroutineScope) {
            val cur = _sharedVoskState.value
            if (cur is VoskState.Ready || cur is VoskState.Downloading) return
            scope.launch(Dispatchers.IO) { doDownload(context) }
        }

        private suspend fun doDownload(context: Context) {
            val voskDir = File(context.filesDir, "vosk")
            val zipFile = File(voskDir, "$VOSK_MODEL_NAME.zip")
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            _sharedVoskState.value = VoskState.Downloading(0f)
            try {
                voskDir.mkdirs()
                val resp = http.newCall(Request.Builder().url(VOSK_MODEL_URL).build()).execute()
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: (134L * 1024 * 1024)
                FileOutputStream(zipFile).use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(64 * 1024); var dl = 0L; var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n); dl += n
                            _sharedVoskState.value = VoskState.Downloading((dl.toFloat() / total).coerceIn(0f, 0.99f))
                        }
                    }
                }
                _sharedVoskState.value = VoskState.Downloading(0.99f)
                ZipInputStream(zipFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val out = File(voskDir, entry.name)
                        if (entry.isDirectory) out.mkdirs()
                        else { out.parentFile?.mkdirs(); FileOutputStream(out).use { zis.copyTo(it) } }
                        zis.closeEntry(); entry = zis.nextEntry
                    }
                }
                zipFile.delete()
                _sharedVoskState.value = VoskState.Ready
                Log.d(TAG, "Vosk model ready")
            } catch (e: Exception) {
                Log.e(TAG, "Vosk download failed", e)
                zipFile.delete()
                _sharedVoskState.value = VoskState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

sealed class VoskState {
    object Idle : VoskState()
    data class Downloading(val progress: Float) : VoskState()
    object Ready : VoskState()
    data class Error(val message: String) : VoskState()
}
