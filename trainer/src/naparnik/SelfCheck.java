package naparnik;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Минимальная проверка: сломается механика мира, детерминизм или сам отбор — упадёт здесь. */
public final class SelfCheck {
    static int passed;

    public static void main(String[] args) throws Exception {
        deterministic();
        digGivesWood();
        craftAdvancesTech();
        starvationKills();
        genomeRoundTrip();
        placeDigLoopPaysNothing();
        selectionImproves();
        System.out.println("\nвсе " + passed + " проверок пройдены");
    }

    static void ok(String what) { passed++; System.out.println("ok  " + what); }

    static void must(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }

    /** Без этого нельзя сравнивать поколения: отбор начнёт гоняться за шумом. */
    static void deterministic() {
        Brain b = new Brain(new Random(1));
        float a = Sim.evaluate(b, 5000, 99);
        float c = Sim.evaluate(b, 5000, 99);
        must(a == c, "один геном на одном сиде дал разный фитнес: " + a + " != " + c);
        ok("симулятор детерминирован");
    }

    static void digGivesWood() {
        Sim s = clearAhead(new Sim(5));
        s.map[s.aheadIdx()] = Sim.TREE;
        s.trees[s.nTrees++] = s.aheadIdx();
        s.act(3);
        must(s.wood == 1, "копка дерева не дала дерева: wood=" + s.wood);
        must(s.map[s.aheadIdx()] == Sim.AIR, "блок не исчез");
        ok("копка дерева даёт ресурс");
    }

    static void craftAdvancesTech() {
        Sim s = new Sim(6);
        s.wood = 5;
        s.act(7);
        must(s.tech == 1, "крафт не поднял тех-уровень: tech=" + s.tech);
        s.wood = 3; s.stone = 5;
        s.act(7);
        must(s.tech == 2, "второй крафт не сработал: tech=" + s.tech);
        must(s.wood == 0 && s.stone == 0, "ресурсы не списались");
        ok("лестница крафта работает");
    }

    /** Если голод не убивает, у выживания нет цены и отбору не за что цепляться. */
    static void starvationKills() {
        Sim s = new Sim(7);
        while (!s.dead && s.tick < 20000) s.act(1);   // только крутится на месте
        must(s.dead, "бездельник не умер за 20000 тиков, hp=" + s.hp + " hunger=" + s.hunger);
        ok("бездействие смертельно");
    }

    static void genomeRoundTrip() throws Exception {
        float[] g = new Brain(new Random(3)).g;
        Path p = Files.createTempFile("brain", ".json");
        Genome.save(g, p);
        float[] back = Genome.load(p);
        Files.delete(p);
        must(back.length == g.length, "длина генома не пережила запись");
        float[] x = new Sim(11).sense();
        must(new Brain(g).act(x) == new Brain(back).act(x), "мозг после перезагрузки решает иначе");
        ok("brain.json переживает круг запись-чтение");
    }

    /** Поставить блок и тут же его сломать — не работа. Отбор нашёл эту петлю: 111 «добыто»
     *  и 105 «построено» при 18 пройденных клетках, то есть напарник стоял и накручивал счёт. */
    static void placeDigLoopPaysNothing() {
        Sim s = clearAhead(new Sim(8));
        s.stone = 5;
        s.tech = 1;
        for (int i = 0; i < 20; i++) { s.act(4); s.act(3); }
        must(s.mined == 0, "петля поставил-сломал засчиталась как добыча: mined=" + s.mined);
        must(s.built <= 1, "одна и та же клетка засчиталась постройкой много раз: built=" + s.built);
        ok("петля «поставил-сломал» награды не даёт");
    }

    /** Главная проверка: отбор реально двигает популяцию, а не перемешивает случайные веса. */
    static void selectionImproves() {
        var r = Evolve.run(40, 15, 6000, 2026, false);
        float first = r.medianByGen()[0], last = r.medianByGen()[r.medianByGen().length - 1];
        System.out.printf("    медиана: поколение 0 = %.0f, поколение 14 = %.0f%n", first, last);
        must(last > first, "отбор не улучшил медиану популяции: " + first + " -> " + last);
        ok("генетический отбор улучшает популяцию");
    }

    static Sim clearAhead(Sim s) {
        for (int t = 0; t < 4 && s.map[s.aheadIdx()] != Sim.AIR; t++) s.facing = (s.facing + 1) & 3;
        must(s.map[s.aheadIdx()] == Sim.AIR, "не нашлось свободной клетки впереди");
        return s;
    }
}
