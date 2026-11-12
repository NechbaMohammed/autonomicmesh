package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.*;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Tests de bout en bout des trois services via un vrai serveur gRPC (port libre). */
class ServicesTest {

    private MeshServer calc, storage, notif;
    private ManagedChannel chCalc, chStorage, chNotif;

    @BeforeEach
    void demarrer() throws Exception {
        calc = MeshServer.demarrer("calc", 0, "calc-test");
        storage = MeshServer.demarrer("storage", 0, "storage-test");
        notif = MeshServer.demarrer("notif", 0, "notif-test");
        chCalc = canal(calc);
        chStorage = canal(storage);
        chNotif = canal(notif);
    }

    @AfterEach
    void arreter() throws Exception {
        for (ManagedChannel c : new ManagedChannel[]{chCalc, chStorage, chNotif}) {
            c.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }
        calc.arreter();
        storage.arreter();
        notif.arreter();
    }

    private static ManagedChannel canal(MeshServer s) {
        return ManagedChannelBuilder.forAddress("localhost", s.port()).usePlaintext().build();
    }

    @Test
    void calcCalculeLesQuatreOperations() {
        var stub = CalcServiceGrpc.newBlockingStub(chCalc);
        assertEquals(7.0, calcul(stub, 3, 4, "+"));
        assertEquals(-1.0, calcul(stub, 3, 4, "-"));
        assertEquals(12.0, calcul(stub, 3, 4, "*"));
        assertEquals(0.75, calcul(stub, 3, 4, "/"));
        assertTrue(Double.isNaN(calcul(stub, 1, 0, "/")), "division par zero => NaN");
    }

    @Test
    void calcRefuseUneOperationInconnue() {
        var stub = CalcServiceGrpc.newBlockingStub(chCalc);
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> stub.compute(CalcRequest.newBuilder().setA(1).setB(2).setOp("^").build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    }

    @Test
    void storageStockeEtRecupere() {
        var stub = StorageServiceGrpc.newBlockingStub(chStorage);
        assertTrue(stub.put(PutReq.newBuilder().setKey("k").setValue("v").build()).getOk());
        GetResp r = stub.get(GetReq.newBuilder().setKey("k").build());
        assertTrue(r.getFound());
        assertEquals("v", r.getValue());
        assertFalse(stub.get(GetReq.newBuilder().setKey("absente").build()).getFound());
    }

    @Test
    void notifAccepteUnEvenementEtRefuseLeVide() {
        var stub = NotifServiceGrpc.newBlockingStub(chNotif);
        assertTrue(stub.notify(NotifRequest.newBuilder().setEvent("test").setPayload("{}").build()).getAccepted());
        assertFalse(stub.notify(NotifRequest.newBuilder().setEvent(" ").build()).getAccepted());
    }

    @Test
    void healthCheckRepondServingAvecUptimeEtInstance() throws Exception {
        Thread.sleep(50);
        HealthResp h = CalcServiceGrpc.newBlockingStub(chCalc)
                .healthCheck(HealthReq.newBuilder().setServiceName("calc").build());
        assertEquals(Status_SERVING(), h.getStatus());
        assertTrue(h.getUptimeMs() >= 50);
        assertEquals("calc-test", h.getInstanceId());
        assertTrue(h.getCpuLoad() >= 0 && h.getCpuLoad() <= 1);
    }

    @Test
    void injectionDeLatenceRendLeServiceDegradePuisOnLeRetablit() {
        var admin = AdminServiceGrpc.newBlockingStub(chStorage);
        var stub = StorageServiceGrpc.newBlockingStub(chStorage);
        HealthReq req = HealthReq.newBuilder().setServiceName("storage").build();

        admin.inject(FaultReq.newBuilder().setLatencyMs(300).build());
        long t0 = System.nanoTime();
        HealthResp h = stub.healthCheck(req);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(ca.usherbrooke.ift605.mesh.proto.MeshProto.Status.DEGRADED, h.getStatus());
        assertTrue(ms >= 300, "la latence injectee doit etre observable : " + ms + " ms");

        admin.inject(FaultReq.newBuilder().setClear(true).build());
        assertEquals(Status_SERVING(), stub.healthCheck(req).getStatus());
    }

    @Test
    void injectionCpuEstRapporteeDansLeHealthCheck() {
        var admin = AdminServiceGrpc.newBlockingStub(chNotif);
        admin.inject(FaultReq.newBuilder().setCpuLoad(0.95).build());
        HealthResp h = NotifServiceGrpc.newBlockingStub(chNotif)
                .healthCheck(HealthReq.newBuilder().setServiceName("notif").build());
        assertEquals(0.95, h.getCpuLoad(), 1e-9);
    }

    private static double calcul(CalcServiceGrpc.CalcServiceBlockingStub stub, double a, double b, String op) {
        return stub.compute(CalcRequest.newBuilder().setA(a).setB(b).setOp(op).build()).getResult();
    }

    private static ca.usherbrooke.ift605.mesh.proto.MeshProto.Status Status_SERVING() {
        return ca.usherbrooke.ift605.mesh.proto.MeshProto.Status.SERVING;
    }
}
