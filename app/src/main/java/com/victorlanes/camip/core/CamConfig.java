package com.victorlanes.camip.core;

/** Dados que a parte de rede (RTSP, ONVIF, descoberta) precisa saber sobre a câmera. */
public class CamConfig {
    public String nome = "CamIP";
    public String fabricante = "CamIP";
    public String modelo = "Android IP Camera";
    public String firmware = "1.0";
    public String serial = "CAMIP0001";
    public String hardware = "android";
    public String mac = "02:00:00:00:00:01";
    public String uuid = "5f0c0a0e-0000-4000-8000-000000000001";

    public volatile String ip = "0.0.0.0";
    public int portaHttp = 8080;
    public int portaRtsp = 8554;

    public String usuario = "admin";
    public String senha = "admin";
    public boolean exigirSenha = true;
    public boolean credenciaisNaUrl = false;
    public boolean descoberta = true;
    public String caminhoPrincipal = "stream1";
    public String caminhoSub = "stream2";

    public String rtspUrl(String caminho, boolean comCredenciais) {
        String cred = comCredenciais && exigirSenha ? enc(usuario) + ":" + enc(senha) + "@" : "";
        return "rtsp://" + cred + ip + ":" + portaRtsp + "/" + caminho;
    }

    public String onvifUrl() {
        return "http://" + ip + ":" + portaHttp + "/onvif/device_service";
    }

    public String snapshotUrl() {
        return "http://" + ip + ":" + portaHttp + "/snapshot.jpg";
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
