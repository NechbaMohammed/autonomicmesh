package ca.usherbrooke.ift605.mesh.mapek;

import ca.usherbrooke.ift605.mesh.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Lance les sondes (un thread par service) : {@code java -cp mape-k-all.jar ...ProbesMain [portIPC]}.
 */
public final class ProbesMain {

    private ProbesMain() {
    }

    public static void main(String[] args) throws Exception {
        int ipcPort = args.length > 0 ? Integer.parseInt(args[0]) : MeshConfig.IPC_PORT;
        List<Thread> threads = new ArrayList<>();
        for (ServiceDef def : MeshConfig.SERVICES) {
            Sonde sonde = new Sonde(def, "localhost", ipcPort, MeshConfig.PERIODE_SONDE_MS);
            Thread t = new Thread(sonde, "Sonde-" + def.name());
            threads.add(t);
            t.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Log.info("SONDE", "arret des sondes")));
        for (Thread t : threads) {
            t.join();
        }
    }
}

