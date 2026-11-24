package com.victorlanes.camip.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Servidor RTSP: vários clientes ao mesmo tempo, TCP (interleaved) ou UDP, autenticação Digest/Basic. */
public class RtspServer {
    final CamConfig cfg;
    final MediaStream principal;
    final MediaStream sub;
    private ServerSocket server;
    private Thread aceitar, relatorios;
    private volatile boolean rodando;
    final List<RtspConnection> conexoes = new CopyOnWriteArrayList<>();
    final Map<String, RtspSession> sessoes = new ConcurrentHashMap<>();
    public volatile boolean subAtivo = true;

    public RtspServer(CamConfig cfg, MediaStream principal, MediaStream sub) {
        this.cfg = cfg;
        this.principal = principal;
        this.sub = sub;
    }

    public void iniciar() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(cfg.portaRtsp));
        rodando = true;
        aceitar = new Thread(() -> {
            while (rodando) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    s.setSendBufferSize(512 * 1024);
                    RtspConnection c = new RtspConnection(this, s);
                    conexoes.add(c);
                    c.start();
                } catch (SocketException e) {
                    break;
                } catch (IOException ignored) {
                }
            }
        }, "rtsp-accept");
        aceitar.start();
        relatorios = new Thread(() -> {
            while (rodando) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
                for (RtspSession s : sessoes.values()) s.enviarRelatorios();
            }
        }, "rtsp-rtcp");
        relatorios.start();
        CoreLog.i("RTSP ouvindo na porta " + cfg.portaRtsp);
    }

    public void parar() {
        rodando = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (RtspConnection c : new ArrayList<>(conexoes)) c.fechar();
        conexoes.clear();
        sessoes.clear();
        if (relatorios != null) relatorios.interrupt();
    }

    public int clientes() {
        return principal.clientes() + (sub != null ? sub.clientes() : 0);
    }

    /** encontra o stream pelo caminho da URL (aceita vários apelidos usados por NVRs) */
    MediaStream resolver(String caminho) {
        String c = caminho.toLowerCase();
        int q = c.indexOf('?');
        if (q >= 0) c = c.substring(0, q);
        while (c.startsWith("/")) c = c.substring(1);
        int t = c.indexOf("/track");
        if (t >= 0) c = c.substring(0, t);
        while (c.endsWith("/")) c = c.substring(0, c.length() - 1);
        if (sub != null && (c.equals(cfg.caminhoSub.toLowerCase()) || c.equals("sub") || c.equals("stream2")
                || c.equals("substream") || c.equals("ch1") || c.endsWith("subtype=1"))) {
            return subAtivo ? sub : null;
        }
        return principal;
    }

    String ipLocal(Socket s) {
        InetAddress a = s.getLocalAddress();
        return a == null ? cfg.ip : a.getHostAddress();
    }
}
