package ca.usherbrooke.ift605.mesh.mapek;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration centralisee (ports, periodes, seuils). Les valeurs par defaut sont celles de l'enonce ;
 * toute modification doit etre expliquee dans le rapport.
 */
public final class MeshConfig {

    private MeshConfig() {
    }

    /** Port TCP du canal IPC sondes -> Monitor. */
    public static final int IPC_PORT = 9090;
    /** Port HTTP du serveur de tableau de bord et API REST/SSE. */
    public static final int DASHBOARD_PORT = 8088;

    /** Periode des sondes (ms). */
    public static final long PERIODE_SONDE_MS = 5_000;
    /** Delai maximal d'un HealthCheck gRPC avant de conclure DOWN (ms). */
    public static final long TIMEOUT_GRPC_MS = 1_000;
    /** Periode du cycle MAPE-K (s). */
    public static final long PERIODE_CYCLE_S = 10;

    /** Seuil de DEGRADATION : latence strictement superieure (ms). */
    public static final long SEUIL_LATENCE_MS = 500;
    /** Seuil de SURCHARGE : charge CPU strictement superieure. */
    public static final double SEUIL_CPU = 0.90;

    /** Delai de silence d'une sonde avant de declarer PANNE_TOTALE (ms). */
    public static final long TIMEOUT_SILENCE_SONDE_MS = 15_000;
    /** Temps de garde (cooldown) minimal entre deux redemarrages d'un meme service (ms). */
    public static final long COOLDOWN_REDEMARRAGE_MS = 20_000;
    /** Nombre maximal de redemarrages autorises par fenetre temporelle. */
    public static final int MAX_REDEMARRAGES_FENETRE = 3;
    /** Fenetre temporelle glissante d'evaluation des redemarrages multiples (ms = 5 minutes). */
    public static final long FENETRE_REDEMARRAGES_MS = 300_000;
    /** Delai maximal d'attente active lors d'un redemarrage avant de conclure a un echec (ms). */
    public static final long TIMEOUT_RECUPERATION_MS = 10_000;

    /** Services supervises par defaut (instances primaires). */
    public static final List<ServiceDef> SERVICES = List.of(
            new ServiceDef("calc", "calc", "localhost", 50051),
            new ServiceDef("storage", "storage", "localhost", 50052),
            new ServiceDef("notif", "notif", "localhost", 50053));

    /** Cibles des instances de secours pre-allouees pour le reacheminement rapide. */
    public static final Map<String, String> SECOURS_PAR_DEFAUT = Map.of(
            "calc", "localhost:50061",
            "storage", "localhost:50062",
            "notif", "localhost:50063");

    /** Retrouve la definition primaire d'un service par son identifiant logique. */
    public static Optional<ServiceDef> findService(String name) {
        return SERVICES.stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Retrouve l'adresse hote:port de secours recommandee pour un service. */
    public static String getCibleSecours(String service) {
        return SECOURS_PAR_DEFAUT.getOrDefault(service.toLowerCase(), "localhost:50061");
    }
}
