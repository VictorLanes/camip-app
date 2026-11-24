package com.victorlanes.camip.core;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um stream (principal ou extra): recebe os quadros do encoder (NALs H.264/H.265 e quadros AAC),
 * quebra em pacotes RTP e entrega para cada cliente RTSP conectado.
 */
public class MediaStream {
    public static final int MTU = 1400;

    public final String nome;
    public volatile String codec = "H264";
    private volatile byte[] vps, sps, pps;
    public volatile int largura, altura, fps = 25, bitrateKbps;

    public volatile boolean temAudio;
    public volatile int taxaAudio = 44100, canais = 1;
    private volatile byte[] aacConfig;

    final CopyOnWriteArrayList<RtspSession> sessoes = new CopyOnWriteArrayList<>();
    /** chamado quando um cliente começa a assistir (para o encoder gerar um quadro-chave na hora) */
    public volatile Runnable pedirKeyframe;
    final int ssrcVideo, ssrcAudio;
    volatile int ultimoTsVideo, ultimoTsAudio;
    volatile long ultimoQuadroEm;

    public MediaStream(String nome) {
        this.nome = nome;
        Random r = new Random();
        ssrcVideo = r.nextInt();
        ssrcAudio = r.nextInt();
    }

    // ------------------------------------------------------------------ configuração

    public void setVideo(String codec, byte[] vps, byte[] sps, byte[] pps, int w, int h, int fps, int kbps) {
        this.codec = codec;
        this.vps = vps;
        this.sps = sps;
        this.pps = pps;
        this.largura = w;
        this.altura = h;
        this.fps = fps;
        this.bitrateKbps = kbps;
    }

    public void setAudio(boolean on, int taxa, int canais, byte[] config) {
        this.taxaAudio = taxa;
        this.canais = canais;
        this.aacConfig = config;
        this.temAudio = on && config != null;
    }

    public void limpar() {
        sps = pps = vps = null;
        aacConfig = null;
        temAudio = false;
    }

    public boolean pronto() {
        return sps != null && pps != null && (!"H265".equals(codec) || vps != null);
    }

    public int clientes() {
        int n = 0;
        for (RtspSession s : sessoes) if (s.tocando) n++;
        return n;
    }

    public boolean ativo() {
        return pronto() && System.currentTimeMillis() - ultimoQuadroEm < 5000;
    }

    // ------------------------------------------------------------------ vídeo

    private boolean h265() {
        return "H265".equals(codec);
    }

    private int tipo(byte[] nal) {
        return h265() ? (nal[0] >> 1) & 0x3F : nal[0] & 0x1F;
    }

    public boolean ehChave(byte[] nal) {
        int t = tipo(nal);
        return h265() ? (t >= 16 && t <= 21) : t == 5;
    }

    /** nals sem os códigos de início (00 00 01). ptsUs em microssegundos. */
    public void pushVideo(List<byte[]> nals, long ptsUs, boolean chave) {
        if (!pronto() || nals.isEmpty()) return;
        ultimoQuadroEm = System.currentTimeMillis();
        int ts = (int) (ptsUs * 9 / 100);
        ultimoTsVideo = ts;
        if (sessoes.isEmpty()) return;
        List<byte[]> lista = new ArrayList<>();
        if (chave) {
            // manda os parâmetros antes de cada quadro-chave (o cliente consegue começar em qualquer chave)
            boolean tem = false;
            for (byte[] n : nals) {
                int t = tipo(n);
                if (h265() ? t == 33 : t == 7) tem = true;
            }
            if (!tem) {
                if (h265() && vps != null) lista.add(vps);
                lista.add(sps);
                lista.add(pps);
            }
        }
        lista.addAll(nals);
        List<byte[]> payloads = new ArrayList<>();
        List<Boolean> marcas = new ArrayList<>();
        for (int i = 0; i < lista.size(); i++) fragmentar(lista.get(i), i == lista.size() - 1, payloads, marcas);
        for (RtspSession s : sessoes) s.enviarVideo(payloads, marcas, ts, chave);
    }

    private void fragmentar(byte[] nal, boolean ultimo, List<byte[]> out, List<Boolean> marcas) {
        if (nal.length <= MTU) {
            out.add(nal);
            marcas.add(ultimo);
            return;
        }
        if (h265()) {
            int tipo = (nal[0] >> 1) & 0x3F;
            byte h0 = (byte) ((nal[0] & 0x81) | (49 << 1)), h1 = nal[1];
            int pos = 2;
            boolean inicio = true;
            while (pos < nal.length) {
                int n = Math.min(MTU - 3, nal.length - pos);
                boolean fim = pos + n >= nal.length;
                byte[] p = new byte[3 + n];
                p[0] = h0;
                p[1] = h1;
                p[2] = (byte) ((inicio ? 0x80 : 0) | (fim ? 0x40 : 0) | tipo);
                System.arraycopy(nal, pos, p, 3, n);
                out.add(p);
                marcas.add(fim && ultimo);
                pos += n;
                inicio = false;
            }
        } else {
            byte ind = (byte) ((nal[0] & 0xE0) | 28);
            int tipo = nal[0] & 0x1F;
            int pos = 1;
            boolean inicio = true;
            while (pos < nal.length) {
                int n = Math.min(MTU - 2, nal.length - pos);
                boolean fim = pos + n >= nal.length;
                byte[] p = new byte[2 + n];
                p[0] = ind;
                p[1] = (byte) ((inicio ? 0x80 : 0) | (fim ? 0x40 : 0) | tipo);
                System.arraycopy(nal, pos, p, 2, n);
                out.add(p);
                marcas.add(fim && ultimo);
                pos += n;
                inicio = false;
            }
        }
    }

    // ------------------------------------------------------------------ áudio (AAC, RFC 3640)

    public void pushAudio(byte[] quadroAac, long ptsUs) {
        if (!temAudio) return;
        int ts = (int) (ptsUs * taxaAudio / 1_000_000L);
        ultimoTsAudio = ts;
        if (sessoes.isEmpty() || quadroAac.length > 8000) return;
        byte[] p = new byte[4 + quadroAac.length];
        p[0] = 0;
        p[1] = 16; // AU-headers-length em bits
        p[2] = (byte) (quadroAac.length >> 5);
        p[3] = (byte) ((quadroAac.length & 0x1F) << 3);
        System.arraycopy(quadroAac, 0, p, 4, quadroAac.length);
        for (RtspSession s : sessoes) s.enviarAudio(p, ts);
    }

    // ------------------------------------------------------------------ SDP

    public String sdp(String ip) {
        StringBuilder sb = new StringBuilder();
        long id = System.currentTimeMillis();
        sb.append("v=0\r\n");
        sb.append("o=- ").append(id).append(" 1 IN IP4 ").append(ip).append("\r\n");
        sb.append("s=CamIP\r\n");
        sb.append("i=").append(nome).append("\r\n");
        sb.append("c=IN IP4 0.0.0.0\r\n");
        sb.append("t=0 0\r\n");
        sb.append("a=tool:CamIP\r\n");
        sb.append("a=range:npt=0-\r\n");
        sb.append("a=control:*\r\n");
        sb.append("m=video 0 RTP/AVP 96\r\n");
        if (bitrateKbps > 0) sb.append("b=AS:").append(bitrateKbps).append("\r\n");
        Base64.Encoder b64 = Base64.getEncoder();
        if (h265()) {
            sb.append("a=rtpmap:96 H265/90000\r\n");
            sb.append("a=fmtp:96 sprop-vps=").append(b64.encodeToString(vps))
                    .append(";sprop-sps=").append(b64.encodeToString(sps))
                    .append(";sprop-pps=").append(b64.encodeToString(pps)).append("\r\n");
        } else {
            sb.append("a=rtpmap:96 H264/90000\r\n");
            String pli = sps.length >= 4 ? String.format("%02X%02X%02X", sps[1], sps[2], sps[3]) : "42E01F";
            sb.append("a=fmtp:96 packetization-mode=1;profile-level-id=").append(pli)
                    .append(";sprop-parameter-sets=").append(b64.encodeToString(sps)).append(",").append(b64.encodeToString(pps)).append("\r\n");
        }
        if (largura > 0) sb.append("a=x-dimensions:").append(largura).append(",").append(altura).append("\r\n");
        if (fps > 0) sb.append("a=framerate:").append(fps).append("\r\n");
        sb.append("a=control:trackID=0\r\n");
        if (temAudio) {
            sb.append("m=audio 0 RTP/AVP 97\r\n");
            sb.append("a=rtpmap:97 MPEG4-GENERIC/").append(taxaAudio).append("/").append(canais).append("\r\n");
            StringBuilder cfg = new StringBuilder();
            for (byte x : aacConfig) cfg.append(String.format("%02X", x));
            sb.append("a=fmtp:97 streamtype=5;profile-level-id=15;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=")
                    .append(cfg).append("\r\n");
            sb.append("a=control:trackID=1\r\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ utilitários

    /** separa um buffer Annex-B (00 00 01 / 00 00 00 01) em NALs */
    public static List<byte[]> separarNals(byte[] b, int off, int len) {
        List<byte[]> out = new ArrayList<>();
        int fim = off + len;
        int i = off, inicio = -1;
        while (i + 3 <= fim) {
            if (b[i] == 0 && b[i + 1] == 0 && (b[i + 2] == 1 || (i + 3 < fim && b[i + 2] == 0 && b[i + 3] == 1))) {
                int sc = b[i + 2] == 1 ? 3 : 4;
                if (inicio >= 0) out.add(copiar(b, inicio, i));
                inicio = i + sc;
                i += sc;
            } else i++;
        }
        if (inicio >= 0 && inicio < fim) out.add(copiar(b, inicio, fim));
        else if (inicio < 0 && len > 0) out.add(copiar(b, off, fim)); // sem código de início
        // remove zeros sobrando no fim de cada NAL
        for (int k = 0; k < out.size(); k++) {
            byte[] n = out.get(k);
            int e = n.length;
            while (e > 1 && n[e - 1] == 0) e--;
            if (e != n.length) out.set(k, java.util.Arrays.copyOf(n, e));
        }
        out.removeIf(n -> n.length == 0);
        return out;
    }

    private static byte[] copiar(byte[] b, int a, int z) {
        byte[] r = new byte[z - a];
        System.arraycopy(b, a, r, 0, r.length);
        return r;
    }
}
