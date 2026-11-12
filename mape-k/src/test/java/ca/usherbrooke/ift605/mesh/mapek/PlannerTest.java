package ca.usherbrooke.ift605.mesh.mapek;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private KnowledgeBase kb;
    private Planner planner;
    private long now;

    @BeforeEach
    void setUp() {
        kb = new KnowledgeBase();
        planner = new Planner();
        now = 1_000_000L;
    }

    @Test
    @DisplayName("Panne totale : planifie REDEMARRER si aucun cooldown actif")
    void testPanneTotalePlanifieRedemarrer() {
        Symptome s = new Symptome("calc", TypeSymptome.PANNE_TOTALE, now, 0.0, "2x DOWN");
        List<ActionCorrective> actions = planner.planifier(List.of(s), kb, now);

        assertEquals(1, actions.size());
        assertEquals(TypeAction.REDEMARRER, actions.get(0).type());
        assertEquals("calc", actions.get(0).service());
    }

    @Test
    @DisplayName("Garde-fou cooldown : ignore une nouvelle demande dans la fenetre de 20 s")
    void testCooldownIgnoreNouvelleAction() {
        // Redemarrage effectue il y a 5 secondes (< 20 s de cooldown)
        kb.enregistrerRedemarrage("calc", now - 5_000);

        Symptome s = new Symptome("calc", TypeSymptome.PANNE_TOTALE, now, 0.0, "2x DOWN");
        List<ActionCorrective> actions = planner.planifier(List.of(s), kb, now);

        assertTrue(actions.isEmpty(), "Le cooldown de 20 s doit bloquer un second redemarrage trop rapide");
    }

    @Test
    @DisplayName("Garde-fou limite de crash : bascule vers REACHEMINEMENT apres 3 redemarrages")
    void testLimiteRedemarragesBasculeReacheminement() {
        // 3 redemarrages enregistres dans les 5 dernieres minutes, le dernier datant de plus de 20s
        kb.enregistrerRedemarrage("calc", now - 150_000);
        kb.enregistrerRedemarrage("calc", now - 90_000);
        kb.enregistrerRedemarrage("calc", now - 30_000);

        Symptome s = new Symptome("calc", TypeSymptome.PANNE_TOTALE, now, 0.0, "2x DOWN");
        List<ActionCorrective> actions = planner.planifier(List.of(s), kb, now);

        assertEquals(1, actions.size());
        assertEquals(TypeAction.REACHEMINEMENT, actions.get(0).type(),
                "Apres 3 redemarrages infructueux, le systeme doit basculer vers le secours");
        assertEquals("localhost:50061", actions.get(0).cible());
    }

    @Test
    @DisplayName("Degradation : planifie un REACHEMINEMENT vers le secours")
    void testDegradationPlanifieReacheminement() {
        Symptome s = new Symptome("storage", TypeSymptome.DEGRADATION, now, 620.0, "latence elevee");
        List<ActionCorrective> actions = planner.planifier(List.of(s), kb, now);

        assertEquals(1, actions.size());
        assertEquals(TypeAction.REACHEMINEMENT, actions.get(0).type());
        assertEquals("localhost:50062", actions.get(0).cible());
    }

    @Test
    @DisplayName("Degradation : pas d'action redondante si le routage pointe deja vers le secours")
    void testDegradationPasDeDoublon() {
        kb.setRoutage("storage", "localhost:50062"); // Deja reachemine

        Symptome s = new Symptome("storage", TypeSymptome.DEGRADATION, now, 620.0, "latence elevee");
        List<ActionCorrective> actions = planner.planifier(List.of(s), kb, now);

        assertTrue(actions.isEmpty(), "Si le trafic est deja reachemine vers le secours, aucune nouvelle action requise");
    }

    @Test
    @DisplayName("Surcharge : planifie une MISE_A_ECHELLE")
    void testSurchargePlanifieMiseAEchelle() {
        Symptome s = new Symptome("notif", TypeSymptome.SURCHARGE, now, 0.95, "cpu surcharge");
        List<ActionCorrective> actions = planner.planifier(List.of(s), kb, now);

        assertEquals(1, actions.size());
        assertEquals(TypeAction.MISE_A_ECHELLE, actions.get(0).type());
        assertEquals("notif", actions.get(0).service());
    }
}

