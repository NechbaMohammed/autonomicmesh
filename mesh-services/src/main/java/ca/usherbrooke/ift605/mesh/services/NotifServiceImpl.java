package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifResponse;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.util.Log;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.atomic.AtomicLong;

/** Service de notification : accepte un evenement non vide et confirme sa prise en charge. */
public class NotifServiceImpl extends NotifServiceGrpc.NotifServiceImplBase {

    private final String instanceId;
    private final FaultState faults;
    private final long startTime = System.currentTimeMillis();
    private final AtomicLong recues = new AtomicLong();

    public NotifServiceImpl(String instanceId, FaultState faults) {
        this.instanceId = instanceId;
        this.faults = faults;
    }

    @Override
    public void notify(NotifRequest req, StreamObserver<NotifResponse> resp) {
        faults.retarder();
        boolean accepte = !req.getEvent().isBlank();
        if (accepte) {
            long n = recues.incrementAndGet();
            Log.info("NOTIF", instanceId + " : evenement #" + n + " '" + req.getEvent() + "'");
        }
        resp.onNext(NotifResponse.newBuilder().setAccepted(accepte).build());
        resp.onCompleted();
    }

    /** Nombre d'evenements acceptes depuis le demarrage (utile aux tests). */
    public long recues() {
        return recues.get();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        faults.retarder();
        resp.onNext(HealthSupport.construire(instanceId, startTime, faults));
        resp.onCompleted();
    }
}

