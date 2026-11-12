package ca.usherbrooke.ift605.mesh.chaos;

import ca.usherbrooke.ift605.mesh.mapek.KnowledgeBase;
import ca.usherbrooke.ift605.mesh.services.MeshServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshClientTest {

    @Test
    @DisplayName("MeshClient route vers le primaire puis bascule dynamiquement vers le secours")
    void testBasculeRoutageDynamique() throws Exception {
        int portPrimaire = 56101;
        int portSecours = 56102;

        MeshServer srvPrim = MeshServer.demarrer("calc", portPrimaire, "calc-primaire");
        MeshServer srvSec = MeshServer.demarrer("calc", portSecours, "calc-secours");

        KnowledgeBase kb = new KnowledgeBase();
        kb.setRoutage("calc", "localhost:" + portPrimaire);

        try (MeshClient client = new MeshClient(kb)) {
            assertEquals("localhost:" + portPrimaire, client.resoudreCible("calc"));
            double r1 = client.calculer(10, 5, "+");
            assertEquals(15.0, r1, 1e-6);

            // On coupe le serveur primaire et on reachemine vers le secours dans la KB
            srvPrim.arreter();
            kb.setRoutage("calc", "localhost:" + portSecours);

            assertEquals("localhost:" + portSecours, client.resoudreCible("calc"));
            double r2 = client.calculer(7, 6, "*");
            assertEquals(42.0, r2, 1e-6);
        } finally {
            srvPrim.arreter();
            srvSec.arreter();
        }
    }

    @Test
    @DisplayName("MeshClient execute les appels Storage et Notif de bout en bout")
    void testStorageEtNotif() throws Exception {
        int portStorage = 56103;
        int portNotif = 56104;

        MeshServer srvStor = MeshServer.demarrer("storage", portStorage, "storage-1");
        MeshServer srvNotif = MeshServer.demarrer("notif", portNotif, "notif-1");

        KnowledgeBase kb = new KnowledgeBase();
        kb.setRoutage("storage", "localhost:" + portStorage);
        kb.setRoutage("notif", "localhost:" + portNotif);

        try (MeshClient client = new MeshClient(kb)) {
            assertTrue(client.stocker("session-42", "active"));
            assertEquals("active", client.recuperer("session-42"));
            assertTrue(client.notifier("REBOOT", "calc-1 retabli"));
        } finally {
            srvStor.arreter();
            srvNotif.arreter();
        }
    }
}
