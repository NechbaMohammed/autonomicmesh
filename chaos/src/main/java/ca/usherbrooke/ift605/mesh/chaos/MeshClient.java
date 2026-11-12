package ca.usherbrooke.ift605.mesh.chaos;

import ca.usherbrooke.ift605.mesh.mapek.KnowledgeBase;
import ca.usherbrooke.ift605.mesh.mapek.MeshConfig;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutResp;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import com.google.gson.Gson;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client intelligent (passerelle de routage dynamique) pour les micro-services AutonomicMesh.
 * Consulte la table de routage de la {@link KnowledgeBase} (directement ou via l'API HTTP du gestionnaire
 * avec un cache de 1 s) afin d'acheminer les appels metier vers l'instance active (primaire ou secours).
 */
public final class MeshClient implements AutoCloseable {

    private final KnowledgeBase kbLocale;
    private final String urlGestionnaire;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, String> cacheRoutage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ManagedChannel> canaux = new ConcurrentHashMap<>();
    private volatile long dernierRafraichissement = 0;

    /** Constructeur attache directement a une KnowledgeBase locale. */
    public MeshClient(KnowledgeBase kbLocale) {
        this.kbLocale = kbLocale;
        this.urlGestionnaire = null;
    }

    /** Constructeur interrogeant l'API REST du Gestionnaire (ex. http://localhost:8088). */
    public MeshClient(String urlGestionnaire) {
        this.kbLocale = null;
        this.urlGestionnaire = urlGestionnaire;
    }

    public String resoudreCible(String service) {
        if (kbLocale != null) {
            String r = kbLocale.getRoutage(service);
            if (r != null) return r;
        }
        long now = System.currentTimeMillis();
        if (urlGestionnaire != null && (now - dernierRafraichissement) > 1000) {
            rafraichirTableHttp();
            dernierRafraichissement = now;
        }
        String cached = cacheRoutage.get(service);
        if (cached != null) return cached;

        return MeshConfig.findService(service)
                .map(d -> d.host() + ":" + d.port())
                .orElse("localhost:50051");
    }

    @SuppressWarnings("unchecked")
    private void rafraichirTableHttp() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(urlGestionnaire + "/api/state").toURL().openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            try (InputStreamReader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                Map<?, ?> body = gson.fromJson(r, Map.class);
                Object routageObj = body.get("routage");
                if (routageObj instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> cacheRoutage.put(String.valueOf(k), String.valueOf(v)));
                }
            }
        } catch (Exception ignored) {
            // En cas d'indisponibilite temporaire, conserve le cache precedent
        }
    }

    private ManagedChannel getCanal(String cible) {
        return canaux.computeIfAbsent(cible, c -> {
            String[] parts = c.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 50051;
            return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        });
    }

    public double calculer(double a, double b, String op) {
        String cible = resoudreCible("calc");
        CalcRequest req = CalcRequest.newBuilder().setA(a).setB(b).setOp(op).build();
        CalcResponse resp = CalcServiceGrpc.newBlockingStub(getCanal(cible))
                .withDeadlineAfter(2, TimeUnit.SECONDS)
                .compute(req);
        return resp.getResult();
    }

    public boolean stocker(String cle, String valeur) {
        String cible = resoudreCible("storage");
        PutReq req = PutReq.newBuilder().setKey(cle).setValue(valeur).build();
        PutResp resp = StorageServiceGrpc.newBlockingStub(getCanal(cible))
                .withDeadlineAfter(2, TimeUnit.SECONDS)
                .put(req);
        return resp.getOk();
    }

    public String recuperer(String cle) {
        String cible = resoudreCible("storage");
        GetReq req = GetReq.newBuilder().setKey(cle).build();
        GetResp resp = StorageServiceGrpc.newBlockingStub(getCanal(cible))
                .withDeadlineAfter(2, TimeUnit.SECONDS)
                .get(req);
        return resp.getFound() ? resp.getValue() : null;
    }

    public boolean notifier(String event, String payload) {
        String cible = resoudreCible("notif");
        NotifRequest req = NotifRequest.newBuilder().setEvent(event).setPayload(payload).build();
        NotifResponse resp = NotifServiceGrpc.newBlockingStub(getCanal(cible))
                .withDeadlineAfter(2, TimeUnit.SECONDS)
                .notify(req);
        return resp.getAccepted();
    }

    @Override
    public void close() {
        for (ManagedChannel ch : canaux.values()) {
            ch.shutdownNow();
        }
        canaux.clear();
    }
}

