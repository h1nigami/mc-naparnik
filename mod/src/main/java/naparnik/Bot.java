package naparnik;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
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
    public int kills, hits, wasted, picked, built, sheltered, rivals;
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
        visited.add(before.asLong());
        clock(body);
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
        lastAction = ACTION[a];
        tried[a] = true;
        turnsInRow = a == 1 || a == 2 ? turnsInRow + 1 : 0;
        if (!act(lv, body, a)) wasted++;
        /* Решение видно прямо над головой — иначе "он думает" и "он завис" выглядят одинаково. */
        body.setCustomName(Component.literal(
                "Напарник " + generation + "\u00B7" + life + " \u00B7 " + lastAction));
        float yaw = lookYaw != null ? lookYaw : facing.toYRot();
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        if (shelteredAt(lv, body.blockPosition())) sheltered++;
        BlockPos now = body.blockPosition();
        if (!now.equals(before)) walked += Math.sqrt(now.distSqr(before));
        visited.add(now.asLong());
        where = now;
    }

    /** 120 тиков на единицу сытости — тот же темп, что в симуляторе. */
    void clock(Mob body) {
        if (age % 12 == 0 && hunger > 0) hunger--;
        if (hunger == 0 && age % 4 == 0) body.setHealth(body.getHealth() - 1f);
        if (hunger >= 18 && age % 8 == 0 && body.getHealth() < body.getMaxHealth()) {
            body.setHealth(body.getHealth() + 1f);
        }
    }

    // ---------- сенсоры ----------

    /** 25 входов — раскладка обязана совпадать с Sim.sense() число в число. */
    float[] sense(ServerLevel lv, Mob body) {
        float[] s = new float[Brain.IN];
        BlockPos at = body.blockPosition();

        s[0] = body.getHealth() / 10f - 1f;
        s[1] = hunger / 10f - 1f;
        double t = 2 * Math.PI * (lv.getDefaultClockTime() % 24000L) / 24000.0;   // в 26.2 сутки живут в мировых часах
        s[2] = (float) Math.sin(t);
        s[3] = (float) Math.cos(t);

        BlockPos front = at.relative(facing);
        BlockState ahead = lv.getBlockState(front);
        boolean solid = !soft(lv, front);
        s[4] = solid ? 1f : -1f;
        s[5] = diggable(lv, front) ? 1f : -1f;
        s[6] = ahead.is(BlockTags.LOGS) ? 1f : -1f;

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
            it.discard();
        }
    }

    /* ponytail: список еды перечислен руками вместо разбора компонента питания — восемь строк
       против возни с DataComponents. Потолок — незнакомая еда просто не считается едой. */
    static final Set<Item> FOOD = Set.of(
            Items.APPLE, Items.BREAD, Items.CARROT, Items.POTATO, Items.BAKED_POTATO,
            Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP, Items.SWEET_BERRIES);

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
            case 0 -> passable(lv, front) || (!soft(lv, front) && passable(lv, front.above()));
            case 1, 2 -> turnsInRow < 2;   // третий поворот подряд — это уже кружение на месте
            case 3 -> diggable(lv, front);
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
    boolean dig(ServerLevel lv, Mob body) {
        BlockPos p = body.blockPosition().relative(facing);
        BlockState st = lv.getBlockState(p);
        if (st.isAir()) return false;
        if (st.getDestroySpeed(lv, p) < 0) return false;                      // коренная порода
        if (st.is(BlockTags.IRON_ORES) && tech < 2) return false;
        if (st.is(BlockTags.BASE_STONE_OVERWORLD) && tech < 1) return false;

        if (placed.remove(p.asLong())) {
            /* Свой блок уходит обратно в сумку без дропа: иначе он падает, подбирается и
               засчитывается «поднял», а петля «поставил-сломал» платит стоя на месте. */
            Item item = st.getBlock().asItem();
            if (!lv.destroyBlock(p, false, body, 512)) return false;
            if (item instanceof BlockItem) blocks.merge(item, 1, Integer::sum);
            return true;
        }
        if (!lv.destroyBlock(p, true, body, 512)) return false;
        mined++;
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
        m.setHealth(m.getHealth() - 4f);
        hits++;
        if (m.getHealth() <= 0f) {
            kills++;
            if (rival) rivals++;
        }
        return true;
    }

    boolean eat() {
        if (food <= 0 || hunger > 14) return false;
        food--;
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
                + c.reward("wasted") * wasted;
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
