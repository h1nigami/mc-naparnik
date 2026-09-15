package naparnik;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Популяция мозгов, живущая прямо в мире. Каждая особь проживает настоящую жизнь, фитнес
 * меряется в настоящем Minecraft. Удачная жизнь сразу даёт потомка на место худшего —
 * опыт каждого напарника входит в общий алгоритм немедленно, без ожидания поколения.
 */
public final class Population {
    public final int size;
    public final float[][] genomes;
    public final float[] fitness;
    public int generation, current, lives, replacements;
    public int[] home;
    public float record = Float.NaN;
    final Random rnd = new Random();

    /** Засев вокруг мозга: начинать со случайных весов значит выбросить всё обучение. */
    public Population(float[] seed) {
        this(Math.max(4, Config.get().i("population")));
        reseed(seed);
    }

    private Population(int size) {
        this.size = size;
        genomes = new float[size][];
        fitness = new float[size];
        Arrays.fill(fitness, Float.NaN);
    }

    /** Переселить популяцию вокруг нового мозга — так в игру въезжает результат фонового
     *  обучения. Хвост остаётся случайным: без него популяция схлопнется в один геном. */
    public void reseed(float[] seed) {
        int tail = Math.min(size - 1, Math.max(0, Config.get().i("randomTail")));
        genomes[0] = seed.clone();
        for (int i = 1; i < size; i++) {
            if (i >= size - tail) {
                genomes[i] = new Brain(rnd).g;
            } else {
                /* Близкие вариации, а не почти случайные мозги: при 40% весов со сдвигом ±0.6 потомок
                   уже не похож на родителя, и лучшая особь растворяется в первом же засеве. */
                float[] g = seed.clone();
                for (int k = 0; k < g.length; k++) {
                    if (rnd.nextFloat() < 0.1f) g[k] += (float) (rnd.nextGaussian() * 0.2);
                }
                genomes[i] = g;
            }
        }
        Arrays.fill(fitness, Float.NaN);
        current = 0;
    }

    /** Выдать следующий геном по кругу: несколько напарников живут одновременно, никто не ждёт. */
    public int take() {
        int i = current;
        current = (current + 1) % size;
        return i;
    }

    public Brain brain(int slot) { return new Brain(genomes[slot].clone()); }

    public void record(int slot, float f) {
        fitness[slot] = f;
        lives++;
        generation = lives / size;
        if (Float.isNaN(record) || f > record) record = f;

        int worst = -1;
        for (int i = 0; i < size; i++) {
            if (i == slot || Float.isNaN(fitness[i])) continue;
            if (worst < 0 || fitness[i] < fitness[worst]) worst = i;
        }
        if (worst < 0 || f <= fitness[worst]) return;

        float rate = Config.get().f("mutationRate"), sigma = Config.get().f("mutationSigma");
        float[] partner = genomes[Evolve.pick(fitnessOrWorst(), rnd)];
        float[] child = new float[genomes[slot].length];
        for (int k = 0; k < child.length; k++) {
            child[k] = rnd.nextBoolean() ? genomes[slot][k] : partner[k];
            if (rnd.nextFloat() < rate) child[k] += (float) (rnd.nextGaussian() * sigma);
        }
        genomes[worst] = child;
        fitness[worst] = Float.NaN;   // потомок обязан доказать себя своей жизнью
        replacements++;
    }

    float[] fitnessOrWorst() {
        float[] v = fitness.clone();
        for (int i = 0; i < v.length; i++) if (Float.isNaN(v[i])) v[i] = Float.NEGATIVE_INFINITY;
        return v;
    }

    public int evaluated() {
        int n = 0;
        for (float f : fitness) if (!Float.isNaN(f)) n++;
        return n;
    }

    public float bestGenomeFitness() {
        float best = Float.NaN;
        for (float f : fitness) if (!Float.isNaN(f) && (Float.isNaN(best) || f > best)) best = f;
        return best;
    }

    public int bestSlot() {
        int best = 0;
        for (int i = 1; i < size; i++) {
            if (Float.isNaN(fitness[best]) || (!Float.isNaN(fitness[i]) && fitness[i] > fitness[best])) best = i;
        }
        return best;
    }

    public float medianOfEvaluated() {
        List<Float> v = new ArrayList<>();
        for (float f : fitness) if (!Float.isNaN(f)) v.add(f);
        if (v.isEmpty()) return Float.NaN;
        v.sort(null);
        return v.get(v.size() / 2);
    }

    // ---------- сохранение ----------

    public void save(Path p) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(lives + " " + current + " " + record + " " + size + " "
                + (home == null ? "нет" : home[0] + " " + home[1] + " " + home[2]));
        for (int i = 0; i < size; i++) {
            StringBuilder sb = new StringBuilder().append(fitness[i]);
            for (float v : genomes[i]) sb.append(' ').append(v);
            lines.add(sb.toString());
        }
        Files.write(p, lines);
    }

    public static Population load(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p);
        String[] head = lines.get(0).trim().split("\\s+");
        int size = Integer.parseInt(head[3]);
        if (lines.size() < size + 1) throw new IOException("файл популяции короче ожидаемого");
        Population pop = new Population(size);
        pop.lives = Integer.parseInt(head[0]);
        pop.current = Integer.parseInt(head[1]) % size;
        pop.record = Float.parseFloat(head[2]);
        pop.generation = pop.lives / size;
        if (head.length >= 7) {
            pop.home = new int[]{Integer.parseInt(head[4]), Integer.parseInt(head[5]), Integer.parseInt(head[6])};
        }
        for (int i = 0; i < size; i++) {
            String[] f = lines.get(i + 1).trim().split("\\s+");
            pop.fitness[i] = Float.parseFloat(f[0]);
            float[] raw = new float[f.length - 1];
            for (int k = 0; k < raw.length; k++) raw[k] = Float.parseFloat(f[k + 1]);
            float[] g = Genome.upgrade(raw);   // популяция мира переживает добавление входов
            if (g.length != Brain.GENOME) throw new IOException("геном " + g.length + ", ждали " + Brain.GENOME);
            pop.genomes[i] = g;
        }
        return pop;
    }
}
