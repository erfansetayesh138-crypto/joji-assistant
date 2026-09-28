package com.joji.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WakeService : Service() {

    companion object {
        @Volatile
        var status: String = "متوقف"

        @Volatile
        var lastError: String = ""

        @Volatile
        var lastLog: String = ""

        @Volatile
        var running = false
        const val SAMPLE = 16000
    }

    private var worker: Thread? = null
    private var model: Model? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        val channel = "joji"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel, "Joji", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, channel)
            .setContentTitle("جوجی در حال گوش دادن است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
        running = true
        status = "در حال بارگذاری مدل (بار اول چند دقیقه طول می‌کشد)..."
        StorageService.unpack(this, "model-fa", "model",
            { m ->
                model = m
                worker = Thread { loop() }.also { it.start() }
            },
            { e ->
                status = "خطای مدل: " + e.message
                running = false
                stopSelf()
            })
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        status = "متوقف"
        worker?.interrupt()
        super.onDestroy()
    }

    private fun newRecorder(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SAMPLE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16384)
        )
    }

    private fun loop() {
        val m = model ?: return
        val rec = Recognizer(m, SAMPLE.toFloat())
        val buf = ByteArray(4096)
        var ar = newRecorder()
        ar.startRecording()
        status = "گوش می‌دهم... بگو «جوجی»"
        var lastShown = ""
        while (running) {
            val n = ar.read(buf, 0, buf.size)
            if (n <= 0) {
                Thread.sleep(20)
                continue
            }
            if (Speaker.busy) {
                rec.reset()
                lastShown = ""
                continue
            }
            val text = if (rec.acceptWaveForm(buf, n)) {
                JSONObject(rec.result).optString("text")
            } else {
                JSONObject(rec.partialResult).optString("partial")
            }
            if (text.isNotEmpty() && text != lastShown) {
                lastShown = text
                status = "شنیدم: $text"
            }
            if (text.contains("جوج")) {
                ar.stop()
                ar.release()
                rec.reset()
                lastShown = ""
                onWake()
                if (!running) break
                ar = newRecorder()
                ar.startRecording()
                status = "گوش می‌دهم... بگو «جوجی»"
            }
        }
        try {
            ar.stop()
            ar.release()
        } catch (_: Exception) {
        }
        rec.close()
    }

    private fun onWake() {
        status = "بله؟ منتظر دستور..."
        Thread.sleep(200)
        val cmd = listenCommand()
        if (cmd.isBlank()) {
            status = "چیزی نشنیدم"
            return
        }
        status = "دستور: $cmd"
        val key = getSharedPreferences("joji", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) {
            status = "کلید API ثبت نشده"
            return
        }
        Agent(this, key).handle(cmd)
    }

    private fun listenCommand(): String {
        val latch = CountDownLatch(1)
        var result = ""
        var sr: SpeechRecognizer? = null
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                status = "موتور تشخیص گفتار گوگل در دسترس نیست"
                latch.countDown()
            } else {
                val r = SpeechRecognizer.createSpeechRecognizer(this)
                sr = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) {
                        result = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                        latch.countDown()
                    }

                    override fun onError(e: Int) {
                        latch.countDown()
                    }

                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                r.startListening(i)
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        main.post { sr?.destroy() }
        return result
    }
}
