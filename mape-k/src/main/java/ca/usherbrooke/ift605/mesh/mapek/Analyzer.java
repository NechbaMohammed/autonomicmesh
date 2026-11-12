package ca.usherbrooke.ift605.mesh.mapek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composant « Analyze » de la boucle MAPE-K.
 * Fonction pure : diagnostique les symptomes a partir des mesures de la {@link KnowledgeBase}.
 * N'effectue aucun appel reseau et ne modifie pas l'etat du systeme, ce qui permet
 * des tests unitaires deterministes et reproductibles.
 */
public final class Analyzer {

    /** Nombre d'echantillons consecutifs necessaires pour confirmer une anomalie (suppression des faux positifs). */
    public static final int ECHANTILLONS_CONFIRMATION = 2;

    /** Analyse l'etat a l'instant present. */
    public List<Symptome> analyser(KnowledgeBase kb) {
        return analyser(kb, System.currentTimeMillis());
    }

    /**
     * Analyse l'etat a un instant donne {@code nowMs} (facilite les simulations temporelles).
     *
     * @param kb    base de connaissances en lecture
     * @param nowMs instant d'evaluation
     * @return liste des symptomes identifies
     */
    public List<Symptome> analyser(KnowledgeBase kb, long nowMs) {
        List<Symptome> symptomes = new ArrayList<>();

        for (ServiceDef def : MeshConfig.SERVICES) {
            String svc = def.name();

            // 1. Verification de la vitalite de la sonde (timeout silence > 15s)
            Long derniere = kb.getDerniereReception(svc);
            if (derniere != null && (nowMs - derniere) > MeshConfig.TIMEOUT_SILENCE_SONDE_MS) {
                long silenceMs = nowMs - derniere;
                symptomes.add(new Symptome(
                        svc,
                        TypeSymptome.PANNE_TOTALE,
                        nowMs,
                        silenceMs,
                        String.format("Sonde muette depuis %d ms (> seuil %d ms)", silenceMs, MeshConfig.TIMEOUT_SILENCE_SONDE_MS)));
                continue; // Pas besoin de tester les valeurs numeriques si le service est injoignable
            }

            // 2. Verification sur l'historique des mesures
            List<MesureSante> hist = kb.getHistorique(svc);
            if (hist.size() < ECHANTILLONS_CONFIRMATION) {
                // Pas assez d'echantillons pour confirmer avec certitude
                continue;
            }

            MesureSante m1 = hist.get(hist.size() - 2);
            MesureSante m2 = hist.get(hist.size() - 1);

            // Cas A : Panne totale confirmee (2x DOWN consecutifs)
            if ("DOWN".equalsIgnoreCase(m1.statut()) && "DOWN".equalsIgnoreCase(m2.statut())) {
                symptomes.add(new Symptome(
                        svc,
                        TypeSymptome.PANNE_TOTALE,
                        nowMs,
                        0.0,
                        "2 mesures consecutives en statut DOWN"));
                continue; // Panne prioritaire sur les degradations
            }

            // Si le statut actuel est DOWN mais seul (pas encore confirme), on evite le faux positif
            if ("DOWN".equalsIgnoreCase(m2.statut())) {
                continue;
            }

            // Cas B : Degradation de latence (2x > 500 ms)
            if (m1.latenceMs() > MeshConfig.SEUIL_LATENCE_MS && m2.latenceMs() > MeshConfig.SEUIL_LATENCE_MS) {
                double avgLat = (m1.latenceMs() + m2.latenceMs()) / 2.0;
                symptomes.add(new Symptome(
                        svc,
                        TypeSymptome.DEGRADATION,
                        nowMs,
                        avgLat,
                        String.format("Latence critique confirmee sur 2 echantillons (%.1f ms > %d ms)",
                                avgLat, MeshConfig.SEUIL_LATENCE_MS)));
            }

            // Cas C : Surcharge CPU (2x > 0.90)
            if (m1.cpuLoad() > MeshConfig.SEUIL_CPU && m2.cpuLoad() > MeshConfig.SEUIL_CPU) {
                double avgCpu = (m1.cpuLoad() + m2.cpuLoad()) / 2.0;
                symptomes.add(new Symptome(
                        svc,
                        TypeSymptome.SURCHARGE,
                        nowMs,
                        avgCpu,
                        String.format("Charge CPU critique confirmee sur 2 echantillons (%.2f > %.2f)",
                                avgCpu, MeshConfig.SEUIL_CPU)));
            }
        }

        return Collections.unmodifiableList(symptomes);
    }
}

