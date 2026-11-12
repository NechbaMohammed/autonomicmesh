package ca.usherbrooke.ift605.mesh.mapek;

/**
 * Description d'un service supervise.
 *
 * @param name nom logique ({@code calc}, {@code storage}, {@code notif})
 * @param type type gRPC ({@code calc}, {@code storage}, {@code notif})
 * @param host hote de l'instance principale
 * @param port port gRPC de l'instance principale
 */
public record ServiceDef(String name, String type, String host, int port) {

    /** Meme definition avec un autre port (tests, instances de secours). */
    public ServiceDef avecPort(int nouveauPort) {
        return new ServiceDef(name, type, host, nouveauPort);
    }
}

