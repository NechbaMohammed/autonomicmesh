package ca.usherbrooke.ift605.mesh.mapek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composant « Plan » de la boucle MAPE-K.
 * Fonction pure : elabore des actions correctives a partir des symptomes diagnostiques
 * et des politiques de garde-fous (cooldown, fenetre de redemarrage, reacheminement).
 */
public final class Planner {

    public List<ActionCorrective> planifier(List<Symptome> symptomes, KnowledgeBase kb) {
        return planifier(symptomes, kb, System.currentTimeMillis());
    }

    /**
     * Planifie les actions correctives a un instant donne {@code nowMs}.
     *
     * @param symptomes liste des anomalies detectees
     * @param kb        base de connaissances
     * @param nowMs     instant d'evaluation
     * @return liste des actions correctives a executer
     */
    public List<ActionCorrective> planifier(List<Symptome> symptomes, KnowledgeBase kb, long nowMs) {
        List<ActionCorrective> actions = new ArrayList<>();

        for (Symptome sym : symptomes) {
            String svc = sym.service();

            switch (sym.type()) {
                case PANNE_TOTALE -> {
                    // Garde-fou 1 : Cooldown de 20s pour eviter des rebonds pendant l'initialisation
                    long dernierReboot = kb.getDernierRedemarrage(svc);
                    if (dernierReboot > 0 && (nowMs - dernierReboot) < MeshConfig.COOLDOWN_REDEMARRAGE_MS) {
                        // Service encore sous periode d'amorcage/cooldown
                        continue;
                    }

                    // Garde-fou 2 : Limite de 3 redemarrages par tranche de 5 minutes (eviter crash loops)
                    int rebootsRecents = kb.getRedemarragesRecents(svc, MeshConfig.FENETRE_REDEMARRAGES_MS, nowMs);
                    if (rebootsRecents >= MeshConfig.MAX_REDEMARRAGES_FENETRE) {
                        String cibleSecours = MeshConfig.getCibleSecours(svc);
                        String cibleActuelle = kb.getRoutage(svc);
                        if (!cibleSecours.equalsIgnoreCase(cibleActuelle)) {
                            actions.add(new ActionCorrective(
                                    svc,
                                    TypeAction.REACHEMINEMENT,
                                    String.format("Seuil de securite atteint (%d redemarrages en 5 min) : bascule d'urgence vers %s",
                                            rebootsRecents, cibleSecours),
                                    cibleSecours,
                                    nowMs));
                        }
                    } else {
                        actions.add(new ActionCorrective(
                                svc,
                                TypeAction.REDEMARRER,
                                String.format("Panne totale confirmee (%s) : redemarrage automatique via ProcessBuilder",
                                        sym.message()),
                                null,
                                nowMs));
                    }
                }

                case DEGRADATION -> {
                    String cibleSecours = MeshConfig.getCibleSecours(svc);
                    String cibleActuelle = kb.getRoutage(svc);
                    if (!cibleSecours.equalsIgnoreCase(cibleActuelle)) {
                        actions.add(new ActionCorrective(
                                svc,
                                TypeAction.REACHEMINEMENT,
                                String.format("Latence critique confirmee (%.1f ms > %d ms) : reacheminement vers %s",
                                        sym.valeurDetectee(), MeshConfig.SEUIL_LATENCE_MS, cibleSecours),
                                cibleSecours,
                                nowMs));
                    }
                }

                case SURCHARGE -> {
                    String cibleWorker = MeshConfig.getCibleSecours(svc);
                    actions.add(new ActionCorrective(
                            svc,
                            TypeAction.MISE_A_ECHELLE,
                            String.format("Charge CPU critique confirmee (%.2f > %.2f) : instanciation dynamique d'un travailleur auxiliaire",
                                    sym.valeurDetectee(), MeshConfig.SEUIL_CPU),
                            cibleWorker,
                            nowMs));
                }
            }
        }

        return Collections.unmodifiableList(actions);
    }
}

