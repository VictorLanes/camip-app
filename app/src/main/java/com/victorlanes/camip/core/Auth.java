package com.victorlanes.camip.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Autenticação Basic/Digest (RTSP e HTTP) e WS-UsernameToken (ONVIF). */
public final class Auth {
    public static final String REALM = "CamIP";
    private static final byte[] SEGREDO = new byte[16];
    private static final Pattern PARAM = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

    static {
        new SecureRandom().nextBytes(SEGREDO);
    }

    private Auth() {
    }

    public static String md5(String s) {
        return hex(digest("MD5", s.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] digest(String alg, byte[] b) {
        try {
            return MessageDigest.getInstance(alg).digest(b);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static String novoNonce() {
        String ts = Long.toHexString(System.currentTimeMillis() / 1000);
        return ts + md5(ts + hex(SEGREDO)).substring(0, 16);
    }

    private static boolean nonceValido(String n) {
        if (n == null || n.length() < 17) return false;
        String ts = n.substring(0, n.length() - 16), sig = n.substring(n.length() - 16);
        if (!md5(ts + hex(SEGREDO)).substring(0, 16).equals(sig)) return false;
        try {
            return System.currentTimeMillis() / 1000 - Long.parseLong(ts, 16) < 3600;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** cabeçalhos WWW-Authenticate a enviar quando falta autenticação */
    public static String[] desafios() {
        return new String[]{
                "Digest realm=\"" + REALM + "\", nonce=\"" + novoNonce() + "\", algorithm=\"MD5\", qop=\"auth\"",
                "Basic realm=\"" + REALM + "\""};
    }

    /** versão para RTSP: alguns NVRs não entendem qop */
    public static String[] desafiosRtsp() {
        return new String[]{"Digest realm=\"" + REALM + "\", nonce=\"" + novoNonce() + "\"", "Basic realm=\"" + REALM + "\""};
    }

    public static boolean verificar(String header, String metodo, CamConfig cfg) {
        if (!cfg.exigirSenha) return true;
        if (header == null) return false;
        int sp = header.indexOf(' ');
        if (sp < 0) return false;
        String tipo = header.substring(0, sp).toLowerCase();
        String resto = header.substring(sp + 1).trim();
        if (tipo.equals("basic")) {
            try {
                String up = new String(Base64.getDecoder().decode(resto), StandardCharsets.UTF_8);
                int c = up.indexOf(':');
                return c > 0 && up.substring(0, c).equals(cfg.usuario) && up.substring(c + 1).equals(cfg.senha);
            } catch (Exception e) {
                return false;
            }
        }
        if (tipo.equals("digest")) {
            Map<String, String> p = new HashMap<>();
            Matcher m = PARAM.matcher(resto);
            while (m.find()) p.put(m.group(1).toLowerCase(), m.group(2) != null ? m.group(2) : m.group(3));
            if (!cfg.usuario.equals(p.get("username")) || !nonceValido(p.get("nonce"))) return false;
            String realm = p.getOrDefault("realm", REALM);
            String ha1 = md5(cfg.usuario + ":" + realm + ":" + cfg.senha);
            String ha2 = md5(metodo + ":" + p.getOrDefault("uri", ""));
            String esperado = p.get("qop") != null
                    ? md5(ha1 + ":" + p.get("nonce") + ":" + p.getOrDefault("nc", "") + ":" + p.getOrDefault("cnonce", "") + ":" + p.get("qop") + ":" + ha2)
                    : md5(ha1 + ":" + p.get("nonce") + ":" + ha2);
            return esperado.equalsIgnoreCase(p.getOrDefault("response", ""));
        }
        return false;
    }

    /** WS-Security UsernameToken (PasswordDigest ou PasswordText) */
    public static boolean wsse(String user, String senha, String nonceB64, String created, boolean isDigest, CamConfig cfg) {
        if (!cfg.exigirSenha) return true;
        if (user == null || !user.equals(cfg.usuario)) return false;
        if (!isDigest) return cfg.senha.equals(senha);
        try {
            byte[] nonce = nonceB64 == null ? new byte[0] : Base64.getMimeDecoder().decode(nonceB64.trim());
            byte[] c = (created == null ? "" : created.trim()).getBytes(StandardCharsets.UTF_8);
            byte[] p = cfg.senha.getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[nonce.length + c.length + p.length];
            System.arraycopy(nonce, 0, all, 0, nonce.length);
            System.arraycopy(c, 0, all, nonce.length, c.length);
            System.arraycopy(p, 0, all, nonce.length + c.length, p.length);
            String calc = Base64.getEncoder().encodeToString(digest("SHA-1", all));
            return calc.equals(senha == null ? "" : senha.trim());
        } catch (Exception e) {
            return false;
        }
    }
}
