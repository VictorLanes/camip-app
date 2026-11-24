package com.victorlanes.camip;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Range;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;

import com.victorlanes.camip.core.CamConfig;
import com.victorlanes.camip.core.CoreLog;
import com.victorlanes.camip.core.Discovery;
import com.victorlanes.camip.core.HttpServer;
import com.victorlanes.camip.core.MediaStream;
import com.victorlanes.camip.core.Onvif;
import com.victorlanes.camip.core.RtspServer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Serviço em primeiro plano que mantém a câmera transmitindo mesmo com a tela desligada:
 * câmera → OpenGL → encoders → RTSP, mais ONVIF, snapshot e descoberta.
 */
public class CamService extends Service implements Onvif.Fonte {

    public static final String ACAO_INICIAR = "com.victorlanes.camip.INICIAR";
    public static final String ACAO_PARAR = "com.victorlanes.camip.PARAR";
    private static final String CANAL = "camera";

    public static volatile CamService inst;
    public static volatile String estado = "parada"; // parada | iniciando | transmitindo | erro
    public static volatile String erro = "";
    private static final Deque<String> LOG = new ArrayDeque<>();

    // rede
    private CamConfig cfg;
    private MediaStream principal, sub;
    private RtspServer rtsp;
    private HttpServer http;
    private Onvif onvif;
    private Discovery descoberta;

    // vídeo
    private GlRender gl;
    private VideoEncoder encPrincipal, encSub;
    private AudioEncoder audio;
    private CameraDevice camera;
    private CameraCaptureSession sessao;
    private CaptureRequest.Builder req;
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraCharacteristics carac;
    private Surface preview;
    private int prevW, prevH;
    public volatile int saidaW, saidaH;
    private volatile int orientacaoDispositivo;
    private OrientationEventListener orientacao;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private WifiManager.MulticastLock multicast;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long inicioEm;
    private long ultimosQuadros;
    private long semQuadrosDesde;
    private int falhasSeguidas;
    private boolean rodando;
    private final Runnable vigia = this::vigiar;
    private final Runnable reconfigurarAgora = this::reconfigurarJa;

    public static void log(String m) {
        String l = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + m;
        synchronized (LOG) {
            LOG.addLast(l);
            while (LOG.size() > 150) LOG.removeFirst();
        }
        android.util.Log.i("CamIP", m);
    }

    public static String logTexto() {
        synchronized (LOG) {
            StringBuilder sb = new StringBuilder();
            for (String s : LOG) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    public static void iniciar(Context c) {
        Intent i = new Intent(c, CamService.class).setAction(ACAO_INICIAR);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void parar(Context c) {
        c.startService(new Intent(c, CamService.class).setAction(ACAO_PARAR));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        CoreLog.setSink(CamService::log);
        orientacao = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o != ORIENTATION_UNKNOWN) orientacaoDispositivo = ((o + 45) / 90 * 90) % 360;
            }
        };
        if (orientacao.canDetectOrientation()) orientacao.enable();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : ACAO_INICIAR;
        if (ACAO_PARAR.equals(a)) {
            pararTudo();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            entrarPrimeiroPlano();
        } catch (Exception e) {
            // Android 14+: a câmera só pode ser ligada com o app aberto na tela
            log("O Android não deixou ligar a câmera em segundo plano: abra o app e toque em Iniciar");
            estado = "parada";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!rodando) iniciarTudo();
        return START_STICKY;
    }

    // ================================================================== primeiro plano

    private void entrarPrimeiroPlano() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Câmera transmitindo", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = notificacao();
        if (Build.VERSION.SDK_INT >= 29) {
            int tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (CamPrefs.audio(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(1, n, tipo);
        } else startForeground(1, n);
    }

    private Notification notificacao() {
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent parar = PendingIntent.getService(this, 1, new Intent(this, CamService.class).setAction(ACAO_PARAR), PendingIntent.FLAG_IMMUTABLE);
        String txt = cfg == null ? "Iniciando…" : "rtsp://" + cfg.ip + ":" + cfg.portaRtsp + "/stream1 · " + clientes() + " cliente(s)";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_notificacao)
                .setContentTitle("CamIP transmitindo")
                .setContentText(txt)
                .setOngoing(true)
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(null, "Parar", parar).build())
                .build();
    }

    private void atualizarNotificacao() {
        if (!rodando) return;
        getSystemService(NotificationManager.class).notify(1, notificacao());
    }

    // ================================================================== ciclo de vida

    @SuppressLint("WakelockTimeout")
    private void iniciarTudo() {
        rodando = true;
        estado = "iniciando";
        erro = "";
        inicioEm = System.currentTimeMillis();
        CamPrefs.inicializar(this);
        cfg = CamPrefs.config(this);
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamIP:transmissao");
        wake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CamIP:wifi");
        wifi.acquire();
        multicast = wm.createMulticastLock("CamIP:descoberta");
        multicast.setReferenceCounted(false);
        multicast.acquire();

        principal = new MediaStream("principal");
        sub = new MediaStream("extra");
        try {
            iniciarServidores();
        } catch (Exception e) {
            falhar("Não foi possível abrir as portas " + cfg.portaRtsp + "/" + cfg.portaHttp + ": " + e.getMessage());
            return;
        }
        iniciarVideo();
        main.postDelayed(vigia, 3000);
    }

    private void iniciarServidores() throws Exception {
        rtsp = new RtspServer(cfg, principal, sub);
        rtsp.subAtivo = CamPrefs.sub(this);
        rtsp.iniciar();
        onvif = new Onvif(cfg, principal, sub, this);
        onvif.subAtivo = CamPrefs.sub(this);
        http = new HttpServer(cfg.portaHttp, onvif);
        http.iniciar();
        if (cfg.descoberta) {
            try {
                descoberta = new Discovery(cfg);
                descoberta.iniciar();
            } catch (Exception e) {
                log("Descoberta ONVIF indisponível: " + e.getMessage());
                descoberta = null;
            }
        }
    }

    private void pararServidores() {
        if (descoberta != null) descoberta.parar();
        if (http != null) http.parar();
        if (rtsp != null) rtsp.parar();
        descoberta = null;
        http = null;
        rtsp = null;
    }

    private void pararTudo() {
        rodando = false;
        main.removeCallbacks(vigia);
        main.removeCallbacks(reconfigurarAgora);
        pararVideo();
        pararServidores();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (multicast != null && multicast.isHeld()) multicast.release();
        estado = "parada";
        log("Câmera parada");
    }

    @Override
    public void onDestroy() {
        if (rodando) pararTudo();
        if (orientacao != null) orientacao.disable();
        inst = null;
        super.onDestroy();
    }

    private void falhar(String msg) {
        log("ERRO: " + msg);
        erro = msg;
        estado = "erro";
    }

    // ================================================================== vídeo

    private void iniciarVideo() {
        inst = this;
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String id = escolherCamera(cm, "frontal".equals(CamPrefs.camera(this)));
            if (id == null) {
                falhar("Nenhuma câmera encontrada");
                return;
            }
            carac = cm.getCameraCharacteristics(id);
            boolean frontal = carac.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
            int sensor = carac.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rot;
            String r = CamPrefs.rotacao(this);
            if ("auto".equals(r)) {
                int dev = orientacaoDispositivo;
                rot = frontal ? (sensor - dev + 360) % 360 : (sensor + dev) % 360;
            } else {
                int dev = Integer.parseInt(r); // posição do celular escolhida pelo usuário
                rot = frontal ? (sensor - dev + 360) % 360 : (sensor + dev) % 360;
            }
            boolean deitado = rot % 180 != 0;
            int w = CamPrefs.largura(this), h = CamPrefs.altura(this);
            saidaW = deitado ? h : w;
            saidaH = deitado ? w : h;
            int fps = CamPrefs.fps(this);
            boolean h265 = "H265".equals(CamPrefs.codec(this)) && VideoEncoder.suportaH265();

            Size tam = tamanhoCamera(carac, w, h);
            gl = new GlRender();
            gl.configurar(rot, CamPrefs.espelhar(this), CamPrefs.osd(this), CamPrefs.osdNome(this) ? cfg.nome : "");
            final int fpsF = fps;
            gl.iniciar(tam.getWidth(), tam.getHeight(), surface -> main.post(() -> {
                try {
                    encPrincipal = new VideoEncoder(principal);
                    Surface s1 = encPrincipal.iniciar(saidaW, saidaH, fpsF, CamPrefs.bitrate(this), h265);
                    gl.adicionarSaida(s1, encPrincipal.largura(), encPrincipal.altura(), fpsF, true, true);
                    if (CamPrefs.sub(this)) {
                        int sw = CamPrefs.subLargura(this), sh = CamPrefs.subAltura(this);
                        encSub = new VideoEncoder(sub);
                        Surface s2 = encSub.iniciar(deitado ? sh : sw, deitado ? sw : sh, CamPrefs.subFps(this), CamPrefs.subBitrate(this), h265);
                        gl.adicionarSaida(s2, encSub.largura(), encSub.altura(), CamPrefs.subFps(this), true, false);
                    }
                    if (preview != null) gl.adicionarSaida(preview, prevW, prevH, 30, false, false);
                    abrirCamera(cm, id, surface, fpsF);
                    if (CamPrefs.audio(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            audio = new AudioEncoder(principal, sub);
                            audio.iniciar();
                        } catch (Exception e) {
                            log("Microfone indisponível: " + e.getMessage());
                            audio = null;
                        }
                    }
                } catch (Exception e) {
                    falhar("Encoder: " + e.getMessage());
                }
            }));
            log("Câmera " + (frontal ? "frontal" : "traseira") + " " + tam.getWidth() + "x" + tam.getHeight() + " → " + saidaW + "x" + saidaH + " (rotação " + rot + "°)");
        } catch (Exception e) {
            falhar("Câmera: " + e.getMessage());
        }
    }

    private static String escolherCamera(CameraManager cm, boolean frontal) throws Exception {
        String reserva = null;
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            if (frontal ? f == CameraCharacteristics.LENS_FACING_FRONT : f == CameraCharacteristics.LENS_FACING_BACK) return id;
            if (reserva == null) reserva = id;
        }
        return reserva;
    }

    /** tamanho do buffer da câmera: mesma proporção da saída e pelo menos do tamanho dela */
    private static Size tamanhoCamera(CameraCharacteristics c, int w, int h) {
        StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] ts = m.getOutputSizes(SurfaceTexture.class);
        int lado = Math.max(w, h), menor = Math.min(w, h);
        float alvo = (float) lado / menor;
        Size melhor = null;
        double melhorNota = Double.MAX_VALUE;
        for (Size s : ts) {
            int sl = Math.max(s.getWidth(), s.getHeight()), sm = Math.min(s.getWidth(), s.getHeight());
            double difProp = Math.abs((double) sl / sm - alvo);
            double nota = difProp * 10;
            if (sl < lado || sm < menor) nota += 5 + (lado - sl) / 100.0;
            else nota += (double) (sl * sm) / (lado * menor) - 1; // prefere o menor que cobre
            if (nota < melhorNota) {
                melhorNota = nota;
                melhor = s;
            }
        }
        return melhor != null ? melhor : new Size(1280, 720);
    }

    @SuppressLint("MissingPermission")
    private void abrirCamera(CameraManager cm, String id, Surface surface, int fps) throws Exception {
        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cd) {
                camera = cd;
                try {
                    req = cd.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    req.addTarget(surface);
                    Range<Integer> faixa = faixaFps(fps);
                    if (faixa != null) req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, faixa);
                    int[] af = carac.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                    if (af != null) for (int x : af) if (x == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) req.set(CaptureRequest.CONTROL_AF_MODE, x);
                    aplicarControles();
                    cd.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            sessao = s;
                            try {
                                s.setRepeatingRequest(req.build(), null, camHandler);
                                estado = "transmitindo";
                                falhasSeguidas = 0;
                                log("Transmitindo em " + cfg.rtspUrl("stream1", false));
                                main.post(CamService.this::atualizarNotificacao);
                            } catch (Exception e) {
                                falhar("Câmera: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            falhar("A câmera recusou a configuração");
                            agendarReinicio();
                        }
                    }, camHandler);
                } catch (Exception e) {
                    falhar("Câmera: " + e.getMessage());
                }
            }

            @Override
            public void onDisconnected(CameraDevice cd) {
                log("Câmera desconectada (outro app pode estar usando)");
                cd.close();
                camera = null;
                agendarReinicio();
            }

            @Override
            public void onError(CameraDevice cd, int e) {
                log("Erro da câmera: " + e);
                cd.close();
                camera = null;
                agendarReinicio();
            }
        }, camHandler);
    }

    private Range<Integer> faixaFps(int fps) {
        Range<Integer>[] rs = carac.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (rs == null) return null;
        Range<Integer> melhor = null;
        for (Range<Integer> r : rs) {
            if (r.getUpper() < Math.min(fps, 30)) continue;
            if (melhor == null || Math.abs(r.getUpper() - fps) < Math.abs(melhor.getUpper() - fps)
                    || (r.getUpper().equals(melhor.getUpper()) && r.getLower() > melhor.getLower())) melhor = r;
        }
        return melhor;
    }

    /** lanterna e zoom (podem mudar com a câmera ligada) */
    private void aplicarControles() {
        if (req == null || carac == null) return;
        boolean flash = Boolean.TRUE.equals(carac.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        if (flash) req.set(CaptureRequest.FLASH_MODE, CamPrefs.lanterna(this) ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        float z = Math.max(1f, Math.min(CamPrefs.zoom(this), zoomMaximo()));
        if (Build.VERSION.SDK_INT >= 30 && carac.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            req.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
        } else {
            Rect a = carac.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (a != null) {
                int cw = (int) (a.width() / z), ch = (int) (a.height() / z);
                req.set(CaptureRequest.SCALER_CROP_REGION, new Rect((a.width() - cw) / 2, (a.height() - ch) / 2, (a.width() + cw) / 2, (a.height() + ch) / 2));
            }
        }
    }

    public float zoomMaximo() {
        if (carac == null) return 1f;
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = carac.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) return Math.min(r.getUpper(), 10f);
        }
        Float m = carac.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        return m == null ? 1f : Math.min(m, 10f);
    }

    public boolean temLanterna() {
        return carac != null && Boolean.TRUE.equals(carac.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
    }

    public void atualizarControles() {
        if (camHandler == null) return;
        camHandler.post(() -> {
            try {
                aplicarControles();
                if (sessao != null) sessao.setRepeatingRequest(req.build(), null, camHandler);
            } catch (Exception ignored) {
            }
        });
    }

    private void pararVideo() {
        try {
            if (sessao != null) sessao.close();
        } catch (Exception ignored) {
        }
        sessao = null;
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        camera = null;
        if (camThread != null) camThread.quitSafely();
        camThread = null;
        camHandler = null;
        if (audio != null) audio.parar();
        audio = null;
        if (gl != null) gl.parar();
        gl = null;
        if (encPrincipal != null) encPrincipal.parar();
        if (encSub != null) encSub.parar();
        encPrincipal = null;
        encSub = null;
    }

    private void agendarReinicio() {
        if (!rodando) return;
        falhasSeguidas++;
        long espera = Math.min(30_000, 2000L * falhasSeguidas);
        main.removeCallbacks(reconfigurarAgora);
        main.postDelayed(reconfigurarAgora, espera);
    }

    // ================================================================== vigia (nunca travar)

    private void vigiar() {
        if (!rodando) return;
        // IP mudou (reconectou no Wi-Fi)? atualiza endereços e descoberta
        String ip = Rede.ip();
        if (!ip.equals(cfg.ip)) {
            log("IP mudou: " + cfg.ip + " → " + ip);
            cfg.ip = ip;
            if (descoberta != null) {
                descoberta.parar();
                try {
                    descoberta = new Discovery(cfg);
                    descoberta.iniciar();
                } catch (Exception e) {
                    descoberta = null;
                }
            }
        }
        long q = gl != null ? gl.quadros : 0;
        if ("transmitindo".equals(estado)) {
            if (q == ultimosQuadros) {
                if (semQuadrosDesde == 0) semQuadrosDesde = System.currentTimeMillis();
                else if (System.currentTimeMillis() - semQuadrosDesde > 8000 && CamPrefs.autoReiniciar(this)) {
                    log("A câmera parou de mandar imagens: reiniciando o vídeo");
                    semQuadrosDesde = 0;
                    reconfigurarJa();
                }
            } else semQuadrosDesde = 0;
        } else if ("erro".equals(estado) && CamPrefs.autoReiniciar(this) && rtsp != null && camera == null) {
            agendarReinicio();
        }
        ultimosQuadros = q;
        atualizarNotificacao();
        main.postDelayed(vigia, 3000);
    }

    // ================================================================== API para a tela

    /** reaplica as configurações (com um pequeno atraso para juntar várias mudanças) */
    public void reconfigurar() {
        main.removeCallbacks(reconfigurarAgora);
        main.postDelayed(reconfigurarAgora, 700);
    }

    private void reconfigurarJa() {
        if (!rodando) return;
        CamConfig novo = CamPrefs.config(this);
        boolean servidores = novo.portaHttp != cfg.portaHttp || novo.portaRtsp != cfg.portaRtsp || novo.descoberta != cfg.descoberta;
        cfg.usuario = novo.usuario;
        cfg.senha = novo.senha;
        cfg.exigirSenha = novo.exigirSenha;
        cfg.credenciaisNaUrl = novo.credenciaisNaUrl;
        cfg.nome = novo.nome;
        pararVideo();
        if (servidores) {
            pararServidores();
            cfg = novo;
            principal = new MediaStream("principal");
            sub = new MediaStream("extra");
            try {
                iniciarServidores();
            } catch (Exception e) {
                falhar("Não foi possível abrir as portas: " + e.getMessage());
                return;
            }
        }
        if (rtsp != null) rtsp.subAtivo = CamPrefs.sub(this);
        if (onvif != null) onvif.subAtivo = CamPrefs.sub(this);
        estado = "iniciando";
        iniciarVideo();
    }

    /** usuário, senha, nome e opções de acesso valem na hora, sem reiniciar a câmera */
    public void atualizarAcesso() {
        if (cfg == null) return;
        CamConfig novo = CamPrefs.config(this);
        cfg.usuario = novo.usuario;
        cfg.senha = novo.senha;
        cfg.exigirSenha = novo.exigirSenha;
        cfg.credenciaisNaUrl = novo.credenciaisNaUrl;
        cfg.nome = novo.nome;
    }

    public void definirPreview(Surface s, int w, int h) {
        if (preview != null && gl != null) gl.removerSaida(preview);
        preview = s;
        prevW = w;
        prevH = h;
        if (s != null && gl != null) gl.adicionarSaida(s, w, h, 30, false, false);
    }

    public int clientes() {
        return rtsp == null ? 0 : rtsp.clientes();
    }

    public CamConfig config() {
        return cfg;
    }

    public long ligadaHa() {
        return System.currentTimeMillis() - inicioEm;
    }

    public boolean audioAtivo() {
        return principal != null && principal.temAudio;
    }

    public String codecAtual() {
        return principal == null ? "" : principal.codec;
    }

    // ================================================================== Onvif.Fonte

    @Override
    public byte[] snapshot() {
        GlRender g = gl;
        return g == null ? null : g.snapshot();
    }

    @Override
    public void definirEncoder(int stream, int largura, int altura, int fps, int kbps) {
        boolean deitado = saidaW < saidaH;
        int w = deitado ? altura : largura, h = deitado ? largura : altura;
        CamPrefs.put(this, stream == 1 ? "subLargura" : "largura", w);
        CamPrefs.put(this, stream == 1 ? "subAltura" : "altura", h);
        if (fps > 0) CamPrefs.put(this, stream == 1 ? "subFps" : "fps", Math.min(60, fps));
        if (kbps > 0) CamPrefs.put(this, stream == 1 ? "subBitrate" : "bitrate", Math.min(50_000, kbps));
        log("O NVR mudou o stream " + (stream == 1 ? "extra" : "principal") + " para " + largura + "x" + altura);
        reconfigurar();
    }

    @Override
    public void reiniciar() {
        main.post(this::reconfigurarJa);
    }

    public static List<Size> resolucoes(Context c, boolean frontal) {
        try {
            CameraManager cm = c.getSystemService(CameraManager.class);
            String id = escolherCamera(cm, frontal);
            StreamConfigurationMap m = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<Size> l = new java.util.ArrayList<>();
            for (Size s : m.getOutputSizes(SurfaceTexture.class)) {
                if (s.getWidth() < 320 || s.getWidth() > 3840) continue;
                if (s.getWidth() % 16 != 0 && s.getHeight() % 16 != 0 && s.getHeight() != 1080) continue;
                l.add(s);
            }
            l.sort((a, b) -> b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight());
            return l;
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }
}
