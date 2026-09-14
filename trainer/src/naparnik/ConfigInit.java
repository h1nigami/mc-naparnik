package naparnik;

/** Создаёт файл настроек со значениями по умолчанию. Зовёт панель при первом запуске. */
public final class ConfigInit {
    public static void main(String[] args) throws Exception {
        Config.ensureFile();
    }
}
