package ca.usherbrooke.ift605.mesh.mapek;

/**
 * Point d'entree executable du gestionnaire autonome (Semaine 2 & 3).
 * Delegue a {@link GestionnaireAutonome} qui orchestre l'ensemble de la boucle MAPE-K.
 */
public final class GestionnaireMain {

    private GestionnaireMain() {
    }

    public static void main(String[] args) throws Exception {
        GestionnaireAutonome.main(args);
    }
}
