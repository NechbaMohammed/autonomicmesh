package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.util.Log;

/**
 * Point d'entree d'un micro-service : {@code java -jar mesh-services-all.jar <type> <port> <instanceId>}.
 * Exemple : {@code calc 50051 calc-1}.
 */
public final class ServiceMain {

    private ServiceMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage : ServiceMain <calc|storage|notif> <port> [instanceId]");
            System.exit(2);
        }
        String type = args[0];
        int port = Integer.parseInt(args[1]);
        String instanceId = args.length > 2 ? args[2] : type + "-" + port;

        MeshServer server = MeshServer.demarrer(type, port, instanceId);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("SERVICE", instanceId + " : arret demande (SIGTERM/Ctrl+C)");
            server.arreter();
        }, "shutdown-" + instanceId));
        server.attendre();
    }
}

