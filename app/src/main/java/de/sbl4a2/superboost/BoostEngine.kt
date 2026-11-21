package de.sbl4a2.superboost

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Audio-Engine auf Basis der stabilen Plattform-API android.media.audiofx:
 *  - LoudnessEnhancer: Verstärkung (0..+6,2 dB entspricht 100..200 %)
 *  - Equalizer:        frei einstellbare Bänder (Anzahl/Frequenzen vom Gerät)
 *  - BassBoost:        Bass (falls das Gerät die Stärke unterstützt, sonst über tiefe EQ-Bänder)
 *  - Visualizer:       Echtzeit-Spektrum und Spitzenpegel (optional, braucht RECORD_AUDIO)
 *
 * Alle Aufrufe erfolgen auf dem Main-Thread. Alle Änderungen werden über einen 20-ms-Takt
 * weich angefahren (Slew-Rate-Begrenzung), damit es keine plötzlichen Lautstärkesprünge gibt.
 */
object BoostEngine {

    /** Absolute Obergrenze der Software-Verstärkung (~ +6,2 dB, entspricht ~200 %). */
    const val HARD_CAP_MB = 620

    private const val TICK_MS = 20L
    private const val BARS = 24

    data class Capabilities(
        val equalizer: Boolean = false,
        val bassBoost: Boolean = false,
        val bassStrength: Boolean = false,
        val loudness: Boolean = false,
        val probed: Boolean = false
    )

    data class AttachResult(val ok: Boolean, val message: String)

    interface MonitorListener {
        fun onData(levels: FloatArray, peak: Float, clipping: Boolean)
        fun onMonitorError(message: String)
    }

    // ---- Zustand (Nutzerwünsche) ----
    var capabilities = Capabilities()
        private set
    var bandCount = 0
        private set
    var bandFreqsHz = IntArray(0)
        private set
    var bandMinMb = -1200
        private set
    var bandMaxMb = 900
        private set
    var enabled = false
        private set
    var gainPercent = 100
        private set
    var bassPercent = 0
        private set
    var treblePercent = 0
        private set
    var bandUserMb = IntArray(0)
        private set
    var attachedSession = -1
        private set
    var lastWarning: String? = null
        private set

    val isAttached: Boolean get() = attachedSession >= 0

    var onIdleDisabled: (() -> Unit)? = null
    var onPlayerVolume: ((Float) -> Unit)? = null

    // ---- Effekte ----
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var loud: LoudnessEnhancer? = null
    private var visualizer: Visualizer? = null
    private var bassHw = false
    private var effectsOn = false

    // ---- aktuell angewendete Werte (werden Richtung Ziel gerampt) ----
    private var curGainMb = 0
    private var curBass = 0
    private var curBands = IntArray(0)
    private var curVol = 1f

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var monitorListener: MonitorListener? = null

    // =====================================================================
    // Geräte-Check (ohne hörbare Wirkung, auf einer Wegwerf-Session)
    // =====================================================================
    fun probe(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sid = am.generateAudioSessionId()
        var eqOk = false
        var bassOk = false
        var bassStr = false
        var loudOk = false
        if (sid != AudioManager.ERROR) {
            try {
                val e = Equalizer(0, sid)
                readBandInfo(e)
                e.release()
                eqOk = true
            } catch (ex: Exception) { }
            try {
                val b = BassBoost(0, sid)
                bassOk = true
                bassStr = b.strengthSupported
                b.release()
            } catch (ex: Exception) { }
            try {
                val l = LoudnessEnhancer(sid)
                loudOk = true
                l.release()
            } catch (ex: Exception) { }
        }
        bassHw = bassStr
        if (!eqOk) bandCount = 0
        capabilities = Capabilities(eqOk, bassOk, bassStr, loudOk, true)
    }

    private fun readBandInfo(e: Equalizer) {
        val n = e.numberOfBands.toInt()
        val range = e.bandLevelRange
        val devMin = range[0].toInt()
        val devMax = range[1].toInt()
        bandMinMb = (ceil(max(devMin, -1200) / 50.0) * 50).toInt()
        bandMaxMb = (floor(min(devMax, 900) / 50.0) * 50).toInt()
        bandFreqsHz = IntArray(n) { e.getCenterFreq(it.toShort()) / 1000 }
        if (bandUserMb.size != n) bandUserMb = IntArray(n)
        if (curBands.size != n) curBands = IntArray(n)
        bandCount = n
    }

    // =====================================================================
    // An eine Audio-Session hängen (0 = globaler Ausgabe-Mix, sonst eigene Session)
    // =====================================================================
    fun attach(sessionId: Int): AttachResult {
        if (attachedSession == sessionId && (eq != null || loud != null || bass != null)) {
            return AttachResult(true, "")
        }
        release()
        val msgs = ArrayList<String>()
        eq = create("Equalizer", msgs) { Equalizer(0, sessionId) }
        bass = create("Bass Boost", msgs) { BassBoost(0, sessionId) }
        loud = create("Lautstärke-Verstärker (LoudnessEnhancer)", msgs) { LoudnessEnhancer(sessionId) }

        eq?.let { readBandInfo(it) }
        bassHw = try { bass?.strengthSupported == true } catch (e: Exception) { false }

        if (eq == null && bass == null && loud == null) {
            release()
            val detail = msgs.joinToString("; ")
            return AttachResult(
                false,
                "Auf diesem Gerät konnten keine Audioeffekte erzeugt werden.\n\n" +
                    "Mögliche Gründe: Der Hersteller blockiert Effekte (besonders systemweit), die " +
                    "Wiedergabe läuft über Hardware-Offload bzw. einen Bluetooth-Pfad ohne " +
                    "Effektunterstützung, oder eine andere App (z. B. ein anderer Equalizer) belegt die Effekte.\n\n" +
                    "Tipp: Wähle den Modus \"Eigener Player\". Details: $detail"
            )
        }
        if (loud == null) msgs.add("Verstärkung über 100 % ist auf diesem Gerät wirkungslos.")

        // Neutral starten, dann weich hochfahren
        curGainMb = 0
        curBass = 0
        curBands = IntArray(bandCount)
        curVol = 0f
        try { loud?.setTargetGain(0) } catch (e: Exception) { }
        try { bass?.setStrength(0) } catch (e: Exception) { }
        for (i in 0 until bandCount) {
            try { eq?.setBandLevel(i.toShort(), 0) } catch (e: Exception) { }
        }
        attachedSession = sessionId
        effectsOn = false
        lastWarning = if (msgs.isEmpty()) null else msgs.joinToString("; ")
        if (enabled) setEffectsEnabled(true)
        onPlayerVolume?.invoke(0f)
        scheduleTick()
        refreshMonitor()
        return AttachResult(true, lastWarning ?: "")
    }

    private fun <T : AudioEffect> create(label: String, msgs: MutableList<String>, factory: () -> T): T? {
        val fx = try {
            factory()
        } catch (e: Exception) {
            msgs.add("$label nicht verfügbar (${e.javaClass.simpleName})")
            return null
        }
        if (!fx.hasControl()) {
            msgs.add("$label ist durch eine andere App belegt")
            fx.release()
            return null
        }
        return fx
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        ticking = false
        stopVisualizer()
        try { eq?.release() } catch (e: Exception) { }
        try { bass?.release() } catch (e: Exception) { }
        try { loud?.release() } catch (e: Exception) { }
        eq = null
        bass = null
        loud = null
        attachedSession = -1
        effectsOn = false
    }

    /** Beim Beenden der App: Booster aus, alle Effekte freigeben. */
    fun shutdown() {
        enabled = false
        release()
    }

    // =====================================================================
    // Nutzer-Einstellungen
    // =====================================================================
    fun setEnabled(on: Boolean) {
        enabled = on
        if (on && isAttached && !effectsOn) setEffectsEnabled(true)
        scheduleTick()
    }

    fun setGainPercent(p: Int) { gainPercent = p.coerceIn(0, 200); scheduleTick() }
    fun setBassPercent(p: Int) { bassPercent = p.coerceIn(0, 100); scheduleTick() }
    fun setTreblePercent(p: Int) { treblePercent = p.coerceIn(-100, 100); scheduleTick() }

    fun setBandMb(index: Int, mb: Int) {
        if (index !in bandUserMb.indices) return
        bandUserMb[index] = mb.coerceIn(bandMinMb, bandMaxMb)
        scheduleTick()
    }

    /** Zielverstärkung des LoudnessEnhancers in dB (inkl. Headroom-Abzug) – für die Anzeige. */
    fun targetGainDb(): Float = targetGainMb() / 100f

    // =====================================================================
    // Zielwerte
    // =====================================================================
    private fun userGainMb(): Int =
        if (gainPercent <= 100) 0 else (2000.0 * log10(gainPercent / 100.0)).roundToInt()

    private fun bandTargetMb(i: Int): Int {
        val f = max(bandFreqsHz.getOrElse(i) { 1000 }.toDouble(), 20.0)
        // "Höhen": Shelf-Näherung – wirkt von 2 kHz (0) bis 8 kHz (voll), max. ±6 dB
        val tw = (ln(f / 2000.0) / ln(4.0)).coerceIn(0.0, 1.0)
        val trebleMb = treblePercent / 100.0 * 600.0 * tw
        // Bass-Ersatz über tiefe Bänder, falls BassBoost keine Stärke unterstützt (max. +6 dB)
        val bw = (1.0 - ln(f / 100.0) / ln(5.0)).coerceIn(0.0, 1.0)
        val bassEmu = if (bassHw) 0.0 else bassPercent / 100.0 * 600.0 * bw
        return (bandUserMb.getOrElse(i) { 0 } + trebleMb + bassEmu).roundToInt().coerceIn(bandMinMb, bandMaxMb)
    }

    private fun targetGainMb(): Int {
        if (!enabled) return 0
        // Headroom: Wer EQ/Bass anhebt, bekommt die Hälfte der größten Anhebung von der Verstärkung abgezogen
        var maxBoostMb = 0
        for (i in 0 until bandCount) maxBoostMb = max(maxBoostMb, bandTargetMb(i))
        if (bassHw) maxBoostMb = max(maxBoostMb, (bassPercent / 100.0 * 600.0).roundToInt())
        val comp = maxBoostMb / 2
        return (userGainMb() - comp).coerceIn(0, HARD_CAP_MB)
    }

    private fun targetBass(): Int = if (enabled && bassHw) bassPercent * 10 else 0

    private fun targetVol(): Float = if (enabled) min(gainPercent, 100) / 100f else 1f

    // =====================================================================
    // Weiches Anfahren (Slew-Limiting)
    // =====================================================================
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (step()) handler.postDelayed(this, TICK_MS) else ticking = false
        }
    }

    private fun scheduleTick() {
        if (!ticking) {
            ticking = true
            handler.post(tickRunnable)
        }
    }

    private fun approach(cur: Int, target: Int, up: Int, down: Int): Int =
        if (target > cur) min(cur + up, target) else max(cur - down, target)

    private fun step(): Boolean {
        var moving = false

        // Verstärkung: hoch max. 3 dB/s, runter max. 10 dB/s
        val tg = targetGainMb()
        if (curGainMb != tg) {
            curGainMb = approach(curGainMb, tg, 6, 20)
            try { loud?.setTargetGain(curGainMb) } catch (e: Exception) { }
            if (curGainMb != tg) moving = true
        }

        // Equalizer-Bänder: max. 0,25 dB pro Tick
        if (curBands.size == bandCount) {
            for (i in 0 until bandCount) {
                val t = if (enabled) bandTargetMb(i) else 0
                if (curBands[i] != t) {
                    curBands[i] = approach(curBands[i], t, 25, 25)
                    try { eq?.setBandLevel(i.toShort(), curBands[i].toShort()) } catch (e: Exception) { }
                    if (curBands[i] != t) moving = true
                }
            }
        }

        // Bass Boost (Stärke 0..1000)
        val tb = targetBass()
        if (curBass != tb) {
            curBass = approach(curBass, tb, 25, 50)
            try { bass?.setStrength(curBass.toShort()) } catch (e: Exception) { }
            if (curBass != tb) moving = true
        }

        // Player-Lautstärke (nur im Modus "Eigener Player" relevant): Soft-Start
        val tv = targetVol()
        if (curVol != tv) {
            curVol = if (tv > curVol) min(curVol + 0.01f, tv) else max(curVol - 0.04f, tv)
            onPlayerVolume?.invoke(curVol)
            if (curVol != tv) moving = true
        }

        if (!moving && !enabled && effectsOn) {
            setEffectsEnabled(false)
            onIdleDisabled?.invoke()
        }
        return moving
    }

    private fun setEffectsEnabled(on: Boolean) {
        var failed = false
        for (fx in listOf<AudioEffect?>(eq, bass, loud)) {
            if (fx == null) continue
            try {
                if (fx.setEnabled(on) != AudioEffect.SUCCESS) failed = true
            } catch (e: Exception) {
                failed = true
            }
        }
        effectsOn = on
        if (on && failed) {
            lastWarning = "Mindestens ein Effekt ließ sich nicht aktivieren. Das Gerät lässt hier evtl. keine Effekte zu."
        }
    }

    // =====================================================================
    // Echtzeit-Anzeige (Visualizer)
    // =====================================================================
    fun setMonitor(l: MonitorListener?) {
        monitorListener = l
        refreshMonitor()
    }

    fun refreshMonitor() {
        stopVisualizer()
        val l = monitorListener ?: return
        if (attachedSession < 0) return
        try {
            val v = Visualizer(attachedSession)
            v.setEnabled(false)
            v.setCaptureSize(min(Visualizer.getCaptureSizeRange()[1], 1024))
            val cb = object : Visualizer.OnDataCaptureListener {
                private var peak = 0f
                private val levels = FloatArray(BARS)

                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    if (waveform == null) return
                    var m = 0
                    for (x in waveform) {
                        val a = Math.abs((x.toInt() and 0xFF) - 128)
                        if (a > m) m = a
                    }
                    peak = m / 128f
                }

                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || fft.size < 8) return
                    val binHz = (samplingRate / 1000f) / fft.size
                    val maxBin = fft.size / 2
                    for (i in 0 until BARS) {
                        val lo = 40.0 * (16000.0 / 40.0).pow(i.toDouble() / BARS)
                        val hi = 40.0 * (16000.0 / 40.0).pow((i + 1).toDouble() / BARS)
                        val k0 = max(1, (lo / binHz).toInt())
                        val k1 = min(maxBin, max(k0 + 1, (hi / binHz).toInt()))
                        var m = 0f
                        for (k in k0 until k1) {
                            m = max(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
                        }
                        levels[i] = (20f * log10(m + 1f) / 42f).coerceIn(0f, 1f)
                    }
                    val copy = levels.copyOf()
                    val p = peak
                    handler.post { l.onData(copy, p, p >= 0.97f) }
                }
            }
            v.setDataCaptureListener(cb, Visualizer.getMaxCaptureRate() / 2, true, true)
            v.setEnabled(true)
            visualizer = v
        } catch (e: Exception) {
            l.onMonitorError(
                "Echtzeit-Anzeige auf diesem Gerät/dieser Session nicht verfügbar (${e.javaClass.simpleName})."
            )
        }
    }

    private fun stopVisualizer() {
        val v = visualizer ?: return
        try { v.setEnabled(false) } catch (e: Exception) { }
        try { v.release() } catch (e: Exception) { }
        visualizer = null
    }
}
