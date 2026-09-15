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
        repeatLoopIsPunished();
        idleGetsKicked();
        starvationKillsLikePlayer();
        drowningKillsLikePlayer();
        oldGenomeUpgradeKeepsBehavior();
        hungryHuntsAndEats();
        treesNoLongerFeed();
        wontWalkIntoWater();
        escapesWaterBeforeDrowning();
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

    /** Мало не платить за петлю — её надо штрафовать, иначе особь, которой больше нечего
     *  делать, так и будет ставить и ломать один и тот же блок до конца жизни. */
    static void repeatLoopIsPunished() {
        Sim s = clearAhead(new Sim(8));
        s.stone = 5;
        s.tech = 1;
        for (int i = 0; i < 20; i++) { s.act(4); s.act(3); }
        must(s.repeats >= 19, "повторы одного и того же блока не считаются: repeats=" + s.repeats);
        must(Config.get().reward("repeat") < 0, "за повтор нет штрафа: reward.repeat=" + Config.get().reward("repeat"));
        ok("повтор одного и того же блока штрафуется");
    }

    /** Особь, которая только крутится на месте, должна получить толчок и сдвинуться с места. */
    static void idleGetsKicked() {
        Sim s = clearAhead(new Sim(9));
        s.nAnimals = 0;   // без дичи: голод иначе уведёт на охоту, и проверялся бы не толчок
        for (int i = 0; i < 80 && !s.dead; i++) s.act(s.steer(1));   // мозг хочет только поворачиваться
        must(s.kicks >= 1, "зависшую особь ни разу не толкнули: kicks=" + s.kicks);
        must(s.visited > 1, "толчок не сдвинул особь с места: клеток " + s.visited);
        must(s.stagnant > 0, "простой не учитывается в фитнесе: stagnant=" + s.stagnant);
        ok("зависшую особь толкают, и она сдвигается");
    }

    /** Голод как у игрока на сложном уровне: на нуле сытости 1 урон раз в 80 тиков, до смерти. */
    static void starvationKillsLikePlayer() {
        Sim s = new Sim(10);
        s.hunger = 0;
        while (!s.dead && s.tick < 5000) s.act(1);
        must(s.dead, "голодная особь не умерла за 5000 тиков, hp=" + s.hp);
        must(s.tick >= 1500 && s.tick <= 1700, "смерть от голода не по часам игрока: тик " + s.tick + ", ждали ~1600");
        must("голод".equals(s.deathCause), "причина смерти не голод: " + s.deathCause);
        ok("голод убивает как у игрока: 1 урон раз в 4 секунды");
    }

    /** Вода как у игрока: 300 тиков воздуха, затем 2 урона каждые 20 тиков. */
    static void drowningKillsLikePlayer() {
        Sim s = new Sim(11);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) s.map[Sim.idx(s.x + dx, s.y + dy)] = Sim.WATER;
        while (!s.dead && s.tick < 5000) s.act(1);
        must(s.dead, "особь в воде не утонула за 5000 тиков");
        must(s.tick >= 480 && s.tick <= 520, "утопление не по часам игрока: тик " + s.tick + ", ждали ~500");
        must("вода".equals(s.deathCause), "причина смерти не вода: " + s.deathCause);
        ok("вода топит как у игрока: 15 секунд воздуха, потом 2 урона в секунду");
    }

    /** Старый мозг на 25 входов после миграции обязан решать ровно так же, как до неё:
     *  так переживают добавление входов и чемпион, и острова, и популяции в мирах. */
    static void oldGenomeUpgradeKeepsBehavior() {
        int oldIn = 25, hid = Brain.HID, out = Brain.OUT;
        java.util.Random r = new java.util.Random(12);
        float[] old = new float[oldIn * hid + hid + hid * out + out];
        for (int i = 0; i < old.length; i++) old[i] = (float) r.nextGaussian();
        Brain upgraded = new Brain(Genome.upgrade(old));
        for (int trial = 0; trial < 300; trial++) {
            float[] x = new float[Brain.IN];
            for (int i = 0; i < x.length; i++) x[i] = r.nextFloat() * 2 - 1;
            must(upgraded.act(x) == oldAct(old, x, oldIn), "мигрированный мозг решает иначе, чем старый");
        }
        ok("старый мозг после миграции решает ровно так же");
    }

    /** Прямой проход в старой раскладке — эталон для проверки миграции. */
    static int oldAct(float[] g, float[] x, int in) {
        int hid = Brain.HID, out = Brain.OUT, b1 = in * hid, w2 = b1 + hid, b2 = w2 + hid * out;
        float[] h = new float[hid];
        for (int j = 0; j < hid; j++) {
            float s = g[b1 + j];
            for (int i = 0; i < in; i++) s += x[i] * g[j * in + i];
            h[j] = s > 0 ? s : 0;
        }
        int best = 0;
        float bv = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < out; k++) {
            float s = g[b2 + k];
            for (int j = 0; j < hid; j++) s += h[j] * g[w2 + k * hid + j];
            if (s > bv) { bv = s; best = k; }
        }
        return best;
    }

    /** Голодная особь рядом с дичью обязана её добыть и поесть, даже если мозг хочет только крутиться. */
    static void hungryHuntsAndEats() {
        Sim s = new Sim(13);
        s.facing = 0;
        for (int dx = 1; dx <= 3; dx++) s.map[Sim.idx(s.x + dx, s.y)] = Sim.AIR;
        s.nAnimals = 0;
        s.animals[s.nAnimals++] = Sim.idx(s.x + 3, s.y);
        s.hunger = 8;
        s.food = 0;
        for (int i = 0; i < 40 && !s.dead; i++) s.act(s.steer(1));
        must(s.hunted >= 1, "голодная особь не добыла дичь в трёх клетках: hunted=" + s.hunted);
        must(s.eats >= 1, "добыла дичь, но не поела: eats=" + s.eats + ", еды " + s.food + ", сытость " + s.hunger);
        ok("голодная особь охотится и ест");
    }

    /** Еда с деревьев была выдумкой симулятора: в игре брёвна не кормят, и привычка к ней не переносится. */
    static void treesNoLongerFeed() {
        Sim s = clearAhead(new Sim(14));
        for (int i = 0; i < 50; i++) {
            s.map[s.aheadIdx()] = Sim.TREE;
            s.trees[s.nTrees++] = s.aheadIdx();
            s.act(3);
        }
        must(s.woodMined == 50, "деревья не рубятся: woodMined=" + s.woodMined);
        must(s.food <= 6, "деревья всё ещё кормят: еды " + s.food + " с 50 деревьев");
        ok("деревья почти не кормят — еду надо добывать");
    }

    /** Шаг в воду запрещён: особи намеренно топились, а отбор не успевал от этого отучить. */
    static void wontWalkIntoWater() {
        Sim s = clearAhead(new Sim(15));
        s.map[s.aheadIdx()] = Sim.WATER;
        boolean[] all = new boolean[8];
        java.util.Arrays.fill(all, true);
        must(!s.mask(all)[0], "шаг в воду разрешён");
        ok("в воду по своей воле не заходит");
    }

    /** Попавшая в воду особь обязана выбраться до того, как кончится воздух. */
    static void escapesWaterBeforeDrowning() {
        Sim s = new Sim(16);
        for (int dx = -2; dx <= 1; dx++) for (int dy = -2; dy <= 2; dy++) s.map[Sim.idx(s.x + dx, s.y + dy)] = Sim.WATER;
        s.map[Sim.idx(s.x + 2, s.y)] = Sim.AIR;
        s.facing = 2;                                   // отвёрнута от берега
        for (int i = 0; i < 60 && !s.dead; i++) s.act(s.steer(1));
        must(!s.dead, "утонула в двух клетках от берега: причина " + s.deathCause);
        must(s.map[Sim.idx(s.x, s.y)] != Sim.WATER, "так и осталась в воде");
        ok("выплывает к берегу до утопления");
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
