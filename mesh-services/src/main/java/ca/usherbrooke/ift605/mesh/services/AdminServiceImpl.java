package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultResp;
import ca.usherbrooke.ift605.mesh.util.Log;
import io.grpc.stub.StreamObserver;

/** Service d'administration : injection de latence / charge CPU pour les tests de chaos. */
public class AdminServiceImpl extends AdminServiceGrpc.AdminServiceImplBase {

    private final String instanceId;
    private final FaultState faults;

    public AdminServiceImpl(String instanceId, FaultState faults) {
        this.instanceId = instanceId;
        this.faults = faults;
    }

    @Override
    public void inject(FaultReq req, StreamObserver<FaultResp> resp) {
        if (req.getClear()) {
            faults.effacer();
            Log.info("ADMIN", instanceId + " : injection effacee");
        } else {
            faults.appliquer(req.getLatencyMs(), req.getCpuLoad());
            Log.info("ADMIN", instanceId + " : injection latence=" + req.getLatencyMs()
                    + " ms, cpu=" + req.getCpuLoad());
        }
        resp.onNext(FaultResp.newBuilder().setOk(true).build());
        resp.onCompleted();
    }
}

