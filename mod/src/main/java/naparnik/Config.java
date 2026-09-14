package naparnik;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Единые настройки для офлайн-обучения и для игры. Панель пишет этот файл, тренер и мод его
 * читают. Формат key=value: встроенный Properties разбирает его сам, своего парсера не нужно.
 *
 * Перечитывается на лету не чаще раза в секунду — меняешь награду в панели, игра подхватывает
 * без перезапуска.
 */
public final class Config {
    public static final Path HOME = Path.of(System.getProperty("user.home"), ".naparnik");
    public static final Path FILE = HOME.resolve("config.properties");

    public static final String[] ACTION_NAMES = {
        "вперёд", "влево", "вправо", "копать", "ставить", "бить", "есть", "крафт"
    };

    /** Значения по умолчанию — они же документация: что вообще можно настроить. */
    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("life", "240");
        DEFAULTS.put("population", "20");
        DEFAULTS.put("randomTail", "8");
        DEFAULTS.put("mutationRate", "0.03");
        DEFAULTS.put("mutationSigma", "0.25");
        DEFAULTS.put("tournament", "3");
        DEFAULTS.put("explore", "0.2");
        DEFAULTS.put("decisionEvery", "10");
        DEFAULTS.put("sight", "10");
        DEFAULTS.put("seeds", "3");
        DEFAULTS.put("faceEnemyRange", "4");

        DEFAULTS.put("attack.monsters", "1");
        DEFAULTS.put("attack.rivals", "1");
        DEFAULTS.put("attack.animals", "0");
        DEFAULTS.put("attack.players", "0");

        DEFAULTS.put("reward.age", "0.2");
        DEFAULTS.put("reward.visited", "15");
        DEFAULTS.put("reward.mined", "200");
        DEFAULTS.put("reward.picked", "30");
        DEFAULTS.put("reward.built", "60");
        DEFAULTS.put("reward.sheltered", "20");
        DEFAULTS.put("reward.hits", "50");
        DEFAULTS.put("reward.kills", "300");
        DEFAULTS.put("reward.rivals", "500");
        DEFAULTS.put("reward.repertoire", "100");
        DEFAULTS.put("reward.tech", "5000");
        DEFAULTS.put("reward.wasted", "-5");

        for (int i = 0; i < ACTION_NAMES.length; i++) DEFAULTS.put("action." + i, "1");
    }

    private static Config instance;
    private static long checkedAt, loadedFrom;

    private final Properties p = new Properties();

    private Config() {
        p.putAll(DEFAULTS);
    }

    public static synchronized Config get() {
        long now = System.currentTimeMillis();
        if (instance != null && now - checkedAt < 1000) return instance;
        checkedAt = now;
        try {
            long mtime = Files.exists(FILE) ? Files.getLastModifiedTime(FILE).toMillis() : 0;
            if (instance != null && mtime == loadedFrom) return instance;
            Config c = new Config();
            if (mtime != 0) {
                try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    c.p.load(r);
                }
            }
            loadedFrom = mtime;
            instance = c;
        } catch (IOException e) {
            if (instance == null) instance = new Config();
        }
        return instance;
    }

    /** Записать настройки по умолчанию, если файла ещё нет — панели есть что показать сразу. */
    public static void ensureFile() throws IOException {
        Files.createDirectories(HOME);
        if (Files.exists(FILE)) return;
        StringBuilder sb = new StringBuilder("# настройки напарников: правит панель, читают тренер и игра\n");
        DEFAULTS.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8);
    }

    public float f(String key) {
        try {
            return Float.parseFloat(p.getProperty(key, DEFAULTS.getOrDefault(key, "0")).trim());
        } catch (NumberFormatException e) {
            return Float.parseFloat(DEFAULTS.getOrDefault(key, "0"));
        }
    }

    public int i(String key) { return Math.round(f(key)); }

    /** Разрешено ли действие. Запрещённое мозг не выбирает вовсе. */
    public boolean action(int idx) {
        return !"0".equals(p.getProperty("action." + idx, "1").trim());
    }

    public float reward(String what) { return f("reward." + what); }

    /** Кого можно бить: monsters, rivals, animals, players. */
    public boolean attacks(String who) { return i("attack." + who) != 0; }
}
