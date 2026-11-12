package ca.usherbrooke.ift605.mesh.mapek;

/** Types de symptomes detectes par l'Analyzer. */
public enum TypeSymptome {
    /** Le service ne repond plus (HealthCheck DOWN ou sonde muette). */
    PANNE_TOTALE,
    /** La latence depasse le seuil critique (MeshConfig.SEUIL_LATENCE_MS = 500 ms). */
    DEGRADATION,
    /** La charge CPU depasse le seuil critique (MeshConfig.SEUIL_CPU = 0.90). */
    SURCHARGE
}

