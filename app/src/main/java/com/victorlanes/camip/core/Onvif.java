package com.victorlanes.camip.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Serviços ONVIF (Profile S): Device, Media (ver10) e Media2 (ver20), snapshot e uma página web simples.
 * Suficiente para NVRs (Intelbras, Hikvision, Dahua...), VMS e apps como ONVIF Device Manager.
 */
public class Onvif implements HttpServer.Handler {

    public interface Fonte {
        /** JPEG do quadro atual (ou null) */
        byte[] snapshot();

        /** o NVR pediu para mudar a configuração de um stream (0 = principal, 1 = extra) */
        void definirEncoder(int stream, int largura, int altura, int fps, int kbps);

        void reiniciar();
    }

    private static final String NS = "xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
            + "xmlns:tt=\"http://www.onvif.org/ver10/schema\" "
            + "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" "
            + "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" "
            + "xmlns:tr2=\"http://www.onvif.org/ver20/media/wsdl\" "
            + "xmlns:ter=\"http://www.onvif.org/ver10/error\" "
            + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"";
    private static final String NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl";
    private static final String NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl";
    private static final String NS_MEDIA2 = "http://www.onvif.org/ver20/media/wsdl";

    private final CamConfig cfg;
    private final MediaStream[] streams;
    private final Fonte fonte;
    public volatile boolean subAtivo = true;

    public Onvif(CamConfig cfg, MediaStream principal, MediaStream sub, Fonte fonte) {
        this.cfg = cfg;
        this.streams = new MediaStream[]{principal, sub};
        this.fonte = fonte;
    }

    // ================================================================== HTTP

    @Override
    public HttpServer.Resposta tratar(HttpServer.Requisicao r) throws Exception {
        String c = r.caminho.toLowerCase(Locale.ROOT);
        if (r.metodo.equals("POST") && c.startsWith("/onvif")) return soap(r);
        if (c.equals("/snapshot.jpg") || c.equals("/onvif/snapshot") || c.equals("/snapshot") || c.equals("/jpg/image.jpg")) {
            if (!Auth.verificar(r.header("authorization"), r.metodo, cfg)) return negar();
            byte[] jpg = fonte.snapshot();
            if (jpg == null) return HttpServer.Resposta.texto(503, "text/plain", "sem imagem");
            HttpServer.Resposta resp = new HttpServer.Resposta();
            resp.tipo = "image/jpeg";
            resp.corpo = jpg;
            return resp;
        }
        if (c.equals("/") || c.equals("/index.html")) {
            if (!Auth.verificar(r.header("authorization"), r.metodo, cfg)) return negar();
            return HttpServer.Resposta.texto(200, "text/html; charset=utf-8", pagina(r.ipLocal));
        }
        return HttpServer.Resposta.texto(404, "text/plain", "não encontrado");
    }

    private HttpServer.Resposta negar() {
        HttpServer.Resposta r = HttpServer.Resposta.texto(401, "text/plain", "autenticação necessária");
        r.desafios = Auth.desafios();
        return r;
    }

    private String ip(String local) {
        return local != null && !local.isEmpty() && !local.startsWith("0.") ? local : cfg.ip;
    }

    // ================================================================== SOAP

    private HttpServer.Resposta soap(HttpServer.Requisicao r) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
            }
            DocumentBuilder b = f.newDocumentBuilder();
            doc = b.parse(new ByteArrayInputStream(r.corpo));
        } catch (Exception e) {
            return falha(400, "s:Sender", "ter:WellFormed", "XML inválido");
        }
        Element body = primeiro(doc.getDocumentElement(), "Body");
        Element acao = null;
        if (body != null) for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                acao = (Element) n;
                break;
            }
        }
        if (acao == null) return falha(400, "s:Sender", "ter:InvalidArgVal", "sem ação");
        String nome = acao.getLocalName();
        String ns = acao.getNamespaceURI() == null ? "" : acao.getNamespaceURI();
        String ipLocal = ip(r.ipLocal);

        if (!livre(nome) && cfg.exigirSenha && !autenticado(doc, r)) {
            HttpServer.Resposta x = falha(401, "s:Sender", "ter:NotAuthorized", "Sender not authorized");
            x.desafios = Auth.desafios();
            return x;
        }
        String corpo;
        try {
            if (ns.equals(NS_MEDIA2)) corpo = media2(nome, acao, ipLocal);
            else if (ns.equals(NS_MEDIA)) corpo = media(nome, acao, ipLocal);
            else corpo = device(nome, acao, ipLocal);
        } catch (Exception e) {
            CoreLog.i("ONVIF erro em " + nome + ": " + e);
            corpo = null;
        }
        if (corpo == null) return falha(400, "s:Receiver", "ter:ActionNotSupported", "Ação não suportada: " + nome);
        return HttpServer.Resposta.texto(200, "application/soap+xml; charset=utf-8", envelope(corpo));
    }

    private static boolean livre(String acao) {
        switch (acao) {
            case "GetSystemDateAndTime":
            case "GetCapabilities":
            case "GetServices":
            case "GetServiceCapabilities":
            case "GetEndpointReference":
            case "GetWsdlUrl":
            case "GetHostname":
                return true;
            default:
                return false;
        }
    }

    private boolean autenticado(Document doc, HttpServer.Requisicao r) {
        Element tok = primeiro(doc.getDocumentElement(), "UsernameToken");
        if (tok != null) {
            Element senha = primeiro(tok, "Password");
            boolean digest = senha != null && senha.getAttribute("Type").endsWith("PasswordDigest");
            return Auth.wsse(texto(tok, "Username"), senha == null ? null : senha.getTextContent(),
                    texto(tok, "Nonce"), texto(tok, "Created"), digest, cfg);
        }
        return Auth.verificar(r.header("authorization"), "POST", cfg);
    }

    private static String envelope(String corpo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope " + NS + "><s:Body>" + corpo + "</s:Body></s:Envelope>";
    }

    private static HttpServer.Resposta falha(int http, String code, String sub, String msg) {
        String c = "<s:Fault><s:Code><s:Value>" + code + "</s:Value><s:Subcode><s:Value>" + sub + "</s:Value></s:Subcode></s:Code>"
                + "<s:Reason><s:Text xml:lang=\"en\">" + esc(msg) + "</s:Text></s:Reason></s:Fault>";
        return HttpServer.Resposta.texto(http, "application/soap+xml; charset=utf-8", envelope(c));
    }

    // ================================================================== Device

    private String device(String nome, Element a, String ip) {
        String base = "http://" + ip + ":" + cfg.portaHttp + "/onvif/";
        switch (nome) {
            case "GetSystemDateAndTime":
                return "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:DateTimeType>NTP</tt:DateTimeType>"
                        + "<tt:DaylightSavings>false</tt:DaylightSavings><tt:TimeZone><tt:TZ>" + tz() + "</tt:TZ></tt:TimeZone>"
                        + "<tt:UTCDateTime>" + dataHora(true) + "</tt:UTCDateTime><tt:LocalDateTime>" + dataHora(false) + "</tt:LocalDateTime>"
                        + "</tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>";
            case "SetSystemDateAndTime":
                return "<tds:SetSystemDateAndTimeResponse/>";
            case "GetDeviceInformation":
                return "<tds:GetDeviceInformationResponse><tds:Manufacturer>" + esc(cfg.fabricante) + "</tds:Manufacturer><tds:Model>" + esc(cfg.modelo)
                        + "</tds:Model><tds:FirmwareVersion>" + esc(cfg.firmware) + "</tds:FirmwareVersion><tds:SerialNumber>" + esc(cfg.serial)
                        + "</tds:SerialNumber><tds:HardwareId>" + esc(cfg.hardware) + "</tds:HardwareId></tds:GetDeviceInformationResponse>";
            case "GetCapabilities":
                return "<tds:GetCapabilitiesResponse><tds:Capabilities>"
                        + "<tt:Device><tt:XAddr>" + base + "device_service</tt:XAddr>"
                        + "<tt:Network><tt:IPFilter>false</tt:IPFilter><tt:ZeroConfiguration>false</tt:ZeroConfiguration><tt:IPVersion6>false</tt:IPVersion6><tt:DynDNS>false</tt:DynDNS></tt:Network>"
                        + "<tt:System><tt:DiscoveryResolve>false</tt:DiscoveryResolve><tt:DiscoveryBye>true</tt:DiscoveryBye><tt:RemoteDiscovery>false</tt:RemoteDiscovery>"
                        + "<tt:SystemBackup>false</tt:SystemBackup><tt:SystemLogging>false</tt:SystemLogging><tt:FirmwareUpgrade>false</tt:FirmwareUpgrade>"
                        + "<tt:SupportedVersions><tt:Major>2</tt:Major><tt:Minor>60</tt:Minor></tt:SupportedVersions></tt:System>"
                        + "<tt:IO><tt:InputConnectors>0</tt:InputConnectors><tt:RelayOutputs>0</tt:RelayOutputs></tt:IO>"
                        + "<tt:Security><tt:TLS1.1>false</tt:TLS1.1><tt:TLS1.2>false</tt:TLS1.2><tt:OnboardKeyGeneration>false</tt:OnboardKeyGeneration>"
                        + "<tt:AccessPolicyConfig>false</tt:AccessPolicyConfig><tt:X.509Token>false</tt:X.509Token><tt:SAMLToken>false</tt:SAMLToken>"
                        + "<tt:KerberosToken>false</tt:KerberosToken><tt:RELToken>false</tt:RELToken></tt:Security></tt:Device>"
                        + "<tt:Media><tt:XAddr>" + base + "media_service</tt:XAddr><tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast>"
                        + "<tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities></tt:Media>"
                        + "</tds:Capabilities></tds:GetCapabilitiesResponse>";
            case "GetServices": {
                StringBuilder sb = new StringBuilder("<tds:GetServicesResponse>");
                String[][] sv = {{NS_DEVICE, "device_service"}, {NS_MEDIA, "media_service"}, {NS_MEDIA2, "media2_service"}};
                for (String[] s : sv) {
                    sb.append("<tds:Service><tds:Namespace>").append(s[0]).append("</tds:Namespace><tds:XAddr>").append(base).append(s[1])
                            .append("</tds:XAddr><tds:Version><tt:Major>").append(s[0].contains("ver20") ? 2 : 2).append("</tt:Major><tt:Minor>60</tt:Minor></tds:Version></tds:Service>");
                }
                return sb.append("</tds:GetServicesResponse>").toString();
            }
            case "GetServiceCapabilities":
                return "<tds:GetServiceCapabilitiesResponse><tds:Capabilities><tds:Network IPFilter=\"false\" ZeroConfiguration=\"false\" IPVersion6=\"false\" DynDNS=\"false\" NTP=\"0\"/>"
                        + "<tds:Security TLS1.0=\"false\" TLS1.1=\"false\" TLS1.2=\"false\" UsernameToken=\"true\" HttpDigest=\"true\"/>"
                        + "<tds:System DiscoveryResolve=\"false\" DiscoveryBye=\"true\" RemoteDiscovery=\"false\" SystemBackup=\"false\" SystemLogging=\"false\" FirmwareUpgrade=\"false\"/>"
                        + "</tds:Capabilities></tds:GetServiceCapabilitiesResponse>";
            case "GetScopes": {
                String[] sc = {"onvif://www.onvif.org/type/video_encoder", "onvif://www.onvif.org/type/audio_encoder", "onvif://www.onvif.org/Profile/Streaming",
                        "onvif://www.onvif.org/Profile/T", "onvif://www.onvif.org/hardware/" + url(cfg.modelo), "onvif://www.onvif.org/name/" + url(cfg.nome),
                        "onvif://www.onvif.org/location/country/brazil"};
                StringBuilder sb = new StringBuilder("<tds:GetScopesResponse>");
                for (String s : sc) sb.append("<tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>").append(esc(s)).append("</tt:ScopeItem></tds:Scopes>");
                return sb.append("</tds:GetScopesResponse>").toString();
            }
            case "GetHostname":
                return "<tds:GetHostnameResponse><tds:HostnameInformation><tt:FromDHCP>false</tt:FromDHCP><tt:Name>" + esc(cfg.nome.replace(' ', '-'))
                        + "</tt:Name></tds:HostnameInformation></tds:GetHostnameResponse>";
            case "SetHostname":
                return "<tds:SetHostnameResponse/>";
            case "GetNetworkInterfaces":
                return "<tds:GetNetworkInterfacesResponse><tds:NetworkInterfaces token=\"wlan0\"><tt:Enabled>true</tt:Enabled>"
                        + "<tt:Info><tt:Name>wlan0</tt:Name><tt:HwAddress>" + cfg.mac + "</tt:HwAddress><tt:MTU>1500</tt:MTU></tt:Info>"
                        + "<tt:IPv4><tt:Enabled>true</tt:Enabled><tt:Config><tt:FromDHCP><tt:Address>" + ip + "</tt:Address><tt:PrefixLength>24</tt:PrefixLength></tt:FromDHCP>"
                        + "<tt:DHCP>true</tt:DHCP></tt:Config></tt:IPv4></tds:NetworkInterfaces></tds:GetNetworkInterfacesResponse>";
            case "GetNetworkProtocols":
                return "<tds:GetNetworkProtocolsResponse><tds:NetworkProtocols><tt:Name>HTTP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>" + cfg.portaHttp + "</tt:Port></tds:NetworkProtocols>"
                        + "<tds:NetworkProtocols><tt:Name>RTSP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>" + cfg.portaRtsp + "</tt:Port></tds:NetworkProtocols>"
                        + "<tds:NetworkProtocols><tt:Name>HTTPS</tt:Name><tt:Enabled>false</tt:Enabled><tt:Port>443</tt:Port></tds:NetworkProtocols></tds:GetNetworkProtocolsResponse>";
            case "GetDNS":
                return "<tds:GetDNSResponse><tds:DNSInformation><tt:FromDHCP>true</tt:FromDHCP></tds:DNSInformation></tds:GetDNSResponse>";
            case "GetNTP":
                return "<tds:GetNTPResponse><tds:NTPInformation><tt:FromDHCP>false</tt:FromDHCP><tt:NTPManual><tt:Type>DNS</tt:Type><tt:DNSname>pool.ntp.org</tt:DNSname></tt:NTPManual></tds:NTPInformation></tds:GetNTPResponse>";
            case "GetNetworkDefaultGateway":
                return "<tds:GetNetworkDefaultGatewayResponse><tds:NetworkGateway/></tds:GetNetworkDefaultGatewayResponse>";
            case "GetDiscoveryMode":
                return "<tds:GetDiscoveryModeResponse><tds:DiscoveryMode>" + (cfg.descoberta ? "Discoverable" : "NonDiscoverable") + "</tds:DiscoveryMode></tds:GetDiscoveryModeResponse>";
            case "GetUsers":
                return "<tds:GetUsersResponse><tds:User><tt:Username>" + esc(cfg.usuario) + "</tt:Username><tt:UserLevel>Administrator</tt:UserLevel></tds:User></tds:GetUsersResponse>";
            case "GetRelayOutputs":
                return "<tds:GetRelayOutputsResponse/>";
            case "GetEndpointReference":
                return "<tds:GetEndpointReferenceResponse><tds:GUID>urn:uuid:" + cfg.uuid + "</tds:GUID></tds:GetEndpointReferenceResponse>";
            case "GetWsdlUrl":
                return "<tds:GetWsdlUrlResponse><tds:WsdlUrl>http://www.onvif.org/</tds:WsdlUrl></tds:GetWsdlUrlResponse>";
            case "SystemReboot":
                new Thread(() -> {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {
                    }
                    fonte.reiniciar();
                }).start();
                return "<tds:SystemRebootResponse><tds:Message>Reiniciando</tds:Message></tds:SystemRebootResponse>";
            case "SetNTP":
            case "SetDNS":
            case "SetNetworkDefaultGateway":
            case "SetDiscoveryMode":
            case "SetScopes":
                return "<tds:" + nome + "Response/>";
            default:
                return null;
        }
    }

    // ================================================================== Media (ver10)

    private List<Integer> perfis() {
        List<Integer> l = new ArrayList<>();
        l.add(0);
        if (subAtivo && streams[1] != null && streams[1].pronto()) l.add(1);
        return l;
    }

    private int indice(String token) {
        return token != null && token.endsWith("2") ? 1 : 0;
    }

    private String fonteVideo() {
        MediaStream m = streams[0];
        return "<tt:Name>VideoSourceConfig</tt:Name><tt:UseCount>" + perfis().size() + "</tt:UseCount><tt:SourceToken>VideoSource_1</tt:SourceToken>"
                + "<tt:Bounds x=\"0\" y=\"0\" width=\"" + m.largura + "\" height=\"" + m.altura + "\"/>";
    }

    private String encoder1(int i) {
        MediaStream m = streams[i];
        return "<tt:Name>VideoEncoder_" + (i + 1) + "</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>H264</tt:Encoding>"
                + "<tt:Resolution><tt:Width>" + m.largura + "</tt:Width><tt:Height>" + m.altura + "</tt:Height></tt:Resolution><tt:Quality>4</tt:Quality>"
                + "<tt:RateControl><tt:FrameRateLimit>" + m.fps + "</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>" + m.bitrateKbps + "</tt:BitrateLimit></tt:RateControl>"
                + "<tt:H264><tt:GovLength>" + (m.fps * 2) + "</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>"
                + multicast() + "<tt:SessionTimeout>PT60S</tt:SessionTimeout>";
    }

    private static String multicast() {
        return "<tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address><tt:Port>0</tt:Port><tt:TTL>0</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast>";
    }

    private String audio1(boolean resposta) {
        MediaStream m = streams[0];
        return "<tt:Name>AudioEncoder_1</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>AAC</tt:Encoding><tt:Bitrate>64</tt:Bitrate>"
                + "<tt:SampleRate>" + (m.taxaAudio / 1000) + "</tt:SampleRate>" + multicast() + "<tt:SessionTimeout>PT60S</tt:SessionTimeout>";
    }

    private String perfil1(int i, String tag) {
        MediaStream m = streams[i];
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(tag).append(" token=\"Profile_").append(i + 1).append("\" fixed=\"true\"><tt:Name>").append(i == 0 ? "MainStream" : "SubStream").append("</tt:Name>");
        sb.append("<tt:VideoSourceConfiguration token=\"VideoSourceConfig_1\">").append(fonteVideo()).append("</tt:VideoSourceConfiguration>");
        if (m.temAudio) sb.append("<tt:AudioSourceConfiguration token=\"AudioSourceConfig_1\"><tt:Name>AudioSourceConfig</tt:Name><tt:UseCount>1</tt:UseCount><tt:SourceToken>AudioSource_1</tt:SourceToken></tt:AudioSourceConfiguration>");
        sb.append("<tt:VideoEncoderConfiguration token=\"VideoEncoder_").append(i + 1).append("\">").append(encoder1(i)).append("</tt:VideoEncoderConfiguration>");
        if (m.temAudio) sb.append("<tt:AudioEncoderConfiguration token=\"AudioEncoder_1\">").append(audio1(true)).append("</tt:AudioEncoderConfiguration>");
        sb.append("</").append(tag).append(">");
        return sb.toString();
    }

    private String media(String nome, Element a, String ip) {
        switch (nome) {
            case "GetServiceCapabilities":
                return "<trt:GetServiceCapabilitiesResponse><trt:Capabilities SnapshotUri=\"true\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\">"
                        + "<trt:ProfileCapabilities MaximumNumberOfProfiles=\"2\"/><trt:StreamingCapabilities RTPMulticast=\"false\" RTP_TCP=\"true\" RTP_RTSP_TCP=\"true\" NonAggregateControl=\"false\"/>"
                        + "</trt:Capabilities></trt:GetServiceCapabilitiesResponse>";
            case "GetProfiles": {
                StringBuilder sb = new StringBuilder("<trt:GetProfilesResponse>");
                for (int i : perfis()) sb.append(perfil1(i, "trt:Profiles"));
                return sb.append("</trt:GetProfilesResponse>").toString();
            }
            case "GetProfile":
                return "<trt:GetProfileResponse>" + perfil1(indice(texto(a, "ProfileToken")), "trt:Profile") + "</trt:GetProfileResponse>";
            case "GetStreamUri": {
                int i = indice(texto(a, "ProfileToken"));
                return "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>" + esc(uri(i, ip)) + "</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>"
                        + "<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>";
            }
            case "GetSnapshotUri":
                return "<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>http://" + ip + ":" + cfg.portaHttp + "/snapshot.jpg</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>"
                        + "<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetSnapshotUriResponse>";
            case "GetVideoSources": {
                MediaStream m = streams[0];
                return "<trt:GetVideoSourcesResponse><trt:VideoSources token=\"VideoSource_1\"><tt:Framerate>" + m.fps + "</tt:Framerate>"
                        + "<tt:Resolution><tt:Width>" + m.largura + "</tt:Width><tt:Height>" + m.altura + "</tt:Height></tt:Resolution></trt:VideoSources></trt:GetVideoSourcesResponse>";
            }
            case "GetVideoSourceConfigurations":
            case "GetCompatibleVideoSourceConfigurations":
                return "<trt:" + nome + "Response><trt:Configurations token=\"VideoSourceConfig_1\">" + fonteVideo() + "</trt:Configurations></trt:" + nome + "Response>";
            case "GetVideoSourceConfiguration":
                return "<trt:GetVideoSourceConfigurationResponse><trt:Configuration token=\"VideoSourceConfig_1\">" + fonteVideo() + "</trt:Configuration></trt:GetVideoSourceConfigurationResponse>";
            case "GetVideoEncoderConfigurations":
            case "GetCompatibleVideoEncoderConfigurations": {
                StringBuilder sb = new StringBuilder("<trt:" + nome + "Response>");
                for (int i : perfis()) sb.append("<trt:Configurations token=\"VideoEncoder_").append(i + 1).append("\">").append(encoder1(i)).append("</trt:Configurations>");
                return sb.append("</trt:").append(nome).append("Response>").toString();
            }
            case "GetVideoEncoderConfiguration": {
                int i = indice(texto(a, "ConfigurationToken"));
                return "<trt:GetVideoEncoderConfigurationResponse><trt:Configuration token=\"VideoEncoder_" + (i + 1) + "\">" + encoder1(i)
                        + "</trt:Configuration></trt:GetVideoEncoderConfigurationResponse>";
            }
            case "GetVideoEncoderConfigurationOptions":
                return "<trt:GetVideoEncoderConfigurationOptionsResponse><trt:Options><tt:QualityRange><tt:Min>1</tt:Min><tt:Max>6</tt:Max></tt:QualityRange><tt:H264>"
                        + "<tt:ResolutionsAvailable><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:ResolutionsAvailable>"
                        + "<tt:ResolutionsAvailable><tt:Width>1280</tt:Width><tt:Height>720</tt:Height></tt:ResolutionsAvailable>"
                        + "<tt:ResolutionsAvailable><tt:Width>960</tt:Width><tt:Height>540</tt:Height></tt:ResolutionsAvailable>"
                        + "<tt:ResolutionsAvailable><tt:Width>640</tt:Width><tt:Height>360</tt:Height></tt:ResolutionsAvailable>"
                        + "<tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>120</tt:Max></tt:GovLengthRange><tt:FrameRateRange><tt:Min>1</tt:Min><tt:Max>30</tt:Max></tt:FrameRateRange>"
                        + "<tt:EncodingIntervalRange><tt:Min>1</tt:Min><tt:Max>1</tt:Max></tt:EncodingIntervalRange><tt:H264ProfilesSupported>Main</tt:H264ProfilesSupported>"
                        + "</tt:H264></trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>";
            case "SetVideoEncoderConfiguration": {
                Element c = primeiro(a, "Configuration");
                if (c != null) {
                    int i = indice(c.getAttribute("token"));
                    int w = num(texto(c, "Width")), h = num(texto(c, "Height")), fps = num(texto(c, "FrameRateLimit")), br = num(texto(c, "BitrateLimit"));
                    if (w > 0 && h > 0) fonte.definirEncoder(i, w, h, fps, br);
                }
                return "<trt:SetVideoEncoderConfigurationResponse/>";
            }
            case "GetAudioSources":
                return streams[0].temAudio
                        ? "<trt:GetAudioSourcesResponse><trt:AudioSources token=\"AudioSource_1\"><tt:Channels>1</tt:Channels></trt:AudioSources></trt:GetAudioSourcesResponse>"
                        : "<trt:GetAudioSourcesResponse/>";
            case "GetAudioEncoderConfigurations":
            case "GetCompatibleAudioEncoderConfigurations":
                return streams[0].temAudio
                        ? "<trt:" + nome + "Response><trt:Configurations token=\"AudioEncoder_1\">" + audio1(true) + "</trt:Configurations></trt:" + nome + "Response>"
                        : "<trt:" + nome + "Response/>";
            case "GetAudioSourceConfigurations":
            case "GetCompatibleAudioSourceConfigurations":
                return streams[0].temAudio
                        ? "<trt:" + nome + "Response><trt:Configurations token=\"AudioSourceConfig_1\"><tt:Name>AudioSourceConfig</tt:Name><tt:UseCount>1</tt:UseCount><tt:SourceToken>AudioSource_1</tt:SourceToken></trt:Configurations></trt:" + nome + "Response>"
                        : "<trt:" + nome + "Response/>";
            case "GetGuaranteedNumberOfVideoEncoderInstances":
                return "<trt:GetGuaranteedNumberOfVideoEncoderInstancesResponse><trt:TotalNumber>2</trt:TotalNumber><trt:H264>2</trt:H264></trt:GetGuaranteedNumberOfVideoEncoderInstancesResponse>";
            case "GetOSDs":
            case "GetMetadataConfigurations":
            case "GetVideoAnalyticsConfigurations":
            case "GetAudioOutputs":
            case "GetAudioOutputConfigurations":
                return "<trt:" + nome + "Response/>";
            case "AddVideoEncoderConfiguration":
            case "AddVideoSourceConfiguration":
            case "AddAudioEncoderConfiguration":
            case "AddAudioSourceConfiguration":
            case "SetVideoSourceConfiguration":
            case "SetAudioEncoderConfiguration":
            case "StartMulticastStreaming":
            case "StopMulticastStreaming":
            case "SetSynchronizationPoint":
                return "<trt:" + nome + "Response/>";
            default:
                return null;
        }
    }

    // ================================================================== Media2 (ver20) — informa H.265 corretamente

    private String encoder2(int i) {
        MediaStream m = streams[i];
        String perfil = "H265".equals(m.codec) ? "Main" : "Main";
        return "<tr2:VideoEncoder token=\"VideoEncoder_" + (i + 1) + "\" GovLength=\"" + (m.fps * 2) + "\" Profile=\"" + perfil + "\">"
                + "<tt:Name>VideoEncoder_" + (i + 1) + "</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>" + m.codec + "</tt:Encoding>"
                + "<tt:Resolution><tt:Width>" + m.largura + "</tt:Width><tt:Height>" + m.altura + "</tt:Height></tt:Resolution>"
                + "<tt:RateControl ConstantBitRate=\"false\"><tt:FrameRateLimit>" + m.fps + "</tt:FrameRateLimit><tt:BitrateLimit>" + m.bitrateKbps + "</tt:BitrateLimit></tt:RateControl>"
                + multicast() + "<tt:Quality>4</tt:Quality></tr2:VideoEncoder>";
    }

    private String perfil2(int i) {
        MediaStream m = streams[i];
        StringBuilder sb = new StringBuilder();
        sb.append("<tr2:Profiles token=\"Profile_").append(i + 1).append("\" fixed=\"true\"><tr2:Name>").append(i == 0 ? "MainStream" : "SubStream").append("</tr2:Name><tr2:Configurations>");
        sb.append("<tr2:VideoSource token=\"VideoSourceConfig_1\">").append(fonteVideo()).append("</tr2:VideoSource>");
        if (m.temAudio) sb.append("<tr2:AudioSource token=\"AudioSourceConfig_1\"><tt:Name>AudioSourceConfig</tt:Name><tt:UseCount>1</tt:UseCount><tt:SourceToken>AudioSource_1</tt:SourceToken></tr2:AudioSource>");
        sb.append(encoder2(i).replace("<tr2:VideoEncoder ", "<tr2:VideoEncoder "));
        if (m.temAudio) sb.append("<tr2:AudioEncoder token=\"AudioEncoder_1\"><tt:Name>AudioEncoder_1</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>MP4A-LATM</tt:Encoding>")
                .append(multicast()).append("<tt:Bitrate>64</tt:Bitrate><tt:SampleRate>").append(m.taxaAudio).append("</tt:SampleRate></tr2:AudioEncoder>");
        return sb.append("</tr2:Configurations></tr2:Profiles>").toString();
    }

    private String media2(String nome, Element a, String ip) {
        switch (nome) {
            case "GetServiceCapabilities":
                return "<tr2:GetServiceCapabilitiesResponse><tr2:Capabilities SnapshotUri=\"true\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\">"
                        + "<tr2:ProfileCapabilities MaximumNumberOfProfiles=\"2\" ConfigurationsSupported=\"VideoSource VideoEncoder AudioSource AudioEncoder\"/>"
                        + "<tr2:StreamingCapabilities RTSPStreaming=\"true\" RTPMulticast=\"false\" RTP_RTSP_TCP=\"true\" NonAggregateControl=\"false\"/>"
                        + "</tr2:Capabilities></tr2:GetServiceCapabilitiesResponse>";
            case "GetProfiles": {
                StringBuilder sb = new StringBuilder("<tr2:GetProfilesResponse>");
                String tok = texto(a, "Token");
                for (int i : perfis()) if (tok == null || tok.isEmpty() || indice(tok) == i) sb.append(perfil2(i));
                return sb.append("</tr2:GetProfilesResponse>").toString();
            }
            case "GetStreamUri":
                return "<tr2:GetStreamUriResponse><tr2:Uri>" + esc(uri(indice(texto(a, "ProfileToken")), ip)) + "</tr2:Uri></tr2:GetStreamUriResponse>";
            case "GetSnapshotUri":
                return "<tr2:GetSnapshotUriResponse><tr2:Uri>http://" + ip + ":" + cfg.portaHttp + "/snapshot.jpg</tr2:Uri></tr2:GetSnapshotUriResponse>";
            case "GetVideoSourceConfigurations":
                return "<tr2:GetVideoSourceConfigurationsResponse><tr2:Configurations token=\"VideoSourceConfig_1\">" + fonteVideo() + "</tr2:Configurations></tr2:GetVideoSourceConfigurationsResponse>";
            case "GetVideoEncoderConfigurations": {
                StringBuilder sb = new StringBuilder("<tr2:GetVideoEncoderConfigurationsResponse>");
                for (int i : perfis()) sb.append(encoder2(i).replace("<tr2:VideoEncoder ", "<tr2:Configurations ").replace("</tr2:VideoEncoder>", "</tr2:Configurations>"));
                return sb.append("</tr2:GetVideoEncoderConfigurationsResponse>").toString();
            }
            case "SetVideoEncoderConfiguration": {
                Element c = primeiro(a, "Configuration");
                if (c != null) {
                    int i = indice(c.getAttribute("token"));
                    int w = num(texto(c, "Width")), h = num(texto(c, "Height")), fps = num(texto(c, "FrameRateLimit")), br = num(texto(c, "BitrateLimit"));
                    if (w > 0 && h > 0) fonte.definirEncoder(i, w, h, fps, br);
                }
                return "<tr2:SetVideoEncoderConfigurationResponse/>";
            }
            case "GetOSDs":
            case "GetMetadataConfigurations":
            case "GetAudioOutputConfigurations":
                return "<tr2:" + nome + "Response/>";
            default:
                return null;
        }
    }

    private String uri(int i, String ip) {
        String c = i == 1 ? cfg.caminhoSub : cfg.caminhoPrincipal;
        String cred = cfg.credenciaisNaUrl && cfg.exigirSenha ? enc(cfg.usuario) + ":" + enc(cfg.senha) + "@" : "";
        return "rtsp://" + cred + ip + ":" + cfg.portaRtsp + "/" + c;
    }

    // ================================================================== página web

    private String pagina(String ip) {
        String ipx = ip(ip);
        return "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(cfg.nome) + "</title><style>body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:20px}"
                + "h1{margin:0 0 4px;font-size:22px}p{color:#94a3b8;margin:0 0 16px}img{width:100%;max-width:960px;border-radius:14px;background:#000;display:block}"
                + "code{display:block;background:#1e293b;padding:10px 12px;border-radius:10px;margin:6px 0;word-break:break-all;color:#5eead4}</style></head><body>"
                + "<h1>📷 " + esc(cfg.nome) + "</h1><p>Imagem atualizada a cada segundo</p><img id=\"i\" src=\"/snapshot.jpg\">"
                + "<h3>Endereços</h3><code>rtsp://" + ipx + ":" + cfg.portaRtsp + "/" + cfg.caminhoPrincipal + "</code>"
                + (subAtivo ? "<code>rtsp://" + ipx + ":" + cfg.portaRtsp + "/" + cfg.caminhoSub + "</code>" : "")
                + "<code>http://" + ipx + ":" + cfg.portaHttp + "/onvif/device_service</code>"
                + "<script>setInterval(function(){var n=new Image();n.onload=function(){document.getElementById('i').src=n.src};n.src='/snapshot.jpg?'+Date.now()},1000)</script>"
                + "</body></html>";
    }

    // ================================================================== utilitários

    private static Element primeiro(Element raiz, String local) {
        if (raiz == null) return null;
        NodeList l = raiz.getElementsByTagNameNS("*", local);
        return l.getLength() > 0 ? (Element) l.item(0) : null;
    }

    private static String texto(Element raiz, String local) {
        Element e = primeiro(raiz, local);
        return e == null ? null : e.getTextContent().trim();
    }

    private static int num(String s) {
        try {
            return s == null ? 0 : (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String url(String s) {
        return s.replace(" ", "%20");
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String tz() {
        int off = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        int h = Math.abs(off) / 60, m = Math.abs(off) % 60;
        // formato POSIX: o sinal é invertido (UTC-3 = "<-03>3")
        String nome = String.format(Locale.US, "<%s%02d%s>", off < 0 ? "-" : "+", h, m > 0 ? String.format(Locale.US, "%02d", m) : "");
        return nome + (off < 0 ? "" : "-") + h + (m > 0 ? ":" + m : "");
    }

    private static String dataHora(boolean utc) {
        Calendar c = Calendar.getInstance(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
        return "<tt:Time><tt:Hour>" + c.get(Calendar.HOUR_OF_DAY) + "</tt:Hour><tt:Minute>" + c.get(Calendar.MINUTE) + "</tt:Minute><tt:Second>" + c.get(Calendar.SECOND)
                + "</tt:Second></tt:Time><tt:Date><tt:Year>" + c.get(Calendar.YEAR) + "</tt:Year><tt:Month>" + (c.get(Calendar.MONTH) + 1)
                + "</tt:Month><tt:Day>" + c.get(Calendar.DAY_OF_MONTH) + "</tt:Day></tt:Date>";
    }

    static String agoraIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
