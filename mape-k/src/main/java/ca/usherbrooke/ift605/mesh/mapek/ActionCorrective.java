package ca.usherbrooke.ift605.mesh.mapek;

/**
 * Action corrective decidee par le Planner conformement aux regles d'adaptation du middleware.
 */
public record ActionCorrective(
        String service,
        TypeAction type,
        String raison,
        String cible,
        long timestamp
) {
    public ActionCorrective(String service, TypeAction type, String raison, long timestamp) {
        this(service, type, raison, null, timestamp);
    }
}

