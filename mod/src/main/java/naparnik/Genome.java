package naparnik;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.StringJoiner;

/** brain.json — единственный артефакт обучения. Мод грузит этот же файл. */
public final class Genome {
    public static void save(float[] g, Path p) throws IOException {
        StringJoiner j = new StringJoiner(",");
        for (float v : g) j.add(Float.toString(v));
        Files.writeString(p, "{\"in\":" + Brain.IN + ",\"hid\":" + Brain.HID
                + ",\"out\":" + Brain.OUT + ",\"g\":[" + j + "]}");
    }

    public static float[] load(Path p) throws IOException {
        return parse(Files.readString(p));
    }

    /**
     * Мозг под меньшее число входов расширяется нулевыми весами на новых входах. Решения от этого
     * не меняются ни на одном входе — новые признаки он просто пока не учитывает. Так добавление
     * входов не выбрасывает ни чемпиона, ни острова, ни популяции в мирах.
     */
    public static float[] upgrade(float[] g) {
        int rest = Brain.HID + Brain.HID * Brain.OUT + Brain.OUT;
        if (g.length == Brain.GENOME || (g.length - rest) % Brain.HID != 0) return g;
        int oldIn = (g.length - rest) / Brain.HID;
        if (oldIn <= 0 || oldIn >= Brain.IN) return g;
        float[] out = new float[Brain.GENOME];
        for (int j = 0; j < Brain.HID; j++) {
            System.arraycopy(g, j * oldIn, out, j * Brain.IN, oldIn);   // новые входы — нулевые веса
        }
        System.arraycopy(g, oldIn * Brain.HID, out, Brain.IN * Brain.HID, rest);
        return out;
    }

    /** Мод читает brain.json ресурсом из jar, где никакого Path нет. */
    public static float[] parse(String s) throws IOException {
        int a = s.indexOf("\"g\":[");
        if (a < 0) throw new IOException("нет поля g в brain.json");
        String body = s.substring(a + 5, s.indexOf(']', a));
        String[] parts = body.split(",");
        float[] g = new float[parts.length];
        for (int i = 0; i < parts.length; i++) g[i] = Float.parseFloat(parts[i].trim());
        return upgrade(g);
    }
}
