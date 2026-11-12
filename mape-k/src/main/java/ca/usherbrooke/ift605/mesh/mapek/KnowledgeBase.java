package ca.usherbrooke.ift605.mesh.mapek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base de connaissances (le « K » de MAPE-K) : source de verite unique et thread-safe.
 * Ecrite par le Monitor et l'Executor, lue par l'Analyzer, le Planner et le tableau de bord.
 */
public final class KnowledgeBase {

    /** Taille de l'historique conserve par service (anneau glissant). */
    public static final int HISTORIQUE_MAX = 60;
    /** Nombre maximal d'evenements MAPE-K archives en memoire vive. */
    public static final int EVENEMENTS_MAX = 500;

    private final ConcurrentHashMap<String, MesureSante> mesures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<MesureSante>> historique = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> derniereReception = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> nbPannes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> routage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> historiqueRedemarrages = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<EvenementMapeK> evenements = new ConcurrentLinkedDeque<>();
    private final AtomicLong eventIdSeq = new AtomicLong(1);
    private final AtomicBoolean modeAuto = new AtomicBoolean(true);

    public KnowledgeBase() {
        // Initialisation du routage par defaut vers les ports primaires
        for (ServiceDef def : MeshConfig.SERVICES) {
            routage.put(def.name(), def.host() + ":" + def.port());
        }
    }

    /** Remplace l'etat courant du service et ajoute la mesure a l'historique glissant. */
    public void enregistrer(MesureSante m) {
        enregistrer(m, System.currentTimeMillis());
    }

    /** Variante permettant de preciser l'instant de reception (utilisee pour les tests deterministes). */
    public void enregistrer(MesureSante m, long tReception) {
        mesures.put(m.service(), m);
        derniereReception.put(m.service(), tReception);
        Deque<MesureSante> h = historique.computeIfAbsent(m.service(), k -> new ConcurrentLinkedDeque<>());
        h.addLast(m);
        while (h.size() > HISTORIQUE_MAX) {
            h.pollFirst();
        }
    }

    /** Journalise un evenement du cycle MAPE-K. */
    public EvenementMapeK enregistrerEvenement(
            String phase,
            String service,
            TypeSymptome symptome,
            TypeAction action,
            String details,
            long dureeMs,
            boolean succes) {
        long id = eventIdSeq.getAndIncrement();
        EvenementMapeK evt = new EvenementMapeK(
                id,
                System.currentTimeMillis(),
                phase,
                service,
                symptome,
                action,
                details,
                dureeMs,
                succes);
        evenements.addLast(evt);
        while (evenements.size() > EVENEMENTS_MAX) {
            evenements.pollFirst();
        }
        return evt;
    }

    /** Enregistre le timestamp d'un redemarrage pour les regles de cooldown et max-restarts. */
    public void enregistrerRedemarrage(String service, long timestamp) {
        Deque<Long> dates = historiqueRedemarrages.computeIfAbsent(service, k -> new ConcurrentLinkedDeque<>());
        dates.addLast(timestamp);
        long horizon = timestamp - MeshConfig.FENETRE_REDEMARRAGES_MS;
        while (!dates.isEmpty() && dates.peekFirst() < horizon) {
            dates.pollFirst();
        }
    }

    /** Compte les redemarrages d'un service dans la fenetre temporelle passee. */
    public int getRedemarragesRecents(String service, long windowMs, long now) {
        Deque<Long> dates = historiqueRedemarrages.get(service);
        if (dates == null) {
            return 0;
        }
        long minTs = now - windowMs;
        int count = 0;
        for (Long t : dates) {
            if (t >= minTs) {
                count++;
            }
        }
        return count;
    }

    /** Retourne le timestamp du dernier redemarrage de ce service, ou 0 si aucun. */
    public long getDernierRedemarrage(String service) {
        Deque<Long> dates = historiqueRedemarrages.get(service);
        if (dates == null || dates.isEmpty()) {
            return 0L;
        }
        return dates.peekLast();
    }

    /** Vue en lecture seule de l'etat courant (une mesure recente par service). */
    public Map<String, MesureSante> getMesures() {
        return Collections.unmodifiableMap(mesures);
    }

    /** Copie de l'historique d'un service, du plus ancien au plus recent. */
    public List<MesureSante> getHistorique(String service) {
        Deque<MesureSante> h = historique.get(service);
        return h == null ? List.of() : new ArrayList<>(h);
    }

    /** Instant (epoch ms) de la derniere mesure recue pour ce service, ou {@code null}. */
    public Long getDerniereReception(String service) {
        return derniereReception.get(service);
    }

    /** Incremente et retourne le compteur de pannes du service. */
    public int incrementerPannes(String service) {
        return nbPannes.merge(service, 1, Integer::sum);
    }

    public int getNbPannes(String service) {
        return nbPannes.getOrDefault(service, 0);
    }

    public Map<String, Integer> getTablePannes() {
        return Collections.unmodifiableMap(nbPannes);
    }

    /** Cible de routage courante ({@code hote:port}) du service logique, ou defaut. */
    public String getRoutage(String service) {
        return routage.get(service);
    }

    public void setRoutage(String service, String cible) {
        routage.put(service, cible);
    }

    public Map<String, String> getTableRoutage() {
        return Collections.unmodifiableMap(routage);
    }

    public List<EvenementMapeK> getEvenements() {
        return new ArrayList<>(evenements);
    }

    public List<EvenementMapeK> getDerniersEvenements(int limit) {
        List<EvenementMapeK> all = new ArrayList<>(evenements);
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }

    public boolean isModeAuto() {
        return modeAuto.get();
    }

    public void setModeAuto(boolean actif) {
        modeAuto.set(actif);
    }

    /** Reinitialise la base (utilise notamment dans les scenarios de test). */
    public void reinitialiser() {
        mesures.clear();
        historique.clear();
        derniereReception.clear();
        nbPannes.clear();
        historiqueRedemarrages.clear();
        evenements.clear();
        for (ServiceDef def : MeshConfig.SERVICES) {
            routage.put(def.name(), def.host() + ":" + def.port());
        }
        modeAuto.set(true);
    }
}
