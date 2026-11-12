package ca.usherbrooke.ift605.mesh.mapek;

/**
 * Types d'actions correctives planifiees par le Planner et appliquees par l'Executor.
 */
public enum TypeAction {
    /** Redemarrage propre du processus associe via ProcessBuilder. */
    REDEMARRER,
    /** Reacheminement du trafic vers une instance de secours saine. */
    REACHEMINEMENT,
    /** Instanciation dynamique d'un nouveau travailleur pour absorber la surcharge. */
    MISE_A_ECHELLE
}

