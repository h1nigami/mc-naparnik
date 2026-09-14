package naparnik;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

/** Генетический алгоритм: турнирный отбор, равномерный кроссовер, гауссова мутация, элитизм. */
public final class Evolve {
    /** Каждая особь живёт на нескольких картах — иначе отбирается везение, а не стратегия. */
    public static int seeds() { return Math.max(1, Config.get().i("seeds")); }

    public record Result(float[] genome, float[] bestByGen, float[] medianByGen) { }

    public static Result run(int pop, int gens, int episode, long seed, boolean verbose) {
        Random r = new Random(seed);
        float[][] P = new float[pop][];
        for (int i = 0; i < pop; i++) P[i] = new Brain(r).g;

        float[] fit = new float[pop];
        float[] best = new float[gens], median = new float[gens];

        for (int gen = 0; gen < gens; gen++) {
            final float[][] cur = P;
            final long gs = seed + gen * 1000L;
            IntStream.range(0, pop).parallel().forEach(i -> {
                float sum = 0;
                int n = seeds();
                for (int s = 0; s < n; s++) sum += Sim.evaluate(new Brain(cur[i]), episode, gs + s);
                fit[i] = sum / n;
            });

            int elite = 0;
            for (int i = 1; i < pop; i++) if (fit[i] > fit[elite]) elite = i;
            best[gen] = fit[elite];
            median[gen] = median(fit);
            if (verbose) System.out.printf("поколение %3d  лучший=%8.0f  медиана=%8.0f%n", gen, best[gen], median[gen]);

            if (gen == gens - 1) return new Result(P[elite].clone(), best, median);
            P = next(P, fit, elite, r);
        }
        throw new IllegalStateException("gens должно быть > 0");
    }

    static float[][] next(float[][] P, float[] fit, int elite, Random r) {
        float rate = Config.get().f("mutationRate"), sigma = Config.get().f("mutationSigma");
        float[][] out = new float[P.length][];
        out[0] = P[elite].clone();
        for (int i = 1; i < P.length; i++) {
            float[] a = P[pick(fit, r)], b = P[pick(fit, r)], c = new float[a.length];
            for (int k = 0; k < c.length; k++) {
                c[k] = r.nextBoolean() ? a[k] : b[k];
                if (r.nextFloat() < rate) c[k] += (float) (r.nextGaussian() * sigma);
            }
            out[i] = c;
        }
        return out;
    }

    static int pick(float[] fit, Random r) {
        int best = r.nextInt(fit.length);
        for (int t = 0; t < Math.max(1, Config.get().i("tournament")) - 1; t++) {
            int c = r.nextInt(fit.length);
            if (fit[c] > fit[best]) best = c;
        }
        return best;
    }

    static float median(float[] v) {
        float[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }
}
