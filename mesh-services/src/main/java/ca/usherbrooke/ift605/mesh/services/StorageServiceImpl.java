package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutResp;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.ConcurrentHashMap;

/** Service de stockage cle/valeur en memoire (thread-safe). L'etat est perdu au redemarrage. */
public class StorageServiceImpl extends StorageServiceGrpc.StorageServiceImplBase {

    private final String instanceId;
    private final FaultState faults;
    private final long startTime = System.currentTimeMillis();
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    public StorageServiceImpl(String instanceId, FaultState faults) {
        this.instanceId = instanceId;
        this.faults = faults;
    }

    @Override
    public void put(PutReq req, StreamObserver<PutResp> resp) {
        faults.retarder();
        if (req.getKey().isBlank()) {
            resp.onError(Status.INVALID_ARGUMENT.withDescription("cle vide").asRuntimeException());
            return;
        }
        store.put(req.getKey(), req.getValue());
        resp.onNext(PutResp.newBuilder().setOk(true).build());
        resp.onCompleted();
    }

    @Override
    public void get(GetReq req, StreamObserver<GetResp> resp) {
        faults.retarder();
        String v = store.get(req.getKey());
        resp.onNext(GetResp.newBuilder().setFound(v != null).setValue(v == null ? "" : v).build());
        resp.onCompleted();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        faults.retarder();
        resp.onNext(HealthSupport.construire(instanceId, startTime, faults));
        resp.onCompleted();
    }
}

