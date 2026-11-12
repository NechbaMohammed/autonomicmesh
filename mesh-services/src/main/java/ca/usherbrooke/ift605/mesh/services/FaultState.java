package ca.usherbrooke.ift605.mesh.services;

/**
 * Etat d'injection de fautes d'un service (thread-safe). Pilote par {@link AdminServiceImpl}.
 * <ul>
 *   <li>{@code latencyMs} : delai ajoute a chaque appel (simule une degradation) ;</li>
 *   <li>{@code cpuOverride} : charge CPU rapportee (negatif = pas de surcharge simulee).</li>
 * </ul>
 */
public final class FaultState {

    private volatile long latencyMs = 0;
    private volatile double cpuOverride = -1.0;

    /** Applique une injection ; {@code latencyMs <= 0} et {@code cpu <= 0} reviennent a l'etat nominal. */
    public void appliquer(long latencyMs, double cpu) {
        this.latencyMs = Math.max(0, latencyMs);
        this.cpuOverride = cpu > 0 ? Math.min(1.0, cpu) : -1.0;
    }

    /** Retourne a l'etat nominal. */
    public void effacer() {
        appliquer(0, 0);
    }

    /** Delai artificiel courant, en millisecondes. */
    public long latencyMs() {
        return latencyMs;
    }

    /** Charge CPU forcee, ou une valeur negative si aucune surcharge n'est simulee. */
    public double cpuOverride() {
        return cpuOverride;
    }

    /** Vrai si une latence artificielle est active (le service est alors {@code DEGRADED}). */
    public boolean degrade() {
        return latencyMs > 0;
    }

    /** Dort pour la duree de latence injectee, le cas echeant. Conserve l'etat d'interruption. */
    public void retarder() {
        long d = latencyMs;
        if (d > 0) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

