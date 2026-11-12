package ca.usherbrooke.ift605.mesh.mapek;

import com.google.gson.Gson;

/**
 * Journalisation structuree d'un cycle ou d'une action MAPE-K.
 * Stocke dans la KnowledgeBase et exportable en CSV pour la reproductibilite scientifique et le rapport.
 */
public record EvenementMapeK(
        long id,
        long timestamp,
        String phase,        // "MONITOR", "ANALYZE", "PLAN", "EXECUTE", "CHAOS"
        String service,
        TypeSymptome symptome,
        TypeAction action,
        String details,
        long dureeMs,
        boolean succes
) {
    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }

    public String toCsvLine() {
        return String.format("%d,%d,%s,%s,%s,%s,\"%s\",%d,%b",
                id,
                timestamp,
                phase != null ? phase : "",
                service != null ? service : "",
                symptome != null ? symptome.name() : "",
                action != null ? action.name() : "",
                details != null ? details.replace("\"", "\"\"") : "",
                dureeMs,
                succes);
    }

    public static String csvHeader() {
        return "id,timestamp,phase,service,symptome,action,details,dureeMs,succes";
    }
}

