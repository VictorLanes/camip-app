package com.victorlanes.camip.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WS-Discovery: responde às buscas de câmeras ONVIF dos NVRs na rede local. */
public class Discovery {
    private static final String GRUPO = "239.255.255.250";
    private static final int PORTA = 3702;
    private static final Pattern MSGID = Pattern.compile("MessageID[^>]*>\\s*([^<\\s]+)\\s*<");
    private final CamConfig cfg;
    private MulticastSocket sock;
    private volatile boolean rodando;
    private Thread t;

    public Discovery(CamConfig cfg) {
        this.cfg = cfg;
    }

    public void iniciar() throws Exception {
        sock = new MulticastSocket(null);
        sock.setReuseAddress(true);
        sock.bind(new InetSocketAddress(PORTA));
        InetAddress grupo = InetAddress.getByName(GRUPO);
        NetworkInterface ni = null;
        try {
            ni = NetworkInterface.getByInetAddress(InetAddress.getByName(cfg.ip));
        } catch (Exception ignored) {
        }
        if (ni != null) sock.joinGroup(new InetSocketAddress(grupo, PORTA), ni);
        else sock.joinGroup(grupo);
        rodando = true;
        t = new Thread(this::loop, "ws-discovery");
        t.start();
        enviarMulticast(hello(true));
        CoreLog.i("Descoberta ONVIF ativa");
    }

    public void parar() {
        rodando = false;
        try {
            enviarMulticast(hello(false));
        } catch (Exception ignored) {
        }
        if (sock != null) sock.close();
    }

    private void loop() {
        byte[] buf = new byte[65536];
        while (rodando) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!msg.contains("Probe") || msg.contains("ProbeMatches")) continue;
                // responde só a buscas por câmeras (NetworkVideoTransmitter) ou buscas sem tipo
                if (msg.contains("Types") && !(msg.contains("NetworkVideoTransmitter") || msg.contains("Device") || msg.matches("(?s).*<[^>]*Types\\s*/>.*"))) continue;
                Matcher m = MSGID.matcher(msg);
                String rel = m.find() ? m.group(1) : "";
                byte[] resp = probeMatch(rel).getBytes(StandardCharsets.UTF_8);
                try (DatagramSocket s = new DatagramSocket()) {
                    s.send(new DatagramPacket(resp, resp.length, p.getAddress(), p.getPort()));
                }
            } catch (Exception e) {
                if (!rodando) break;
            }
        }
    }

    private void enviarMulticast(String msg) throws Exception {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket s = new DatagramSocket()) {
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName(GRUPO), PORTA));
        }
    }

    private String escopos() {
        return "onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/type/audio_encoder onvif://www.onvif.org/Profile/Streaming "
                + "onvif://www.onvif.org/Profile/T onvif://www.onvif.org/hardware/" + cfg.modelo.replace(' ', '_')
                + " onvif://www.onvif.org/name/" + cfg.nome.replace(' ', '_') + " onvif://www.onvif.org/location/country/brazil";
    }

    private String probeMatch(String relatesTo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\" xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">"
                + "<s:Header><a:MessageID>urn:uuid:" + UUID.randomUUID() + "</a:MessageID><a:RelatesTo>" + Onvif.esc(relatesTo) + "</a:RelatesTo>"
                + "<a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>"
                + "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action></s:Header>"
                + "<s:Body><d:ProbeMatches><d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:" + cfg.uuid + "</a:Address></a:EndpointReference>"
                + "<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types><d:Scopes>" + escopos() + "</d:Scopes>"
                + "<d:XAddrs>" + cfg.onvifUrl() + "</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion></d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>";
    }

    private String hello(boolean ola) {
        String acao = ola ? "Hello" : "Bye";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\" xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">"
                + "<s:Header><a:MessageID>urn:uuid:" + UUID.randomUUID() + "</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>"
                + "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/" + acao + "</a:Action></s:Header>"
                + "<s:Body><d:" + acao + "><a:EndpointReference><a:Address>urn:uuid:" + cfg.uuid + "</a:Address></a:EndpointReference>"
                + (ola ? "<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types><d:Scopes>" + escopos() + "</d:Scopes><d:XAddrs>" + cfg.onvifUrl() + "</d:XAddrs>" : "")
                + "<d:MetadataVersion>1</d:MetadataVersion></d:" + acao + "></s:Body></s:Envelope>";
    }
}
