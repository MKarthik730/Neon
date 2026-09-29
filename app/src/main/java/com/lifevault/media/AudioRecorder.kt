package com.lifevault.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import com.lifevault.vault.store.BlobStore
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records the microphone straight into an encrypted blob: PCM from [AudioRecord] is encoded to AAC by
 * [MediaCodec], framed as ADTS, and written into the Streaming AEAD output. No plaintext file exists at any point.
 */
class AudioRecorder(private val blobs: BlobStore) {
    val blobId: String = blobs.newId()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var startedAt = 0L
    @Volatile private var error: Throwable? = null
    @Volatile var level: Float = 0f
        private set

    val elapsedMs: Long get() = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Requires the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    fun start() {
        check(!running.getAndSet(true)) { "Already recording" }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, 8192)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            throw IllegalStateException("The microphone is not available.")
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val out = blobs.openEncryptingOutput(blobId)

        codec.start()
        record.startRecording()
        startedAt = SystemClock.elapsedRealtime()
        worker = thread(name = "vault-recorder") {
            try {
                pump(record, codec, out, bufSize)
            } catch (t: Throwable) {
                error = t
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { codec.stop() }
                codec.release()
                runCatching { out.close() }
            }
        }
    }

    /** Stops and finalises the blob. Returns the duration in ms. Throws if recording failed. */
    fun stop(): Long {
        val duration = elapsedMs
        running.set(false)
        worker?.join(5_000)
        error?.let { runCatching { blobs.delete(blobId, null) }; throw IllegalStateException("Recording failed: ${it.message}", it) }
        return duration
    }

    /** Stops and deletes what was recorded. */
    fun cancel() {
        running.set(false)
        worker?.join(5_000)
        runCatching { blobs.delete(blobId, null) }
    }

    private fun pump(record: AudioRecord, codec: MediaCodec, out: OutputStream, bufSize: Int) {
        val pcm = ByteArray(bufSize)
        val info = MediaCodec.BufferInfo()
        var totalSamples = 0L
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    buffer.clear()
                    val stopRequested = !running.get()
                    val n = if (stopRequested) 0 else record.read(pcm, 0, minOf(pcm.size, buffer.remaining()))
                    val pts = totalSamples * 1_000_000L / SAMPLE_RATE
                    if (stopRequested || n < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.put(pcm, 0, n)
                        level = peak(pcm, n)
                        totalSamples += n / 2
                        codec.queueInputBuffer(inIndex, 0, n, pts, 0)
                    }
                }
            }
            var outIndex = codec.dequeueOutputBuffer(info, 10_000)
            while (outIndex >= 0) {
                val encoded = codec.getOutputBuffer(outIndex)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val frame = ByteArray(info.size)
                    encoded.position(info.offset)
                    encoded.get(frame, 0, info.size)
                    out.write(adtsHeader(info.size))
                    out.write(frame)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                    break
                }
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
    }

    private fun peak(pcm: ByteArray, n: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val a = kotlin.math.abs(s.toShort().toInt())
            if (a > max) max = a
            i += 2
        }
        return max / 32768f
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 96_000
        const val MIME = "audio/aac"
        private const val FREQ_INDEX_44100 = 4
        private const val CHANNELS = 1
        private const val PROFILE_LC = 2

        /** 7-byte ADTS header (no CRC) for one AAC-LC frame of [frameLength] bytes. */
        fun adtsHeader(frameLength: Int): ByteArray {
            val packetLen = frameLength + 7
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(),
                (((PROFILE_LC - 1) shl 6) or (FREQ_INDEX_44100 shl 2) or (CHANNELS shr 2)).toByte(),
                (((CHANNELS and 3) shl 6) or (packetLen shr 11)).toByte(),
                ((packetLen and 0x7FF) shr 3).toByte(),
                (((packetLen and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        }
    }
}
