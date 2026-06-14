import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class SoundManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalVolume: Int = -1

    // ==========================================
    // 1. ZARZĄDZANIE GŁOŚNOŚCIĄ (AUTO-VOLUME)
    // ==========================================
    fun setVolumeToOptimal() {
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // Ustawiamy głośność dokładnie na 50% maxa (najlepsza słyszalność)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume / 2, 0)
    }

    fun restoreOriginalVolume() {
        if (originalVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    // ==========================================
    // 2. SYNTEZATOR FALI (SINE WAVE Z ADSR ENVELOPE)
    // ==========================================
    private suspend fun playTone(freqHz: Double, durationMs: Int) = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val numSamples = (durationMs * sampleRate) / 1000
        val sample = ShortArray(numSamples)

        // Envelope (Miękkie uderzenie marimby - bez tego byłby trzask)
        val attackSamples = (10 * sampleRate) / 1000   // 10ms zgłaśniania
        val releaseSamples = (30 * sampleRate) / 1000  // 30ms wyciszania na końcu

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var amplitude = 1.0

            if (i < attackSamples) {
                amplitude = i.toDouble() / attackSamples
            } else if (i > numSamples - releaseSamples) {
                amplitude = (numSamples - i).toDouble() / releaseSamples
            }

            // Wzór na falę sinusoidalną
            val sineValue = sin(2.0 * PI * freqHz * t)
            sample[i] = (sineValue * amplitude * Short.MAX_VALUE).toInt().toShort()
        }

        // Tworzymy bezpieczny strumień audio bez opóźnień
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(numSamples * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(sample, 0, numSamples)
        audioTrack.play()

        // Czekamy na koniec odtwarzania i sprzątamy pamięć
        Thread.sleep(durationMs.toLong() + 10)
        audioTrack.release()
    }

    // ==========================================
    // 3. MAPA DŹWIĘKOWA (Nuty i Akordy)
    // ==========================================

    // 3.1. Faza: Szukanie szklarni (Radar Ping)
    suspend fun playRadarPing() {
        playTone(1174.66, 100) // Nuta D6 - krótki, krystaliczny "ting" sonaru
    }

    // 3.2. Faza: Checkmark / Połączono (Handoff Chime - C6 -> D6 -> E6)
    suspend fun playConnectionChime() {
        playTone(1046.50, 150) // C6
        playTone(1174.66, 150) // D6
        playTone(1318.51, 200) // E6
        // Zaraz po tej nutce, ESP32 zagra swoje piezo F6 -> G6 -> C7
    }

    // 3.3. Faza: Alarm - Zbliż telefon!
    suspend fun playAlarmSweep() {
        // Zamiast jednej nuty, gramy drażniący alarm (Sweep nie jest konieczny,
        // dwa szybkie, dysonansowe uderzenia też postawią dziadka na nogi)
        for(i in 0..2) {
            playTone(800.0, 150)
            playTone(1200.0, 150)
        }
    }
}