package com.victorlanes.camip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.victorlanes.camip.core.CamConfig;

import java.util.ArrayList;
import java.util.List;

/** Tela do app: Câmera, Ajustes, Endereços e Sobre. */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 10;
    private Ui.Cores k;
    private FrameLayout raiz, conteudo;
    private LinearLayout nav;
    private String aba = "camera";
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView tSub, tResol, tAudio, tClientes, tIp, tTempo;
    private Button bTopo;
    private TextureView previa;
    private Surface previaSurface;
    private View telaPreta;
    private boolean estavaTransmitindo;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            atualizarStatus();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CamPrefs.inicializar(this);
        Ui.tema = CamPrefs.tema(this);
        k = Ui.cores(this);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = Ui.escuro(this) ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }
        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(k.fundo);
        conteudo = new FrameLayout(this);
        raiz.addView(conteudo, new FrameLayout.LayoutParams(-1, -1));
        montarNav();
        setContentView(raiz);
        Ui.aplicarInsets(raiz);
        mostrar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        soltarPrevia();
    }

    private boolean transmitindo() {
        return CamService.inst != null && !"parada".equals(CamService.estado);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ================================================================== navegação

    private void montarNav() {
        nav = Ui.linha(this);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable fundo = new GradientDrawable();
        fundo.setColor(k.superficie);
        fundo.setCornerRadius(dp(40));
        fundo.setStroke(dp(1), k.linha);
        nav.setBackground(fundo);
        nav.setElevation(dp(8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(14);
        raiz.addView(nav, lp);
        desenharNav();
    }

    private void desenharNav() {
        nav.removeAllViews();
        String[][] itens = {{"camera", "Câmera"}, {"ajustes", "Ajustes"}, {"links", "Endereços"}, {"sobre", "Sobre"}};
        int[] icones = {R.drawable.ic_camera, R.drawable.ic_ajustes, R.drawable.ic_link, R.drawable.ic_info};
        for (int i = 0; i < itens.length; i++) {
            final String id = itens[i][0];
            boolean on = id.equals(aba);
            LinearLayout it = Ui.linha(this);
            it.setGravity(Gravity.CENTER);
            it.setPadding(dp(on ? 18 : 16), dp(12), dp(on ? 22 : 16), dp(12));
            ImageView ic = new ImageView(this);
            ic.setImageResource(icones[i]);
            ic.setColorFilter(on ? 0xFFFFFFFF : k.texto);
            it.addView(ic, Ui.lp(dp(24), dp(24)));
            if (on) {
                TextView t = Ui.texto(this, itens[i][1], 15, 0xFFFFFFFF, true);
                t.setPadding(dp(8), 0, 0, 0);
                it.addView(t);
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, Ui.GRADIENTE);
                g.setCornerRadius(dp(30));
                it.setBackground(g);
            }
            it.setOnClickListener(v -> {
                aba = id;
                desenharNav();
                mostrar();
            });
            nav.addView(it);
        }
    }

    private void mostrar() {
        soltarPrevia();
        conteudo.removeAllViews();
        tSub = tResol = tAudio = tClientes = tIp = tTempo = null;
        bTopo = null;
        switch (aba) {
            case "ajustes": telaAjustes(); break;
            case "links": telaLinks(); break;
            case "sobre": telaSobre(); break;
            default: telaCamera();
        }
    }

    private LinearLayout pagina(String titulo, String subtitulo, boolean comBotao) {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout col = Ui.coluna(this);
        col.setPadding(dp(22), dp(16), dp(22), dp(110));
        sv.addView(col);
        conteudo.addView(sv);
        LinearLayout topo = Ui.linha(this);
        TextView t = Ui.texto(this, titulo, 34, k.texto, true);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        topo.addView(t, Ui.lp(0, -2, 1f));
        if (comBotao) {
            bTopo = Ui.botaoGradiente(this, "");
            bTopo.setTextSize(16);
            bTopo.setOnClickListener(v -> alternar());
            topo.addView(bTopo, Ui.lp(-2, dp(50)));
        }
        col.addView(topo);
        tSub = Ui.texto(this, subtitulo, 15, k.sub, false);
        tSub.setPadding(0, dp(4), 0, 0);
        col.addView(tSub);
        return col;
    }

    private TextView rotulo(LinearLayout col, String t) {
        TextView r = Ui.texto(this, t.toUpperCase(), 13, k.sub, true);
        r.setLetterSpacing(0.08f);
        r.setPadding(dp(4), dp(24), 0, dp(10));
        col.addView(r);
        return r;
    }

    private LinearLayout cartao(LinearLayout col) {
        LinearLayout c = Ui.coluna(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(k.superficie);
        g.setCornerRadius(dp(24));
        g.setStroke(dp(1), k.linha);
        c.setBackground(g);
        c.setPadding(dp(20), dp(8), dp(20), dp(8));
        col.addView(c, Ui.lp(-1, -2));
        return c;
    }

    private TextView linhaInfo(LinearLayout card, String nome, String valor, boolean divisor) {
        LinearLayout l = Ui.linha(this);
        l.setPadding(0, dp(16), 0, dp(16));
        TextView n = Ui.texto(this, nome.toUpperCase(), 13, k.sub, true);
        n.setLetterSpacing(0.08f);
        l.addView(n, Ui.lp(0, -2, 1f));
        TextView v = Ui.texto(this, valor, 16, k.texto, false);
        l.addView(v);
        card.addView(l);
        if (divisor) divisor(card);
        return v;
    }

    private void divisor(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor(k.linha);
        card.addView(d, Ui.lp(-1, dp(1)));
    }

    // ================================================================== Câmera

    private void telaCamera() {
        LinearLayout col = pagina("Câmera", "", true);
        LinearLayout card = cartao(col);
        ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = dp(18);
        if (!transmitindo()) {
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(20), dp(26), dp(20), dp(26));
            FrameLayout circ = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(k.superficie);
            g.setStroke(dp(4), 0xFF14B8A6);
            circ.setBackground(g);
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_camera_off);
            ic.setColorFilter(0xFF0F766E);
            circ.addView(ic, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
            card.addView(circ, Ui.lp(dp(96), dp(96)));
            TextView t = Ui.texto(this, "Câmera parada", 24, k.texto, true);
            t.setPadding(0, dp(16), 0, 0);
            card.addView(t);
            TextView d = Ui.texto(this, "Toque em Iniciar para transformar o celular numa câmera IP ONVIF na rede.", 15, k.sub, false);
            d.setGravity(Gravity.CENTER);
            d.setPadding(dp(8), dp(8), dp(8), dp(20));
            card.addView(d);
            Button b = Ui.botaoGradiente(this, "▶  Iniciar a câmera");
            b.setOnClickListener(v -> alternar());
            card.addView(b, Ui.lp(-2, dp(58)));
        } else {
            card.setPadding(dp(10), dp(10), dp(10), dp(12));
            FrameLayout moldura = new FrameLayout(this);
            moldura.setBackground(Ui.fundo(0xFF000000, dp(18)));
            moldura.setClipToOutline(true);
            CamService s = CamService.inst;
            float prop = s != null && s.saidaH > 0 ? (float) s.saidaW / s.saidaH : 16f / 9f;
            int larg = getResources().getDisplayMetrics().widthPixels - dp(64);
            int alt = Math.min((int) (larg / prop), dp(440));
            int largP = (int) (alt * prop);
            previa = new TextureView(this);
            previa.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                    previaSurface = new Surface(st);
                    if (CamService.inst != null) CamService.inst.definirPreview(previaSurface, w, hh);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    soltarPrevia();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
            moldura.addView(previa, new FrameLayout.LayoutParams(largP, alt, Gravity.CENTER));
            card.addView(moldura, Ui.lp(-1, alt));
            // controles rápidos
            LinearLayout ctrl = Ui.linha(this);
            ctrl.setPadding(0, dp(10), 0, 0);
            if (s == null || s.temLanterna()) ctrl.addView(chip(CamPrefs.lanterna(this) ? "🔦 Lanterna ligada" : "🔦 Lanterna", v -> {
                CamPrefs.put(this, "lanterna", !CamPrefs.lanterna(this));
                if (CamService.inst != null) CamService.inst.atualizarControles();
                mostrar();
            }), chipLp());
            ctrl.addView(chip("🔄 Trocar câmera", v -> {
                CamPrefs.put(this, "camera", "frontal".equals(CamPrefs.camera(this)) ? "traseira" : "frontal");
                CamPrefs.put(this, "zoom", 1f);
                aplicar(true);
                Toast.makeText(this, "Trocando de câmera…", Toast.LENGTH_SHORT).show();
            }), chipLp());
            ctrl.addView(chip("🌙 Tela preta", v -> ativarTelaPreta()), chipLp());
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(ctrl);
            card.addView(hs);
            float zmax = s != null ? s.zoomMaximo() : 1f;
            if (zmax > 1.05f) {
                TextView zt = Ui.texto(this, String.format("Zoom %.1fx", CamPrefs.zoom(this)), 13, k.sub, true);
                zt.setPadding(dp(6), dp(12), 0, 0);
                card.addView(zt);
                SeekBar sb = new SeekBar(this);
                sb.setMax(100);
                sb.setProgress((int) ((CamPrefs.zoom(this) - 1f) / (zmax - 1f) * 100));
                sb.setOnSeekBarChangeListener(new BarraSimples(p -> {
                    float z = 1f + (zmax - 1f) * p / 100f;
                    CamPrefs.put(this, "zoom", z);
                    zt.setText(String.format("Zoom %.1fx", z));
                    if (CamService.inst != null) CamService.inst.atualizarControles();
                }));
                card.addView(sb);
            }
        }
        rotulo(col, "Stream");
        LinearLayout st = cartao(col);
        tResol = linhaInfo(st, "Resolução", "-", true);
        tAudio = linhaInfo(st, "Áudio", "-", true);
        tClientes = linhaInfo(st, "Clientes conectados", "0", true);
        tTempo = linhaInfo(st, "Ligada há", "-", true);
        tIp = linhaInfo(st, "IP", Rede.ip(), false);
        tIp.setTypeface(Typeface.MONOSPACE);

        if (!bateriaLiberada()) {
            rotulo(col, "Para não travar");
            LinearLayout b = cartao(col);
            b.setPadding(dp(20), dp(16), dp(20), dp(16));
            b.addView(Ui.texto(this, "Libere a bateria do CamIP", 16, k.texto, true));
            TextView d = Ui.texto(this, "Sem isso o Android pode pausar a câmera depois de um tempo com a tela desligada.", 14, k.sub, false);
            d.setPadding(0, dp(4), 0, dp(12));
            b.addView(d);
            Button bt = Ui.botao(this, "Liberar bateria", true);
            bt.setOnClickListener(v -> pedirBateria());
            b.addView(bt, Ui.lp(-2, -2));
        }
        atualizarStatus();
    }

    private View chip(String t, View.OnClickListener cl) {
        TextView c = Ui.texto(this, t, 14, k.texto, true);
        c.setPadding(dp(14), dp(9), dp(14), dp(9));
        c.setBackground(Ui.clicavel(k.chip, dp(20), 0x22000000));
        c.setOnClickListener(cl);
        return c;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams l = Ui.lp(-2, -2);
        l.rightMargin = dp(8);
        return l;
    }

    private void atualizarStatus() {
        boolean on = transmitindo();
        if (on != estavaTransmitindo && "camera".equals(aba)) {
            estavaTransmitindo = on;
            mostrar();
            return;
        }
        estavaTransmitindo = on;
        CamService s = CamService.inst;
        String ip = s != null && s.config() != null ? s.config().ip : Rede.ip();
        if (bTopo != null) bTopo.setText(on ? "■  Parar" : "▶  Iniciar");
        if (tSub != null && "camera".equals(aba)) {
            if (!on) tSub.setText("Parada · em " + ip);
            else if ("erro".equals(CamService.estado)) tSub.setText("⚠️ " + CamService.erro);
            else if ("iniciando".equals(CamService.estado)) tSub.setText("Iniciando… · " + ip);
            else tSub.setText("Transmitindo · " + ip);
        }
        if (tResol == null) return;
        if (on && s != null) {
            tResol.setText(s.saidaW + " × " + s.saidaH + " · " + CamPrefs.fps(this) + " fps · " + (s.codecAtual().isEmpty() ? CamPrefs.codec(this) : s.codecAtual()));
            tAudio.setText(s.audioAtivo() ? "Vídeo + áudio" : "Só vídeo");
            tClientes.setText(String.valueOf(s.clientes()));
            long seg = s.ligadaHa() / 1000;
            tTempo.setText(String.format("%02d:%02d:%02d", seg / 3600, (seg / 60) % 60, seg % 60));
        } else {
            tResol.setText("-");
            tAudio.setText(CamPrefs.audio(this) ? "Vídeo + áudio" : "Só vídeo");
            tClientes.setText("0");
            tTempo.setText("-");
        }
        tIp.setText(ip);
    }

    private void soltarPrevia() {
        if (previaSurface != null) {
            if (CamService.inst != null) CamService.inst.definirPreview(null, 0, 0);
            previaSurface.release();
            previaSurface = null;
        }
    }

    // ================================================================== iniciar / parar

    private void alternar() {
        if (transmitindo()) {
            soltarPrevia();
            CamService.parar(this);
            CamService.estado = "parada";
            h.postDelayed(this::mostrar, 300);
            return;
        }
        List<String> faltam = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) faltam.add(Manifest.permission.CAMERA);
        if (CamPrefs.audio(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltam.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) faltam.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!faltam.isEmpty()) {
            requestPermissions(faltam.toArray(new String[0]), REQ_PERM);
            return;
        }
        if ("0.0.0.0".equals(Rede.ip())) {
            Toast.makeText(this, "Conecte o celular ao Wi-Fi da mesma rede do NVR", Toast.LENGTH_LONG).show();
        }
        CamService.estado = "iniciando";
        CamService.iniciar(this);
        h.postDelayed(this::mostrar, 600);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERM) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sem a permissão da câmera não dá para transmitir", Toast.LENGTH_LONG).show();
            return;
        }
        if (CamPrefs.audio(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CamPrefs.put(this, "audio", false);
            Toast.makeText(this, "Sem o microfone: transmitindo só vídeo", Toast.LENGTH_SHORT).show();
        }
        alternar();
    }

    /** video = precisa reiniciar câmera/encoders/servidores; senão só atualiza usuário/senha/nome na hora */
    private void aplicar(boolean video) {
        CamService s = CamService.inst;
        if (s == null || !transmitindo()) return;
        if (video) s.reconfigurar();
        else s.atualizarAcesso();
    }

    // ================================================================== tela preta (economia)

    private void ativarTelaPreta() {
        if (telaPreta != null) return;
        soltarPrevia();
        TextView t = Ui.texto(this, "Transmitindo…\nToque duas vezes para acordar", 14, 0xFF334155, false);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xFF000000);
        telaPreta = t;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        nav.setVisibility(View.GONE);
        final long[] ultimo = {0};
        t.setOnClickListener(v -> {
            long a = System.currentTimeMillis();
            if (a - ultimo[0] < 400) sairTelaPreta();
            ultimo[0] = a;
        });
        raiz.addView(t, new FrameLayout.LayoutParams(-1, -1));
    }

    private void sairTelaPreta() {
        raiz.removeView(telaPreta);
        telaPreta = null;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        nav.setVisibility(View.VISIBLE);
        mostrar();
    }

    @Override
    public void onBackPressed() {
        if (telaPreta != null) {
            sairTelaPreta();
            return;
        }
        if (!"camera".equals(aba)) {
            aba = "camera";
            desenharNav();
            mostrar();
            return;
        }
        moveTaskToBack(true); // a câmera continua transmitindo
    }

    private boolean bateriaLiberada() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void pedirBateria() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ================================================================== Ajustes

    private void telaAjustes() {
        LinearLayout col = pagina("Ajustes", "Vídeo, acesso, estabilidade e aparência", true);

        rotulo(col, "Vídeo");
        LinearLayout v = cartao(col);
        segmento(v, "Câmera", new String[]{"traseira", "frontal"}, new String[]{"Traseira", "Frontal"}, CamPrefs.camera(this), id -> {
            CamPrefs.put(this, "camera", id);
            CamPrefs.put(this, "zoom", 1f);
            aplicar(true);
        });
        divisor(v);
        LinearLayout lr = Ui.linha(this);
        lr.setPadding(0, dp(16), 0, dp(16));
        TextView nr = Ui.texto(this, "RESOLUÇÃO", 13, k.sub, true);
        nr.setLetterSpacing(0.08f);
        lr.addView(nr, Ui.lp(0, -2, 1f));
        TextView vr = Ui.texto(this, CamPrefs.largura(this) + " × " + CamPrefs.altura(this), 16, k.texto, false);
        lr.addView(vr);
        TextView alt = Ui.texto(this, "Alterar", 16, 0xFF0D9488, true);
        alt.setPadding(dp(18), dp(4), 0, dp(4));
        alt.setOnClickListener(x -> escolherResolucao(false, vr));
        lr.addView(alt);
        v.addView(lr);
        divisor(v);
        deslizante(v, "Qualidade (bitrate)", 500, 20000, 250, CamPrefs.bitrate(this), val -> formatarKbps(val), val -> {
            CamPrefs.put(this, "bitrate", val);
            aplicar(true);
        });
        divisor(v);
        deslizante(v, "Taxa de quadros", 5, 60, 1, CamPrefs.fps(this), val -> val + " fps", val -> {
            CamPrefs.put(this, "fps", val);
            aplicar(true);
        });
        divisor(v);
        boolean h265 = VideoEncoder.suportaH265();
        segmento(v, "Codec", new String[]{"H264", "H265"}, new String[]{"H.264", h265 ? "H.265" : "H.265 (sem suporte)"}, CamPrefs.codec(this), id -> {
            if ("H265".equals(id) && !h265) {
                Toast.makeText(this, "Este celular não tem encoder H.265", Toast.LENGTH_SHORT).show();
                return;
            }
            CamPrefs.put(this, "codec", id);
            aplicar(true);
        });
        divisor(v);
        TextView rr = Ui.texto(this, "ROTAÇÃO", 13, k.sub, true);
        rr.setLetterSpacing(0.08f);
        rr.setPadding(0, dp(16), 0, 0);
        v.addView(rr);
        Ui.Chips rot = new Ui.Chips(this, new String[]{"auto", "0", "90", "180", "270"},
                new String[]{"Auto", "0° em pé", "90° deitado", "180°", "270° deitado"}, CamPrefs.rotacao(this), id -> {
            CamPrefs.put(this, "rotacao", id);
            aplicar(true);
        });
        v.addView(rot.view);
        TextView dr = Ui.texto(this, "Auto usa a posição do celular na hora de iniciar. Para câmera fixa, escolha como o celular vai ficar.", 13, k.sub, false);
        dr.setPadding(0, dp(6), 0, dp(14));
        v.addView(dr);
        divisor(v);
        interruptor(v, "Espelhar imagem", null, CamPrefs.espelhar(this), on -> {
            CamPrefs.put(this, "espelhar", on);
            aplicar(true);
        }, true);
        interruptor(v, "Data e hora na imagem", "Como nas câmeras de segurança", CamPrefs.osd(this), on -> {
            CamPrefs.put(this, "osd", on);
            aplicar(true);
        }, true);
        interruptor(v, "Nome da câmera na imagem", null, CamPrefs.osdNome(this), on -> {
            CamPrefs.put(this, "osdNome", on);
            aplicar(true);
        }, false);

        rotulo(col, "Substream e áudio");
        LinearLayout sa = cartao(col);
        interruptor(sa, "Substream /stream2", "Segunda qualidade, mais leve, para a visão em mosaico do NVR", CamPrefs.sub(this), on -> {
            CamPrefs.put(this, "sub", on);
            aplicar(true);
            mostrar();
        }, true);
        if (CamPrefs.sub(this)) {
            TextView sr = Ui.texto(this, "RESOLUÇÃO DO SUBSTREAM", 13, k.sub, true);
            sr.setLetterSpacing(0.08f);
            sr.setPadding(0, dp(14), 0, 0);
            sa.addView(sr);
            String atual = CamPrefs.subLargura(this) + "x" + CamPrefs.subAltura(this);
            sa.addView(new Ui.Chips(this, new String[]{"640x360", "854x480", "960x540", "1280x720", "640x480", "352x288"},
                    new String[]{"640×360", "854×480", "960×540", "1280×720", "640×480", "352×288"}, atual, id -> {
                String[] p = id.split("x");
                CamPrefs.put(this, "subLargura", Integer.parseInt(p[0]));
                CamPrefs.put(this, "subAltura", Integer.parseInt(p[1]));
                aplicar(true);
            }).view);
            deslizante(sa, "Bitrate do substream", 128, 4000, 64, CamPrefs.subBitrate(this), this::formatarKbps, val -> {
                CamPrefs.put(this, "subBitrate", val);
                aplicar(true);
            });
            deslizante(sa, "FPS do substream", 5, 30, 1, CamPrefs.subFps(this), val -> val + " fps", val -> {
                CamPrefs.put(this, "subFps", val);
                aplicar(true);
            });
            divisor(sa);
        }
        interruptor(sa, "Áudio do microfone", "O som do ambiente junto com o vídeo (AAC)", CamPrefs.audio(this), on -> {
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11);
            }
            CamPrefs.put(this, "audio", on);
            if (transmitindo()) {
                Toast.makeText(this, "Pare e inicie a câmera para aplicar o áudio", Toast.LENGTH_LONG).show();
            }
        }, false);

        rotulo(col, "Acesso ONVIF");
        campo(col, "Nome da câmera", CamPrefs.s(this, "nome", "CamIP"), InputType.TYPE_CLASS_TEXT, false, val -> CamPrefs.put(this, "nome", val.trim().isEmpty() ? "CamIP" : val.trim()));
        campo(col, "Usuário", CamPrefs.s(this, "usuario", "admin"), InputType.TYPE_CLASS_TEXT, false, val -> CamPrefs.put(this, "usuario", val.trim()));
        campo(col, "Senha", CamPrefs.s(this, "senha", "admin"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true, val -> CamPrefs.put(this, "senha", val));
        LinearLayout ac = cartao(col);
        ((LinearLayout.LayoutParams) ac.getLayoutParams()).topMargin = dp(14);
        interruptor(ac, "Exigir usuário e senha", "Recomendado. Sem senha, qualquer um na rede pode ver a câmera.", CamPrefs.b(this, "exigirSenha", true), on -> {
            CamPrefs.put(this, "exigirSenha", on);
            aplicar(false);
        }, true);
        interruptor(ac, "Credenciais no endereço do stream", "Coloca usuário e senha no endereço RTSP entregue aos NVRs. Só para aparelhos que não conseguem fazer login sozinhos: qualquer um na rede consegue ler.",
                CamPrefs.b(this, "credUrl", false), on -> {
                    CamPrefs.put(this, "credUrl", on);
                    aplicar(false);
                }, true);
        interruptor(ac, "Descoberta ONVIF", "Deixa o NVR encontrar a câmera sozinho na rede", CamPrefs.b(this, "descoberta", true), on -> {
            CamPrefs.put(this, "descoberta", on);
            aplicar(true);
        }, false);
        LinearLayout portas = Ui.linha(this);
        portas.setPadding(0, dp(4), 0, 0);
        LinearLayout p1 = Ui.coluna(this), p2 = Ui.coluna(this);
        portas.addView(p1, Ui.lp(0, -2, 1f));
        LinearLayout.LayoutParams l2 = Ui.lp(0, -2, 1f);
        l2.leftMargin = dp(12);
        portas.addView(p2, l2);
        col.addView(portas);
        campo(p1, "Porta ONVIF (HTTP)", String.valueOf(CamPrefs.i(this, "portaHttp", 8080)), InputType.TYPE_CLASS_NUMBER, false, val -> porta("portaHttp", val));
        campo(p2, "Porta RTSP", String.valueOf(CamPrefs.i(this, "portaRtsp", 8554)), InputType.TYPE_CLASS_NUMBER, false, val -> porta("portaRtsp", val));

        rotulo(col, "Estabilidade");
        LinearLayout es = cartao(col);
        interruptor(es, "Reiniciar sozinho se travar", "Se a câmera parar de mandar imagem ou outro app usar a câmera, o CamIP volta sozinho", CamPrefs.autoReiniciar(this), on -> CamPrefs.put(this, "autoReiniciar", on), true);
        LinearLayout bat = Ui.linha(this);
        bat.setPadding(0, dp(14), 0, dp(14));
        LinearLayout bt = Ui.coluna(this);
        bt.addView(Ui.texto(this, "Bateria sem restrição", 16, k.texto, false));
        bt.addView(Ui.texto(this, bateriaLiberada() ? "Liberada ✓ o Android não vai pausar o app" : "Recomendado para transmitir com a tela desligada", 13, k.sub, false));
        bat.addView(bt, Ui.lp(0, -2, 1f));
        if (!bateriaLiberada()) {
            Button b = Ui.botao(this, "Liberar", true);
            b.setOnClickListener(x -> pedirBateria());
            bat.addView(b);
        }
        es.addView(bat);

        rotulo(col, "Aparência");
        LinearLayout ap = cartao(col);
        segmento(ap, "Tema", new String[]{"sistema", "claro", "escuro"}, new String[]{"Sistema", "Claro", "Escuro"}, CamPrefs.tema(this), id -> {
            CamPrefs.put(this, "tema", id);
            recreate();
        });
    }

    private void porta(String chave, String val) {
        try {
            int p = Integer.parseInt(val.trim());
            if (p < 1024 || p > 65535) return; // portas abaixo de 1024 não são permitidas para apps no Android
            if (p == CamPrefs.i(this, chave, 0)) return;
            CamPrefs.put(this, chave, p);
            aplicar(true);
        } catch (NumberFormatException ignored) {
        }
    }

    private String formatarKbps(int v) {
        return v >= 1000 ? (v % 1000 == 0 ? (v / 1000) + " Mbps" : String.format("%.2f Mbps", v / 1000f).replace(".", ",")) : v + " kbps";
    }

    private void escolherResolucao(boolean sub, TextView alvo) {
        List<Size> l = CamService.resolucoes(this, "frontal".equals(CamPrefs.camera(this)));
        List<String> nomes = new ArrayList<>();
        List<int[]> vals = new ArrayList<>();
        int[][] padrao = {{3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}, {960, 540}, {854, 480}, {640, 360}};
        for (int[] p : padrao) {
            boolean ok = l.isEmpty();
            for (Size s : l) if (s.getWidth() >= p[0] && s.getHeight() >= p[1]) ok = true;
            if (ok) {
                vals.add(p);
                nomes.add(p[0] + " × " + p[1] + (p[1] == 2160 ? " (4K)" : p[1] == 1440 ? " (2K)" : p[1] == 1080 ? " (Full HD)" : p[1] == 720 ? " (HD)" : ""));
            }
        }
        for (Size s : l) {
            float a = (float) s.getWidth() / s.getHeight();
            if (Math.abs(a - 16f / 9f) < 0.02f) continue; // os 16:9 já estão acima
            if (s.getWidth() > 2600) continue;
            vals.add(new int[]{s.getWidth(), s.getHeight()});
            nomes.add(s.getWidth() + " × " + s.getHeight() + (Math.abs(a - 4f / 3f) < 0.02f ? " (4:3)" : ""));
        }
        new AlertDialog.Builder(this).setTitle("Resolução").setItems(nomes.toArray(new String[0]), (d, w) -> {
            int[] r = vals.get(w);
            CamPrefs.put(this, "largura", r[0]);
            CamPrefs.put(this, "altura", r[1]);
            alvo.setText(r[0] + " × " + r[1]);
            aplicar(true);
        }).show();
    }

    interface AoTexto {
        void valor(String v);
    }

    interface AoInt {
        void valor(int v);
    }

    interface AoBool {
        void valor(boolean v);
    }

    interface Fmt {
        String f(int v);
    }

    private void campo(LinearLayout col, String rot, String valor, int tipo, boolean senha, AoTexto cb) {
        TextView r = Ui.texto(this, rot.toUpperCase(), 13, k.sub, true);
        r.setLetterSpacing(0.08f);
        r.setPadding(dp(4), dp(14), 0, dp(8));
        col.addView(r);
        FrameLayout f = new FrameLayout(this);
        EditText e = new EditText(this);
        e.setText(valor);
        e.setInputType(tipo);
        e.setSingleLine();
        e.setTextSize(18);
        e.setTextColor(k.texto);
        e.setTypeface(Typeface.MONOSPACE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(k.superficie);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), k.linha);
        e.setBackground(g);
        e.setPadding(dp(18), dp(16), dp(senha ? 56 : 18), dp(16));
        f.addView(e, new FrameLayout.LayoutParams(-1, -2));
        if (senha) {
            TextView olho = Ui.texto(this, "👁", 20, k.sub, false);
            olho.setPadding(dp(14), dp(12), dp(16), dp(12));
            olho.setOnClickListener(x -> {
                boolean oculto = e.getTransformationMethod() instanceof PasswordTransformationMethod;
                e.setTransformationMethod(oculto ? null : PasswordTransformationMethod.getInstance());
                e.setSelection(e.getText().length());
            });
            f.addView(olho, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        }
        col.addView(f, Ui.lp(-1, -2));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                cb.valor(s.toString());
                if (!senha && !rot.startsWith("Porta")) aplicar(false);
                if (senha && !s.toString().isEmpty()) aplicar(false);
            }
        });
    }

    private void interruptor(LinearLayout pai, String t, String sub, boolean on, AoBool cb, boolean comDivisor) {
        LinearLayout l = Ui.linha(this);
        l.setPadding(0, dp(16), 0, dp(16));
        LinearLayout tx = Ui.coluna(this);
        tx.addView(Ui.texto(this, t, 17, k.texto, false));
        if (sub != null) {
            TextView s = Ui.texto(this, sub, 14, k.sub, false);
            s.setPadding(0, dp(4), dp(10), 0);
            tx.addView(s);
        }
        l.addView(tx, Ui.lp(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((v, x) -> cb.valor(x));
        l.addView(sw);
        pai.addView(l);
        if (comDivisor) divisor(pai);
    }

    private void segmento(LinearLayout pai, String nome, String[] ids, String[] nomes, String sel, Ui.AoEscolher cb) {
        LinearLayout l = Ui.linha(this);
        l.setPadding(0, dp(12), 0, dp(12));
        TextView n = Ui.texto(this, nome.toUpperCase(), 13, k.sub, true);
        n.setLetterSpacing(0.08f);
        l.addView(n, Ui.lp(0, -2, 1f));
        LinearLayout seg = Ui.linha(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(k.cartao);
        g.setCornerRadius(dp(24));
        seg.setBackground(g);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        List<TextView> bts = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            TextView b = Ui.texto(this, nomes[i], 15, k.texto, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(16), dp(9), dp(16), dp(9));
            bts.add(b);
            seg.addView(b);
        }
        final String[] atual = {sel};
        Runnable p = () -> {
            for (int i = 0; i < ids.length; i++) {
                boolean on = ids[i].equals(atual[0]);
                bts.get(i).setBackground(on ? Ui.fundo(Ui.escuro(this) ? 0xFF2DD4BF : 0xFF0F172A, dp(22)) : null);
                bts.get(i).setTextColor(on ? (Ui.escuro(this) ? 0xFF042F2E : 0xFFFFFFFF) : k.texto);
            }
        };
        p.run();
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            bts.get(i).setOnClickListener(v -> {
                atual[0] = id;
                p.run();
                cb.escolheu(id);
            });
        }
        l.addView(seg);
        pai.addView(l);
    }

    private void deslizante(LinearLayout pai, String nome, int min, int max, int passo, int valor, Fmt fmt, AoInt cb) {
        LinearLayout l = Ui.linha(this);
        l.setPadding(0, dp(16), 0, 0);
        TextView n = Ui.texto(this, nome.toUpperCase(), 13, k.sub, true);
        n.setLetterSpacing(0.08f);
        l.addView(n, Ui.lp(0, -2, 1f));
        TextView v = Ui.texto(this, fmt.f(valor), 16, k.texto, false);
        l.addView(v);
        pai.addView(l);
        SeekBar sb = new SeekBar(this);
        sb.setMax((max - min) / passo);
        sb.setProgress((Math.max(min, Math.min(max, valor)) - min) / passo);
        sb.setPadding(dp(8), dp(18), dp(8), dp(18));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int ultimo = valor;

            public void onProgressChanged(SeekBar s, int p, boolean user) {
                ultimo = min + p * passo;
                v.setText(fmt.f(ultimo));
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                cb.valor(ultimo);
            }
        });
        pai.addView(sb);
    }

    // ================================================================== Endereços

    private void telaLinks() {
        LinearLayout col = pagina("Endereços", "Use no NVR, no VLC ou no seu sistema", false);
        CamService s = CamService.inst;
        CamConfig c = s != null && s.config() != null ? s.config() : CamPrefs.config(this);
        c.ip = Rede.ip();
        rotulo(col, "Streams RTSP");
        endereco(col, "Principal", c.rtspUrl(c.caminhoPrincipal, false));
        if (CamPrefs.sub(this)) endereco(col, "Substream", c.rtspUrl(c.caminhoSub, false));
        if (c.exigirSenha) endereco(col, "Principal com usuário e senha (VLC)", c.rtspUrl(c.caminhoPrincipal, true));
        rotulo(col, "ONVIF e imagem");
        endereco(col, "ONVIF (device service)", c.onvifUrl());
        endereco(col, "Foto (snapshot JPEG)", c.snapshotUrl());
        endereco(col, "Página da câmera no navegador", "http://" + c.ip + ":" + c.portaHttp + "/");

        rotulo(col, "Como adicionar no NVR");
        LinearLayout g = cartao(col);
        g.setPadding(dp(20), dp(16), dp(20), dp(18));
        String txt = "1. No NVR (Intelbras, Hikvision, Dahua…), vá em Adicionar câmera e escolha o protocolo ONVIF. Se a descoberta estiver ligada, ela aparece na busca sozinha.\n\n"
                + "2. Se for adicionar manual: IP " + c.ip + ", porta " + c.portaHttp + ", usuário \"" + c.usuario + "\" e a senha dos Ajustes.\n\n"
                + "3. Canal principal = Profile_1 (stream1). Canal extra = Profile_2 (stream2).\n\n"
                + "4. O celular e o NVR precisam estar na mesma rede. Fixe o IP do celular no roteador para ele não mudar.";
        g.addView(Ui.texto(this, txt, 15, k.texto, false));
    }

    private void endereco(LinearLayout col, String nome, String url) {
        LinearLayout c = cartao(col);
        ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = dp(10);
        c.setPadding(dp(18), dp(14), dp(12), dp(14));
        LinearLayout l = Ui.linha(this);
        LinearLayout tx = Ui.coluna(this);
        tx.addView(Ui.texto(this, nome, 14, k.sub, true));
        TextView u = Ui.texto(this, url, 15, k.texto, false);
        u.setTypeface(Typeface.MONOSPACE);
        u.setTextIsSelectable(true);
        tx.addView(u);
        l.addView(tx, Ui.lp(0, -2, 1f));
        TextView copiar = Ui.texto(this, "Copiar", 14, 0xFF0D9488, true);
        copiar.setPadding(dp(12), dp(10), dp(8), dp(10));
        copiar.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText(nome, url));
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show();
        });
        l.addView(copiar);
        c.addView(l);
    }

    // ================================================================== Sobre

    private void telaSobre() {
        LinearLayout col = pagina("Sobre", "CamIP " + BuildConfigVersao.nome(this), false);
        rotulo(col, "Tudo liberado");
        LinearLayout c = cartao(col);
        c.setPadding(dp(20), dp(16), dp(20), dp(18));
        c.addView(Ui.texto(this, "✓ Sem limite de tempo de transmissão\n✓ H.264 e H.265\n✓ Bitrate até 20 Mbps e até 60 fps\n✓ Substream e áudio do microfone\n"
                + "✓ ONVIF (Profile S, Media e Media2) com descoberta\n✓ RTSP por TCP e UDP, vários clientes ao mesmo tempo\n✓ Snapshot JPEG e página web da câmera\n"
                + "✓ Data e hora na imagem, zoom e lanterna\n✓ Funciona com a tela desligada", 15, k.texto, false));
        rotulo(col, "Registro");
        LinearLayout lg = cartao(col);
        lg.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = Ui.texto(this, CamService.logTexto().isEmpty() ? "Nada registrado ainda." : CamService.logTexto(), 12, k.texto, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        lg.addView(t);
        Button cp = Ui.botao(this, "Copiar registro", false);
        cp.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("log", CamService.logTexto()));
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.topMargin = dp(12);
        col.addView(cp, lp);
    }

    // ================================================================== util

    static final class BarraSimples implements SeekBar.OnSeekBarChangeListener {
        private final AoInt cb;

        BarraSimples(AoInt cb) {
            this.cb = cb;
        }

        public void onProgressChanged(SeekBar s, int p, boolean u) {
            if (u) cb.valor(p);
        }

        public void onStartTrackingTouch(SeekBar s) {
        }

        public void onStopTrackingTouch(SeekBar s) {
        }
    }
}
