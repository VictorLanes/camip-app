package com.victorlanes.camip;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pipeline OpenGL: a câmera desenha numa textura e daqui a imagem vai, girada/recortada e com
 * data e hora, para cada encoder (principal e extra) e para a pré-visualização do app.
 * Assim a câmera só precisa de UMA saída (funciona em qualquer celular).
 */
public class GlRender implements SurfaceTexture.OnFrameAvailableListener {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    static final class Saida {
        final Surface surface;
        final int largura, altura;
        final long intervaloNs;
        final boolean encoder;
        final boolean principal;
        EGLSurface egl = EGL14.EGL_NO_SURFACE;
        long ultimo;

        Saida(Surface s, int w, int h, int fps, boolean encoder, boolean principal) {
            this.surface = s;
            this.largura = w;
            this.altura = h;
            this.intervaloNs = fps > 0 ? 1_000_000_000L / fps : 0;
            this.encoder = encoder;
            this.principal = principal;
        }
    }

    private final HandlerThread thread = new HandlerThread("gl");
    private Handler h;
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private int texCamera, progCamera, progOsd, texOsd;
    private SurfaceTexture st;
    private Surface camSurface;
    private final List<Saida> saidas = new CopyOnWriteArrayList<>();
    private final float[] texMatrix = new float[16];
    private final float[] transform = new float[16];
    private final float[] final4 = new float[16];
    private int camW, camH;
    private volatile int rotacao;
    private volatile boolean espelhar;
    private volatile boolean osd = true;
    private volatile String nomeOsd = "";
    private String textoOsd = "";
    private int osdW, osdH;
    private volatile CountDownLatch pedidoFoto;
    private volatile byte[] foto;
    private long fotoEm;
    public volatile long quadros;

    private FloatBuffer quad, coords;

    public interface Pronto {
        void surfaceCamera(Surface s);
    }

    /** cria o contexto OpenGL e a textura que recebe a câmera (tamanho do buffer da câmera) */
    public void iniciar(int camLargura, int camAltura, Pronto cb) {
        camW = camLargura;
        camH = camAltura;
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(() -> {
            configurarEgl();
            texCamera = criarTexturaOes();
            progCamera = programa(VS, FS_OES);
            progOsd = programa(VS_2D, FS_2D);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texOsd = t[0];
            st = new SurfaceTexture(texCamera);
            st.setDefaultBufferSize(camW, camH);
            st.setOnFrameAvailableListener(this, h);
            camSurface = new Surface(st);
            cb.surfaceCamera(camSurface);
        });
        quad = buffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        coords = buffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
    }

    public void configurar(int rotacao, boolean espelhar, boolean osd, String nome) {
        this.rotacao = rotacao;
        this.espelhar = espelhar;
        this.osd = osd;
        this.nomeOsd = nome == null ? "" : nome;
    }

    public void adicionarSaida(Surface s, int w, int h2, int fps, boolean encoder, boolean principal) {
        Saida x = new Saida(s, w, h2, fps, encoder, principal);
        h.post(() -> {
            try {
                int[] attrs = {EGL14.EGL_NONE};
                x.egl = EGL14.eglCreateWindowSurface(dpy, config, s, attrs, 0);
                saidas.add(x);
            } catch (Exception e) {
                com.victorlanes.camip.core.CoreLog.i("GL: não foi possível criar saída: " + e);
            }
        });
    }

    public void removerSaida(Surface s) {
        CountDownLatch l = new CountDownLatch(1);
        h.post(() -> {
            for (Saida x : saidas) {
                if (x.surface == s) {
                    saidas.remove(x);
                    EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx);
                    EGL14.eglDestroySurface(dpy, x.egl);
                }
            }
            l.countDown();
        });
        try {
            l.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    // ================================================================== quadros

    @Override
    public void onFrameAvailable(SurfaceTexture s) {
        try {
            EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx);
            st.updateTexImage();
            st.getTransformMatrix(texMatrix);
            quadros++;
            long agora = System.nanoTime();
            atualizarOsd();
            for (Saida x : saidas) {
                if (x.intervaloNs > 0 && agora - x.ultimo < x.intervaloNs - 3_000_000L) continue; // limita o FPS de cada saída
                x.ultimo = x.ultimo == 0 || agora - x.ultimo > 2 * x.intervaloNs ? agora : x.ultimo + x.intervaloNs;
                if (!EGL14.eglMakeCurrent(dpy, x.egl, x.egl, ctx)) continue;
                desenhar(x);
                if (x.principal && pedidoFoto != null) lerFoto(x);
                if (x.encoder) EGLExt.eglPresentationTimeANDROID(dpy, x.egl, agora);
                EGL14.eglSwapBuffers(dpy, x.egl);
            }
        } catch (Exception e) {
            com.victorlanes.camip.core.CoreLog.i("GL: " + e);
        }
    }

    private void desenhar(Saida x) {
        GLES20.glViewport(0, 0, x.largura, x.altura);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // transformação das coordenadas: recorte central -> rotação -> espelho
        boolean deitado = rotacao % 180 != 0;
        float srcA = deitado ? (float) camH / camW : (float) camW / camH;
        float outA = (float) x.largura / x.altura;
        float sx = 1, sy = 1;
        if (srcA > outA) sx = outA / srcA;
        else sy = srcA / outA;
        // x' = T(0,5) · Rot · Escala(recorte, espelho) · T(-0,5) — a escala é no eixo da saída
        Matrix.setIdentityM(transform, 0);
        Matrix.translateM(transform, 0, 0.5f, 0.5f, 0);
        Matrix.rotateM(transform, 0, rotacao, 0, 0, 1);
        Matrix.scaleM(transform, 0, sx * (espelhar ? -1 : 1), sy, 1);
        Matrix.translateM(transform, 0, -0.5f, -0.5f, 0);
        Matrix.multiplyMM(final4, 0, texMatrix, 0, transform, 0);

        GLES20.glUseProgram(progCamera);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texCamera);
        desenharQuad(progCamera, quad, coords, final4);

        if (osd && osdW > 0) {
            float alturaTexto = x.altura * 0.05f;
            float esc = alturaTexto / osdH;
            float w = osdW * esc, hh = osdH * esc;
            float m = x.altura * 0.025f;
            float x0 = -1 + 2 * m / x.largura, y1 = 1 - 2 * m / x.altura;
            float x1 = x0 + 2 * w / x.largura, y0 = y1 - 2 * hh / x.altura;
            FloatBuffer q = buffer(new float[]{x0, y0, x1, y0, x0, y1, x1, y1});
            FloatBuffer c = buffer(new float[]{0, 1, 1, 1, 0, 0, 1, 0});
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(progOsd);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texOsd);
            float[] id = new float[16];
            Matrix.setIdentityM(id, 0);
            desenharQuad(progOsd, q, c, id);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void desenharQuad(int prog, FloatBuffer pos, FloatBuffer tex, float[] m) {
        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        int aTex = GLES20.glGetAttribLocation(prog, "aTex");
        int uM = GLES20.glGetUniformLocation(prog, "uTexMatrix");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, pos);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tex);
        GLES20.glUniformMatrix4fv(uM, 1, false, m, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    // ================================================================== data e hora na imagem

    private final SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);

    private void atualizarOsd() {
        if (!osd) return;
        String t = fmt.format(new Date()) + (nomeOsd.isEmpty() ? "" : "   " + nomeOsd);
        if (t.equals(textoOsd)) return;
        textoOsd = t;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(44);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setShadowLayer(3, 0, 0, Color.BLACK);
        int w = (int) p.measureText(t) + 24, hh = 60;
        Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint fundo = new Paint();
        fundo.setColor(0x66000000);
        cv.drawRoundRect(0, 0, w, hh, 10, 10, fundo);
        cv.drawText(t, 12, 45, p);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texOsd);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        osdW = w;
        osdH = hh;
        b.recycle();
    }

    // ================================================================== snapshot

    /** JPEG do quadro atual do stream principal */
    public byte[] snapshot() {
        if (foto != null && System.currentTimeMillis() - fotoEm < 700) return foto;
        CountDownLatch l = new CountDownLatch(1);
        pedidoFoto = l;
        try {
            l.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return foto;
    }

    private void lerFoto(Saida x) {
        CountDownLatch l = pedidoFoto;
        pedidoFoto = null;
        try {
            int w = x.largura, hh = x.altura;
            ByteBuffer bb = ByteBuffer.allocateDirect(w * hh * 4).order(ByteOrder.LITTLE_ENDIAN);
            GLES20.glReadPixels(0, 0, w, hh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
            Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            bb.rewind();
            b.copyPixelsFromBuffer(bb);
            android.graphics.Matrix flip = new android.graphics.Matrix();
            flip.postScale(1, -1);
            float esc = w > 1920 ? 1920f / w : 1f;
            flip.postScale(esc, esc);
            Bitmap c = Bitmap.createBitmap(b, 0, 0, w, hh, flip, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            c.compress(Bitmap.CompressFormat.JPEG, 85, out);
            foto = out.toByteArray();
            fotoEm = System.currentTimeMillis();
            b.recycle();
            c.recycle();
        } catch (Exception e) {
            com.victorlanes.camip.core.CoreLog.i("snapshot: " + e);
        } finally {
            if (l != null) l.countDown();
        }
    }

    // ================================================================== EGL / shaders

    private void configurarEgl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(dpy, v, 0, v, 1);
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0);
        config = cfgs[0];
        ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        pbuffer = EGL14.eglCreatePbufferSurface(dpy, config, new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx);
    }

    private static int criarTexturaOes() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static final String VS = "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTexMatrix; varying vec2 vTex;"
            + "void main(){ gl_Position = aPos; vTex = (uTexMatrix * aTex).xy; }";
    private static final String FS_OES = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; varying vec2 vTex; uniform samplerExternalOES uTex;"
            + "void main(){ gl_FragColor = texture2D(uTex, vTex); }";
    private static final String VS_2D = VS;
    private static final String FS_2D = "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;"
            + "void main(){ gl_FragColor = texture2D(uTex, vTex); }";

    private static int shader(int tipo, String src) {
        int s = GLES20.glCreateShader(tipo);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static int programa(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        return p;
    }

    private static FloatBuffer buffer(float[] v) {
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    public void parar() {
        CountDownLatch l = new CountDownLatch(1);
        if (h == null) return;
        h.post(() -> {
            try {
                for (Saida x : saidas) EGL14.eglDestroySurface(dpy, x.egl);
                saidas.clear();
                if (camSurface != null) camSurface.release();
                if (st != null) st.release();
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(dpy, pbuffer);
                EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            } catch (Exception ignored) {
            }
            l.countDown();
        });
        try {
            l.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        thread.quitSafely();
    }
}
