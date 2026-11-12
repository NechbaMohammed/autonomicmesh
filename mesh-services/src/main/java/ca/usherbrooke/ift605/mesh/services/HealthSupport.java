package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.Status;

import java.lang.management.ManagementFactory;

/** Construction commune de la reponse {@code HealthCheck} des trois services. */
final class HealthSupport {

    private HealthSupport() {
    }

    /**
     * Construit la reponse de sante.
     *
     * @param instanceId identifiant de l'instance (ex. {@code calc-1})
     * @param startMs    instant de demarrage du service (epoch ms)
     * @param faults     etat d'injection de fautes
     */
    static HealthResp construire(String instanceId, long startMs, FaultState faults) {
        double cpu = faults.cpuOverride() >= 0 ? faults.cpuOverride() : cpuProcessus();
        return HealthResp.newBuilder()
                .setStatus(faults.degrade() ? Status.DEGRADED : Status.SERVING)
                .setUptimeMs(System.currentTimeMillis() - startMs)
                .setCpuLoad(cpu)
                .setInstanceId(instanceId)
                .build();
    }

    /** Charge CPU recente du processus JVM (0 si non disponible). */
    private static double cpuProcessus() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double v = sun.getProcessCpuLoad();
            return v < 0 ? 0.0 : Math.min(1.0, v);
        }
        return 0.0;
    }
}

