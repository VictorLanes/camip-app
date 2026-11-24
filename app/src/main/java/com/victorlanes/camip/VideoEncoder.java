package com.victorlanes.camip;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import com.victorlanes.camip.core.CoreLog;
import com.victorlanes.camip.core.MediaStream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Encoder de vídeo por hardware (MediaCodec) com entrada por Surface. */
public class VideoEncoder {
    private final MediaStream stream;
    private MediaCodec codec;
    private Surface entrada;
    private Thread drenar;
    private volatile boolean rodando;
    private String nomeCodec;
    private int w, h, fps, kbps;
    private long ultimaChave;

    public VideoEncoder(MediaStream stream) {
        this.stream = stream;
    }

    public static boolean suportaH265() {
        for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!i.isEncoder()) continue;
            for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) return true;
        }
        return false;
    }

    public Surface iniciar(int largura, int altura, int fps, int kbps, boolean h265) throws Exception {
        this.w = largura & ~1;
        this.h = altura & ~1;
        this.fps = fps;
        this.kbps = kbps;
        nomeCodec = h265 ? "H265" : "H264";
        String mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        codec = MediaCodec.createEncoderByType(mime);
        MediaCodecInfo.EncoderCapabilities ec = codec.getCodecInfo().getCapabilitiesForType(mime).getEncoderCapabilities();
        boolean cbr = ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        try {
            codec.configure(formato(mime, cbr, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // alguns aparelhos recusam CBR/prioridade: tenta o básico
            codec.release();
            codec = MediaCodec.createEncoderByType(mime);
            codec.configure(formato(mime, false, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        entrada = codec.createInputSurface();
        codec.start();
        rodando = true;
        stream.pedirKeyframe = this::pedirChave;
        drenar = new Thread(this::loop, "enc-" + stream.nome);
        drenar.start();
        CoreLog.i("Encoder " + stream.nome + ": " + nomeCodec + " " + w + "x" + h + " " + fps + " fps " + kbps + " kbps (" + codec.getName() + ")");
        return entrada;
    }

    private MediaFormat formato(String mime, boolean cbr, boolean extras) {
        MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // quadro-chave a cada 1 s: o NVR conecta rápido
        // se a câmera atrasar, o encoder repete o último quadro (o stream nunca "congela" para o NVR)
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / Math.max(5, fps) * 3);
        if (extras) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (cbr) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        return f;
    }

    public void pedirChave() {
        long agora = System.currentTimeMillis();
        if (codec == null || agora - ultimaChave < 300) return;
        ultimaChave = agora;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {
        }
    }

    public void mudarBitrate(int kbps) {
        this.kbps = kbps;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000);
            codec.setParameters(b);
            stream.bitrateKbps = kbps;
        } catch (Exception ignored) {
        }
    }

    private void loop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] vps = null, sps = null, pps = null;
        boolean h265 = "H265".equals(nomeCodec);
        while (rodando) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 20_000);
            } catch (Exception e) {
                break;
            }
            if (idx < 0) continue;
            try {
                ByteBuffer buf = codec.getOutputBuffer(idx);
                byte[] dados = new byte[info.size];
                buf.position(info.offset);
                buf.get(dados, 0, info.size);
                List<byte[]> nals = MediaStream.separarNals(dados, 0, dados.length);
                List<byte[]> vcl = new ArrayList<>();
                boolean chave = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                for (byte[] n : nals) {
                    int t = h265 ? (n[0] >> 1) & 0x3F : n[0] & 0x1F;
                    if (h265 ? t == 32 : false) vps = n;
                    else if (h265 ? t == 33 : t == 7) sps = n;
                    else if (h265 ? t == 34 : t == 8) pps = n;
                    else if (h265 ? t == 35 : t == 9) continue; // delimitador
                    else vcl.add(n);
                    if (h265 ? (t >= 16 && t <= 21) : t == 5) chave = true;
                }
                if (sps != null && pps != null && (!h265 || vps != null) && !stream.pronto()) {
                    stream.setVideo(nomeCodec, vps, sps, pps, w, h, fps, kbps);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && !vcl.isEmpty()) {
                    stream.pushVideo(vcl, info.presentationTimeUs, chave);
                }
            } catch (Exception e) {
                CoreLog.i("encoder: " + e);
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
        stream.pedirKeyframe = null;
        try {
            if (drenar != null) drenar.join(500);
        } catch (InterruptedException ignored) {
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        try {
            codec.release();
        } catch (Exception ignored) {
        }
        if (entrada != null) entrada.release();
        codec = null;
        stream.limpar();
    }

    public int largura() {
        return w;
    }

    public int altura() {
        return h;
    }
}
