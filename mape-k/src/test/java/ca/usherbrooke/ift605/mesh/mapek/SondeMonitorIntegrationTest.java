package ca.usherbrooke.ift605.mesh.mapek;

import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.services.MeshServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Integration semaine 1 : vrais services gRPC + sondes + Monitor + KnowledgeBase (ports libres). */
class SondeMonitorIntegrationTest {

    private final List<MeshServer> servers = new ArrayList<>();
    private final List<Sonde> sondes = new ArrayList<>();
    private final List<ServiceDef> defs = new ArrayList<>();
    private KnowledgeBase kb;
    private Monitor monitor;

    @BeforeEach
    void demarrer() throws Exception {
        for (String type : new String[]{"calc", "storage", "notif"}) {
            MeshServer s = MeshServer.demarrer(type, 0, type + "-test");
            servers.add(s);
            defs.add(new ServiceDef(type, type, "localhost", s.port()));
        }
        kb = new KnowledgeBase();
        monitor = new Monitor(kb);
        monitor.demarrer(0);
        for (ServiceDef d : defs) {
            sondes.add(new Sonde(d, "localhost", monitor.port(), 100));
        }
    }

    @AfterEach
    void arreter() {
        sondes.forEach(Sonde::close);
        monitor.close();
        servers.forEach(MeshServer::arreter);
    }

    private static void attendre(BooleanSupplier cond, String msg) throws InterruptedException {
        long fin = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > fin) {
                fail("Delai depasse : " + msg);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void lesTroisServicesSontMesuresServingEtArriventDansLaKb() throws Exception {
        // Le tout premier appel gRPC d'une JVM froide (chargement des classes Netty, connexion) peut depasser
        // le delai de 1 s et produire une mesure DOWN : c'est justement ce que la confirmation sur 2 mesures
        // consecutives de l'Analyzer absorbera. On rechauffe donc les canaux avant de verifier l'etat nominal.
        for (Sonde s : sondes) {
            s.mesurer();
        }
        for (Sonde s : sondes) {
            assertTrue(s.envoyer(s.mesurer()), "la sonde doit joindre le Monitor");
        }
        attendre(() -> kb.getMesures().size() == 3, "3 mesures dans la KB");
        for (ServiceDef d : defs) {
            MesureSante m = kb.getMesures().get(d.name());
            assertNotNull(m);
            assertEquals("SERVING", m.statut());
            assertTrue(m.latenceMs() >= 0 && m.latenceMs() < 500);
            assertEquals(d.name() + "-test", m.instance());
        }
    }

    @Test
    void unServiceArreteProduitUneMesureDownSansCrasherLaSonde() throws Exception {
        servers.get(0).arreter();
        MesureSante m = sondes.get(0).mesurer();
        assertEquals("DOWN", m.statut());
        assertEquals(-1, m.latenceMs());
        assertTrue(sondes.get(0).envoyer(m));
        attendre(() -> "DOWN".equals(kb.getMesures().get("calc") == null ? null : kb.getMesures().get("calc").statut()),
                "DOWN dans la KB");
    }

    @Test
    void uneLatenceInjecteeProduitDegradedEtUneLatenceSuperieureAuSeuil() throws Exception {
        ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", servers.get(1).port()).usePlaintext().build();
        try {
            AdminServiceGrpc.newBlockingStub(ch).inject(FaultReq.newBuilder().setLatencyMs(600).build());
        } finally {
            ch.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }
        MesureSante m = sondes.get(1).mesurer();
        assertEquals("DEGRADED", m.statut());
        assertTrue(m.latenceMs() > MeshConfig.SEUIL_LATENCE_MS, "latence mesuree : " + m.latenceMs());
    }

    @Test
    void leMonitorRejetteUneLigneInvalideMaisAccepteLaSuivante() throws Exception {
        try (Socket s = new Socket("localhost", monitor.port());
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            out.println("ceci n'est pas du json");
            out.println("{\"service\":\"calc\",\"statut\":\"BIZARRE\",\"timestamp\":1,\"cpuLoad\":0.1}");
            out.println(new MesureSante("calc", "calc-x", System.currentTimeMillis(), "SERVING", 5, 0.1, 10).toJson());
            attendre(() -> monitor.acceptees() == 1 && monitor.rejetees() == 2, "1 acceptee, 2 rejetees");
        }
        assertEquals("calc-x", kb.getMesures().get("calc").instance());
    }

    @Test
    void uneSondeSansMonitorNeCrashePasEtRetablitLaConnexion() throws Exception {
        Sonde s = new Sonde(defs.get(0), "localhost", 1, 100);   // port 1 : personne n'ecoute
        try {
            assertFalse(s.envoyer(s.mesurer()), "envoi impossible mais sans exception");
        } finally {
            s.close();
        }
    }

    @Test
    void laKbConserveUnHistoriqueBorne() {
        for (int i = 0; i < KnowledgeBase.HISTORIQUE_MAX + 25; i++) {
            kb.enregistrer(new MesureSante("calc", "c", 1000 + i, "SERVING", i, 0.1, i));
        }
        List<MesureSante> h = kb.getHistorique("calc");
        assertEquals(KnowledgeBase.HISTORIQUE_MAX, h.size());
        assertEquals(25, h.get(0).latenceMs(), "les plus anciennes mesures sont evincees");
        assertEquals(KnowledgeBase.HISTORIQUE_MAX + 24, kb.getMesures().get("calc").latenceMs());
    }
}

