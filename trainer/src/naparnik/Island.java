package naparnik;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Один остров фонового обучения — отдельный процесс со своей популяцией.
 *
 *   java -cp out naparnik.Island <имя> [зерно]
 *
 * Пишет в ~/.naparnik/runs/<имя>/: status.json (живой статус), history.csv (по поколениям),
 * best.json (лучший мозг). Останавливается, когда в своей папке находит файл stop.
 *
 * Раз в MIGRATE поколений забирает лучших с соседних островов на место своих худших. Острова
 * держат разнообразие: застрявший в тупике не утягивает остальных, а удачная находка одного
 * расходится по всем.
 */
public final class Island {
    static final int MIGRATE = 5;

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "остров";
        long seed = args.length > 1 ? Long.parseLong(args[1]) : System.nanoTime();
        Config.ensureFile();

        Path dir = Config.HOME.resolve("runs").resolve(name);
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("stop"));
        Path history = dir.resolve("history.csv");
        if (!Files.exists(history)) Files.writeString(history, "поколение,лучший,медиана,время\n");

        Random r = new Random(seed);
        int pop = Math.max(4, Config.get().i("population"));
        float[][] P = new float[pop][];
        Path seedBrain = Path.of("brain.json");
        for (int i = 0; i < pop; i++) {
            float[] seeded = i == 0 && Files.exists(seedBrain) ? Genome.load(seedBrain) : null;
            // мозг под другое число входов молча сломал бы остров — берём только совместимый
            P[i] = seeded != null && seeded.length == Brain.GENOME ? seeded : new Brain(r).g;
        }

        float[] fit = new float[pop];
        float record = Float.NEGATIVE_INFINITY;
        long started = System.currentTimeMillis();
        long lastWrite = 0;

        for (int gen = 0; ; gen++) {
            if (Files.exists(dir.resolve("stop"))) {
                status(dir, name, gen, record, Float.NaN, false, started);
                return;
            }
            Config c = Config.get();
            int life = Math.max(10, c.i("life")) * Sim.DECISION_EVERY;
            int seeds = Math.max(1, c.i("seeds"));
            final float[][] cur = P;
            final long gs = seed + gen * 1000L;

            IntStream.range(0, pop).parallel().forEach(i -> {
                float sum = 0;
                for (int s = 0; s < seeds; s++) sum += Sim.evaluate(new Brain(cur[i]), life, gs + s);
                fit[i] = sum / seeds;
            });

            int elite = 0;
            for (int i = 1; i < pop; i++) if (fit[i] > fit[elite]) elite = i;
            float best = fit[elite];
            float[] sorted = fit.clone();
            Arrays.sort(sorted);
            float median = sorted[pop / 2];

            boolean improved = best > record;
            if (improved) {
                record = best;
                Genome.save(P[elite], dir.resolve("best.json"));
            }
            /* Остров делает сотни поколений в секунду. Писать историю на каждое — это десятки
               мегабайт в час; пишем раз в секунду и обязательно при новом рекорде. */
            long now = System.currentTimeMillis();
            if (improved || now - lastWrite >= 1000) {
                lastWrite = now;
                Files.writeString(history, gen + "," + best + "," + median + ","
                        + (now - started) / 1000 + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                status(dir, name, gen, record, median, true, started);
            }

            P = Evolve.next(P, fit, elite, r);
            if (gen % MIGRATE == MIGRATE - 1) migrate(dir, P, r);
        }
    }

    /** Лучшие мозги соседних островов заменяют случайных особей (кроме элиты на позиции 0). */
    static void migrate(Path self, float[][] P, Random r) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(self.getParent())) {
            for (Path other : ds) {
                if (other.equals(self)) continue;
                Path best = other.resolve("best.json");
                if (!Files.exists(best)) continue;
                try {
                    float[] g = Genome.load(best);
                    if (g.length == Brain.GENOME) P[1 + r.nextInt(P.length - 1)] = g;
                } catch (IOException ignored) {
                    // сосед как раз переписывает свой файл — возьмём на следующей миграции
                }
            }
        } catch (IOException ignored) {
            // соседей нет — остров учится один, это законный режим
        }
    }

    static void status(Path dir, String name, int gen, float record, float median, boolean running, long started)
            throws IOException {
        String json = "{\"name\":\"" + name + "\",\"generation\":" + gen
                + ",\"record\":" + (Float.isInfinite(record) ? "null" : record)
                + ",\"median\":" + (Float.isNaN(median) ? "null" : median)
                + ",\"running\":" + running
                + ",\"seconds\":" + (System.currentTimeMillis() - started) / 1000
                + ",\"updated\":" + System.currentTimeMillis() + "}";
        Path tmp = dir.resolve("status.json.tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("status.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
