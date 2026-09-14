package naparnik;

import java.util.Random;

/**
 * Персептрон 25 -> 12 -> 8 без зависимостей.
 * Этот файл копируется в мод БЕЗ ИЗМЕНЕНИЙ — иначе обученные веса означают в игре не то,
 * что означали при обучении.
 */
public final class Brain {
    public static final int IN = 25, HID = 12, OUT = 8;

    static final int W1 = 0;
    static final int B1 = W1 + IN * HID;
    static final int W2 = B1 + HID;
    static final int B2 = W2 + HID * OUT;
    public static final int GENOME = B2 + OUT;

    public final float[] g;

    public Brain(Random r) {
        g = new float[GENOME];
        for (int i = 0; i < GENOME; i++) g[i] = (float) (r.nextGaussian() * 0.5);
    }

    public Brain(float[] genome) {
        if (genome.length != GENOME) throw new IllegalArgumentException("геном " + genome.length + ", ждали " + GENOME);
        g = genome;
    }

    /** argmax выходов = выбранное действие. */
    public int act(float[] x) { return act(x, null); }

    /** allowed != null — запрещённые действия не рассматриваются вовсе, а не штрафуются потом. */
    public int act(float[] x, boolean[] allowed) {
        float[] h = new float[HID];
        for (int j = 0; j < HID; j++) {
            float s = g[B1 + j];
            for (int i = 0; i < IN; i++) s += x[i] * g[W1 + j * IN + i];
            h[j] = s > 0 ? s : 0;
        }
        int best = -1;
        float bv = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < OUT; k++) {
            if (allowed != null && !allowed[k]) continue;
            float s = g[B2 + k];
            for (int j = 0; j < HID; j++) s += h[j] * g[W2 + k * HID + j];
            if (s > bv) { bv = s; best = k; }
        }
        return best < 0 ? 0 : best;
    }
}
