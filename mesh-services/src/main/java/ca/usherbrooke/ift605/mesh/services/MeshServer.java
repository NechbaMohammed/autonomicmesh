package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.util.Log;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Serveur gRPC d'un micro-service ({@code calc}, {@code storage} ou {@code notif}) avec le service
 * d'administration (injection de fautes). Un {@code MeshServer} = un processus Java dans le deploiement reel.
 */
public final class MeshServer implements AutoCloseable {

    private final Server server;
    private final FaultState faults;
    private final String type;
    private final String instanceId;

    private MeshServer(Server server, FaultState faults, String type, String instanceId) {
        this.server = server;
        this.faults = faults;
        this.type = type;
        this.instanceId = instanceId;
    }

    /**
     * Demarre un service.
     *
     * @param type       {@code calc}, {@code storage} ou {@code notif}
     * @param port       port TCP ({@code 0} = port libre choisi par le systeme)
     * @param instanceId identifiant d'instance (ex. {@code calc-1})
     */
    public static MeshServer demarrer(String type, int port, String instanceId) throws IOException {
        FaultState faults = new FaultState();
        BindableService impl = switch (type) {
            case "calc" -> new CalcServiceImpl(instanceId, faults);
            case "storage" -> new StorageServiceImpl(instanceId, faults);
            case "notif" -> new NotifServiceImpl(instanceId, faults);
            default -> throw new IllegalArgumentException("Type de service inconnu : " + type);
        };
        Server s = ServerBuilder.forPort(port)
                .addService(impl)
                .addService(new AdminServiceImpl(instanceId, faults))
                .build()
                .start();
        Log.info("SERVICE", instanceId + " (" + type + ") demarre sur le port " + s.getPort());
        return new MeshServer(s, faults, type, instanceId);
    }

    /** Port effectivement ecoute. */
    public int port() {
        return server.getPort();
    }

    public String type() {
        return type;
    }

    public String instanceId() {
        return instanceId;
    }

    /** Etat d'injection de fautes (pour les tests). */
    public FaultState faults() {
        return faults;
    }

    /** Bloque jusqu'a l'arret du serveur. */
    public void attendre() throws InterruptedException {
        server.awaitTermination();
    }

    /** Arret immediat, simule un plantage pour les tests. */
    public void arreter() {
        server.shutdownNow();
        try {
            server.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        arreter();
    }
}

