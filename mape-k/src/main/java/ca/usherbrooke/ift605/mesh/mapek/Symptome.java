package ca.usherbrooke.ift605.mesh.mapek;

/**
 * Symptome diagnostique par l'Analyzer.
 * Immuable et evaluable de maniere deterministe pour faciliter les tests unitaires.
 */
public record Symptome(
        String service,
        TypeSymptome type,
        long timestamp,
        double valeurDetectee,
        String message
) {
    public Symptome(String service, TypeSymptome type, long timestamp, double valeurDetectee) {
        this(service, type, timestamp, valeurDetectee, "");
    }
}

