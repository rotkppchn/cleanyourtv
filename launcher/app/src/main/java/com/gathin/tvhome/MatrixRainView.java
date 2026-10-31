package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Falling "digital rain" for the power-on intro (matrix_intro), drawn with OpenGL ES 2 so it stays smooth on a slow
 * TV chip: every glyph is a quad from one texture atlas, and a frame is a single draw call. Columns of katakana,
 * letters and digits drop on three depth layers; each drop has a white head and a trail that fades towards its top.
 * Sizes are given for a 1080-pixel-high screen and scaled to the real height.
 */
final class MatrixRainView extends GLSurfaceView {
    private static final String GLYPHS =
            "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    /** Text size, brightness and speed factor per depth layer, far to near. */
    static final float[] LAYER_SIZE = {16f, 21f, 27f};
    static final float[] LAYER_LIGHT = {0.42f, 0.70f, 1f};
    static final float[] LAYER_SPEED = {0.72f, 0.86f, 1f};
    /** Row pitch as a multiple of the text size. */
    static final float PITCH = 1.12f;
    /** The most quads one frame can hold. */
    private static final int MAX_QUADS = 6000;

    private final Rain rain = new Rain();

    MatrixRainView(Context ctx) {
        super(ctx);
        setEGLContextClientVersion(2);
        setRenderer(rain);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    void setColor(int rgb) {
        rain.red = rgb & 0xFFFFFF;
    }

    void setEntrySeconds(float s) {
        rain.entrySeconds = Math.max(0f, s);
    }

    /** Fall speed factor, 1 for the reference video. */
    void setSpeed(float f) {
        rain.speed = f;
    }

    /** Fade everything to black over {@code ms}, starting now. */
    void fadeOut(long ms) {
        rain.fadeStart = SystemClock.uptimeMillis();
        rain.fadeMs = ms;
    }

    /** Frames drawn so far and the slowest frame in milliseconds, for the log. */
    String stats() {
        return rain.frames + " frames, slowest " + rain.slowestMs + " ms";
    }

    /** Noto Sans CJK draws the katakana and the Latin glyphs in one style; any other font falls back per glyph. */
    private static Typeface typeface() {
        File cjk = new File("/system/fonts/NotoSansCJK-Regular.ttc");
        if (cjk.canRead()) {
            try {
                Typeface t = new Typeface.Builder(cjk).setTtcIndex(0).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
                // fall through to the default
            }
        }
        return Typeface.MONOSPACE;
    }

    private static final class Rain implements Renderer {
        final Random rnd = new Random();
        volatile int red = 0xD63C54;
        volatile float entrySeconds = 1.6f;
        volatile float speed = 1f;
        volatile long fadeStart = -1, fadeMs = 700;
        volatile int frames;
        volatile long slowestMs;

        int width, height;
        float scale = 1f;
        Column[] columns = new Column[0];
        long startMs = -1, lastMs;

        // Atlas: per layer, per glyph, its texture rectangle and its quad size in pixels.
        final float[][] u0 = new float[3][], v0 = new float[3][], u1 = new float[3][], v1 = new float[3][];
        final float[] cellW = new float[3], cellH = new float[3];

        int program, aPos, aUv, aColor, uScreen, uFade, texture;
        FloatBuffer verts;
        ShortBuffer indices;
        float[] vbuf;

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = link(
                    "attribute vec2 aPos; attribute vec2 aUv; attribute vec4 aColor; uniform vec2 uScreen;"
                    + "varying vec2 vUv; varying vec4 vColor;"
                    + "void main() { vUv = aUv; vColor = aColor;"
                    + " gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0); }",
                    "precision mediump float; uniform sampler2D uTex; uniform float uFade;"
                    + "varying vec2 vUv; varying vec4 vColor;"
                    + "void main() { float a = texture2D(uTex, vUv).a;"
                    + " gl_FragColor = vec4(vColor.rgb * uFade, vColor.a * a); }");
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aUv = GLES20.glGetAttribLocation(program, "aUv");
            aColor = GLES20.glGetAttribLocation(program, "aColor");
            uScreen = GLES20.glGetUniformLocation(program, "uScreen");
            uFade = GLES20.glGetUniformLocation(program, "uFade");

            vbuf = new float[MAX_QUADS * 4 * 8];
            verts = ByteBuffer.allocateDirect(vbuf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            short[] idx = new short[MAX_QUADS * 6];
            for (int q = 0, v = 0; q < MAX_QUADS && v + 3 < 65536; q++, v += 4) {
                int i = q * 6;
                idx[i] = (short) v; idx[i + 1] = (short) (v + 1); idx[i + 2] = (short) (v + 2);
                idx[i + 3] = (short) (v + 2); idx[i + 4] = (short) (v + 1); idx[i + 5] = (short) (v + 3);
            }
            indices = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            indices.put(idx).position(0);
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            width = w;
            height = h;
            scale = h / 1080f;
            GLES20.glViewport(0, 0, w, h);
            buildAtlas();
            buildColumns();
        }

        /** White glyphs on transparent, one row per layer, in a single texture. */
        private void buildAtlas() {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(typeface());
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            int n = GLYPHS.length();
            int[] rowY = new int[3];
            int atlasW = 2048, y = 0;
            for (int l = 0; l < 3; l++) {
                float size = LAYER_SIZE[l] * scale;
                cellW[l] = (float) Math.ceil(size * 1.2f);
                cellH[l] = (float) Math.ceil(size * 1.3f);
                int perRow = (int) (atlasW / cellW[l]);
                rowY[l] = y;
                y += (int) (cellH[l] * Math.ceil(n / (double) perRow)) + 2;
            }
            int atlasH = 1;
            while (atlasH < y) atlasH <<= 1;
            Bitmap bmp = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            char[] one = new char[1];
            for (int l = 0; l < 3; l++) {
                float size = LAYER_SIZE[l] * scale;
                p.setTextSize(size);
                Paint.FontMetrics fm = p.getFontMetrics();
                int perRow = (int) (atlasW / cellW[l]);
                u0[l] = new float[n]; v0[l] = new float[n]; u1[l] = new float[n]; v1[l] = new float[n];
                for (int g = 0; g < n; g++) {
                    float x = (g % perRow) * cellW[l];
                    float top = rowY[l] + (g / perRow) * cellH[l];
                    one[0] = GLYPHS.charAt(g);
                    float base = top + (cellH[l] - (fm.descent - fm.ascent)) / 2f - fm.ascent;
                    c.drawText(one, 0, 1, x + cellW[l] / 2f, base, p);
                    u0[l][g] = x / atlasW;
                    u1[l][g] = (x + cellW[l]) / atlasW;
                    v0[l][g] = top / atlasH;
                    v1[l][g] = (top + cellH[l]) / atlasH;
                }
            }
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
        }

        private void buildColumns() {
            ArrayList<Column> list = new ArrayList<>();
            float x = 6f * scale;
            while (x < width) {
                float r = rnd.nextFloat();
                int layer = r < 0.38f ? 0 : r < 0.72f ? 1 : 2;
                list.add(new Column(this, layer, x));
                x += LAYER_SIZE[layer] * scale * (1.25f + rnd.nextFloat() * 0.9f);
            }
            columns = list.toArray(new Column[0]);
            for (Column c : columns) c.spawn(true);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            long now = SystemClock.uptimeMillis();
            if (startMs < 0) { startMs = now; lastMs = now; }
            long frameMs = now - lastMs;
            if (frames > 2 && frameMs > slowestMs) slowestMs = frameMs;
            float dt = Math.min(0.05f, frameMs / 1000f);
            float t = (now - startMs) / 1000f;
            lastMs = now;
            frames++;

            float fade = 1f;
            if (fadeStart >= 0) fade = Math.max(0f, 1f - (now - fadeStart) / (float) Math.max(1, fadeMs));

            int quads = 0;
            for (Column c : columns) {
                c.step(t, dt);
                quads = c.emit(vbuf, quads);
            }

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (quads == 0) return;
            GLES20.glUseProgram(program);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform2f(uScreen, width, height);
            GLES20.glUniform1f(uFade, fade);
            verts.position(0);
            verts.put(vbuf, 0, quads * 32).position(0);
            int stride = 8 * 4;
            verts.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, verts);
            GLES20.glEnableVertexAttribArray(aPos);
            verts.position(2);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, verts);
            GLES20.glEnableVertexAttribArray(aUv);
            verts.position(4);
            GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, verts);
            GLES20.glEnableVertexAttribArray(aColor);
            indices.position(0);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, quads * 6, GLES20.GL_UNSIGNED_SHORT, indices);
        }

        private static int link(String vs, String fs) {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(p);
            return p;
        }

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }

    /** One column of cells; a drop runs down it, leaving lit cells that fade with their distance above the head. */
    private static final class Column {
        final Rain r;
        final int layer;
        final float x, pitch;
        final int rows;
        final int[] glyph;
        final boolean[] mirror;
        float head;      // in rows, may be negative while the drop is above the screen
        float speed;     // rows per second
        float trail;     // lit rows behind the head
        float startAt;   // seconds after the intro starts
        boolean running;

        Column(Rain r, int layer, float left) {
            this.r = r;
            this.layer = layer;
            x = left + r.cellW[layer] / 2f;
            pitch = LAYER_SIZE[layer] * r.scale * PITCH;
            rows = (int) (r.height / pitch) + 2;
            glyph = new int[rows];
            mirror = new boolean[rows];
            for (int i = 0; i < rows; i++) {
                glyph[i] = r.rnd.nextInt(GLYPHS.length());
                mirror[i] = r.rnd.nextFloat() < 0.18f;
            }
        }

        void spawn(boolean first) {
            float pxPerSecond = (390f + r.rnd.nextFloat() * 330f) * LAYER_SPEED[layer] * r.scale * r.speed;
            speed = pxPerSecond / pitch;
            trail = rows * (0.55f + r.rnd.nextFloat() * 0.75f);
            head = first ? -1f - r.rnd.nextFloat() * 3f : -1f - r.rnd.nextFloat() * rows * 0.6f;
            startAt = first ? r.rnd.nextFloat() * r.entrySeconds : 0f;
            running = !first;
        }

        void step(float t, float dt) {
            if (!running) {
                if (t < startAt) return;
                running = true;
            }
            head += speed * dt;
            // A few lit cells change their glyph now and then, as in the film.
            if (r.rnd.nextFloat() < 0.35f) glyph[r.rnd.nextInt(rows)] = r.rnd.nextInt(GLYPHS.length());
            if (head - trail > rows) spawn(false);
        }

        /** Appends this column's lit cells as quads; returns the new quad count. */
        int emit(float[] b, int quads) {
            if (!running) return quads;
            int h = (int) Math.floor(head);
            int top = Math.max(0, (int) Math.ceil(head - trail));
            int bottom = Math.min(rows - 1, h);
            float light = LAYER_LIGHT[layer];
            float rr = ((r.red >> 16) & 0xFF) / 255f, rg = ((r.red >> 8) & 0xFF) / 255f, rb = (r.red & 0xFF) / 255f;
            float w = r.cellW[layer], hh = r.cellH[layer];
            for (int i = top; i <= bottom && quads < MAX_QUADS; i++) {
                float dist = head - i;            // 0 at the head, trail at the end
                float cr, cg, cb, a;
                if (i == h) {
                    cr = cg = cb = 1f;
                    a = light;
                } else {
                    float f = 1f - dist / trail;  // 1 near the head, 0 at the end of the trail
                    a = f > 0.55f ? 1f : f / 0.55f;
                    a = a * a * (3f - 2f * a) * light;
                    float white = dist < 2f ? Math.max(0f, 2f - dist) * 0.5f : 0f;   // the cell above the head glows
                    cr = rr + (1f - rr) * white;
                    cg = rg + (1f - rg) * white;
                    cb = rb + (1f - rb) * white;
                }
                if (a <= 0.01f) continue;
                float cy = (i + 0.5f) * pitch;
                float x0 = x - w / 2f, x1 = x + w / 2f, y0 = cy - hh / 2f, y1 = cy + hh / 2f;
                int g = glyph[i];
                float ua = r.u0[layer][g], ub = r.u1[layer][g];
                if (mirror[i]) { float s = ua; ua = ub; ub = s; }
                float va = r.v0[layer][g], vb = r.v1[layer][g];
                int o = quads * 32;
                o = put(b, o, x0, y0, ua, va, cr, cg, cb, a);
                o = put(b, o, x1, y0, ub, va, cr, cg, cb, a);
                o = put(b, o, x0, y1, ua, vb, cr, cg, cb, a);
                put(b, o, x1, y1, ub, vb, cr, cg, cb, a);
                quads++;
            }
            return quads;
        }

        private static int put(float[] b, int o, float x, float y, float u, float v,
                               float cr, float cg, float cb, float a) {
            b[o] = x; b[o + 1] = y; b[o + 2] = u; b[o + 3] = v;
            b[o + 4] = cr; b[o + 5] = cg; b[o + 6] = cb; b[o + 7] = a;
            return o + 8;
        }
    }
}
