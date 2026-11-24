package com.victorlanes.camip.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Uma conexão TCP de um cliente RTSP (lê os comandos e envia respostas e mídia intercalada). */
class RtspConnection extends Thread {
    private static final Random RND = new Random();
    private final RtspServer srv;
    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private final Object travaEscrita = new Object();
    private final BlockingQueue<byte[]> fila = new ArrayBlockingQueue<>(3000);
    private final List<RtspSession> minhas = new ArrayList<>();
    private volatile boolean aberta = true;
    private Thread escritor;
    private int proximoCanal = 0;

    RtspConnection(RtspServer srv, Socket sock) throws IOException {
        super("rtsp-" + sock.getRemoteSocketAddress());
        this.srv = srv;
        this.sock = sock;
        this.in = new BufferedInputStream(sock.getInputStream());
        this.out = sock.getOutputStream();
    }

    @Override
    public void run() {
        escritor = new Thread(this::loopEscrita, getName() + "-w");
        escritor.start();
        try {
            while (aberta) {
                int c = in.read();
                if (c < 0) break;
                if (c == '$') { // RTCP do cliente dentro do TCP: descarta
                    in.read();
                    int len = (in.read() << 8) | in.read();
                    if (len < 0) break;
                    long pulado = 0;
                    while (pulado < len) {
                        long n = in.skip(len - pulado);
                        if (n <= 0) break;
                        pulado += n;
                    }
                    continue;
                }
                String cabecalho = lerAte((char) c);
                if (cabecalho == null) break;
                tratar(cabecalho);
            }
        } catch (IOException ignored) {
        } finally {
            fechar();
        }
    }

    /** lê uma requisição até a linha em branco */
    private String lerAte(char primeiro) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(primeiro);
        int c, n = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (++n > 16384) return null;
            byte[] a = b.toByteArray();
            int l = a.length;
            if (l >= 4 && a[l - 4] == '\r' && a[l - 3] == '\n' && a[l - 2] == '\r' && a[l - 1] == '\n') break;
            if (l >= 2 && a[l - 2] == '\n' && a[l - 1] == '\n') break;
        }
        return c < 0 ? null : b.toString("UTF-8");
    }

    // ------------------------------------------------------------------ comandos

    private void tratar(String req) throws IOException {
        String[] linhas = req.split("\r?\n");
        String[] primeira = linhas[0].trim().split(" ");
        if (primeira.length < 2) return;
        String metodo = primeira[0].toUpperCase(Locale.ROOT), url = primeira[1];
        Map<String, String> h = new LinkedHashMap<>();
        for (int i = 1; i < linhas.length; i++) {
            int p = linhas[i].indexOf(':');
            if (p > 0) h.put(linhas[i].substring(0, p).trim().toLowerCase(Locale.ROOT), linhas[i].substring(p + 1).trim());
        }
        int len = h.containsKey("content-length") ? Integer.parseInt(h.get("content-length")) : 0;
        for (int i = 0; i < len && in.read() >= 0; i++) ; // corpo (não usado)
        String cseq = h.getOrDefault("cseq", "0");
        String caminho = caminhoDe(url);

        switch (metodo) {
            case "OPTIONS":
                responder(200, "OK", cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER\r\n", null);
                return;
            case "GET_PARAMETER":
            case "SET_PARAMETER":
                responder(200, "OK", cseq, sessaoHeader(h), null);
                return;
        }
        if (!Auth.verificar(h.get("authorization"), metodo, srv.cfg)) {
            StringBuilder ex = new StringBuilder();
            for (String d : Auth.desafiosRtsp()) ex.append("WWW-Authenticate: ").append(d).append("\r\n");
            responder(401, "Unauthorized", cseq, ex.toString(), null);
            return;
        }
        MediaStream ms = srv.resolver(caminho);
        switch (metodo) {
            case "DESCRIBE": {
                if (ms == null) {
                    responder(404, "Not Found", cseq, "", null);
                    return;
                }
                if (!ms.pronto()) {
                    responder(503, "Service Unavailable", cseq, "", null);
                    return;
                }
                byte[] sdp = ms.sdp(srv.ipLocal(sock)).getBytes(StandardCharsets.UTF_8);
                String base = url.endsWith("/") ? url : url + "/";
                responder(200, "OK", cseq, "Content-Base: " + base + "\r\nContent-Type: application/sdp\r\n", sdp);
                return;
            }
            case "SETUP":
                setup(url, caminho, ms, h, cseq);
                return;
            case "PLAY": {
                RtspSession s = sessao(h);
                if (s == null) {
                    responder(454, "Session Not Found", cseq, "", null);
                    return;
                }
                StringBuilder info = new StringBuilder("RTP-Info: ");
                String base = url.endsWith("/") ? url : url + "/";
                if (base.contains("/trackID=")) base = base.substring(0, base.indexOf("/trackID=") + 1);
                RtspSession.Faixa v = s.video, a = s.audio;
                if (v != null) info.append("url=").append(base).append("trackID=0;seq=").append(v.seq).append(";rtptime=").append(s.stream.ultimoTsVideo & 0xFFFFFFFFL);
                if (a != null) info.append(v != null ? "," : "").append("url=").append(base).append("trackID=1;seq=").append(a.seq).append(";rtptime=").append(s.stream.ultimoTsAudio & 0xFFFFFFFFL);
                responder(200, "OK", cseq, "Session: " + s.id + ";timeout=60\r\nRange: npt=0.000-\r\n" + info + "\r\n", null);
                s.esperandoChave = true;
                s.tocando = true;
                if (!s.stream.sessoes.contains(s)) s.stream.sessoes.add(s);
                Runnable r = s.stream.pedirKeyframe;
                if (r != null) r.run();
                CoreLog.i("RTSP: cliente " + sock.getInetAddress().getHostAddress() + " assistindo " + s.stream.nome);
                return;
            }
            case "PAUSE": {
                RtspSession s = sessao(h);
                if (s != null) s.tocando = false;
                responder(200, "OK", cseq, sessaoHeader(h), null);
                return;
            }
            case "TEARDOWN": {
                RtspSession s = sessao(h);
                if (s != null) {
                    s.fechar();
                    srv.sessoes.remove(s.id);
                    minhas.remove(s);
                }
                responder(200, "OK", cseq, "", null);
                return;
            }
            default:
                responder(405, "Method Not Allowed", cseq, "", null);
        }
    }

    private void setup(String url, String caminho, MediaStream ms, Map<String, String> h, String cseq) throws IOException {
        if (ms == null) {
            responder(404, "Not Found", cseq, "", null);
            return;
        }
        boolean ehAudio = url.toLowerCase(Locale.ROOT).contains("trackid=1");
        if (ehAudio && !ms.temAudio) {
            responder(404, "Not Found", cseq, "", null);
            return;
        }
        RtspSession s = sessao(h);
        if (s == null) {
            String id = String.format("%08X", RND.nextInt() & 0x7FFFFFFF);
            s = new RtspSession(id, ms, this);
            srv.sessoes.put(id, s);
            minhas.add(s);
        }
        String tr = h.getOrDefault("transport", "");
        String trl = tr.toLowerCase(Locale.ROOT);
        RtspSession.Faixa f = ehAudio
                ? new RtspSession.Faixa(false, ms.ssrcAudio, ms.taxaAudio, 97)
                : new RtspSession.Faixa(true, ms.ssrcVideo, 90000, 96);
        String resp;
        if (trl.contains("tcp") || trl.contains("interleaved")) {
            int a = proximoCanal, b = proximoCanal + 1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("interleaved=(\\d+)-(\\d+)").matcher(trl);
            if (m.find()) {
                a = Integer.parseInt(m.group(1));
                b = Integer.parseInt(m.group(2));
            }
            proximoCanal = Math.max(proximoCanal, b + 1);
            f.tcp = true;
            f.canalRtp = a;
            f.canalRtcp = b;
            resp = "RTP/AVP/TCP;unicast;interleaved=" + a + "-" + b + ";ssrc=" + String.format("%08X", f.ssrc);
        } else if (trl.contains("multicast")) {
            responder(461, "Unsupported Transport", cseq, "", null);
            return;
        } else {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("client_port=(\\d+)(?:-(\\d+))?").matcher(trl);
            if (!m.find()) {
                responder(461, "Unsupported Transport", cseq, "", null);
                return;
            }
            f.portaRtp = Integer.parseInt(m.group(1));
            f.portaRtcp = m.group(2) != null ? Integer.parseInt(m.group(2)) : f.portaRtp + 1;
            f.destino = sock.getInetAddress();
            abrirPortasUdp(f);
            resp = "RTP/AVP;unicast;client_port=" + f.portaRtp + "-" + f.portaRtcp + ";server_port="
                    + f.sockRtp.getLocalPort() + "-" + f.sockRtcp.getLocalPort() + ";ssrc=" + String.format("%08X", f.ssrc);
        }
        if (ehAudio) s.audio = f;
        else s.video = f;
        responder(200, "OK", cseq, "Transport: " + resp + "\r\nSession: " + s.id + ";timeout=60\r\n", null);
    }

    private void abrirPortasUdp(RtspSession.Faixa f) throws IOException {
        for (int tent = 0; tent < 50; tent++) {
            int p = 20000 + RND.nextInt(20000) * 2;
            DatagramSocket a = null;
            try {
                a = new DatagramSocket(null);
                a.setReuseAddress(true);
                a.bind(new InetSocketAddress(p));
                DatagramSocket b = new DatagramSocket(null);
                b.setReuseAddress(true);
                b.bind(new InetSocketAddress(p + 1));
                a.setSendBufferSize(512 * 1024);
                f.sockRtp = a;
                f.sockRtcp = b;
                return;
            } catch (IOException e) {
                if (a != null) a.close();
            }
        }
        throw new IOException("sem portas UDP livres");
    }

    private RtspSession sessao(Map<String, String> h) {
        String s = h.get("session");
        if (s == null) return null;
        int p = s.indexOf(';');
        if (p >= 0) s = s.substring(0, p);
        return srv.sessoes.get(s.trim());
    }

    private String sessaoHeader(Map<String, String> h) {
        RtspSession s = sessao(h);
        return s == null ? "" : "Session: " + s.id + ";timeout=60\r\n";
    }

    private static String caminhoDe(String url) {
        String u = url;
        int i = u.indexOf("://");
        if (i >= 0) {
            int j = u.indexOf('/', i + 3);
            u = j >= 0 ? u.substring(j) : "/";
        }
        return u;
    }

    private void responder(int cod, String txt, String cseq, String extra, byte[] corpo) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 ").append(cod).append(' ').append(txt).append("\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append("Server: CamIP\r\n");
        sb.append("Date: ").append(new java.util.Date().toString()).append("\r\n");
        sb.append(extra);
        if (corpo != null) sb.append("Content-Length: ").append(corpo.length).append("\r\n");
        sb.append("\r\n");
        byte[] cab = sb.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (travaEscrita) {
            out.write(cab);
            if (corpo != null) out.write(corpo);
            out.flush();
        }
    }

    // ------------------------------------------------------------------ mídia intercalada (TCP)

    /** coloca um pacote na fila de envio; false se o cliente está atrasado demais */
    boolean enviarIntercalado(int canal, byte[] pkt) {
        if (!aberta) return true;
        byte[] q = new byte[4 + pkt.length];
        q[0] = '$';
        q[1] = (byte) canal;
        q[2] = (byte) (pkt.length >> 8);
        q[3] = (byte) pkt.length;
        System.arraycopy(pkt, 0, q, 4, pkt.length);
        if (fila.offer(q)) return true;
        fila.clear();
        return false;
    }

    private void loopEscrita() {
        try {
            while (aberta) {
                byte[] p = fila.poll(500, TimeUnit.MILLISECONDS);
                if (p == null) continue;
                synchronized (travaEscrita) {
                    out.write(p);
                    // junta o que mais estiver na fila numa escrita só
                    byte[] x;
                    int n = 0;
                    while (n++ < 64 && (x = fila.poll()) != null) out.write(x);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            fechar();
        }
    }

    void fechar() {
        if (!aberta) return;
        aberta = false;
        for (RtspSession s : minhas) {
            s.fechar();
            srv.sessoes.remove(s.id);
        }
        minhas.clear();
        srv.conexoes.remove(this);
        try {
            sock.close();
        } catch (IOException ignored) {
        }
        if (escritor != null) escritor.interrupt();
    }
}
