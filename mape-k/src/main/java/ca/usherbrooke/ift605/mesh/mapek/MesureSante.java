package ca.usherbrooke.ift605.mesh.mapek;

import com.google.gson.Gson;

import java.util.Set;

/**
 * Mesure de sante transmise par une sonde au Monitor (une ligne JSON par mesure).
 *
 * @param service   nom logique du service
 * @param instance  identifiant d'instance (ex. {@code calc-1}) ; {@code inconnue} si le service ne repond pas
 * @param timestamp instant de la mesure (epoch ms)
 * @param statut    {@code SERVING}, {@code DEGRADED} ou {@code DOWN}
 * @param latenceMs aller-retour du HealthCheck mesure par la sonde ; {@code -1} si DOWN
 * @param cpuLoad   charge CPU du service (0..1)
 * @param uptimeMs  duree de fonctionnement du service
 */
public record MesureSante(String service, String instance, long timestamp, String statut,
                          long latenceMs, double cpuLoad, long uptimeMs) {

    public static final String SERVING = "SERVING";
    public static final String DEGRADED = "DEGRADED";
    public static final String DOWN = "DOWN";

    private static final Set<String> STATUTS = Set.of(SERVING, DEGRADED, DOWN);
    private static final Gson GSON = new Gson();

    /** Fabrique une mesure DOWN (le service ne repond pas). */
    public static MesureSante down(String service, long timestamp) {
        return new MesureSante(service, "inconnue", timestamp, DOWN, -1, 0.0, 0);
    }

    /** Serialise en une ligne JSON. */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Parse et valide une mesure.
     *
     * @throws IllegalArgumentException si le JSON est invalide ou si un champ est hors domaine
     */
    public static MesureSante fromJson(String json) {
        MesureSante m;
        try {
            m = GSON.fromJson(json, MesureSante.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON invalide : " + e.getMessage(), e);
        }
        if (m == null) {
            throw new IllegalArgumentException("mesure vide");
        }
        m.valider();
        return m;
    }

    /** Verifie le domaine des champs. */
    public void valider() {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service manquant");
        }
        if (statut == null || !STATUTS.contains(statut)) {
            throw new IllegalArgumentException("statut invalide : " + statut);
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp invalide : " + timestamp);
        }
        if (cpuLoad < 0.0 || cpuLoad > 1.0 || Double.isNaN(cpuLoad)) {
            throw new IllegalArgumentException("cpuLoad hors [0,1] : " + cpuLoad);
        }
    }
}

