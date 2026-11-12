package ca.usherbrooke.ift605.mesh.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Journal console minimal et thread-safe : {@code HH:mm:ss.SSS [TAG] message}.
 * Les etiquettes (MONITOR, ANALYZE, PLAN, EXECUTE, CHAOS...) servent a lire la boucle MAPE-K dans les logs.
 */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    /** Ecrit une ligne de journal. */
    public static void info(String tag, String message) {
        System.out.println(LocalTime.now().format(FMT) + " [" + tag + "] " + message);
    }

    /** Ecrit une ligne de journal d'avertissement. */
    public static void warn(String tag, String message) {
        System.out.println(LocalTime.now().format(FMT) + " [" + tag + "] WARN " + message);
    }
}

