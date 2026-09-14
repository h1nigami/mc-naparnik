package naparnik;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NaparnikMod implements ModInitializer {
    public static final String MOD_ID = "naparnik";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    static final String POP_FILE = "naparnik-population.txt";

    /** Длина одной жизни в решениях. Короче — поколения сменяются быстрее и прогресс видно
     *  раньше; длиннее — особь успевает показать себя. 240 решений это ровно две минуты. */
    static int life() { return Math.max(10, Config.get().i("life")); }
    /** Сколько кругов подряд тело должно не находиться, прежде чем считать особь погибшей.
     *  Один круг — мало: сразу после подселения сущность ещё не зарегистрирована. */
    static final int MISSING_LIMIT = 4;

    /** Больше этого числа одновременных напарников не заводим: каждый обходит все сущности
     *  уровня дважды в секунду, и толпа съест тик сервера. */
    static final int MAX_BOTS = 8;

    static float[] seed;
    /** Последние итоги жизней для графика в панели: [номер жизни, фитнес]. */
    static final java.util.ArrayDeque<float[]> RECENT = new java.util.ArrayDeque<>();
    static long promotedApplied;
    static Population pop;
    static final Map<UUID, Bot> BOTS = new LinkedHashMap<>();
    static ServerLevel homeLevel;
    static int tick;
    /** 0 — брать темп из настроек; иначе задан командой /naparnik speed и перекрывает их. */
    static int speedOverride = 0;

    static int decisionEvery() {
        return speedOverride > 0 ? speedOverride : Math.max(1, Config.get().i("decisionEvery"));
    }

    /* Ручное управление: напарник читает очередь команд из файла рядом с миром и туда же
       пишет, что видит. Это единственный способ управлять им снаружи игры — внутрь клиента
       достучаться нечем. */
    static boolean manual;
    static final java.util.ArrayDeque<Integer> QUEUE = new java.util.ArrayDeque<>();
    static Path viewFile, cmdFile;
    static final String[] NAMES = {"вперёд", "влево", "вправо", "копать", "ставить", "бить", "есть", "крафт"};

    @Override
    public void onInitialize() {
        seed = weights();
        ServerLifecycleEvents.SERVER_STARTED.register(NaparnikMod::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(NaparnikMod::save);
        ServerTickEvents.END_SERVER_TICK.register(NaparnikMod::onTick);
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> commands(d));
        LOG.info("Напарник загружен, весов в мозге: {}", seed.length);
    }

    /** Веса едут ресурсом внутри jar. Нет их — падаем громко: молча взятый случайный мозг
     *  выглядит как «напарник тупой», и причину потом не найти. */
    static float[] weights() {
        try (InputStream in = NaparnikMod.class.getResourceAsStream("/brain.json")) {
            if (in == null) throw new IOException("в jar нет /brain.json");
            return Genome.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("не читается brain.json", e);
        }
    }

    // ---------- цикл жизни ----------

    static void onTick(MinecraftServer server) {
        if (++tick % 40 == 0) { pickupPromoted(server); exportStats(); }
        if (tick % decisionEvery() != 0 || pop == null || BOTS.isEmpty()) return;

        for (Bot b : BOTS.values()) b.seen = false;

        /* Сначала собираем тела, только потом шагаем. Шаг ломает блоки и подбирает предметы,
           то есть создаёт и уничтожает сущности — делать это во время обхода списка сущностей
           уровня нельзя, сервер падает с NullPointerException внутри итератора. */
        for (ServerLevel lv : server.getAllLevels()) {
            List<Mob> bodies = new ArrayList<>();
            for (Entity e : lv.getAllEntities()) {
                if (e == null) continue;
                Bot b = BOTS.get(e.getUUID());
                if (b != null && e instanceof Mob m && m.isAlive()) bodies.add(m);
            }
            boolean first = true;
            for (Mob m : bodies) {
                Bot b = BOTS.get(m.getUUID());
                if (b == null) continue;
                b.seen = true;
                b.missing = 0;
                homeLevel = lv;
                int override = -1;
                if (manual && first) {
                    readCommands();
                    Integer next = QUEUE.poll();
                    override = next == null ? -1 : next;
                    writeView(b.view(lv, m) + "очередь команд: " + QUEUE.size() + "\n");
                }
                b.step(lv, m, pop.generation, b.slot + 1, override);
                first = false;
            }
        }

        /* Итоги собираем списком: endLife трогает BOTS, а менять его во время обхода нельзя. */
        List<UUID> ended = new ArrayList<>();
        for (Map.Entry<UUID, Bot> e : BOTS.entrySet()) {
            Bot b = e.getValue();
            if (!b.seen && ++b.missing >= MISSING_LIMIT) ended.add(e.getKey());
            else if (b.seen && b.age >= life()) ended.add(e.getKey());
        }
        for (UUID id : ended) endLife(server, id, BOTS.get(id).seen ? "дожил до конца срока" : "погиб");
    }

    static Entity findBody(ServerLevel lv, UUID id) {
        for (Entity e : lv.getAllEntities()) if (e != null && id.equals(e.getUUID())) return e;
        return null;
    }

    static void endLife(MinecraftServer server, UUID id, String reason) {
        Bot b = BOTS.remove(id);
        if (b == null) return;
        float f = b.fitness();
        int gen = pop.generation;
        pop.record(b.slot, f);
        RECENT.addLast(new float[]{pop.lives, f});
        while (RECENT.size() > 400) RECENT.removeFirst();

        String msg = "Напарник " + reason + ": особь " + (b.slot + 1) + "/" + pop.size
                + " поколения " + gen + ", фитнес " + Math.round(f)
                + " (добыто " + b.mined + ", поднял " + b.picked + ", построил " + b.built
                + ", под крышей " + b.sheltered + ", убил " + b.kills
                + (b.rivals > 0 ? " из них своих " + b.rivals : "")
                + ", тех " + b.tech + ", клеток " + b.visited.size()
                + ", впустую " + b.wasted + ")"
                + (pop.generation > gen ? " \u2014 ПОКОЛЕНИЕ " + pop.generation : "")
                + ". Рекорд " + Math.round(pop.record);
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);

        BlockPos home = b.home;
        removeBody(server, id);
        if (homeLevel != null && home != null) spawnBody(homeLevel, home);
    }

    static void removeBody(MinecraftServer server, UUID id) {
        for (ServerLevel lv : server.getAllLevels()) {
            Entity e = findBody(lv, id);
            if (e != null) { e.discard(); return; }
        }
    }

    /** Подселяет тело для текущей особи популяции. */
    static boolean spawnBody(ServerLevel lv, BlockPos near) {
        if (!lv.isLoaded(near)) {
            LOG.warn("возрождение отложено: чанк {} не прогружен", near);
            return false;
        }
        Mob body = EntityTypes.VILLAGER.create(lv, EntitySpawnReason.MOB_SUMMONED);
        if (body == null) return false;

        BlockPos at = null;
        for (BlockPos c : new BlockPos[]{near, near.north(), near.south(), near.east(), near.west()}) {
            if (free(lv, c)) { at = c; break; }
        }
        if (at == null) {
            int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
            at = new BlockPos(near.getX(), y, near.getZ());
        }
        body.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        body.addTag(Bot.TAG);
        body.setNoAi(true);
        body.setPersistenceRequired();
        body.setCustomName(Component.literal("Напарник"));
        body.setCustomNameVisible(true);
        if (!lv.addFreshEntity(body)) return false;

        int slot = pop.take();
        Bot b = new Bot(pop.brain(slot), slot);
        b.home = at;
        BOTS.put(body.getUUID(), b);
        homeLevel = lv;
        pop.home = new int[]{at.getX(), at.getY(), at.getZ()};
        return true;
    }

    static boolean free(ServerLevel lv, BlockPos p) {
        return lv.getBlockState(p).isAir() && lv.getBlockState(p.above()).isAir();
    }

    /** Забирает команды из файла и сразу его очищает: прочитанная команда не должна
     *  исполниться второй раз при следующем решении. */
    static void readCommands() {
        if (cmdFile == null || !Files.exists(cmdFile)) return;
        try {
            for (String line : Files.readAllLines(cmdFile)) {
                String w = line.trim().toLowerCase();
                if (w.isEmpty()) continue;
                int a = -1;
                for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(w)) a = i;
                if (a < 0 && w.matches("[0-7]")) a = Integer.parseInt(w);
                if (a >= 0) QUEUE.add(a);
            }
            Files.delete(cmdFile);
        } catch (IOException e) {
            LOG.error("не читается файл команд {}", cmdFile, e);
        }
    }

    static void writeView(String text) {
        if (viewFile == null) return;
        try {
            Files.writeString(viewFile, text);
        } catch (IOException e) {
            LOG.error("не пишется обзор {}", viewFile, e);
        }
    }

    /** Живая статистика для панели. Пишется атомарно: панель не должна прочитать полфайла. */
    static void exportStats() {
        try {
            java.nio.file.Path dir = Config.HOME.resolve("game");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"updated\":").append(System.currentTimeMillis());
            sb.append(",\"manual\":").append(manual);
            sb.append(",\"decisionEvery\":").append(decisionEvery());
            if (pop != null) {
                sb.append(",\"generation\":").append(pop.generation)
                  .append(",\"lives\":").append(pop.lives)
                  .append(",\"size\":").append(pop.size)
                  .append(",\"evaluated\":").append(pop.evaluated())
                  .append(",\"replacements\":").append(pop.replacements)
                  .append(",\"record\":").append(num(pop.record))
                  .append(",\"median\":").append(num(pop.medianOfEvaluated()));
            }
            sb.append(",\"bots\":[");
            boolean first = true;
            for (Bot b : BOTS.values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"slot\":").append(b.slot + 1)
                  .append(",\"age\":").append(b.age)
                  .append(",\"life\":").append(life())
                  .append(",\"fitness\":").append(num(b.fitness()))
                  .append(",\"mined\":").append(b.mined)
                  .append(",\"picked\":").append(b.picked)
                  .append(",\"built\":").append(b.built)
                  .append(",\"sheltered\":").append(b.sheltered)
                  .append(",\"hits\":").append(b.hits)
                  .append(",\"kills\":").append(b.kills)
                  .append(",\"rivals\":").append(b.rivals)
                  .append(",\"tech\":").append(b.tech)
                  .append(",\"visited\":").append(b.visited.size())
                  .append(",\"wasted\":").append(b.wasted)
                  .append(",\"hunger\":").append(b.hunger)
                  .append(",\"action\":\"").append(b.lastAction).append('"');
                if (b.where != null) {
                    sb.append(",\"pos\":[").append(b.where.getX()).append(',')
                      .append(b.where.getY()).append(',').append(b.where.getZ()).append(']');
                }
                sb.append('}');
            }
            sb.append("],\"recent\":[");
            first = true;
            for (float[] r : RECENT) {
                if (!first) sb.append(',');
                first = false;
                sb.append('[').append((int) r[0]).append(',').append(num(r[1])).append(']');
            }
            sb.append("]}");
            java.nio.file.Path tmp = dir.resolve("stats.json.tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("stats.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("не пишется статистика для панели", e);
        }
    }

    static String num(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? "null" : Float.toString(v);
    }

    /** Мозг, выкаченный из панели, переселяет популяцию вокруг себя. Применяется один раз
     *  на каждую новую выкладку — сравниваем время файла, а не содержимое. */
    static void pickupPromoted(MinecraftServer server) {
        java.nio.file.Path f = Config.HOME.resolve("promote").resolve("brain.json");
        try {
            if (!Files.exists(f)) return;
            long mtime = Files.getLastModifiedTime(f).toMillis();
            if (mtime <= promotedApplied) return;
            float[] g = Genome.load(f);
            if (g.length != Brain.GENOME) throw new IOException("геном " + g.length + ", ждали " + Brain.GENOME);
            if (pop == null) pop = new Population(g);
            else pop.reseed(g);
            promotedApplied = mtime;
            String msg = "В игру выкачен мозг из фонового обучения — популяция переселена вокруг него";
            server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        } catch (IOException e) {
            LOG.error("не применился выкачанный мозг {}", f, e);
            promotedApplied = Long.MAX_VALUE;   // битый файл не должен долбить журнал каждые две секунды
        }
    }

    // ---------- команды ----------

    static void commands(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("naparnik")
                .then(Commands.literal("spawn").executes(c -> spawn(c.getSource())))
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.literal("remove").executes(c -> remove(c.getSource())))
                .then(Commands.literal("control").executes(c -> control(c.getSource())))
                .then(Commands.literal("speed")
                        .then(Commands.argument("тиков", IntegerArgumentType.integer(1, 40))
                                .executes(c -> speed(c.getSource(), IntegerArgumentType.getInteger(c, "тиков"))))));
    }

    static int spawn(CommandSourceStack src) {
        ServerLevel lv = src.getLevel();
        BlockPos where = BlockPos.containing(src.getPosition());
        if (!lv.isLoaded(where)) {
            src.sendFailure(Component.literal("здесь не прогружен чанк — встань в это место сам"));
            return 0;
        }
        if (pop == null) pop = new Population(seed);
        if (BOTS.size() >= MAX_BOTS) {
            src.sendFailure(Component.literal("уже " + MAX_BOTS + " напарников — больше сервер не потянет"));
            return 0;
        }
        if (!spawnBody(lv, where)) {
            src.sendFailure(Component.literal("мир не принял напарника"));
            return 0;
        }
        int n = BOTS.size();
        src.sendSuccess(() -> Component.literal(
                "Напарник призван (" + n + " из " + MAX_BOTS + "). Поколение " + pop.generation
                        + ". Он будет жить, гибнуть и возрождаться сам."), false);
        return 1;
    }

    static int status(CommandSourceStack src) {
        if (pop == null || BOTS.isEmpty()) {
            src.sendFailure(Component.literal("напарников нет — призови /naparnik spawn"));
            return 0;
        }
        int evaluated = 0;
        for (float f : pop.fitness) if (!Float.isNaN(f)) evaluated++;
        String head = "Поколение " + pop.generation + ", оценено " + evaluated + "/" + pop.size
                + ", напарников " + BOTS.size()
                + (Float.isNaN(pop.record) ? "" : ", рекорд " + Math.round(pop.record));
        src.sendSuccess(() -> Component.literal(head), false);
        for (Bot b : BOTS.values()) {
            String line = "особь " + (b.slot + 1) + " [" + b.age + "/" + life() + "] "
                    + b.describe() + " | сейчас " + b.lastAction;
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    static int remove(CommandSourceStack src) {
        int n = BOTS.size();
        for (UUID id : new ArrayList<>(BOTS.keySet())) removeBody(src.getServer(), id);
        BOTS.clear();
        src.sendSuccess(() -> Component.literal("убрано напарников: " + n + ", популяция сохранена"), false);
        return 1;
    }

    static int control(CommandSourceStack src) {
        manual = !manual;
        QUEUE.clear();
        Path dir = src.getServer().getWorldPath(LevelResource.ROOT);
        viewFile = dir.resolve("naparnik-view.txt");
        cmdFile = dir.resolve("naparnik-command.txt");
        boolean on = manual;
        src.sendSuccess(() -> Component.literal(on
                ? "ручное управление ВКЛЮЧЕНО. Обзор: " + viewFile + ", команды: " + cmdFile
                : "ручное управление выключено, мозг снова сам"), false);
        return 1;
    }

    static int speed(CommandSourceStack src, int ticks) {
        speedOverride = ticks;
        src.sendSuccess(() -> Component.literal("решение раз в " + ticks + " тиков ("
                + String.format("%.1f", 20.0 / ticks) + " в секунду)"), false);
        return 1;
    }

    // ---------- сохранение рядом с миром ----------

    static Path popFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(POP_FILE);
    }

    static void load(MinecraftServer server) {
        BOTS.clear();
        try {
            Config.ensureFile();
            java.nio.file.Path f = Config.HOME.resolve("promote").resolve("brain.json");
            if (Files.exists(f)) promotedApplied = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            LOG.error("не создаётся файл настроек", e);
        }
        Path p = popFile(server);
        if (!Files.exists(p)) return;
        try {
            pop = Population.load(p);
            LOG.info("популяция восстановлена: поколение {}, особь {}/{}",
                    pop.generation, pop.current + 1, pop.size);
        } catch (Exception e) {
            LOG.error("не прочиталась популяция {} — начнём заново", p, e);
            pop = null;
        }
    }

    static void save(MinecraftServer server) {
        if (pop == null) return;
        try {
            pop.save(popFile(server));
        } catch (IOException e) {
            LOG.error("не сохранилась популяция", e);
        }
    }
}
