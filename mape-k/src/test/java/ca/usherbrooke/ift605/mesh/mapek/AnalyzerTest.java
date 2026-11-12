package ca.usherbrooke.ift605.mesh.mapek;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerTest {

    private KnowledgeBase kb;
    private Analyzer analyzer;
    private long now;

    @BeforeEach
    void setUp() {
        kb = new KnowledgeBase();
        analyzer = new Analyzer();
        now = 100_000L;
    }

    @Test
    @DisplayName("Regime nominal : aucun symptome detecte")
    void testNominal() {
        kb.enregistrer(new MesureSante("calc", "calc-1", now - 5000, "SERVING", 25, 0.10, 1000));
        kb.enregistrer(new MesureSante("calc", "calc-1", now, "SERVING", 30, 0.12, 6000));

        List<Symptome> symptomes = analyzer.analyser(kb, now);
        assertTrue(symptomes.isEmpty(), "Le regime nominal ne doit generer aucun symptome");
    }

    @Test
    @DisplayName("Pic transitoire isole : filtre et non retenu (suppression faux positif)")
    void testPicTransitoireFiltre() {
        // 1 mesure normale + 1 pic isole de latence (600 ms)
        kb.enregistrer(new MesureSante("calc", "calc-1", now - 5000, "SERVING", 30, 0.10, 1000));
        kb.enregistrer(new MesureSante("calc", "calc-1", now, "SERVING", 600, 0.10, 6000));

        List<Symptome> symptomes = analyzer.analyser(kb, now);
        assertTrue(symptomes.isEmpty(), "Un pic isole sur 1 seul echantillon doit etre filtre pour eviter les faux positifs");
    }

    @Test
    @DisplayName("Panne totale confirmee sur 2 echantillons DOWN")
    void testPanneTotaleConfirmee() {
        kb.enregistrer(new MesureSante("calc", "calc-1", now - 5000, "DOWN", 1000, 0.0, 0));
        kb.enregistrer(new MesureSante("calc", "calc-1", now, "DOWN", 1000, 0.0, 0));

        List<Symptome> symptomes = analyzer.analyser(kb, now);
        assertEquals(1, symptomes.size());
        assertEquals(TypeSymptome.PANNE_TOTALE, symptomes.get(0).type());
        assertEquals("calc", symptomes.get(0).service());
    }

    @Test
    @DisplayName("Degradation de latence confirmee (> 500 ms sur 2 echantillons)")
    void testDegradationConfirmee() {
        kb.enregistrer(new MesureSante("storage", "storage-1", now - 5000, "SERVING", 550, 0.20, 1000));
        kb.enregistrer(new MesureSante("storage", "storage-1", now, "SERVING", 650, 0.25, 6000));

        List<Symptome> symptomes = analyzer.analyser(kb, now);
        assertEquals(1, symptomes.size());
        assertEquals(TypeSymptome.DEGRADATION, symptomes.get(0).type());
        assertEquals("storage", symptomes.get(0).service());
        assertEquals(600.0, symptomes.get(0).valeurDetectee(), 0.1);
    }

    @Test
    @DisplayName("Surcharge CPU confirmee (> 0.90 sur 2 echantillons)")
    void testSurchargeCpuConfirmee() {
        kb.enregistrer(new MesureSante("notif", "notif-1", now - 5000, "SERVING", 15, 0.92, 1000));
        kb.enregistrer(new MesureSante("notif", "notif-1", now, "SERVING", 18, 0.96, 6000));

        List<Symptome> symptomes = analyzer.analyser(kb, now);
        assertEquals(1, symptomes.size());
        assertEquals(TypeSymptome.SURCHARGE, symptomes.get(0).type());
        assertEquals("notif", symptomes.get(0).service());
        assertEquals(0.94, symptomes.get(0).valeurDetectee(), 0.01);
    }

    @Test
    @DisplayName("Sonde muette depuis plus de 15 secondes -> PANNE_TOTALE")
    void testSondeMuette() {
        // Mesure recue a now - 20_000 ms (> 15_000 ms)
        kb.enregistrer(new MesureSante("calc", "calc-1", now - 20_000, "SERVING", 20, 0.05, 1000), now - 20_000);

        List<Symptome> symptomes = analyzer.analyser(kb, now);
        assertEquals(1, symptomes.size());
        assertEquals(TypeSymptome.PANNE_TOTALE, symptomes.get(0).type());
        assertEquals("calc", symptomes.get(0).service());
        assertTrue(symptomes.get(0).message().contains("muette"));
    }
}
