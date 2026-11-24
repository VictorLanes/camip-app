package com.victorlanes.camip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.victorlanes.camip.core.CamConfig;

import java.security.SecureRandom;
import java.util.UUID;

/** Todas as configurações do app (sem limites: tudo liberado). */
public final class CamPrefs {

    private CamPrefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("camip", Context.MODE_PRIVATE);
    }

    public static String s(Context c, String k, String d) {
        return sp(c).getString(k, d);
    }

    public static int i(Context c, String k, int d) {
        return sp(c).getInt(k, d);
    }

    public static boolean b(Context c, String k, boolean d) {
        return sp(c).getBoolean(k, d);
    }

    public static float f(Context c, String k, float d) {
        return sp(c).getFloat(k, d);
    }

    public static void put(Context c, String k, Object v) {
        SharedPreferences.Editor e = sp(c).edit();
        if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else e.putString(k, String.valueOf(v));
        e.apply();
    }

    /** cria valores únicos na primeira execução (senha aleatória, número de série, UUID) */
    public static void inicializar(Context c) {
        SharedPreferences p = sp(c);
        if (p.contains("uuid")) return;
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder senha = new StringBuilder();
        for (int k = 0; k < 8; k++) senha.append(abc.charAt(r.nextInt(abc.length())));
        StringBuilder mac = new StringBuilder("02");
        for (int k = 0; k < 5; k++) mac.append(String.format(":%02X", r.nextInt(256)));
        p.edit().putString("uuid", UUID.randomUUID().toString())
                .putString("senha", senha.toString())
                .putString("serial", "CAMIP" + Long.toString(Math.abs(r.nextLong()), 36).toUpperCase().substring(0, 8))
                .putString("mac", mac.toString())
                .apply();
    }

    // ----------------------------------------------------------- vídeo
    public static String camera(Context c) { return s(c, "camera", "traseira"); }
    public static int largura(Context c) { return i(c, "largura", 1280); }
    public static int altura(Context c) { return i(c, "altura", 720); }
    public static int fps(Context c) { return i(c, "fps", 30); }
    public static int bitrate(Context c) { return i(c, "bitrate", 4000); }
    public static String codec(Context c) { return s(c, "codec", "H264"); }
    public static String rotacao(Context c) { return s(c, "rotacao", "auto"); }
    public static boolean espelhar(Context c) { return b(c, "espelhar", false); }
    public static boolean osd(Context c) { return b(c, "osd", true); }
    public static boolean osdNome(Context c) { return b(c, "osdNome", false); }
    public static float zoom(Context c) { return f(c, "zoom", 1f); }
    public static boolean lanterna(Context c) { return b(c, "lanterna", false); }

    // ----------------------------------------------------------- substream e áudio
    public static boolean sub(Context c) { return b(c, "sub", true); }
    public static int subLargura(Context c) { return i(c, "subLargura", 640); }
    public static int subAltura(Context c) { return i(c, "subAltura", 360); }
    public static int subFps(Context c) { return i(c, "subFps", 15); }
    public static int subBitrate(Context c) { return i(c, "subBitrate", 600); }
    public static boolean audio(Context c) { return b(c, "audio", false); }

    // ----------------------------------------------------------- estabilidade / aparência
    public static boolean autoReiniciar(Context c) { return b(c, "autoReiniciar", true); }
    public static String tema(Context c) { return s(c, "tema", "sistema"); }

    /** configuração usada pelos servidores RTSP/ONVIF */
    public static CamConfig config(Context c) {
        CamConfig k = new CamConfig();
        k.nome = s(c, "nome", "CamIP");
        k.fabricante = "CamIP";
        k.modelo = "CamIP " + Build.MANUFACTURER + " " + Build.MODEL;
        k.firmware = BuildConfigVersao.nome(c);
        k.serial = s(c, "serial", "CAMIP0001");
        k.hardware = Build.HARDWARE;
        k.mac = s(c, "mac", "02:00:00:00:00:01");
        k.uuid = s(c, "uuid", UUID.randomUUID().toString());
        k.portaHttp = i(c, "portaHttp", 8080);
        k.portaRtsp = i(c, "portaRtsp", 8554);
        k.usuario = s(c, "usuario", "admin");
        k.senha = s(c, "senha", "admin");
        k.exigirSenha = b(c, "exigirSenha", true);
        k.credenciaisNaUrl = b(c, "credUrl", false);
        k.descoberta = b(c, "descoberta", true);
        k.caminhoPrincipal = "stream1";
        k.caminhoSub = "stream2";
        k.ip = Rede.ip();
        return k;
    }
}
