package ca.usherbrooke.ift605.mesh.mapek;

import ca.usherbrooke.ift605.mesh.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitor (le « M » de MAPE-K) : recoit les mesures JSON des sondes par socket TCP, les valide et met a jour
 * la {@link KnowledgeBase}. Il ne prend aucune decision.
 * <p>Une ligne invalide est rejetee et comptee ; la connexion reste ouverte.</p>
 */
public final class Monitor implements AutoCloseable {

    private final KnowledgeBase kb;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Monitor-Conn");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong acceptees = new AtomicLong();
    private final AtomicLong rejetees = new AtomicLong();
    private volatile ServerSocket server;

    public Monitor(KnowledgeBase kb) {
        this.kb = kb;
    }

    /**
     * Demarre l'ecoute (non bloquant).
     *
     * @param port port TCP ({@code 0} = port libre)
     */
    public void demarrer(int port) throws IOException {
        server = new ServerSocket(port);
        Thread accept = new Thread(this::boucleAccept, "Monitor-Accept");
        accept.setDaemon(true);
        accept.start();
        Log.info("MONITOR", "ecoute IPC sur le port " + server.getLocalPort());
    }

    /** Port effectivement ecoute. */
    public int port() {
        return server.getLocalPort();
    }

    public long acceptees() {
        return acceptees.get();
    }

    public long rejetees() {
        return rejetees.get();
    }

    private void boucleAccept() {
        ServerSocket s = server;
        while (!s.isClosed()) {
            try {
                Socket client = s.accept();
                pool.submit(() -> lire(client));
            } catch (IOException e) {
                if (!s.isClosed()) {
                    Log.warn("MONITOR", "accept() a echoue : " + e.getMessage());
                }
            }
        }
    }

    private void lire(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = in.readLine()) != null) {
                traiter(ligne);
            }
        } catch (IOException e) {
            // connexion coupee par la sonde : situation normale (redemarrage de la sonde)
        }
    }

    /** Parse, valide et enregistre une ligne ; visible pour les tests. */
    void traiter(String ligne) {
        try {
            MesureSante m = MesureSante.fromJson(ligne);
            kb.enregistrer(m);
            acceptees.incrementAndGet();
        } catch (IllegalArgumentException e) {
            rejetees.incrementAndGet();
            Log.warn("MONITOR", "mesure rejetee (" + e.getMessage() + ")");
        }
    }

    @Override
    public void close() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // arret : rien a faire
        }
        pool.shutdownNow();
    }
}

