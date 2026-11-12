package ca.usherbrooke.ift605.mesh.mapek;

import ca.usherbrooke.ift605.mesh.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrateur central de la boucle autonome MAPE-K.
 * Relie les 4 phases canoniques a la base de connaissances commune :
 * <ul>
 *   <li><b>Monitor</b> : collecte continue des mesures de sante via sockets TCP (IPC :9090).</li>
 *   <li><b>Analyze</b> : correlation statistique et filtrage des faux positifs (cycle 10 s).</li>
 *   <li><b>Plan</b> : selection des actions curatives sous garde-fous (cooldown, fenetre de crash).</li>
 *   <li><b>Execute</b> : execution physique (ProcessBuilder, actualisation du routage, mise a l'echelle).</li>
 * </ul>
 */
public final class GestionnaireAutonome implements AutoCloseable {

    private final KnowledgeBase kb;
    private final Monitor monitor;
    private final Analyzer analyzer;
    private final Planner planner;
    private final Executor executor;
    private final DashboardServer dashboardServer;

    private ScheduledExecutorService scheduler;
    private volatile boolean enCours = false;

    public GestionnaireAutonome() {
        this(new KnowledgeBase(), MeshConfig.DASHBOARD_PORT);
    }

    public GestionnaireAutonome(KnowledgeBase kb, int dashboardPort) {
        this.kb = kb;
        this.monitor = new Monitor(kb);
        this.analyzer = new Analyzer();
        this.planner = new Planner();
        this.executor = new Executor();
        this.dashboardServer = new DashboardServer(kb, dashboardPort, this::executerCycle);
    }

    public synchronized void demarrer(int ipcPort) throws IOException {
        if (enCours) return;
        enCours = true;

        // 1. Demarrage du canal d'entree IPC (Monitor)
        monitor.demarrer(ipcPort);

        // 2. Demarrage du serveur de bord HTTP & SSE
        dashboardServer.demarrer();

        // 3. Demarrage de la boucle MAPE-K cadensee a 10 secondes (MeshConfig.PERIODE_CYCLE_S)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MAPE-K-Loop");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::executerCycle,
                MeshConfig.PERIODE_CYCLE_S,
                MeshConfig.PERIODE_CYCLE_S,
                TimeUnit.SECONDS);

        Log.info("GESTIONNAIRE", "Boucle MAPE-K initialisee (cycle = " + MeshConfig.PERIODE_CYCLE_S + " s).");
    }

    /**
     * Execution d'une iteration complete de la boucle MAPE-K.
     */
    public void executerCycle() {
        long t0 = System.currentTimeMillis();
        try {
            // Affichage diagnostique dans les logs
            afficherEtatConsole();

            // Phase 2 : Analyze
            List<Symptome> symptomes = analyzer.analyser(kb, t0);
            if (!symptomes.isEmpty()) {
                Log.warn("ANALYZE", String.format("%d anomalie(s) identifiee(s) :", symptomes.size()));
                for (Symptome s : symptomes) {
                    Log.warn("ANALYZE", String.format("  -> [%s] %s (valeur=%.1f) : %s",
                            s.service(), s.type(), s.valeurDetectee(), s.message()));
                    kb.enregistrerEvenement("ANALYZE", s.service(), s.type(), null, s.message(), 0, true);
                }
            }

            // Phase 3 : Plan
            List<ActionCorrective> actions = planner.planifier(symptomes, kb, t0);
            if (!actions.isEmpty()) {
                Log.info("PLAN", String.format("%d action(s) planifiee(s) :", actions.size()));
                for (ActionCorrective a : actions) {
                    Log.info("PLAN", String.format("  -> [%s] %s %s (motif: %s)",
                            a.service(), a.type(), a.cible() != null ? "vers " + a.cible() : "", a.raison()));
                    kb.enregistrerEvenement("PLAN", a.service(), null, a.type(), a.raison(), 0, true);
                }
            }

            // Phase 4 : Execute (soumis au mode automatique)
            if (!actions.isEmpty()) {
                if (kb.isModeAuto()) {
                    Log.info("EXECUTE", "Application des actions correctives...");
                    for (ActionCorrective a : actions) {
                        boolean ok = executor.executer(a, kb);
                        Log.info("EXECUTE", String.format("  -> [%s] %s : %s",
                                a.service(), a.type(), ok ? "SUCCES" : "ECHEC"));
                    }
                } else {
                    Log.warn("EXECUTE", "Mode autonome DESACTIVE (manuel) : actions ignorees.");
                }
            }

        } catch (Exception e) {
            Log.warn("MAPE-K", "Anomalie non bloquante dans le cycle : " + e.getMessage());
        }
    }

    private void afficherEtatConsole() {
        Map<String, MesureSante> map = new TreeMap<>(kb.getMesures());
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Cycle MAPE-K [Mode %s | %d mesures] ===%n",
                kb.isModeAuto() ? "AUTO" : "MANUEL", monitor.acceptees()));

        for (Map.Entry<String, MesureSante> e : map.entrySet()) {
            MesureSante m = e.getValue();
            Long recu = kb.getDerniereReception(e.getKey());
            long age = recu == null ? -1 : (now - recu) / 1000;
            sb.append(String.format("  %-7s | %-8s | lat=%4d ms | cpu=%.2f | uptime=%6d ms | route=%-15s (il y a %d s)%n",
                    m.service(), m.statut(), m.latenceMs(), m.cpuLoad(), m.uptimeMs(), kb.getRoutage(m.service()), age));
        }
        Log.info("KB", sb.toString().trim());
    }

    public KnowledgeBase getKnowledgeBase() {
        return kb;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    @Override
    public synchronized void close() {
        enCours = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (dashboardServer != null) {
            dashboardServer.close();
        }
        if (monitor != null) {
            monitor.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int ipcPort = args.length > 0 ? Integer.parseInt(args[0]) : MeshConfig.IPC_PORT;
        int httpPort = args.length > 1 ? Integer.parseInt(args[1]) : MeshConfig.DASHBOARD_PORT;

        GestionnaireAutonome gestionnaire = new GestionnaireAutonome(new KnowledgeBase(), httpPort);
        gestionnaire.demarrer(ipcPort);

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("GESTIONNAIRE", "Arret du gestionnaire demande (SIGTERM)...");
            gestionnaire.close();
            latch.countDown();
        }, "shutdown-gestionnaire"));

        latch.await();
    }
}
