package ca.usherbrooke.ift605.mesh.chaos;

import ca.usherbrooke.ift605.mesh.mapek.MeshConfig;
import ca.usherbrooke.ift605.mesh.mapek.ServiceDef;
import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.util.Log;
import com.google.gson.Gson;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Injecteur de pannes et perturbations controlees (« Chaos Engineering »).
 * Respecte des regles strictes de surete : ne cible que les ports et PIDs locaux du maillage AutonomicMesh.
 */
public final class ChaosMonkey {

    private final Random random = new Random(42L); // Graine fixe par defaut pour la reproductibilite scientifique
    private final Gson gson = new Gson();
    private final File pidsFile;

    public ChaosMonkey() {
        this(new File("C:\\Users\\hp\\am2\\run\\pids.json"));
    }

    public ChaosMonkey(File pidsFile) {
        this.pidsFile = pidsFile;
    }

    /**
     * Tue brutalement le processus associe au service donne (simulation de crash franc).
     *
     * @param service nom logique (calc, storage, notif)
     * @return instant exact de la terminaison (epoch ms), ou -1 en cas d'echec
     */
    public long tuerService(String service) {
        var opt = MeshConfig.findService(service);
        if (opt.isEmpty()) {
            Log.warn("CHAOS", "Service inconnu : " + service);
            return -1L;
        }
        int port = opt.get().port();
        long tKill = System.currentTimeMillis();

        // 1. Tentative par PID enregistre dans pids.json
        Long pid = lirePid(service);
        if (pid != null) {
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent() && handle.get().isAlive()) {
                handle.get().destroyForcibly();
                Log.info("CHAOS", String.format("Kill brutal execute sur [%s] (PID %d) a t=%d", service, pid, tKill));
                return tKill;
            }
        }

        // 2. Fallback par port TCP local (uniquement 50051..50053)
        try {
            String cmd = String.format(
                    "powershell -NoProfile -Command \"Get-NetTCPConnection -LocalPort %d -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess | ForEach-Object { Stop-Process -Id $_ -Force }\"",
                    port);
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor(3, TimeUnit.SECONDS);
            Log.info("CHAOS", String.format("Kill brutal execute sur [%s] (port :%d) a t=%d", service, port, tKill));
            return tKill;
        } catch (Exception e) {
            Log.warn("CHAOS", "Impossible de tuer le service " + service + " : " + e.getMessage());
            return -1L;
        }
    }

    /**
     * Injecte une degradation de latence ou une surcharge CPU via le contrat gRPC AdminService.
     */
    public boolean injecterFaute(String service, long latenceMs, double cpuLoad) {
        return appelerAdmin(service, latenceMs, cpuLoad, false);
    }

    /**
     * Supprime toutes les fautes logicielles injectees sur le service.
     */
    public boolean reinitialiserFaute(String service) {
        return appelerAdmin(service, 0L, 0.0, true);
    }

    private boolean appelerAdmin(String service, long latenceMs, double cpuLoad, boolean clear) {
        var opt = MeshConfig.findService(service);
        if (opt.isEmpty()) return false;
        ServiceDef def = opt.get();
        ManagedChannel ch = ManagedChannelBuilder.forAddress(def.host(), def.port()).usePlaintext().build();
        try {
            FaultReq req = FaultReq.newBuilder()
                    .setLatencyMs(latenceMs)
                    .setCpuLoad(cpuLoad)
                    .setClear(clear)
                    .build();
            AdminServiceGrpc.newBlockingStub(ch)
                    .withDeadlineAfter(1500, TimeUnit.MILLISECONDS)
                    .inject(req);
            if (clear) {
                Log.info("CHAOS", String.format("Fautes reinitialisees sur [%s]", service));
            } else {
                Log.info("CHAOS", String.format("Injection sur [%s] : latence=%d ms, cpu=%.2f", service, latenceMs, cpuLoad));
            }
            return true;
        } catch (Exception e) {
            Log.warn("CHAOS", "Echec appel AdminService sur " + service + " : " + e.getMessage());
            return false;
        } finally {
            ch.shutdownNow();
        }
    }

    /** Tire un service au hasard et applique une perturbation. */
    public String frapperAleatoirement() {
        List<ServiceDef> liste = MeshConfig.SERVICES;
        ServiceDef cible = liste.get(random.nextInt(liste.size()));
        int typePanne = random.nextInt(3);
        switch (typePanne) {
            case 0 -> {
                tuerService(cible.name());
                return cible.name() + ":KILL";
            }
            case 1 -> {
                injecterFaute(cible.name(), 650, 0.25);
                return cible.name() + ":LATENCE_650MS";
            }
            default -> {
                injecterFaute(cible.name(), 30, 0.96);
                return cible.name() + ":CPU_96%";
            }
        }
    }

    private Long lirePid(String service) {
        if (pidsFile == null || !pidsFile.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(pidsFile, StandardCharsets.UTF_8))) {
            Map<?, ?> map = gson.fromJson(br, Map.class);
            Object v = map.get(service);
            if (v instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

