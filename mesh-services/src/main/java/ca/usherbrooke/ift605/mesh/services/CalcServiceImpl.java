package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/** Service de calcul : operations arithmetiques de base, avec mesure de latence cote serveur. */
public class CalcServiceImpl extends CalcServiceGrpc.CalcServiceImplBase {

    private final String instanceId;
    private final FaultState faults;
    private final long startTime = System.currentTimeMillis();

    public CalcServiceImpl(String instanceId, FaultState faults) {
        this.instanceId = instanceId;
        this.faults = faults;
    }

    @Override
    public void compute(CalcRequest req, StreamObserver<CalcResponse> resp) {
        long t0 = System.currentTimeMillis();
        faults.retarder();
        double a = req.getA();
        double b = req.getB();
        double result;
        switch (req.getOp()) {
            case "+" -> result = a + b;
            case "-" -> result = a - b;
            case "*" -> result = a * b;
            case "/" -> result = b != 0 ? a / b : Double.NaN;
            default -> {
                // Dans un handler gRPC on signale l'erreur via onError (pas par throw).
                resp.onError(Status.INVALID_ARGUMENT
                        .withDescription("Operation inconnue : '" + req.getOp() + "' (attendu : + - * /)")
                        .asRuntimeException());
                return;
            }
        }
        resp.onNext(CalcResponse.newBuilder()
                .setResult(result)
                .setLatencyMs(System.currentTimeMillis() - t0)
                .build());
        resp.onCompleted();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        faults.retarder();
        resp.onNext(HealthSupport.construire(instanceId, startTime, faults));
        resp.onCompleted();
    }
}

