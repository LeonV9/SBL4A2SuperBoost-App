package de.sbl4a2.superboost

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import java.util.Random

/**
 * Eigener Player: spielt eine Audiodatei (MediaPlayer) oder ein Testsignal (rosa Rauschen,
 * AudioTrack) auf einer eigenen Audio-Session. Nur auf einer eigenen Session sind die
 * Effekte (Equalizer, BassBoost, LoudnessEnhancer) zuverlässig wirksam.
 * Läuft nur im Vordergrund; es gibt keinen Hintergrunddienst.
 */
class PlaybackController(private val context: Context) {

    var onStateChanged: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var isPlaying = false
        private set
    var nowPlaying: String? = null
        private set

    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    private var volume = 1f

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        try { player?.setVolume(volume, volume) } catch (e: Exception) { }
        try { track?.setVolume(volume) } catch (e: Exception) { }
    }

    fun playFile(uri: Uri, name: String, session: Int): Boolean {
        stop()
        val mp = MediaPlayer()
        return try {
            mp.setAudioSessionId(session)
            mp.setAudioAttributes(attrs)
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.setOnPreparedListener {
                it.start()
                isPlaying = true
                onStateChanged?.invoke()
            }
            mp.setOnErrorListener { _, what, extra ->
                fail("Wiedergabefehler ($what/$extra). Format evtl. nicht unterstützt.")
                true
            }
            player = mp
            nowPlaying = name
            mp.prepareAsync()
            onStateChanged?.invoke()
            true
        } catch (e: Exception) {
            try { mp.release() } catch (x: Exception) { }
            player = null
            nowPlaying = null
            onError?.invoke("Datei konnte nicht geöffnet werden: ${e.javaClass.simpleName}")
            false
        }
    }

    fun playTone(session: Int): Boolean {
        stop()
        return try {
            val sr = 44100
            val pcm = pinkNoise(sr, 4)
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(session)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.setVolume(volume)
            t.play()
            track = t
            nowPlaying = "Testsignal: rosa Rauschen (-14 dBFS Spitze)"
            isPlaying = true
            onStateChanged?.invoke()
            true
        } catch (e: Exception) {
            onError?.invoke("Testton konnte nicht gestartet werden: ${e.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        val was = isPlaying || nowPlaying != null
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { track?.stop() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        track = null
        isPlaying = false
        nowPlaying = null
        if (was) onStateChanged?.invoke()
    }

    private fun fail(msg: String) {
        stop()
        onError?.invoke(msg)
    }

    /** Rosa Rauschen (Paul-Kellet-Filter), niedrig ausgesteuert, kurze Ein-/Ausblendung gegen Klicks. */
    private fun pinkNoise(sampleRate: Int, seconds: Int): ShortArray {
        val n = sampleRate * seconds
        val out = ShortArray(n)
        val rnd = Random(42)
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        val fade = sampleRate / 50 // 20 ms
        for (i in 0 until n) {
            val white = rnd.nextFloat() * 2f - 1f
            b0 = 0.99886f * b0 + white * 0.0555179f
            b1 = 0.99332f * b1 + white * 0.0750759f
            b2 = 0.96900f * b2 + white * 0.1538520f
            b3 = 0.86650f * b3 + white * 0.3104856f
            b4 = 0.55000f * b4 + white * 0.5329522f
            b5 = -0.7616f * b5 - white * 0.0168980f
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
            b6 = white * 0.115926f
            var s = (pink * 0.11f * 0.2f).coerceIn(-0.2f, 0.2f)
            if (i < fade) s *= i / fade.toFloat()
            if (i > n - fade) s *= (n - i) / fade.toFloat()
            out[i] = (s * 32767f).toInt().toShort()
        }
        return out
    }
}
