package ca.usherbrooke.ift605.mesh.mapek;

import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.Status;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import ca.usherbrooke.ift605.mesh.util.Log;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Composant « Execute » de la boucle MAPE-K.
 * Applique reellement les actions decidees par le Planner :
 * <ul>
 *   <li><b>REDEMARRER</b> : lance un nouveau processus via {@link ProcessBuilder}, effectue une
 *   attente active (polling de 10 s max) du premier {@code SERVING}, enregistre le PID.</li>
 *   <li><b>REACHEMINEMENT</b> : met a jour la table de routage de la {@link KnowledgeBase}.</li>
 *   <li><b>MISE_A_ECHELLE</b> : instancie dynamiquement un travailleur sur le port secondaire et valide sa sante.</li>
 * </ul>
 * Toutes les actions sont synchronisees par service pour eviter les operations concurrentes sur un meme composant.
 */
public final class Executor {

    private final File servicesJar;
    private final File logsDir;
    private final File eventsCsvFile;
    private final ConcurrentHashMap<String, Object> verrousParService = new ConcurrentHashMap<>();

    public Executor() {
        this(trouverJarServicesDefaut(), new File("logs"));
    }

    public Executor(File servicesJar, File logsDir) {
        this.servicesJar = servicesJar;
        this.logsDir = logsDir;
        this.eventsCsvFile = new File(logsDir, "events.csv");
        initialiserCsv();
    }

    private void initialiserCsv() {
        try {
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            if (!eventsCsvFile.exists()) {
                try (PrintWriter pw = new PrintWriter(new FileWriter(eventsCsvFile, StandardCharsets.UTF_8, false))) {
                    pw.println(EvenementMapeK.csvHeader());
                }
            }
        } catch (IOException e) {
            Log.warn("EXECUTOR", "Impossible d'initialiser le fichier events.csv : " + e.getMessage());
        }
    }

    private synchronized void appendCsv(EvenementMapeK evt) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(eventsCsvFile, StandardCharsets.UTF_8, true))) {
            pw.println(evt.toCsvLine());
        } catch (IOException e) {
            Log.warn("EXECUTOR", "Erreur lors de l'ecriture dans events.csv : " + e.getMessage());
        }
    }

    /**
     * Execute une action corrective.
     *
     * @param action action decidee par le Planner
     * @param kb     base de connaissances a mettre a jour
     * @return {@code true} si l'action a reussi
     */
    public boolean executer(ActionCorrective action, KnowledgeBase kb) {
        Object verrou = verrousParService.computeIfAbsent(action.service(), k -> new Object());
        synchronized (verrou) {
            long t0 = System.currentTimeMillis();
            boolean succes = false;
            String details = action.raison();

            try {
                switch (action.type()) {
                    case REDEMARRER -> {
                        succes = executerRedemarrage(action.service(), kb);
                        details = succes ? "Redemarrage reussi et verifie (SERVING)" : "Echec du redemarrage (timeout 10 s)";
                    }
                    case REACHEMINEMENT -> {
                        kb.setRoutage(action.service(), action.cible());
                        purgerFautePrimaire(action.service());
                        succes = true;
                        details = "Routage reachemine vers " + action.cible();
                        Log.info("EXECUTOR", String.format("[%s] REACHEMINEMENT -> %s", action.service(), action.cible()));
                    }
                    case MISE_A_ECHELLE -> {
                        succes = executerMiseAEchelle(action.service(), action.cible(), kb);
                        if (succes) {
                            purgerFautePrimaire(action.service());
                        }
                        details = succes ? "Mise a l'echelle reussie vers " + action.cible() : "Echec instanciation travailleur";
                    }
                }
            } catch (Exception e) {
                Log.warn("EXECUTOR", "Exception lors de l'execution de l'action : " + e.getMessage());
                details = "Erreur : " + e.getMessage();
                succes = false;
            }

            long duree = System.currentTimeMillis() - t0;
            EvenementMapeK evt = kb.enregistrerEvenement(
                    "EXECUTE",
                    action.service(),
                    null,
                    action.type(),
                    details,
                    duree,
                    succes);
            appendCsv(evt);
            return succes;
        }
    }

    private boolean executerRedemarrage(String service, KnowledgeBase kb) {
        Optional<ServiceDef> opt = MeshConfig.findService(service);
        if (opt.isEmpty()) {
            Log.warn("EXECUTOR", "Service inconnu pour redemarrage : " + service);
            return false;
        }
        ServiceDef def = opt.get();
        Log.info("EXECUTOR", String.format("[%s] Demarrage du processus de recuperation (port %d)...", service, def.port()));

        Process p = lancerProcessus(def.type(), def.port(), service + "-reboot-" + System.currentTimeMillis() % 10000);
        if (p == null) {
            return false;
        }

        long pid = p.pid();
        Log.info("EXECUTOR", String.format("[%s] Nouveau processus lance (PID %d). Attente active de reprise...", service, pid));

        boolean pret = attendreReprise(def.host(), def.port(), def.type(), def.name(), MeshConfig.TIMEOUT_RECUPERATION_MS);
        if (pret) {
            kb.enregistrerRedemarrage(service, System.currentTimeMillis());
            kb.incrementerPannes(service);
            Log.info("EXECUTOR", String.format("[%s] Recuperation confirmee (statut SERVING recu) pour PID %d", service, pid));
            return true;
        } else {
            Log.warn("EXECUTOR", String.format("[%s] Delai depasse sans reponse SERVING.", service));
            return false;
        }
    }

    private boolean executerMiseAEchelle(String service, String cible, KnowledgeBase kb) {
        Optional<ServiceDef> opt = MeshConfig.findService(service);
        if (opt.isEmpty()) {
            return false;
        }
        ServiceDef def = opt.get();
        int portSecondaire = extrairePort(cible, def.port() + 10);

        Log.info("EXECUTOR", String.format("[%s] MISE A L'ECHELLE : instanciation travailleur sur :%d", service, portSecondaire));
        boolean dejaActif = attendreReprise(def.host(), portSecondaire, def.type(), def.name(), 400);
        if (!dejaActif) {
            Process p = lancerProcessus(def.type(), portSecondaire, service + "-worker-2");
            if (p == null) {
                return false;
            }
        }

        boolean pret = dejaActif || attendreReprise(def.host(), portSecondaire, def.type(), def.name(), MeshConfig.TIMEOUT_RECUPERATION_MS);
        if (pret) {
            kb.setRoutage(service, def.host() + ":" + portSecondaire);
            Log.info("EXECUTOR", String.format("[%s] Travailleur operationnel sur :%d, routage actualise.", service, portSecondaire));
            return true;
        }
        return false;
    }

    private void purgerFautePrimaire(String service) {
        Optional<ServiceDef> opt = MeshConfig.findService(service);
        if (opt.isEmpty()) return;
        ServiceDef def = opt.get();
        ManagedChannel ch = ManagedChannelBuilder.forAddress(def.host(), def.port()).usePlaintext().build();
        try {
            FaultReq req = FaultReq.newBuilder().setLatencyMs(0).setCpuLoad(0.0).setClear(true).build();
            AdminServiceGrpc.newBlockingStub(ch).withDeadlineAfter(500, TimeUnit.MILLISECONDS).inject(req);
        } catch (Exception ignored) {
            // Ignorer si l'instance primaire est hors-ligne
        } finally {
            ch.shutdownNow();
        }
    }

    private Process lancerProcessus(String type, int port, String instanceId) {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-jar",
                servicesJar.getAbsolutePath(),
                type,
                String.valueOf(port),
                instanceId);

        File logFile = new File(logsDir, type + ".log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));

        try {
            return pb.start();
        } catch (IOException e) {
            Log.warn("EXECUTOR", "Impossible de demarrer le processus Java : " + e.getMessage());
            return null;
        }
    }

    /**
     * Interroge gRPC toutes les 250 ms jusqu'a ce que le service reponde SERVING, ou que le timeout expire.
     */
    public static boolean attendreReprise(String host, int port, String type, String serviceName, long timeoutMs) {
        long fin = System.currentTimeMillis() + timeoutMs;
        ManagedChannel ch = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        try {
            HealthReq req = HealthReq.newBuilder().setServiceName(serviceName).build();
            while (System.currentTimeMillis() < fin) {
                try {
                    HealthResp resp = switch (type.toLowerCase()) {
                        case "calc" -> CalcServiceGrpc.newBlockingStub(ch)
                                .withDeadlineAfter(300, TimeUnit.MILLISECONDS).healthCheck(req);
                        case "storage" -> StorageServiceGrpc.newBlockingStub(ch)
                                .withDeadlineAfter(300, TimeUnit.MILLISECONDS).healthCheck(req);
                        case "notif" -> NotifServiceGrpc.newBlockingStub(ch)
                                .withDeadlineAfter(300, TimeUnit.MILLISECONDS).healthCheck(req);
                        default -> null;
                    };
                    if (resp != null && resp.getStatus() == Status.SERVING) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // Service encore en cours de boot, on retente
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        } finally {
            ch.shutdownNow();
        }
    }

    private static int extrairePort(String cible, int defaut) {
        if (cible == null) return defaut;
        int idx = cible.lastIndexOf(':');
        if (idx >= 0 && idx < cible.length() - 1) {
            try {
                return Integer.parseInt(cible.substring(idx + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaut;
    }

    private static File trouverJarServicesDefaut() {
        // Chemin ASCII prioritaire
        File ascii = new File("C:\\Users\\hp\\am2\\mesh-services\\target\\mesh-services-1.0.0-all.jar");
        if (ascii.exists()) {
            return ascii;
        }
        // Fallback relatif
        File rel = new File("../mesh-services/target/mesh-services-1.0.0-all.jar");
        if (rel.exists()) {
            return rel;
        }
        return new File("mesh-services/target/mesh-services-1.0.0-all.jar");
    }
}
