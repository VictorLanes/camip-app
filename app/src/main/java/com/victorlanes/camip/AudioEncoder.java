package com.victorlanes.camip;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import com.victorlanes.camip.core.CoreLog;
import com.victorlanes.camip.core.MediaStream;

import java.nio.ByteBuffer;

/** Microfone → AAC (para os dois streams). */
public class AudioEncoder {
    private static final int TAXA = 44100;
    private final MediaStream[] streams;
    private AudioRecord rec;
    private MediaCodec codec;
    private volatile boolean rodando;
    private Thread tLer, tDrenar;
    private long amostras;
    private long base;

    public AudioEncoder(MediaStream... streams) {
        this.streams = streams;
    }

    @SuppressLint("MissingPermission")
    public void iniciar() throws Exception {
        int min = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2);
        }
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, TAXA, 1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        rec.startRecording();
        rodando = true;
        base = System.nanoTime() / 1000;
        tLer = new Thread(this::ler, "audio-in");
        tDrenar = new Thread(this::drenar, "audio-out");
        tLer.start();
        tDrenar.start();
        CoreLog.i("Áudio AAC 44,1 kHz ativo");
    }

    private void ler() {
        byte[] buf = new byte[2048];
        while (rodando) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) continue;
            try {
                int idx = codec.dequeueInputBuffer(20_000);
                if (idx < 0) continue;
                ByteBuffer in = codec.getInputBuffer(idx);
                in.clear();
                int k = Math.min(n, in.remaining());
                in.put(buf, 0, k);
                long pts = base + amostras * 1_000_000L / TAXA;
                amostras += k / 2;
                codec.queueInputBuffer(idx, 0, k, pts, 0);
            } catch (Exception e) {
                if (!rodando) break;
            }
        }
    }

    private void drenar() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (rodando) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 20_000);
            } catch (Exception e) {
                break;
            }
            if (idx < 0) continue;
            try {
                ByteBuffer b = codec.getOutputBuffer(idx);
                byte[] d = new byte[info.size];
                b.position(info.offset);
                b.get(d, 0, info.size);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    for (MediaStream s : streams) if (s != null) s.setAudio(true, TAXA, 1, d);
                } else if (d.length > 0) {
                    for (MediaStream s : streams) if (s != null) s.pushAudio(d, info.presentationTimeUs);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    codec.releaseOutputBuffer(idx, false);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void parar() {
        rodando = false;
        try {
            if (tLer != null) tLer.join(500);
            if (tDrenar != null) tDrenar.join(500);
        } catch (InterruptedException ignored) {
        }
        try {
            rec.stop();
        } catch (Exception ignored) {
        }
        rec.release();
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
        for (MediaStream s : streams) if (s != null) s.setAudio(false, TAXA, 1, null);
    }
}
