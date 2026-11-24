package com.victorlanes.camip.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Random;

/** Uma sessão RTSP (um cliente assistindo um stream): faixa de vídeo e, se houver, de áudio. */
class RtspSession {

    static final class Faixa {
        final boolean video;
        final int ssrc, clock, pt;
        boolean tcp;
        int canalRtp, canalRtcp;
        InetAddress destino;
        int portaRtp, portaRtcp;
        DatagramSocket sockRtp, sockRtcp;
        int seq = new Random().nextInt(0xFFFF);
        int ultimoTs;
        long ultimoEnvio;
        long pacotes, octetos;

        Faixa(boolean video, int ssrc, int clock, int pt) {
            this.video = video;
            this.ssrc = ssrc;
            this.clock = clock;
            this.pt = pt;
        }
    }

    final String id;
    final MediaStream stream;
    final RtspConnection conexao;
    volatile Faixa video, audio;
    volatile boolean tocando;
    volatile boolean esperandoChave = true;

    RtspSession(String id, MediaStream stream, RtspConnection conexao) {
        this.id = id;
        this.stream = stream;
        this.conexao = conexao;
    }

    void enviarVideo(List<byte[]> payloads, List<Boolean> marcas, int ts, boolean chave) {
        Faixa f = video;
        if (!tocando || f == null) return;
        if (esperandoChave) {
            if (!chave) return;
            esperandoChave = false;
        }
        for (int i = 0; i < payloads.size(); i++) {
            if (!enviar(f, payloads.get(i), marcas.get(i), ts)) {
                // cliente lento: descarta até o próximo quadro-chave
                esperandoChave = true;
                Runnable r = stream.pedirKeyframe;
                if (r != null) r.run();
                return;
            }
        }
    }

    void enviarAudio(byte[] payload, int ts) {
        Faixa f = audio;
        if (!tocando || f == null || esperandoChave) return;
        enviar(f, payload, true, ts);
    }

    private boolean enviar(Faixa f, byte[] payload, boolean marca, int ts) {
        byte[] p = new byte[12 + payload.length];
        p[0] = (byte) 0x80;
        p[1] = (byte) ((marca ? 0x80 : 0) | f.pt);
        p[2] = (byte) (f.seq >> 8);
        p[3] = (byte) f.seq;
        p[4] = (byte) (ts >> 24);
        p[5] = (byte) (ts >> 16);
        p[6] = (byte) (ts >> 8);
        p[7] = (byte) ts;
        p[8] = (byte) (f.ssrc >> 24);
        p[9] = (byte) (f.ssrc >> 16);
        p[10] = (byte) (f.ssrc >> 8);
        p[11] = (byte) f.ssrc;
        System.arraycopy(payload, 0, p, 12, payload.length);
        f.seq = (f.seq + 1) & 0xFFFF;
        f.ultimoTs = ts;
        f.ultimoEnvio = System.currentTimeMillis();
        f.pacotes++;
        f.octetos += payload.length;
        if (f.tcp) return conexao.enviarIntercalado(f.canalRtp, p);
        try {
            f.sockRtp.send(new DatagramPacket(p, p.length, f.destino, f.portaRtp));
        } catch (IOException ignored) {
        }
        return true;
    }

    /** RTCP Sender Report: ajuda o NVR a sincronizar áudio, vídeo e relógio */
    void enviarRelatorios() {
        if (!tocando) return;
        for (Faixa f : new Faixa[]{video, audio}) {
            if (f == null || f.pacotes == 0) continue;
            long agora = System.currentTimeMillis();
            long ntpSeg = agora / 1000 + 2208988800L;
            long ntpFrac = ((agora % 1000) << 32) / 1000;
            int ts = (int) (f.ultimoTs + (agora - f.ultimoEnvio) * f.clock / 1000);
            byte[] p = new byte[28];
            p[0] = (byte) 0x80;
            p[1] = (byte) 200;
            p[2] = 0;
            p[3] = 6;
            put32(p, 4, f.ssrc);
            put32(p, 8, (int) ntpSeg);
            put32(p, 12, (int) ntpFrac);
            put32(p, 16, ts);
            put32(p, 20, (int) f.pacotes);
            put32(p, 24, (int) f.octetos);
            if (f.tcp) conexao.enviarIntercalado(f.canalRtcp, p);
            else {
                try {
                    f.sockRtcp.send(new DatagramPacket(p, p.length, f.destino, f.portaRtcp));
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    void fechar() {
        tocando = false;
        stream.sessoes.remove(this);
        for (Faixa f : new Faixa[]{video, audio}) {
            if (f == null) continue;
            if (f.sockRtp != null) f.sockRtp.close();
            if (f.sockRtcp != null) f.sockRtcp.close();
        }
    }
}
