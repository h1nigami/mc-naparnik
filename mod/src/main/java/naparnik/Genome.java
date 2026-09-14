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

    /** Мод читает brain.json ресурсом из jar, где никакого Path нет. */
    public static float[] parse(String s) throws IOException {
        int a = s.indexOf("\"g\":[");
        if (a < 0) throw new IOException("нет поля g в brain.json");
        String body = s.substring(a + 5, s.indexOf(']', a));
        String[] parts = body.split(",");
        float[] g = new float[parts.length];
        for (int i = 0; i < parts.length; i++) g[i] = Float.parseFloat(parts[i].trim());
        return g;
    }
}
