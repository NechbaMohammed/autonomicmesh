package ca.usherbrooke.ift605.mesh.mapek;

import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import ca.usherbrooke.ift605.mesh.util.Log;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Sonde : toutes les {@code periodeMs}, appelle le {@code HealthCheck} gRPC du service et transmet une
 * {@link MesureSante} au Monitor par socket TCP (IPC).
 * <ul>
 *   <li>Un appel gRPC en echec produit une mesure {@code DOWN}, jamais un crash de la sonde.</li>
 *   <li>Si le Monitor est injoignable, la mesure est perdue (journalisee) et la connexion est retentee.</li>
 * </ul>
 */
public final class Sonde implements Runnable, AutoCloseable {

    private final ServiceDef def;
    private final String ipcHost;
    private final int ipcPort;
    private final long periodeMs;
    private final ManagedChannel channel;

    private volatile boolean actif = true;
    private Socket socket;          // protege par this
    private PrintWriter sortie;     // protege par this

    public Sonde(ServiceDef def, String ipcHost, int ipcPort, long periodeMs) {
        this.def = def;
        this.ipcHost = ipcHost;
        this.ipcPort = ipcPort;
        this.periodeMs = periodeMs;
        this.channel = ManagedChannelBuilder.forAddress(def.host(), def.port()).usePlaintext().build();
    }

    /** Effectue un HealthCheck et construit la mesure ({@code DOWN} si le service ne repond pas). */
    public MesureSante mesurer() {
        long t0 = System.nanoTime();
        try {
            HealthResp r = appelerHealthCheck();
            long latence = (System.nanoTime() - t0) / 1_000_000;
            return new MesureSante(def.name(), r.getInstanceId(), System.currentTimeMillis(),
                    r.getStatus().name(), latence, r.getCpuLoad(), r.getUptimeMs());
        } catch (StatusRuntimeException e) {
            return MesureSante.down(def.name(), System.currentTimeMillis());
        }
    }

    private HealthResp appelerHealthCheck() {
        HealthReq req = HealthReq.newBuilder().setServiceName(def.name()).build();
        long to = MeshConfig.TIMEOUT_GRPC_MS;
        return switch (def.type()) {
            case "calc" -> CalcServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(to, TimeUnit.MILLISECONDS).healthCheck(req);
            case "storage" -> StorageServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(to, TimeUnit.MILLISECONDS).healthCheck(req);
            case "notif" -> NotifServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(to, TimeUnit.MILLISECONDS).healthCheck(req);
            default -> throw new IllegalStateException("type de service inconnu : " + def.type());
        };
    }

    /**
     * Transmet une mesure au Monitor (une ligne JSON).
     *
     * @return {@code true} si la ligne a ete ecrite sans erreur
     */
    public synchronized boolean envoyer(MesureSante m) {
        try {
            if (socket == null || socket.isClosed() || sortie == null) {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ipcHost, ipcPort), 1000);
                socket = s;
                sortie = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), false);
            }
            sortie.println(m.toJson());
            sortie.flush();
            if (sortie.checkError()) {
                fermerSocket();
                return false;
            }
            return true;
        } catch (IOException e) {
            fermerSocket();
            return false;
        }
    }

    private void fermerSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // deja ferme
        }
        socket = null;
        sortie = null;
    }

    @Override
    public void run() {
        Log.info("SONDE", def.name() + " : demarree (periode " + periodeMs + " ms, cible " + def.host() + ":" + def.port() + ")");
        while (actif) {
            try {
                MesureSante m = mesurer();
                boolean ok = envoyer(m);
                Log.info("SONDE", String.format("%-7s %-8s latence=%4d ms cpu=%.2f %s",
                        def.name(), m.statut(), m.latenceMs(), m.cpuLoad(), ok ? "-> IPC ok" : "-> IPC INDISPONIBLE"));
            } catch (RuntimeException e) {
                // la sonde ne doit jamais mourir : on journalise et on continue
                Log.warn("SONDE", def.name() + " : erreur inattendue : " + e);
            }
            try {
                Thread.sleep(periodeMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public synchronized void close() {
        actif = false;
        fermerSocket();
        channel.shutdownNow();
    }
}

