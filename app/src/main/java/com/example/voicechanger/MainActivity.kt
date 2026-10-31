package com.example.voicechanger

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Delay-line pitch shifter (2 crossfading taps). ratio > 1 = voice meyeli/high hoy */
class PitchShifter(@Volatile var ratio: Float, private val win: Int = 1536) {
    private val size = win * 4
    private val buf = FloatArray(size)
    private var w = 0
    private var p = 0f

    private fun w2(x: Float): Float { val s = sin(PI * x).toFloat(); return s * s }

    private fun read(delay: Float): Float {
        var pos = w - (delay + 2f)
        while (pos < 0) pos += size
        val i = floor(pos).toInt()
        val f = pos - i
        return buf[i % size] * (1 - f) + buf[(i + 1) % size] * f
    }

    fun process(x: Float): Float {
        buf[w] = x
        val p2 = (p + 0.5f) % 1f
        val y = read(p * win) * w2(p) + read(p2 * win) * w2(p2)
        w = (w + 1) % size
        p += (1f - ratio) / win
        if (p >= 1f) p -= 1f
        if (p < 0f) p += 1f
        return y
    }
}

class MainActivity : Activity() {
    private val sampleRate = 44100
    @Volatile private var running = false
    private var worker: Thread? = null
    private val shifter = PitchShifter(1.35f)
    private lateinit var btn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val info = TextView(this).apply {
            text = "Headphone/earphone pore nin (na hole echo/feedback hobe).\nPitch barale golar shur meyeli hoy."
            textSize = 15f
        }
        val label = TextView(this).apply { text = "Pitch: 1.35x"; textSize = 18f }
        val seek = SeekBar(this).apply {
            max = 60            // 1.00 .. 1.60
            progress = 35
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    val r = 1f + v / 100f
                    shifter.ratio = r
                    label.text = "Pitch: %.2fx".format(r)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        btn = Button(this).apply {
            text = "START"
            setOnClickListener { toggle() }
        }
        root.addView(info); root.addView(label); root.addView(seek); root.addView(btn)
        setContentView(root)
    }

    private fun toggle() {
        if (running) { stopAudio(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startAudio()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startAudio()
    }

    private fun startAudio() {
        running = true
        btn.text = "STOP"
        worker = Thread {
            var rec: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                val minIn = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val minOut = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

                rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn * 2)
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Mic chalu hoy ni")
                }

                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(minOut * 2)
                    .build()

                val frame = ShortArray(512)
                rec.startRecording(); track.play()
                while (running) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    for (i in 0 until n) {
                        val y = shifter.process(frame[i] / 32768f) * 1.2f
                        frame[i] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    track.write(frame, 0, n)
                }
            } catch (e: Exception) {
                running = false
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    btn.text = "START"
                }
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                try { track?.stop() } catch (_: Exception) {}
                track?.release()
            }
        }.also { it.start() }
    }

    private fun stopAudio() {
        running = false
        btn.text = "START"
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}
