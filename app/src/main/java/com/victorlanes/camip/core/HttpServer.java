package com.victorlanes.camip.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Servidor HTTP mínimo (ONVIF, snapshot e página web da câmera). */
public class HttpServer {

    public static class Requisicao {
        public String metodo, caminho, query = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] corpo = new byte[0];
        public String ipCliente, ipLocal;

        public String header(String n) {
            return headers.get(n.toLowerCase(Locale.ROOT));
        }

        public String corpoTexto() {
            return new String(corpo, StandardCharsets.UTF_8);
        }
    }

    public static class Resposta {
        public int codigo = 200;
        public String tipo = "text/plain; charset=utf-8";
        public byte[] corpo = new byte[0];
        public final Map<String, String> extra = new LinkedHashMap<>();
        public String[] desafios;

        public static Resposta texto(int cod, String tipo, String s) {
            Resposta r = new Resposta();
            r.codigo = cod;
            r.tipo = tipo;
            r.corpo = s.getBytes(StandardCharsets.UTF_8);
            return r;
        }
    }

    public interface Handler {
        Resposta tratar(Requisicao r) throws Exception;
    }

    private final int porta;
    private final Handler handler;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean rodando;

    public HttpServer(int porta, Handler handler) {
        this.porta = porta;
        this.handler = handler;
    }

    public void iniciar() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(porta));
        rodando = true;
        Thread t = new Thread(() -> {
            while (rodando) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> atender(s));
                } catch (SocketException e) {
                    break;
                } catch (IOException ignored) {
                }
            }
        }, "http-accept");
        t.start();
        CoreLog.i("HTTP/ONVIF ouvindo na porta " + porta);
    }

    public void parar() {
        rodando = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void atender(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            for (int n = 0; n < 50; n++) { // keep-alive simples
                Requisicao r = ler(in);
                if (r == null) return;
                r.ipCliente = sock.getInetAddress().getHostAddress();
                r.ipLocal = sock.getLocalAddress().getHostAddress();
                Resposta resp;
                try {
                    resp = handler.tratar(r);
                } catch (Exception e) {
                    CoreLog.i("HTTP erro: " + e);
                    resp = Resposta.texto(500, "text/plain", "erro interno");
                }
                boolean fechar = "close".equalsIgnoreCase(r.header("connection")) || resp.corpo.length > 2_000_000;
                escrever(out, resp, fechar);
                if (fechar) return;
            }
        } catch (IOException ignored) {
        }
    }

    private Requisicao ler(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, n = 0, u1 = 0, u2 = 0, u3 = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (++n > 32768) return null;
            if (c == '\n' && (u1 == '\n' || (u1 == '\r' && u2 == '\n' && u3 == '\r'))) break;
            u3 = u2;
            u2 = u1;
            u1 = c;
        }
        if (c < 0) return null;
        String[] linhas = b.toString("UTF-8").split("\r?\n");
        String[] p = linhas[0].split(" ");
        if (p.length < 2) return null;
        Requisicao r = new Requisicao();
        r.metodo = p[0].toUpperCase(Locale.ROOT);
        String alvo = p[1];
        int q = alvo.indexOf('?');
        r.caminho = q >= 0 ? alvo.substring(0, q) : alvo;
        if (q >= 0) r.query = alvo.substring(q + 1);
        for (int i = 1; i < linhas.length; i++) {
            int k = linhas[i].indexOf(':');
            if (k > 0) r.headers.put(linhas[i].substring(0, k).trim().toLowerCase(Locale.ROOT), linhas[i].substring(k + 1).trim());
        }
        String cl = r.header("content-length");
        if (cl != null) {
            int len = Integer.parseInt(cl.trim());
            if (len > 1_000_000) return null;
            byte[] corpo = new byte[len];
            int lido = 0;
            while (lido < len) {
                int x = in.read(corpo, lido, len - lido);
                if (x < 0) break;
                lido += x;
            }
            r.corpo = corpo;
        }
        return r;
    }

    private void escrever(OutputStream out, Resposta r, boolean fechar) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.codigo).append(' ').append(r.codigo == 200 ? "OK" : r.codigo == 401 ? "Unauthorized" : r.codigo == 404 ? "Not Found" : r.codigo == 400 ? "Bad Request" : "Error").append("\r\n");
        sb.append("Server: CamIP\r\n");
        sb.append("Content-Type: ").append(r.tipo).append("\r\n");
        sb.append("Content-Length: ").append(r.corpo.length).append("\r\n");
        sb.append("Connection: ").append(fechar ? "close" : "keep-alive").append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        if (r.desafios != null) for (String d : r.desafios) sb.append("WWW-Authenticate: ").append(d).append("\r\n");
        for (Map.Entry<String, String> e : r.extra.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(r.corpo);
        out.flush();
    }
}
