package naparnik;

import java.util.Random;

/**
 * Игрушечный мир для обучения: сетка 64x64, деревья/камень/железо, сутки, ночные мобы,
 * голод и дерево крафта до железной кирки.
 *
 * Смысл всей затеи: одна жизнь в настоящем Minecraft — 20 минут, а GA нужны сотни тысяч
 * жизней. Здесь жизнь считается за миллисекунды.
 *
 * ponytail: 2D-сетка вместо 3D-мира. Потолок — напарник не научится копать вниз и строить
 * этажи. Апгрейд — добавить ось z и действие "копать вниз", если стратегия упрётся в это.
 */
public final class Sim {
    public static final int SIZE = 64;
    public static final int DAY = 24000;
    /** Тиков между решениями. Держать равным периоду опроса мозга в моде. */
    public static final int DECISION_EVERY = 10;

    static final byte AIR = 0, TREE = 1, STONE = 2, IRON = 3, WALL = 4, WATER = 5;

    final byte[] map = new byte[SIZE * SIZE];
    final Random rnd;

    int x, y, facing;                       // facing: 0=+x, 1=+y, 2=-x, 3=-y
    int hp = 20, hunger = 20;
    int wood, stone, iron, food;
    int tech, mined, kills, built, sheltered, wasted, woodMined;

    /** Простой и помощь при зависании. streak — решений подряд без прогресса, stagnant — сколько
     *  из них сверх допуска (идёт в штраф), repeats — повторы одного и того же блока, kicks —
     *  сколько раз особь пришлось толкать. */
    int streak, stagnant, repeats, kicks, kickLeft, kickDir;
    final boolean[] tried = new boolean[8];
    int tick;
    boolean dead, hurt;

    /** Запас воздуха в тиках, как у игрока: 300 = 15 секунд. */
    static final int MAX_AIR = 300;
    int air = MAX_AIR;
    /** Причина последнего урона — она же причина смерти: «голод», «вода», «моб». */
    String lastDamage, deathCause;

    final int[] trees = new int[SIZE * SIZE]; int nTrees;
    final int[] stones = new int[SIZE * SIZE]; int nStones;
    final int[] irons = new int[SIZE * SIZE]; int nIrons;

    static final int MAX_MOBS = 8;

    /** Дичь: бродит днём и ночью, в воду не заходит, убитая даёт мясо. Единственный надёжный
     *  источник еды — раньше еда падала с деревьев, а в игре брёвна не кормят. */
    static final int MAX_ANIMALS = 24;
    final int[] animals = new int[MAX_ANIMALS];
    int nAnimals, hunted, eats;
    final int[] mobs = new int[MAX_MOBS]; int nMobs;

    /** Плотная награда за исследование. Без неё вся популяция гибнет от голода на одном тике
     *  с одинаковым фитнесом — отбору не за что зацепиться, GA стоит на месте. */
    final boolean[] seen = new boolean[SIZE * SIZE]; int visited;

    /** Клетка занята блоком, который поставил сам агент, и клетки, где он уже строил.
     *  Без них петля «поставил-сломал» накручивает и добычу, и постройку, стоя на месте. */
    final boolean[] ownBlock = new boolean[SIZE * SIZE];
    final boolean[] everBuilt = new boolean[SIZE * SIZE];

    public Sim(long seed) {
        rnd = new Random(seed);
        for (int i = 0; i < SIZE; i++) {
            map[idx(i, 0)] = WALL; map[idx(i, SIZE - 1)] = WALL;
            map[idx(0, i)] = WALL; map[idx(SIZE - 1, i)] = WALL;
        }
        // густота леса настраивается: при густом лесе искать нечего и навык поиска не нужен
        int treeCount = Math.max(1, Math.min(2000, Config.get().i("sim.trees")));
        scatter(TREE, treeCount, trees); nTrees = treeCount;
        int lakes = Math.max(0, Math.min(40, Config.get().i("sim.lakes")));
        for (int l = 0; l < lakes; l++) lake();
        scatter(STONE, 420, stones); nStones = 420;
        scatter(IRON, 40, irons); nIrons = 40;
        do { x = 1 + rnd.nextInt(SIZE - 2); y = 1 + rnd.nextInt(SIZE - 2); } while (map[idx(x, y)] != AIR);
        facing = rnd.nextInt(4);
        int herd = Math.max(0, Math.min(MAX_ANIMALS, Config.get().i("sim.animals")));
        for (int a = 0; a < herd; a++) spawnAnimal();
        seen[idx(x, y)] = true; visited = 1;
    }

    static int idx(int x, int y) { return y * SIZE + x; }

    /** Озеро — случайное блуждание по свободным клеткам. */
    void lake() {
        int cx = 2 + rnd.nextInt(SIZE - 4), cy = 2 + rnd.nextInt(SIZE - 4);
        int size = Math.max(1, Math.min(400, Config.get().i("sim.lakeSize")));
        for (int k = 0; k < size; k++) {
            if (map[idx(cx, cy)] == AIR) map[idx(cx, cy)] = WATER;
            cx = Math.max(1, Math.min(SIZE - 2, cx + rnd.nextInt(3) - 1));
            cy = Math.max(1, Math.min(SIZE - 2, cy + rnd.nextInt(3) - 1));
        }
    }

    void scatter(byte type, int n, int[] list) {
        for (int k = 0; k < n; k++) {
            int p;
            do { p = idx(1 + rnd.nextInt(SIZE - 2), 1 + rnd.nextInt(SIZE - 2)); } while (map[p] != AIR);
            map[p] = type;
            list[k] = p;
        }
    }

    // ---------- сенсоры ----------

    /**
     * 30 входов. Раскладка обязана совпадать с Bot.sense() в моде число в число:
     *  0 здоровье  1 сытость  2-3 время суток
     *  4 впереди твёрдое  5 впереди можно сломать сейчас  6 впереди бревно
     *  7-9 дерево  10-12 руда  13-15 цель для удара  16-18 лежащий предмет (близость, sin, cos)
     *  19 можно скрафтить  20 блоков в сумке  21 еды в сумке  22 бьют прямо сейчас
     *  23 можно запрыгнуть вперёд  24 стен вокруг  25 запас воздуха  26 впереди вода  27-29 дичь
     * Чего в двумерном мире нет (лежащие предметы, ступеньки), симулятор отдаёт нулём — это
     * честный разрыв с игрой, а не ошибка.
     */
    public float[] sense() {
        float[] s = new float[Brain.IN];
        s[0] = hp / 10f - 1f;
        s[1] = hunger / 10f - 1f;
        double t = 2 * Math.PI * (tick % DAY) / (double) DAY;
        s[2] = (float) Math.sin(t);
        s[3] = (float) Math.cos(t);
        byte ahead = map[aheadIdx()];
        s[4] = ahead != AIR ? 1f : -1f;
        boolean diggable = ahead == TREE || (ahead == STONE && tech >= 1) || (ahead == IRON && tech >= 2);
        s[5] = diggable ? 1f : -1f;
        s[6] = ahead == TREE ? 1f : -1f;
        bearing(s, 7, nearest(trees, nTrees));
        bearing(s, 10, tech < 2 ? nearest(stones, nStones) : nearest(irons, nIrons));
        bearing(s, 13, nearestMob());
        // 16-18: лежащих предметов в симуляторе нет — ресурсы идут сразу в сумку
        s[19] = canCraft() ? 1f : -1f;
        s[20] = Math.min(1f, stone / 16f) * 2f - 1f;
        s[21] = Math.min(1f, food / 4f) * 2f - 1f;
        s[22] = hurt ? 1f : -1f;
        s[23] = -1f;   // ступенек в двумерном мире нет
        s[24] = walls() / 2f - 1f;
        s[25] = Math.max(0, air) / (MAX_AIR / 2f) - 1f;   // полный воздух = 1, кончился = -1
        s[26] = map[aheadIdx()] == WATER ? 1f : -1f;
        bearing(s, 27, nearest(animals, nAnimals));   // ближайшая дичь
        return s;
    }

    /** Три слота: близость, синус и косинус угла на цель относительно взгляда. */
    void bearing(float[] s, int at, int pos) {
        if (pos < 0) return;
        double dx = pos % SIZE - x, dy = pos / SIZE - y;
        double d = Math.sqrt(dx * dx + dy * dy);
        double rel = Math.atan2(dy, dx) - facing * (Math.PI / 2);
        s[at] = (float) Math.max(0, 1 - d / 32.0);
        s[at + 1] = (float) Math.sin(rel);
        s[at + 2] = (float) Math.cos(rel);
    }

    int nearest(int[] list, int n) {
        int best = -1, bd = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int dx = list[i] % SIZE - x, dy = list[i] / SIZE - y;
            int d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = list[i]; }
        }
        return best;
    }

    int nearestMob() {
        int best = -1, bd = Integer.MAX_VALUE;
        for (int i = 0; i < nMobs; i++) {
            int dx = mobs[i] % SIZE - x, dy = mobs[i] / SIZE - y;
            int d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = mobs[i]; }
        }
        return best;
    }

    // ---------- действия ----------

    public void act(int a) {
        int progressBefore = visited + mined + built + kills;
        actInner(a);
        if (visited + mined + built + kills > progressBefore) streak = 0;
        else if (++streak > Config.get().i("stagnation.grace")) stagnant++;
    }

    private void actInner(int a) {
        tried[a] = true;
        turnsInRow = a == 1 || a == 2 ? turnsInRow + 1 : 0;
        boolean did = switch (a) {
            case 0 -> {
                int p = aheadIdx();
                if (map[p] != AIR && map[p] != WATER) yield false;   // в воду зайти можно — и утонуть
                x = p % SIZE; y = p / SIZE;
                if (!seen[p]) { seen[p] = true; visited++; }
                yield true;
            }
            case 1 -> { facing = (facing + 3) & 3; yield true; }
            case 2 -> { facing = (facing + 1) & 3; yield true; }
            case 3 -> dig();
            case 4 -> place();
            case 5 -> attack();
            case 6 -> eat();
            case 7 -> craft();
            default -> false;
        };
        if (!did) wasted++;
        if (walls() >= 3) sheltered++;
        int hpBefore = hp;
        for (int i = 0; i < DECISION_EVERY; i++) world();
        hurt = hp < hpBefore && hunger > 0;   // голод не удар: сенсор про врага, а не про пустой желудок
    }

    int walls() {
        int n = 0;
        if (map[idx(x + 1, y)] != AIR) n++;
        if (map[idx(x - 1, y)] != AIR) n++;
        if (map[idx(x, y + 1)] != AIR) n++;
        if (map[idx(x, y - 1)] != AIR) n++;
        return n;
    }

    int aheadIdx() {
        int nx = x + (facing == 0 ? 1 : facing == 2 ? -1 : 0);
        int ny = y + (facing == 1 ? 1 : facing == 3 ? -1 : 0);
        return idx(nx, ny);
    }

    boolean dig() {
        int p = aheadIdx();
        switch (map[p]) {
            case TREE -> {
                wood++; mined++; woodMined++;
                if (rnd.nextInt(20) == 0) food++;   // изредка яблоко — как из листвы в игре, не больше
                map[p] = AIR; nTrees = drop(trees, nTrees, p);
                return true;
            }
            case STONE -> {
                if (tech < 1) return false;
                stone++;
                if (ownBlock[p]) { ownBlock[p] = false; repeats++; }   // свой блок вернулся в сумку — это не добыча, а повтор
                else mined++;
                map[p] = AIR; nStones = drop(stones, nStones, p);
                return true;
            }
            case IRON -> {
                if (tech < 2) return false;
                iron++; mined++;
                map[p] = AIR; nIrons = drop(irons, nIrons, p);
                return true;
            }
            default -> { return false; }
        }
    }

    boolean place() {
        int p = aheadIdx();
        if (stone <= 0 || map[p] != AIR) return false;
        stone--;
        map[p] = STONE;
        stones[nStones++] = p;
        ownBlock[p] = true;
        if (!everBuilt[p]) { everBuilt[p] = true; built++; }
        else repeats++;                            // в этой клетке уже строил — это повтор
        return true;
    }

    boolean attack() {
        for (int i = 0; i < nMobs; i++) {
            int dx = Math.abs(mobs[i] % SIZE - x), dy = Math.abs(mobs[i] / SIZE - y);
            if (dx <= 1 && dy <= 1) { mobs[i] = mobs[--nMobs]; kills++; return true; }
        }
        for (int i = 0; i < nAnimals; i++) {
            int dx = Math.abs(animals[i] % SIZE - x), dy = Math.abs(animals[i] / SIZE - y);
            if (dx <= 1 && dy <= 1) { animals[i] = animals[--nAnimals]; hunted++; food += 2; return true; }
        }
        return false;
    }

    boolean animalAdjacent() {
        for (int i = 0; i < nAnimals; i++) {
            if (Math.abs(animals[i] % SIZE - x) <= 1 && Math.abs(animals[i] / SIZE - y) <= 1) return true;
        }
        return false;
    }

    void spawnAnimal() {
        if (nAnimals >= MAX_ANIMALS) return;
        for (int t = 0; t < 30; t++) {
            int p = idx(1 + rnd.nextInt(SIZE - 2), 1 + rnd.nextInt(SIZE - 2));
            if (map[p] == AIR && p != idx(x, y)) { animals[nAnimals++] = p; return; }
        }
    }

    boolean eat() {
        if (food <= 0 || hunger > 14) return false;
        food--;
        eats++;
        hunger = Math.min(20, hunger + 6);
        return true;
    }

    /** Единственная лестница прогресса: руки -> деревянная -> каменная -> железная кирка.
     *  Цена шага живёт в одной таблице: условие и списание не могут разъехаться. */
    static final int[][] RECIPES = { {5, 0, 0}, {3, 5, 0}, {2, 0, 3} };   // дерево, камень, железо

    boolean canCraft() {
        if (tech >= RECIPES.length) return false;
        int[] r = RECIPES[tech];
        return wood >= r[0] && stone >= r[1] && iron >= r[2];
    }

    boolean craft() {
        if (!canCraft()) return false;
        int[] r = RECIPES[tech];
        wood -= r[0]; stone -= r[1]; iron -= r[2];
        tech++;
        return true;
    }

    static int drop(int[] list, int n, int pos) {
        for (int i = 0; i < n; i++) if (list[i] == pos) { list[i] = list[n - 1]; return n - 1; }
        return n;
    }

    // ---------- мир ----------

    void world() {
        tick++;
        int drain = Math.max(1, Config.get().i("hunger.drainTicks"));
        if (tick % drain == 0 && hunger > 0) hunger--;
        // голод как у игрока на сложном уровне: 1 урон раз в 80 тиков, до смерти
        if (hunger == 0 && tick % 80 == 0) { hp--; lastDamage = "голод"; }
        // вода как у игрока: 300 тиков воздуха, на -20 — 2 урона и отсчёт заново
        if (map[idx(x, y)] == WATER) {
            if (--air <= -20) { hp -= 2; air = 0; lastDamage = "вода"; }
        } else {
            air = Math.min(MAX_AIR, air + 4);
        }
        if (hunger >= 18 && hp < 20 && tick % 80 == 0) hp++;

        boolean night = tick % DAY >= 13000 && tick % DAY < 23000;
        if (night && nMobs < MAX_MOBS && tick % 200 == 0) spawnMob();
        if (!night && nMobs > 0 && tick % 100 == 0) nMobs--;

        if (tick % 8 == 0) moveMobs();
        if (tick % 16 == 0) moveAnimals();
        if (tick % 400 == 0 && nAnimals < Math.max(0, Config.get().i("sim.animals"))) spawnAnimal();
        if (tick % 20 == 0) {
            for (int i = 0; i < nMobs; i++) {
                int dx = Math.abs(mobs[i] % SIZE - x), dy = Math.abs(mobs[i] / SIZE - y);
                if (dx <= 1 && dy <= 1) { hp -= 2; lastDamage = "моб"; }
            }
        }
        if (hp <= 0 && !dead) { hp = 0; dead = true; deathCause = lastDamage; }
    }

    void spawnMob() {
        for (int t = 0; t < 20; t++) {
            int mx = 1 + rnd.nextInt(SIZE - 2), my = 1 + rnd.nextInt(SIZE - 2);
            int d = Math.abs(mx - x) + Math.abs(my - y);
            if (map[idx(mx, my)] == AIR && d > 10 && d < 25) { mobs[nMobs++] = idx(mx, my); return; }
        }
    }

    void moveAnimals() {
        for (int i = 0; i < nAnimals; i++) {
            int d = rnd.nextInt(4), ax = animals[i] % SIZE, ay = animals[i] / SIZE;
            int nx = ax + (d == 0 ? 1 : d == 2 ? -1 : 0), ny = ay + (d == 1 ? 1 : d == 3 ? -1 : 0);
            int np = idx(nx, ny);
            if (map[np] == AIR && np != idx(x, y)) animals[i] = np;
        }
    }

    void moveMobs() {
        for (int i = 0; i < nMobs; i++) {
            int mx = mobs[i] % SIZE, my = mobs[i] / SIZE;
            int nx = mx + Integer.signum(x - mx), ny = my;
            if (map[idx(nx, ny)] != AIR) { nx = mx; ny = my + Integer.signum(y - my); }
            if (map[idx(nx, ny)] == AIR) mobs[i] = idx(nx, ny);
        }
    }

    /** Та же формула, что в игре, и из того же файла настроек. Чего симулятор не умеет —
     *  подбор предметов и драка с себе подобными — здесь просто равно нулю. */
    public float fitness() {
        Config c = Config.get();
        int repertoire = 0;
        for (boolean b : tried) if (b) repertoire++;
        return c.reward("age") * (tick / (float) DECISION_EVERY)
                + c.reward("visited") * visited
                + c.reward("mined") * mined
                + c.reward("picked") * mined
                + c.reward("built") * built
                + c.reward("sheltered") * sheltered
                + c.reward("kills") * kills
                + c.reward("repertoire") * repertoire
                + c.reward("tech") * tech
                + c.reward("wood") * woodMined
                + c.reward("stagnation") * stagnant
                + c.reward("repeat") * repeats
                + c.reward("kick") * kicks
                + (dead ? c.reward("death") : 0)
                + c.reward("hunt") * hunted
                + c.reward("eat") * eats
                + c.reward("wasted") * wasted;
    }

    /** Одна жизнь особи. */
    public static float evaluate(Brain b, int maxTicks, long seed) {
        Sim s = new Sim(seed);
        run(b, s, maxTicks / DECISION_EVERY);
        return s.fitness();
    }

    int decisions;

    /** Одна жизнь. Через это место идут и оценка особи, и замеры — чтобы мерить то же, что отбирается. */
    static void run(Brain b, Sim s, int maxDecisions) {
        Config c = Config.get();
        boolean[] allowed = new boolean[8];
        for (int i = 0; i < 8; i++) allowed[i] = c.action(i);
        while (!s.dead && s.decisions < maxDecisions) {
            s.act(s.steer(b.act(s.sense(), s.mask(allowed))));
            s.decisions++;
        }
    }

    /**
     * Толчок: если особь слишком долго ничего не добивается, несколько ходов подряд ею правит не
     * мозг, а простое правило «развернись в открытую сторону и иди». Это не обучение, а выход из
     * тупика: зависшая особь иначе доживает до конца срока, так и не показав, на что способна.
     * Каждый толчок штрафуется — отбор всё равно предпочитает тех, кто справляется сам.
     */
    int steer(int wanted) {
        Config c = Config.get();
        int survive = survival(c);
        if (survive >= 0) return survive;
        int after = c.i("kick.after");
        if (kickLeft == 0 && after > 0 && streak >= after) {
            kickLeft = Math.max(1, c.i("kick.length"));
            kicks++;
            kickDir = openDirection();
            streak = 0;                   // новый отсчёт, иначе толкали бы на каждом ходу
        }
        if (kickLeft <= 0) return wanted;
        kickLeft--;
        if (kickDir < 0) return feasible(3) ? 3 : 2;              // замурован: пробиваться или крутиться
        if (facing != kickDir) return ((facing + 1) & 3) == kickDir ? 2 : 1;
        if (map[aheadIdx()] == AIR) return 0;
        if (feasible(3)) return 3;
        kickDir = openDirection();
        return 2;
    }

    /**
     * Рефлексы выживания — толчок для того, чему отбор учится слишком медленно, пока особи гибнут:
     * в воде — к берегу; голоден и есть еда — съесть; голоден и рядом дичь — к ней и бить.
     * Возвращает действие или -1, если вмешиваться не нужно.
     */
    int survival(Config c) {
        if (map[idx(x, y)] == WATER && c.i("water.avoid") != 0) return towardShore();
        int eatBelow = c.i("eat.below");
        if (eatBelow > 0 && hunger <= eatBelow && food > 0) return 6;
        int huntBelow = c.i("hunt.below");
        if (huntBelow > 0 && hunger <= huntBelow) {
            int prey = nearest(animals, nAnimals);
            if (prey >= 0) {
                int dx = prey % SIZE - x, dy = prey / SIZE - y;
                if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1) return 5;
                if (dx * dx + dy * dy <= c.i("hunt.range") * c.i("hunt.range")) {
                    int want = Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? 0 : 2) : (dy > 0 ? 1 : 3);
                    if (facing != want) return ((facing + 1) & 3) == want ? 2 : 1;
                    if (map[aheadIdx()] == AIR) return 0;
                    if (feasible(3)) return 3;
                    int side = Math.abs(dx) >= Math.abs(dy) ? (dy >= 0 ? 1 : 3) : (dx >= 0 ? 0 : 2);
                    return ((facing + 1) & 3) == side ? 2 : 1;
                }
            }
        }
        return -1;
    }

    /** Первый шаг кратчайшего пути по воде к ближайшей суше. */
    int towardShore() {
        int n = SIZE * SIZE, start = idx(x, y);
        int[] prev = new int[n];
        java.util.Arrays.fill(prev, -2);
        int[] q = new int[n];
        int head = 0, tail = 0;
        q[tail++] = start;
        prev[start] = -1;
        int[][] step = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        while (head < tail) {
            int cur = q[head++], cx = cur % SIZE, cy = cur / SIZE;
            for (int d = 0; d < 4; d++) {
                int nb = idx(cx + step[d][0], cy + step[d][1]);
                if (prev[nb] != -2) continue;
                if (map[nb] == AIR) {
                    int c = cur;
                    int target = nb;
                    if (cur != start) { while (prev[c] != start) c = prev[c]; target = c; }
                    int tdx = target % SIZE - x, tdy = target / SIZE - y;
                    int want = tdx == 1 ? 0 : tdy == 1 ? 1 : tdx == -1 ? 2 : 3;
                    if (facing != want) return ((facing + 1) & 3) == want ? 2 : 1;
                    return 0;
                }
                if (map[nb] == WATER) { prev[nb] = cur; q[tail++] = nb; }
            }
        }
        return 2;
    }

    int openDirection() {
        int start = rnd.nextInt(4);
        for (int k = 0; k < 4; k++) {
            int d = (start + k) & 3;
            int nx = x + (d == 0 ? 1 : d == 2 ? -1 : 0), ny = y + (d == 1 ? 1 : d == 3 ? -1 : 0);
            if (map[idx(nx, ny)] == AIR) return d;
        }
        return -1;
    }

    int turnsInRow;

    /** Разрешено настройками И выполнимо прямо сейчас. Без второго условия мозг выбирает
     *  «ставить» с пустой сумкой или «крафт» без ресурсов и просто стоит: замер показал, что
     *  треть популяции тратила на такие ходы 60–100% решений. */
    boolean[] mask(boolean[] allowed) {
        boolean[] m = new boolean[8];
        boolean any = false;
        for (int a = 0; a < 8; a++) {
            m[a] = allowed[a] && feasible(a);
            any |= m[a];
        }
        if (!any) {                       // тупик: хоть поворот, но не стоять столбом
            m[1] = allowed[1];
            m[2] = allowed[2];
        }
        return m;
    }

    boolean feasible(int a) {
        int p = aheadIdx();
        return switch (a) {
            case 0 -> map[p] == AIR || (map[p] == WATER && Config.get().i("water.avoid") == 0);
            case 1, 2 -> turnsInRow < 2;   // третий поворот подряд — это уже кружение на месте
            case 3 -> map[p] == TREE || (map[p] == STONE && tech >= 1) || (map[p] == IRON && tech >= 2);
            case 4 -> stone > 0 && map[p] == AIR;
            case 5 -> mobAdjacent() || animalAdjacent();
            case 6 -> food > 0 && hunger <= 14;
            case 7 -> canCraft();
            default -> false;
        };
    }

    boolean mobAdjacent() {
        for (int i = 0; i < nMobs; i++) {
            if (Math.abs(mobs[i] % SIZE - x) <= 1 && Math.abs(mobs[i] / SIZE - y) <= 1) return true;
        }
        return false;
    }
}
