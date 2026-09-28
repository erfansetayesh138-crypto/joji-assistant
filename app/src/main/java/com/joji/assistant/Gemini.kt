package com.joji.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val TEXT_MODEL = "gemini-2.5-flash"
    const val TTS_MODEL = "gemini-2.5-flash-preview-tts"
    const val VOICE = "Charon"

    private fun post(model: String, key: String, body: JSONObject): JSONObject {
        val conn = URL("$BASE$model:generateContent").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code: " + text.take(300))
        return JSONObject(text)
    }

    fun think(key: String, system: String, user: String, json: Boolean): String {
        val gen = JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", gen)
        val res = post(TEXT_MODEL, key, body)
        return res.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    fun tts(key: String, text: String): ByteArray {
        val prompt = "با لحنی طبیعی، شیوا، گرم و با لهجه فارسی معیار این جمله را بخوان: $text"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE)))))
        val res = post(TTS_MODEL, key, body)
        val b64 = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        return Base64.decode(b64, Base64.DEFAULT)
    }
}

object Speaker {
    // Gemini TTS returns raw PCM: 16-bit, mono, 24 kHz
    fun say(key: String, text: String) {
        val pcm = Gemini.tts(key, text)
        if (pcm.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        val ms = pcm.size / 2 * 1000L / 24000L
        Thread.sleep(ms + 300)
        track.release()
    }
}
