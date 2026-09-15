package naparnik;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Состояние напарника и перевод обученного мозга на настоящий мир.
 *
 * Сенсоры и действия обязаны совпадать с {@link Sim} число в число — веса обучены именно на
 * них. Изменил порядок или смысл слота здесь — старый brain.json молча начинает означать не то,
 * и напарник тупеет без единой ошибки в логах.
 */
public final class Bot {
    public static final String TAG = "naparnik";

    public final Brain brain;
    /** Какая особь популяции проживает эту жизнь. */
    public final int slot;
    public int missing;

    public Bot(Brain brain, int slot) { this.brain = brain; this.slot = slot; }

    /** ponytail: радиус обзора 10 блоков, полный перебор куба на каждом решении (2 раза в секунду).
     *  Потолок — ~3000 обращений к блокам за решение. Упрётся в это при десятке напарников —
     *  кэшировать найденное на несколько решений. */
    static final int SIGHT_Y = 3;
    /** Решений неприкосновенности после появления. */
    static final int GRACE = 20;
    /** Не чаще одного удара за столько игровых тиков — от темпа решений не зависит. */
    static final int HIT_COOLDOWN = 20;

    static int sight() { return Math.max(2, Math.min(24, Config.get().i("sight"))); }

    public int wood, stone, iron, food, tech, mined;
    public int hunger = 20, age;
    public Direction facing = Direction.NORTH;
    /** Точный угол на врага для взгляда; null — смотрит по сторонам света. */
    Float lookYaw;
    public BlockPos home, where;
    public double walked;
    /** Побывал ли в клетке — плотная награда за исследование. Без неё особь, топчущаяся
     *  на месте, получает тот же фитнес, что прошедшая полкарты, и отбору не за что цепляться. */
    public final Set<Long> visited = new HashSet<>();
    public String lastAction = "просыпается";
    public int kills, hits, wasted, picked, built, sheltered, rivals, woodMined;

    /** Простой и помощь при зависании — те же правила, что в Sim.steer(). */
    public int streak, stagnant, repeats, kicks;
    int kickLeft;
    Direction kickDir;
    public boolean kicking;
    /** Подъём из ямы: сколько решений подряд в яме, идёт ли подъём сейчас, сколько раз поднимался. */
    int pitStreak;
    public boolean climbing;
    public int climbs, hunted, eats;
    /** Какой рефлекс сейчас правит: пусто, «ест», «охотится», «к берегу». */
    public String reflex = "";

    /** Всё, что поднял за жизнь, по предметам. Не уменьшается при трате — это «что добыл». */
    public final Map<Item, Integer> collected = new HashMap<>();
    /** Настоящие блоки в сумке: чем строить. Ключ — предмет, значение — сколько. */
    final Map<Item, Integer> blocks = new HashMap<>();
    /** Какие действия особь вообще попробовала. За каждое новое платим — иначе выгодно
     *  долбить одно и то же, и репертуар не растёт. */
    final boolean[] tried = new boolean[8];
    /** Блоки, поставленные им самим. Ломать их можно, но в добычу они не идут:
     *  иначе цикл «поставил булыжник — сломал» накручивает фитнес из ничего. */
    final Set<Long> placed = new HashSet<>();
    final Set<Long> everBuilt = new HashSet<>();
    /** Найдено ли тело в мире: null = ещё не проверяли. Третье состояние обязательно —
     *  без него "ещё не знаю" неотличимо от "не найдено", и детектор молчит ровно там,
     *  где должен кричать. */
    public Boolean bodyFound;
    /** Нашлось ли тело в этом круге обхода. */
    public boolean seen;

    static final String[] ACTION = {
        "идёт", "поворот влево", "поворот вправо", "копает", "ставит блок", "бьётся", "ест", "мастерит"
    };

    /** Что напарник видит вокруг — человекочитаемо, для ручного управления снаружи. */
    public String view(ServerLevel lv, Mob body) {
        BlockPos at = body.blockPosition();
        StringBuilder sb = new StringBuilder();
        sb.append("позиция ").append(at.getX()).append(' ').append(at.getY()).append(' ').append(at.getZ())
          .append(" смотрит ").append(facing.getName()).append('\n');
        sb.append("здоровье ").append(Math.round(body.getHealth())).append("/20 сытость ")
          .append(hunger).append("/20\n");
        sb.append("впереди ").append(lv.getBlockState(at.relative(facing)).getBlock().getName().getString())
          .append(passable(lv, at.relative(facing)) ? " (пройти можно)" : " (блокирует)").append('\n');
        sb.append("под ногами ").append(lv.getBlockState(at.below()).getBlock().getName().getString()).append('\n');
        sb.append(near("дерево", at, nearestBlock(lv, at, BlockTags.LOGS)));
        sb.append(near("камень", at, nearestBlock(lv, at, BlockTags.BASE_STONE_OVERWORLD)));
        sb.append(near("железо", at, nearestBlock(lv, at, BlockTags.IRON_ORES)));
        LivingEntity tg = nearestTarget(lv, body, sight());
        sb.append(near("цель для удара", at, tg == null ? null : tg.blockPosition()));
        sb.append(near("лежит предмет", at, nearestItem(lv, body)));
        sb.append("сумка: дерево ").append(wood).append(" камень ").append(stone)
          .append(" железо ").append(iron).append(" еда ").append(food)
          .append(" блоков ").append(blocks.values().stream().mapToInt(Integer::intValue).sum()).append('\n');
        sb.append("тех ").append(tech).append(" добыто ").append(mined).append(" поднял ").append(picked)
          .append(" построил ").append(built).append(" решений ").append(age).append('\n');
        return sb.toString();
    }

    String near(String what, BlockPos from, BlockPos target) {
        if (target == null) return what + ": не вижу\n";
        int dx = target.getX() - from.getX(), dy = target.getY() - from.getY(), dz = target.getZ() - from.getZ();
        return what + ": " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " блоков"
                + " (x" + (dx >= 0 ? "+" : "") + dx + " y" + (dy >= 0 ? "+" : "") + dy
                + " z" + (dz >= 0 ? "+" : "") + dz + ")\n";
    }

    public void step(ServerLevel lv, Mob body, int generation, int life) {
        step(lv, body, generation, life, -1);
    }

    /** override >= 0 — действие задано снаружи, мозг в этот раз не спрашиваем. */
    public void step(ServerLevel lv, Mob body, int generation, int life, int override) {
        age++;
        if (home == null) home = body.blockPosition();
        BlockPos before = body.blockPosition();
        int progressBefore = visited.size() + mined + built + picked + hits;
        visited.add(before.asLong());
        clock(lv, body);
        pickup(lv, body);
        lookYaw = null;
        double engage = Math.max(0, Config.get().f("faceEnemyRange"));
        /* Разворот только на враждебного моба. Раньше он наводился и на соседнего напарника:
           все появляются в одной точке, каждый целится в соседа, маска делает удар всегда
           доступным — и трое убивали друг друга за доли секунды, молча, без сообщения о смерти. */
        LivingEntity foe = engage > 0 ? nearestEnemy(lv, body, engage) : null;
        if (foe != null) {
            /* ponytail: вблизи врага напарник разворачивается к нему сам, не дожидаясь, пока мозг
               «изобретёт» поворот. Цена — в этом радиусе он не может убежать: вперёд значит на
               врага. Радиус крутится в настройках, 0 выключает рефлекс. */
            float yaw = (float) Math.toDegrees(Math.atan2(-(foe.getX() - body.getX()), foe.getZ() - body.getZ()));
            facing = Direction.fromYRot(yaw);
            lookYaw = yaw;
        }
        Config cfg = Config.get();
        boolean[] allowed = new boolean[8];
        for (int i = 0; i < 8; i++) allowed[i] = cfg.action(i);
        boolean[] can = mask(lv, body, allowed);
        int a = override >= 0 ? override : brain.act(sense(lv, body), can);
        /* ponytail: доля случайных решений с перекосом в деятельные (идти, копать, ставить, бить).
           Это рукотворная подсказка, а не обучение: без неё отбор сначала должен изобрести саму
           мысль «попробовать копнуть». Потолок — шум мешает оценивать особь; долю снижать в
           настройках, когда поведение станет осмысленным. */
        if (override < 0 && body.getRandom().nextFloat() < cfg.f("explore")) {
            int[] active = {0, 3, 3, 4, 5, 5};
            int pick = active[body.getRandom().nextInt(active.length)];
            if (can[pick]) a = pick;
        }
        kicking = false;
        if (override < 0) a = steer(lv, body, a);
        if (a == SWIM_UP) {
            lastAction = "всплывает";
            body.teleportTo(body.getX(), body.getY() + 1, body.getZ());
        } else {
            lastAction = ACTION[a] + (!reflex.isEmpty() ? " (" + reflex + ")" : climbing ? " (выбирается наверх)" : kicking ? " (толчок)" : "");
            tried[a] = true;
            turnsInRow = a == 1 || a == 2 ? turnsInRow + 1 : 0;
            if (!act(lv, body, a)) wasted++;
        }
        /* Решение видно прямо над головой — иначе "он думает" и "он завис" выглядят одинаково. */
        /* Здоровье, сытость и воздух видны прямо над головой, как полоски у игрока. */
        String vitals = " \u00B7 \u2764" + Math.round(body.getHealth()) + " \u00B7 сыт " + hunger
                + (body.getAirSupply() < body.getMaxAirSupply() ? " \u00B7 воздух " + Math.max(0, body.getAirSupply() / 20) : "");
        body.setCustomName(Component.literal(
                "Напарник " + generation + "\u00B7" + life + " \u00B7 " + lastAction + vitals));
        float yaw = lookYaw != null ? lookYaw : facing.toYRot();
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        if (shelteredAt(lv, body.blockPosition())) sheltered++;
        BlockPos now = body.blockPosition();
        if (!now.equals(before)) walked += Math.sqrt(now.distSqr(before));
        visited.add(now.asLong());
        where = now;
        health = Math.round(body.getHealth());
        maxHealth = Math.round(body.getMaxHealth());
        air = body.getAirSupply();
        maxAir = body.getMaxAirSupply();
        /* Прогресс — новая клетка, добыча, постройка в новом месте, подобранный предмет, попадание.
           Кружение, «туда-обратно» и «поставил-сломал» прогрессом не считаются. */
        if (visited.size() + mined + built + picked + hits > progressBefore) streak = 0;
        else if (++streak > Config.get().i("stagnation.grace")) stagnant++;
    }

    static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** Всплыть на блок. Мозгу недоступно — только рефлекс под водой. */
    static final int SWIM_UP = 8;

    /**
     * Толчок: если напарник слишком долго ничего не добивается, несколько ходов подряд им правит
     * не мозг, а правило «развернись в открытую сторону и иди». Выход из тупика, а не обучение.
     * Каждый толчок штрафуется, так что отбор всё равно предпочитает тех, кто справляется сам.
     */
    int steer(ServerLevel lv, Mob body, int wanted) {
        Config c = Config.get();
        int survive = survival(lv, body, c);
        if (survive >= 0) return survive;
        reflex = "";
        int climbAfter = c.i("climb.after");
        /* Гистерезис: начинать подъём при глубине от 3 блоков (у одиночного дерева ложной ямы не будет),
           а продолжать, пока вокруг выше хотя бы на блок, — иначе рефлекс гас за два шага до края. */
        boolean pit = climbAfter > 0 && (climbing ? inPit(lv, body, 1, 3) : inPit(lv, body, 3, 4));
        pitStreak = pit ? pitStreak + 1 : 0;
        if (pit && (climbing || pitStreak >= climbAfter)) {
            if (!climbing) climbs++;
            climbing = true;
            return climbAction(lv, body);
        }
        climbing = false;
        int after = c.i("kick.after");
        if (kickLeft == 0 && after > 0 && streak >= after) {
            kickLeft = Math.max(1, c.i("kick.length"));
            kicks++;
            kickDir = openDirection(lv, body);
            streak = 0;                   // новый отсчёт, иначе толкали бы на каждом ходу
        }
        if (kickLeft <= 0) return wanted;
        kickLeft--;
        kicking = true;
        BlockPos front = body.blockPosition().relative(facing);
        if (kickDir == null) return digTarget(lv, body) != null ? 3 : 2;   // замурован: пробиваться или крутиться
        if (facing != kickDir) return facing.getClockWise() == kickDir ? 2 : 1;
        if (canStep(lv, body)) return 0;
        if (digTarget(lv, body) != null) return 3;
        kickDir = openDirection(lv, body);
        return 2;
    }

    /**
     * «В яме»: поверхность без учёта листвы хотя бы с трёх сторон из четырёх выше на 3 блока и больше.
     * Листву не считаем, иначе под кроной леса напарник решил бы, что сидит в яме; три стороны из
     * четырёх — чтобы одиночный ствол дерева рядом не превращался в яму.
     */
    boolean inPit(ServerLevel lv, Mob body, int depth, int sides) {
        BlockPos at = body.blockPosition();
        int high = 0;
        for (Direction d : HORIZONTAL) {
            BlockPos probe = at.relative(d, 2);
            if (lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.getX(), probe.getZ()) >= at.getY() + depth) high++;
        }
        /* Начинать — только когда высоко со всех четырёх сторон (при трёх из четырёх рефлекс срабатывал в лесу
           и срезал обход на четверть). Продолжать — пока высоко с трёх: на середине лестницы одна сторона
           уже открыта, и при строгом условии подъём обрывался. */
        return high >= sides;
    }

    /**
     * Лестница наверх, как её строит игрок: упереться в стену, освободить место над ступенькой,
     * шагнуть вверх — и так до поверхности. Стены впереди нет — поставить ступеньку из сумки.
     */
    int climbAction(ServerLevel lv, Mob body) {
        BlockPos front = body.blockPosition().relative(facing);
        if (!soft(lv, front)) {
            if (passable(lv, front.above())) return 0;               // ступенька готова — шаг вверх
            return digTarget(lv, body) != null ? 3 : 2;              // голова или потолок; порода — другая стена
        }
        if (!blocks.isEmpty() && lv.getBlockState(front).isAir()) return 4;   // сделать ступеньку самому
        return 2;                                                    // искать стену поворотом
    }

    /**
     * Рефлексы выживания — толчок для того, чему отбор учится слишком медленно, пока особи гибнут.
     * Под водой — к берегу; голоден и есть еда — съесть; голоден и рядом дичь — к ней и бить.
     * Возвращает действие или -1, если вмешиваться не нужно. Те же правила, что в Sim.survival().
     */
    int survival(ServerLevel lv, Mob body, Config c) {
        /* Под водой сначала всплыть, как игрок, и только у поверхности идти к берегу. Раньше напарник
           шёл по дну и рыл лестницу у берега — каждый выкопанный блок заливало, и все четверо утонули. */
        if (avoidWater() && body.isUnderWater() && soft(lv, body.blockPosition().above(2))) {
            reflex = "всплывает";
            return SWIM_UP;
        }
        /* В мелкой воде — к берегу, только если берег нашёлся. Раньше рефлекс срабатывал и в луже у
           моря, не находил берега и бесконечно крутился, а охота ниже по приоритету так и не наступала. */
        if (avoidWater() && feetWet(lv, body)) {
            int shore = towardShore(lv, body);
            if (shore >= 0) { reflex = "к берегу"; return shore; }
        }
        int eatBelow = c.i("eat.below");
        if (eatBelow > 0 && hunger <= eatBelow && food > 0) { reflex = "ест"; return 6; }
        int huntBelow = c.i("hunt.below");
        if (huntBelow > 0 && hunger <= huntBelow && c.attacks("animals")) {
            LivingEntity prey = nearestPrey(lv, body, Math.max(1, c.i("hunt.range")));
            if (prey != null) {
                reflex = "охотится";
                float yaw = (float) Math.toDegrees(Math.atan2(-(prey.getX() - body.getX()), prey.getZ() - body.getZ()));
                facing = Direction.fromYRot(yaw);
                lookYaw = yaw;
                if (body.tickCount - lastHitTick >= HIT_COOLDOWN && targetInFront(lv, body) != null) return 5;
                if (canStep(lv, body) && !waterAhead(lv, body)) return 0;
                if (digTarget(lv, body) != null) return 3;
                return 2;
            }
        }
        return -1;
    }

    /** Дичь — животные, с которых падает мясо. Рыбу не ловим: за ней пришлось бы лезть в воду. */
    LivingEntity nearestPrey(ServerLevel lv, Mob body, double range) {
        LivingEntity best = null;
        double bd = range * range;
        for (Animal m : lv.getEntitiesOfClass(Animal.class, body.getBoundingBox().inflate(range))) {
            if (!m.isAlive()) continue;
            double d = m.distanceToSqr(body);
            if (d <= bd) { bd = d; best = m; }
        }
        return best;
    }

    boolean feetWet(ServerLevel lv, Mob body) {
        return !lv.getBlockState(body.blockPosition()).getFluidState().isEmpty();
    }

    /** К ближайшей суше: смотрим по сторонам до 16 блоков. Берега не видно — -1, пусть решает мозг. */
    int towardShore(ServerLevel lv, Mob body) {
        BlockPos at = body.blockPosition();
        Direction best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Direction d : HORIZONTAL) {
            for (int k = 1; k <= 16; k++) {
                BlockPos p = at.relative(d, k);
                boolean dry = lv.getBlockState(p).getFluidState().isEmpty() && lv.getBlockState(p.above()).getFluidState().isEmpty();
                if (dry) { if (k < bestDist) { bestDist = k; best = d; } break; }
            }
        }
        if (best == null) return -1;
        if (facing != best) return facing.getClockWise() == best ? 2 : 1;
        if (canStep(lv, body)) return 0;
        if (digTarget(lv, body) != null) return 3;
        return 2;
    }

    Direction openDirection(ServerLevel lv, Mob body) {
        int start = body.getRandom().nextInt(4);
        for (int k = 0; k < 4; k++) {
            Direction d = HORIZONTAL[(start + k) % 4];
            BlockPos f = body.blockPosition().relative(d);
            boolean wet = !lv.getBlockState(f).getFluidState().isEmpty();
            if (!(avoidWater() && wet && !feetWet(lv, body)) && (passable(lv, f) || (!soft(lv, f) && passable(lv, f.above())))) return d;
        }
        return null;
    }

    int hungerTicks, starveTicks, regenTicks;
    public int health = 20, maxHealth = 20, air = 300, maxAir = 300;
    /** Причина последнего урона — она же причина смерти: «голод», «вода», «моб». */
    public String lastDamage;
    /** Погиб, а не дожил до конца срока: идёт в штраф фитнеса. */
    public boolean died;

    /**
     * Голод как у игрока, по игровым тикам, а не по решениям — ускорение темпа решений не должно
     * морить голодом быстрее. Сытость падает раз в hunger.drainTicks; на нуле — настоящий урон
     * «голод» раз в 80 тиков до смерти, как на сложном уровне; сытость 18 и выше лечит раз в 80 тиков.
     * Утопление здесь нет намеренно: ванильная игра уже топит жителей, как игрока, — проверено
     * живьём; своё утопление поверх дало бы двойной урон.
     */
    void clock(ServerLevel lv, Mob body) {
        int dt = NaparnikMod.decisionEvery();
        int drain = Math.max(1, Config.get().i("hunger.drainTicks"));
        for (hungerTicks += dt; hungerTicks >= drain; hungerTicks -= drain) if (hunger > 0) hunger--;
        if (hunger == 0) {
            for (starveTicks += dt; starveTicks >= 80; starveTicks -= 80) {
                lastDamage = "голод";
                body.hurtServer(lv, lv.damageSources().starve(), 1f);
            }
        } else {
            starveTicks = 0;
        }
        if (hunger >= 18 && body.getHealth() < body.getMaxHealth()) {
            for (regenTicks += dt; regenTicks >= 80; regenTicks -= 80) body.heal(1f);
        } else {
            regenTicks = 0;
        }
        if (body.getAirSupply() <= 0) {
            lastDamage = "вода";
        } else if (body.hurtTime > 0 && body.getLastHurtByMob() != null) {
            lastDamage = body.getLastHurtByMob().entityTags().contains(TAG) ? "напарник" : "моб";
        }
    }

    // ---------- сенсоры ----------

    /** 30 входов — раскладка обязана совпадать с Sim.sense() число в число. */
    float[] sense(ServerLevel lv, Mob body) {
        float[] s = new float[Brain.IN];
        BlockPos at = body.blockPosition();

        s[0] = body.getHealth() / 10f - 1f;
        s[1] = hunger / 10f - 1f;
        double t = 2 * Math.PI * (lv.getDefaultClockTime() % 24000L) / 24000.0;   // в 26.2 сутки живут в мировых часах
        s[2] = (float) Math.sin(t);
        s[3] = (float) Math.cos(t);

        BlockPos front = at.relative(facing);
        boolean solid = !soft(lv, front);
        /* Смысл входов как в симуляторе: «впереди не пройти», «есть что ломать», «это бревно».
           Высоту симулятор не знает, поэтому блок для копки выбирает тело, а не мозг. */
        BlockPos toDig = digTarget(lv, body);
        s[4] = canStep(lv, body) ? -1f : 1f;
        s[5] = toDig != null ? 1f : -1f;
        s[6] = toDig != null && lv.getBlockState(toDig).is(BlockTags.LOGS) ? 1f : -1f;

        bearing(s, 7, at, nearestBlock(lv, at, BlockTags.LOGS));
        bearing(s, 10, at, nearestBlock(lv, at, tech < 2 ? BlockTags.BASE_STONE_OVERWORLD : BlockTags.IRON_ORES));
        LivingEntity target = nearestTarget(lv, body, sight());
        bearing(s, 13, at, target == null ? null : target.blockPosition());
        bearing(s, 16, at, nearestItem(lv, body));

        s[19] = canCraft() ? 1f : -1f;
        int bag = 0;
        for (int n : blocks.values()) bag += n;
        s[20] = Math.min(1f, bag / 16f) * 2f - 1f;
        s[21] = Math.min(1f, food / 4f) * 2f - 1f;
        s[22] = body.hurtTime > 0 ? 1f : -1f;
        s[23] = solid && passable(lv, front.above()) ? 1f : -1f;
        int walls = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!soft(lv, at.relative(d))) walls++;
        s[24] = walls / 2f - 1f;
        s[25] = Math.max(0, body.getAirSupply()) / (body.getMaxAirSupply() / 2f) - 1f;   // полный воздух = 1
        s[26] = lv.getBlockState(front).getFluidState().isEmpty() ? -1f : 1f;           // впереди вода
        LivingEntity prey = nearestPrey(lv, body, sight());
        bearing(s, 27, at, prey == null ? null : prey.blockPosition());                 // ближайшая дичь
        return s;
    }

    /** Три слота: близость, синус и косинус угла на цель относительно взгляда. */
    void bearing(float[] s, int idx, BlockPos from, BlockPos target) {
        if (target == null) return;
        double dx = target.getX() - from.getX(), dz = target.getZ() - from.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        double rel = Math.atan2(dz, dx) - Math.atan2(facing.getStepZ(), facing.getStepX());
        s[idx] = (float) Math.max(0, 1 - d / 32.0);
        s[idx + 1] = (float) Math.sin(rel);
        s[idx + 2] = (float) Math.cos(rel);
    }

    BlockPos nearestBlock(ServerLevel lv, BlockPos from, TagKey<Block> tag) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                from.offset(-sight(), -SIGHT_Y, -sight()), from.offset(sight(), SIGHT_Y, sight()))) {
            if (!lv.getBlockState(p).is(tag)) continue;
            double d = p.distSqr(from);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    /** Можно ли бить это существо. Решают настройки: мобы, другие напарники, мирные животные,
     *  игроки. По умолчанию игроки и животные выключены — напарник не должен бросаться на тебя. */
    static boolean isTarget(LivingEntity e, Mob body) {
        if (e == body || !e.isAlive()) return false;
        Config c = Config.get();
        if (e instanceof Player) return c.attacks("players");
        if (e.entityTags().contains(TAG)) {
            Bot other = NaparnikMod.BOTS.get(e.getUUID());
            // только что появившегося не бьют: иначе возрождение в толпе — это сразу новая смерть
            if (other != null && other.age < GRACE) return false;
            return c.attacks("rivals");
        }
        if (e instanceof Enemy) return c.attacks("monsters");
        return c.attacks("animals");
    }

    LivingEntity nearestTarget(ServerLevel lv, Mob body, double range) {
        LivingEntity best = null;
        double bd = range * range;
        for (LivingEntity m : lv.getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(range))) {
            if (!isTarget(m, body)) continue;
            double d = m.distanceToSqr(body);
            if (d <= bd) { bd = d; best = m; }
        }
        return best;
    }

    LivingEntity nearestEnemy(ServerLevel lv, Mob body, double range) {
        LivingEntity best = null;
        double bd = range * range;
        for (LivingEntity m : lv.getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(range))) {
            if (!(m instanceof Enemy) || !isTarget(m, body)) continue;
            double d = m.distanceToSqr(body);
            if (d <= bd) { bd = d; best = m; }
        }
        return best;
    }

    BlockPos nearestItem(ServerLevel lv, Mob body) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity it : lv.getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(sight()))) {
            double d = it.distanceToSqr(body);
            if (d < bd) { bd = d; best = it.blockPosition(); }
        }
        return best;
    }

    boolean diggable(ServerLevel lv, BlockPos p) {
        BlockState st = lv.getBlockState(p);
        if (soft(lv, p) || st.getDestroySpeed(lv, p) < 0) return false;
        if (st.is(BlockTags.IRON_ORES) && tech < 2) return false;
        return !st.is(BlockTags.BASE_STONE_OVERWORLD) || tech >= 1;
    }

    /** Подбирает всё, что лежит рядом: свой дроп и брошенное игроком. Сделано рефлексом,
     *  а не действием мозга — иначе отбору пришлось бы сначала изобрести «подойти и поднять»,
     *  и до строительства он не дожил бы никогда. */
    void pickup(ServerLevel lv, Mob body) {
        for (ItemEntity it : lv.getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(2.0))) {
            ItemStack st = it.getItem();
            if (st.isEmpty()) continue;
            int n = st.getCount();
            Item item = st.getItem();
            if (st.is(ItemTags.LOGS) || st.is(ItemTags.PLANKS)) wood += n;
            else if (item == Items.RAW_IRON || item == Items.IRON_INGOT) iron += n;
            else if (FOOD.contains(item)) food += n;
            else if (item instanceof BlockItem) stone += n;
            if (item instanceof BlockItem) blocks.merge(item, n, Integer::sum);
            picked += n;
            collected.merge(item, n, Integer::sum);
            it.discard();
        }
    }

    /* ponytail: список еды перечислен руками вместо разбора компонента питания — восемь строк
       против возни с DataComponents. Потолок — незнакомая еда просто не считается едой. */
    static final Set<Item> FOOD = Set.of(
            Items.APPLE, Items.BREAD, Items.CARROT, Items.POTATO, Items.BAKED_POTATO, Items.BEETROOT,
            Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP,
            Items.MUTTON, Items.COOKED_MUTTON, Items.CHICKEN, Items.COOKED_CHICKEN,
            Items.RABBIT, Items.COOKED_RABBIT, Items.COD, Items.COOKED_COD, Items.SALMON, Items.COOKED_SALMON,
            Items.SWEET_BERRIES, Items.GLOW_BERRIES, Items.MELON_SLICE, Items.COOKIE, Items.PUMPKIN_PIE, Items.DRIED_KELP);

    // ---------- действия ----------

    int turnsInRow;
    int lastHitTick = -HIT_COOLDOWN;

    /** Разрешено настройками И выполнимо прямо сейчас. Без второго условия мозг выбирает
     *  «ставить» с пустой сумкой или «крафт» без ресурсов и стоит: замер показал, что так
     *  уходило 44% решений, а напарник проходил вдвое меньше и добывал втрое меньше. */
    boolean[] mask(ServerLevel lv, Mob body, boolean[] allowed) {
        boolean[] m = new boolean[8];
        boolean any = false;
        for (int a = 0; a < 8; a++) {
            m[a] = allowed[a] && feasible(lv, body, a);
            any |= m[a];
        }
        if (!any) {                       // тупик: хоть поворот, но не стоять столбом
            m[1] = allowed[1];
            m[2] = allowed[2];
        }
        return m;
    }

    boolean feasible(ServerLevel lv, Mob body, int a) {
        BlockPos front = body.blockPosition().relative(facing);
        return switch (a) {
            case 0 -> canStep(lv, body) && !(avoidWater() && !feetWet(lv, body) && waterAhead(lv, body));   // уже в воде — по ней можно
            case 1, 2 -> turnsInRow < 2;   // третий поворот подряд — это уже кружение на месте
            case 3 -> digTarget(lv, body) != null;
            case 4 -> !blocks.isEmpty() && lv.getBlockState(front).isAir();
            case 5 -> body.tickCount - lastHitTick >= HIT_COOLDOWN && targetInFront(lv, body) != null;
            case 6 -> food > 0 && hunger <= 14;
            case 7 -> canCraft();
            default -> false;
        };
    }

    /** Цель в радиусе удара и перед собой, в конусе ±60°. */
    LivingEntity targetInFront(ServerLevel lv, Mob body) {
        double fx = facing.getStepX(), fz = facing.getStepZ();
        for (LivingEntity m : lv.getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(2.5))) {
            if (!isTarget(m, body)) continue;
            double dx = m.getX() - body.getX(), dz = m.getZ() - body.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len <= 0.3 || (dx * fx + dz * fz) / len >= 0.5) return m;
        }
        return null;
    }

    /** Возвращает, изменилось ли хоть что-то. Ложь — действие было впустую: копнул воздух,
     *  ударил в пустоту, поел без еды. Такие решения штрафуются в фитнесе. */
    boolean act(ServerLevel lv, Mob body, int a) {
        return switch (a) {
            case 0 -> forward(lv, body);
            case 1 -> { facing = facing.getCounterClockWise(); yield true; }
            case 2 -> { facing = facing.getClockWise(); yield true; }
            case 3 -> dig(lv, body);
            case 4 -> place(lv, body);
            case 5 -> attack(lv, body);
            case 6 -> eat();
            case 7 -> craft();
            default -> false;
        };
    }

    boolean forward(ServerLevel lv, Mob body) {
        BlockPos to = body.blockPosition().relative(facing);
        if (!passable(lv, to)) {
            if (!soft(lv, to) && passable(lv, to.above())) to = to.above();
            else return false;
        }
        /* ponytail: шаг телепортом на клетку вместо ходьбы по навигации — ИИ жителя выключен,
           а вместе с ним и навигация. Падение ограничено четырьмя блоками, иначе шаг в обрыв
           оставляет напарника висеть в воздухе. Апгрейд — своя сущность со своим MoveControl. */
        int drop = 0;
        while (drop < 4 && soft(lv, to.below())) { to = to.below(); drop++; }
        body.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        return true;
    }

    /** Проходимо = воздух или жидкость. Вода не пол: раньше напарник ходил по её поверхности,
     *  потому что проверка спуска смотрела только на isAir(). */
    /** Стоит ли он сейчас в укрытии: крыша над головой и хотя бы три стены. Это и есть
     *  проверяемый признак «построил дом», а не «расставил блоки». */
    boolean shelteredAt(ServerLevel lv, BlockPos p) {
        boolean roof = false;
        for (int dy = 1; dy <= 3; dy++) if (!soft(lv, p.above(dy))) { roof = true; break; }
        if (!roof) return false;
        int walls = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!soft(lv, p.relative(d))) walls++;
        return walls >= 3;
    }

    boolean passable(ServerLevel lv, BlockPos p) {
        return soft(lv, p) && soft(lv, p.above());
    }

    /** Проходимо = не мешает движению. Именно так, а не «воздух или вода»: трава, цветы и
     *  снег не воздух, но пройти сквозь них можно — раньше напарник об них спотыкался, и
     *  трава на верхнем блоке вдобавок запрещала запрыгнуть, занимая место под голову. */
    static boolean soft(ServerLevel lv, BlockPos p) {
        return !lv.getBlockState(p).blocksMotion();
    }

    /** Ломает почти всё, до чего дотянулся: землю, траву, песок, листву, дерево, камень.
     *  Раньше копались только брёвна, камень и железная руда — а перед напарником в настоящем
     *  мире почти всегда трава или земля, поэтому копка возвращала «впустую» и выглядело это
     *  так, будто он не пытается вовсе. Тир нужен только под камень и железо, как в игре. */
    /**
     * Какой блок ломать, как это сделал бы игрок, глядя на препятствие. Раньше ломался только блок
     * перед собой на уровне ног: блок на уровне головы оставался, пройти всё равно было нельзя, и из
     * ямы напарник не выбирался никогда — проверено шахтой: 0 выбравшихся из 5.
     * Порядок: голова впереди — без неё не пройти ни прямо, ни на ступеньку; потолок над ступенькой —
     * без него не подняться; иначе блок у ног. Так из ямы получается лестница вверх.
     */
    BlockPos digTarget(ServerLevel lv, Mob body) {
        BlockPos front = body.blockPosition().relative(facing);
        BlockPos head = front.above(), top = front.above(2);
        if (!soft(lv, head)) return diggable(lv, head) ? head : null;
        if (!soft(lv, front)) {
            if (!soft(lv, top)) return diggable(lv, top) ? top : null;
            return diggable(lv, front) ? front : null;
        }
        return null;
    }

    /** Можно ли сейчас шагнуть вперёд: прямо или на ступеньку. */
    static boolean avoidWater() { return Config.get().i("water.avoid") != 0; }

    /** Вода впереди на уровне ног или головы — туда по своей воле не шагаем: особи намеренно топились. */
    boolean waterAhead(ServerLevel lv, Mob body) {
        BlockPos front = body.blockPosition().relative(facing);
        return !lv.getBlockState(front).getFluidState().isEmpty() || !lv.getBlockState(front.above()).getFluidState().isEmpty();
    }

    boolean canStep(ServerLevel lv, Mob body) {
        BlockPos front = body.blockPosition().relative(facing);
        return passable(lv, front) || (!soft(lv, front) && passable(lv, front.above()));
    }

    boolean dig(ServerLevel lv, Mob body) {
        BlockPos p = digTarget(lv, body);
        if (p == null) return false;
        BlockState st = lv.getBlockState(p);
        if (st.isAir()) return false;
        if (st.getDestroySpeed(lv, p) < 0) return false;                      // коренная порода
        if (st.is(BlockTags.IRON_ORES) && tech < 2) return false;
        if (st.is(BlockTags.BASE_STONE_OVERWORLD) && tech < 1) return false;

        if (placed.remove(p.asLong())) {
            repeats++;                    // сломал свой же блок — повтор, а не добыча
            /* Свой блок уходит обратно в сумку без дропа: иначе он падает, подбирается и
               засчитывается «поднял», а петля «поставил-сломал» платит стоя на месте. */
            Item item = st.getBlock().asItem();
            if (!lv.destroyBlock(p, false, body, 512)) return false;
            if (item instanceof BlockItem) blocks.merge(item, 1, Integer::sum);
            return true;
        }
        boolean log = st.is(BlockTags.LOGS);
        if (!lv.destroyBlock(p, true, body, 512)) return false;
        mined++;
        if (log) woodMined++;
        return true;
    }

    boolean place(ServerLevel lv, Mob body) {
        Item best = null;
        int max = 0;
        for (Map.Entry<Item, Integer> e : blocks.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); best = e.getKey(); }
        }
        if (best == null) return false;
        BlockPos p = body.blockPosition().relative(facing);
        if (!lv.getBlockState(p).isAir()) return false;

        lv.setBlockAndUpdate(p, ((BlockItem) best).getBlock().defaultBlockState());
        blocks.merge(best, -1, Integer::sum);
        if (blocks.get(best) <= 0) blocks.remove(best);
        if (stone > 0) stone--;
        placed.add(p.asLong());
        /* Платим только за клетку, где ещё не строил: иначе один и тот же блок, поставленный
           сотню раз, считается сотней построек. За блок, приставленный к своим, — втрое:
           так отбор идёт от «накидал где попало» к стене, а от стены к коробке. */
        if (everBuilt.add(p.asLong())) {
            built++;
            int touch = 0;
            for (Direction d : Direction.values()) if (placed.contains(p.relative(d).asLong())) touch++;
            if (touch >= 2) built += 2;
        } else {
            repeats++;                    // в этой клетке уже строил — повтор
        }
        return true;
    }

    /** Бьёт любую живую цель рядом: моба, игрока и другого напарника. Соперник выбывает
     *  из жизни с низким фитнесом, победитель забирает премию — отбор идёт и через драку. */
    boolean attack(ServerLevel lv, Mob body) {
        if (body.tickCount - lastHitTick < HIT_COOLDOWN) return false;
        LivingEntity m = targetInFront(lv, body);
        if (m == null) return false;
        lastHitTick = body.tickCount;
        boolean rival = m.entityTags().contains(TAG);
        /* Настоящий урон от атакующего, как у игрока. Раньше здоровье ставилось напрямую: такая
           смерть не пишет сообщения, не оставляет убийцы, и соперника, убитого соседом, нельзя
           было отличить от умершего с голоду. */
        Bot victim = NaparnikMod.BOTS.get(m.getUUID());
        if (victim != null) victim.lastDamage = "напарник";
        if (!m.hurtServer(lv, lv.damageSources().mobAttack(body), 4f)) return false;
        hits++;
        if (m.isDeadOrDying()) {
            kills++;
            if (rival) rivals++;
            if (m instanceof Animal) hunted++;
        }
        return true;
    }

    boolean eat() {
        if (food <= 0 || hunger > 14) return false;
        food--;
        eats++;
        hunger = Math.min(20, hunger + 6);
        return true;
    }

    boolean canCraft() {
        if (tech >= Sim.RECIPES.length) return false;
        int[] r = Sim.RECIPES[tech];
        return wood >= r[0] && stone >= r[1] && iron >= r[2];
    }

    boolean craft() {
        if (!canCraft()) return false;
        int[] r = Sim.RECIPES[tech];
        wood -= r[0]; stone -= r[1]; iron -= r[2];
        tech++;
        return true;
    }

    /** Платим за действие. Выживание весит мало намеренно: дожить до конца срока может любой,
     *  включая того, кто все 240 решений топтался на месте — такой фитнес не разделяет особей. */
    public float fitness() {
        Config c = Config.get();
        int repertoire = 0;
        for (boolean b : tried) if (b) repertoire++;
        return c.reward("age") * age
                + c.reward("visited") * visited.size()
                + c.reward("mined") * mined
                + c.reward("picked") * picked
                + c.reward("built") * built
                + c.reward("sheltered") * sheltered
                + c.reward("hits") * hits
                + c.reward("kills") * kills
                + c.reward("rivals") * rivals
                + c.reward("repertoire") * repertoire
                + c.reward("tech") * tech
                + c.reward("wood") * woodMined
                + c.reward("stagnation") * stagnant
                + c.reward("repeat") * repeats
                + c.reward("kick") * kicks
                + (died ? c.reward("death") : 0)
                + c.reward("hunt") * hunted
                + c.reward("eat") * eats
                + c.reward("wasted") * wasted;
    }

    static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    static String itemsText(Map<Item, Integer> items) {
        if (items.isEmpty()) return "пусто";
        StringBuilder sb = new StringBuilder();
        items.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> sb.append(sb.length() == 0 ? "" : ", ").append(itemId(e.getKey())).append(" ×").append(e.getValue()));
        return sb.length() == 0 ? "пусто" : sb.toString();
    }

    static final String[] TECH_NAME = {"голые руки", "деревянная кирка", "каменная кирка", "железная кирка"};

    public String describe() {
        return "Напарник: " + TECH_NAME[Math.min(tech, TECH_NAME.length - 1)]
                + " | добыто " + mined + ", поднял " + picked + ", построил " + built
                + ", под крышей " + sheltered + ", попал " + hits + ", убил " + kills + " (соперников " + rivals + ")"
                + ", впустую " + wasted
                + " | дерево " + wood + ", камень " + stone + ", железо " + iron + ", еда " + food
                + " | сытость " + hunger + "/20"
                + " | решений прожито " + age
                + (where == null ? "" : " | сейчас " + where.getX() + " " + where.getY() + " " + where.getZ())
                + " | прошёл " + Math.round(walked) + " блоков"
                + (home == null || where == null ? "" : ", от дома " + Math.round(Math.sqrt(where.distSqr(home))));
    }

}
