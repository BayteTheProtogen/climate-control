package com.jacobp.szklarnia.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

object SoundManager {
    private val sampleRate = 44100

    private suspend fun playSineWave(freqOfTone: Double, durationMs: Int) = withContext(Dispatchers.IO) {
        val numSamples = (durationMs * sampleRate / 1000.0).toInt()
        val sample = DoubleArray(numSamples)
        val generatedSnd = ByteArray(2 * numSamples)

        // Generowanie fal sinusoidy
        for (i in 0 until numSamples) {
            sample[i] = sin(2.0 * Math.PI * i / (sampleRate / freqOfTone))
        }

        // Nakładanie obwiedni ADSR (prostego Attack/Release żeby nie strzelało)
        val attackSamples = (0.1 * numSamples).toInt()
        val releaseSamples = (0.2 * numSamples).toInt()

        for (i in 0 until numSamples) {
            var envelope = 1.0
            if (i < attackSamples) {
                envelope = i.toDouble() / attackSamples
            } else if (i > numSamples - releaseSamples) {
                envelope = (numSamples - i).toDouble() / releaseSamples
            }
            sample[i] *= envelope
        }

        var idx = 0
        for (dVal in sample) {
            val valShort = (dVal * 32767).toInt().toShort()
            generatedSnd[idx++] = (valShort.toInt() and 0x00ff).toByte()
            generatedSnd[idx++] = ((valShort.toInt() and 0xff00) ushr 8).toByte()
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(generatedSnd.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        audioTrack.write(generatedSnd, 0, generatedSnd.size)
        audioTrack.play()

        Thread.sleep(durationMs.toLong())
        audioTrack.release()
    }

    suspend fun playSonarPing() {
        playSineWave(1200.0, 300) // Jasny "ting"
    }

    suspend fun playSuccessChime() {
        playSineWave(523.25, 150) // C5
        playSineWave(587.33, 150) // D5
        playSineWave(659.25, 300) // E5
    }

    suspend fun playErrorBeep() {
        for (i in 0..2) {
            playSineWave(440.0, 150) // A4
            withContext(Dispatchers.IO) { Thread.sleep(100) }
        }
    }
}
