package naparnik;

import java.nio.file.Path;

/** Обучение: java -cp out naparnik.Main [популяция] [поколений] [тиков_на_жизнь] [файл] */
public final class Main {
    public static void main(String[] args) throws Exception {
        int pop = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int gens = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        int episode = args.length > 2 ? Integer.parseInt(args[2]) : Sim.DAY;
        Path out = Path.of(args.length > 3 ? args[3] : "brain.json");

        long t0 = System.currentTimeMillis();
        var res = Evolve.run(pop, gens, episode, 42, true);
        Genome.save(res.genome(), out);
        System.out.printf("%nготово за %d с -> %s%n", (System.currentTimeMillis() - t0) / 1000, out.toAbsolutePath());

        // показ через тот же прогон, что и отбор: свой цикл здесь обходил маску и показывал не то
        Sim s = new Sim(777);
        Sim.run(new Brain(res.genome()), s, episode / Sim.DECISION_EVERY);
        System.out.printf("показательная жизнь: решений=%d клеток=%d добыто=%d построил=%d убил=%d тех=%d впустую=%d %s%n",
                s.decisions, s.visited, s.mined, s.built, s.kills, s.tech, s.wasted, s.dead ? "ПОГИБ" : "выжил");
    }
}
