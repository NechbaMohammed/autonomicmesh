package ca.usherbrooke.ift605.mesh.mapek;

import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.util.Log;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serveur HTTP leger integre au Gestionnaire (JDK pur, sans framework lourd).
 * Expose les endpoints REST et le flux temps reel SSE (Server-Sent Events) pour le tableau de bord web.
 */
public final class DashboardServer implements AutoCloseable {

    private final KnowledgeBase kb;
    private final int port;
    private final Runnable declencheurCycle;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ScheduledExecutorService sseExecutor;
    private final List<OutputStream> sseClients = new CopyOnWriteArrayList<>();

    public DashboardServer(KnowledgeBase kb, int port) {
        this(kb, port, null);
    }

    public DashboardServer(KnowledgeBase kb, int port, Runnable declencheurCycle) {
        this.kb = kb;
        this.port = port;
        this.declencheurCycle = declencheurCycle;
    }

    public synchronized void demarrer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Dashboard-HTTP");
            t.setDaemon(true);
            return t;
        }));

        server.createContext("/api/state", new StateHandler());
        server.createContext("/api/events", new EventsHandler());
        server.createContext("/api/metrics", new MetricsHandler());
        server.createContext("/api/experiments", new ExperimentsHandler());
        server.createContext("/api/cycle", new CycleHandler());
        server.createContext("/api/stream", new SseHandler());
        server.createContext("/api/chaos/kill", new ChaosKillHandler());
        server.createContext("/api/chaos/inject", new ChaosInjectHandler());
        server.createContext("/api/chaos/clear", new ChaosClearHandler());
        server.createContext("/api/mode", new ModeHandler());
        server.createContext("/", new StaticUiHandler());

        server.start();
        Log.info("DASHBOARD", "Serveur HTTP et API SSE demarres sur http://localhost:" + port);

        // Diffuseur SSE : pousse l'etat toutes les secondes aux clients connectes
        sseExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SSE-Broadcast");
            t.setDaemon(true);
            return t;
        });
        sseExecutor.scheduleAtFixedRate(this::diffuserSse, 1, 1, TimeUnit.SECONDS);
    }

    private void diffuserSse() {
        if (sseClients.isEmpty()) return;
        String jsonPayload = gson.toJson(construireSnapshotEtat());
        byte[] sseData = ("data: " + jsonPayload + "\n\n").getBytes(StandardCharsets.UTF_8);

        List<OutputStream> morts = new ArrayList<>();
        for (OutputStream os : sseClients) {
            try {
                os.write(sseData);
                os.flush();
            } catch (IOException e) {
                morts.add(os);
            }
        }
        sseClients.removeAll(morts);
    }

    private Map<String, Object> construireSnapshotEtat() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("timestamp", System.currentTimeMillis());
        snap.put("modeAuto", kb.isModeAuto());

        Map<String, Object> servicesMap = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (ServiceDef def : MeshConfig.SERVICES) {
            String name = def.name();
            MesureSante m = kb.getMesures().get(name);
            Long recu = kb.getDerniereReception(name);
            long ageSec = recu == null ? -1 : (now - recu) / 1000;

            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", name);
            sm.put("type", def.type());
            sm.put("host", def.host());
            sm.put("port", def.port());
            sm.put("cibleRoutage", kb.getRoutage(name));
            sm.put("pannes", kb.getNbPannes(name));
            sm.put("ageDerniereMesureSec", ageSec);

            if (m != null) {
                sm.put("statut", m.statut());
                sm.put("latenceMs", m.latenceMs());
                sm.put("cpuLoad", m.cpuLoad());
                sm.put("uptimeMs", m.uptimeMs());
                sm.put("instanceId", m.instance());
            } else {
                sm.put("statut", "INCONNU");
                sm.put("latenceMs", 0);
                sm.put("cpuLoad", 0.0);
                sm.put("uptimeMs", 0);
                sm.put("instanceId", "none");
            }
            List<MesureSante> hist = kb.getHistorique(name);
            int start = Math.max(0, hist.size() - 25);
            sm.put("historique", hist.subList(start, hist.size()));
            servicesMap.put(name, sm);
        }
        snap.put("services", servicesMap);
        snap.put("routage", kb.getTableRoutage());
        snap.put("pannes", kb.getTablePannes());
        snap.put("recentEvents", kb.getDerniersEvenements(15));
        return snap;
    }

    private Map<String, Object> calculerMetriques() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        List<EvenementMapeK> events = kb.getEvenements();

        long reussites = 0;
        long totalActions = 0;
        long sommeDureeReussite = 0;

        for (EvenementMapeK e : events) {
            if ("EXECUTE".equalsIgnoreCase(e.phase())) {
                totalActions++;
                if (e.succes()) {
                    reussites++;
                    sommeDureeReussite += e.dureeMs();
                }
            }
        }

        double mttrMoyen = reussites > 0 ? (double) sommeDureeReussite / reussites : 0.0;
        double tauxSucces = totalActions > 0 ? ((double) reussites / totalActions) * 100.0 : 100.0;

        // Calcul memoire vive du gestionnaire
        Runtime rt = Runtime.getRuntime();
        long memUtiliseeMo = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        metrics.put("totalEvenements", events.size());
        metrics.put("totalActions", totalActions);
        metrics.put("recuperationsReussies", reussites);
        metrics.put("tauxRecuperationPourcent", tauxSucces);
        metrics.put("mttrMoyenMs", Math.round(mttrMoyen));
        metrics.put("mttrMoyenSec", Math.round((mttrMoyen / 1000.0) * 10.0) / 10.0);
        metrics.put("memoireMo", memUtiliseeMo);
        metrics.put("filsActifs", Thread.activeCount());
        metrics.put("modeAuto", kb.isModeAuto());
        return metrics;
    }

    private static void repondreCors(HttpExchange ex, int code, String contentType, byte[] corps) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.sendResponseHeaders(code, corps.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(corps);
        }
    }

    private static Map<String, String> parserQueryParams(String query) {
        if (query == null || query.isBlank()) return Collections.emptyMap();
        Map<String, String> res = new HashMap<>();
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                res.put(k, v);
            }
        }
        return res;
    }

    private static String lireCorps(HttpExchange ex) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) {
                sb.append(l);
            }
            return sb.toString();
        }
    }

    // --- Gestionnaires d'endpoints ---

    private final class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            byte[] b = gson.toJson(construireSnapshotEtat()).getBytes(StandardCharsets.UTF_8);
            repondreCors(ex, 200, "application/json; charset=utf-8", b);
        }
    }

    private final class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            byte[] b = gson.toJson(kb.getEvenements()).getBytes(StandardCharsets.UTF_8);
            repondreCors(ex, 200, "application/json; charset=utf-8", b);
        }
    }

    private final class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            byte[] b = gson.toJson(calculerMetriques()).getBytes(StandardCharsets.UTF_8);
            repondreCors(ex, 200, "application/json; charset=utf-8", b);
        }
    }

    private final class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, 0);

            OutputStream os = ex.getResponseBody();
            sseClients.add(os);
            // Envoi d'un premier message immediat
            byte[] premier = ("data: " + gson.toJson(construireSnapshotEtat()) + "\n\n").getBytes(StandardCharsets.UTF_8);
            os.write(premier);
            os.flush();
        }
    }

    private final class ChaosKillHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            Map<String, String> q = parserQueryParams(ex.getRequestURI().getQuery());
            String svc = q.getOrDefault("service", "calc");
            boolean ok = tuerServiceParPort(svc);
            kb.enregistrerEvenement("CHAOS", svc, null, null, "Injection Chaos : arret force du processus", 0, ok);

            Map<String, Object> resp = Map.of("service", svc, "killed", ok);
            repondreCors(ex, 200, "application/json", gson.toJson(resp).getBytes(StandardCharsets.UTF_8));
        }
    }

    private final class ChaosInjectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            Map<String, String> q = parserQueryParams(ex.getRequestURI().getQuery());
            String svc = q.getOrDefault("service", "calc");
            long latence = Long.parseLong(q.getOrDefault("latencyMs", "600"));
            double cpu = Double.parseDouble(q.getOrDefault("cpuLoad", "0.95"));

            boolean ok = injecterFauteGrpc(svc, latence, cpu, false);
            kb.enregistrerEvenement("CHAOS", svc, null, null,
                    String.format("Injection faute gRPC (latence=%d ms, cpu=%.2f)", latence, cpu), 0, ok);

            Map<String, Object> resp = Map.of("service", svc, "latencyMs", latence, "cpuLoad", cpu, "success", ok);
            repondreCors(ex, 200, "application/json", gson.toJson(resp).getBytes(StandardCharsets.UTF_8));
        }
    }

    private final class ChaosClearHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            Map<String, String> q = parserQueryParams(ex.getRequestURI().getQuery());
            String svc = q.getOrDefault("service", "calc");

            boolean ok = injecterFauteGrpc(svc, 0, 0.0, true);
            kb.enregistrerEvenement("CHAOS", svc, null, null, "Reinitialisation des fautes injectees", 0, ok);

            Map<String, Object> resp = Map.of("service", svc, "cleared", ok);
            repondreCors(ex, 200, "application/json", gson.toJson(resp).getBytes(StandardCharsets.UTF_8));
        }
    }

    private final class ModeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            Map<String, String> q = parserQueryParams(ex.getRequestURI().getQuery());
            String autoParam = q.get("auto");
            boolean mode = autoParam != null ? Boolean.parseBoolean(autoParam) : !kb.isModeAuto();
            kb.setModeAuto(mode);
            kb.enregistrerEvenement("MODE", null, null, null, "Passage en mode " + (mode ? "AUTOMATIQUE" : "MANUEL"), 0, true);

            Map<String, Object> resp = Map.of("modeAuto", mode);
            repondreCors(ex, 200, "application/json", gson.toJson(resp).getBytes(StandardCharsets.UTF_8));
        }
    }

    private final class CycleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            if (declencheurCycle != null) {
                declencheurCycle.run();
            }
            Map<String, Object> resp = Map.of("triggered", true, "timestamp", System.currentTimeMillis());
            repondreCors(ex, 200, "application/json", gson.toJson(resp).getBytes(StandardCharsets.UTF_8));
        }
    }

    private final class ExperimentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                repondreCors(ex, 204, null, new byte[0]);
                return;
            }
            File f = new File("C:\\Users\\hp\\am2\\logs\\summary_metrics.json");
            if (!f.exists()) f = new File("logs/summary_metrics.json");
            if (f.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
                    Map<?, ?> data = gson.fromJson(br, Map.class);
                    byte[] b = gson.toJson(data).getBytes(StandardCharsets.UTF_8);
                    repondreCors(ex, 200, "application/json; charset=utf-8", b);
                    return;
                } catch (Exception ignored) {
                }
            }
            repondreCors(ex, 200, "application/json; charset=utf-8", "{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private final class StaticUiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String uriPath = ex.getRequestURI().getPath();
            if (uriPath == null || uriPath.equals("/") || uriPath.isBlank()) {
                uriPath = "/index.html";
            }
            File distDir = new File("C:\\Users\\hp\\am2\\dashboard-ui\\dist");
            if (!distDir.exists()) distDir = new File("dashboard-ui/dist");

            File target = new File(distDir, uriPath.substring(1));
            if (!target.exists() || target.isDirectory()) {
                target = new File(distDir, "index.html");
            }
            if (target.exists() && target.isFile()) {
                String mime = uriPath.endsWith(".css") ? "text/css; charset=utf-8"
                        : uriPath.endsWith(".js") ? "application/javascript; charset=utf-8"
                        : uriPath.endsWith(".svg") ? "image/svg+xml"
                        : "text/html; charset=utf-8";
                byte[] bytes = java.nio.file.Files.readAllBytes(target.toPath());
                repondreCors(ex, 200, mime, bytes);
            } else {
                byte[] msg = "AutonomicMesh API Server Active on :8088".getBytes(StandardCharsets.UTF_8);
                repondreCors(ex, 200, "text/plain; charset=utf-8", msg);
            }
        }
    }

    private boolean injecterFauteGrpc(String service, long latenceMs, double cpuLoad, boolean clear) {
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
                    .withDeadlineAfter(1, TimeUnit.SECONDS)
                    .inject(req);
            return true;
        } catch (Exception e) {
            Log.warn("DASHBOARD", "Impossible d'injecter la faute sur " + service + " : " + e.getMessage());
            return false;
        } finally {
            ch.shutdownNow();
        }
    }

    private boolean tuerServiceParPort(String service) {
        var opt = MeshConfig.findService(service);
        if (opt.isEmpty()) return false;
        int portCible = opt.get().port();

        // Cherche le PID a partir du fichier run/pids.json ou de netstat
        Long pid = trouverPidDepuisFichier(service);
        if (pid != null) {
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                handle.get().destroyForcibly();
                Log.info("DASHBOARD", "Processus " + service + " (PID " + pid + ") termine avec succes.");
                return true;
            }
        }

        // Fallback PowerShell via port
        try {
            String cmd = String.format("powershell -NoProfile -Command \"Get-NetTCPConnection -LocalPort %d -ErrorAction SilentlyContinue | Select -ExpandProperty OwningProcess | ForEach-Object { Stop-Process -Id $_ -Force }\"", portCible);
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor(3, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            Log.warn("DASHBOARD", "Echec terminaison forcee service " + service + " : " + e.getMessage());
            return false;
        }
    }

    private Long trouverPidDepuisFichier(String service) {
        File f = new File("run/pids.json");
        if (!f.exists()) f = new File("C:\\Users\\hp\\am2\\run\\pids.json");
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            Map<?, ?> map = gson.fromJson(br, Map.class);
            Object v = map.get(service);
            if (v instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public synchronized void close() {
        if (sseExecutor != null) {
            sseExecutor.shutdownNow();
        }
        for (OutputStream os : sseClients) {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
        sseClients.clear();
        if (server != null) {
            server.stop(0);
        }
    }
}
