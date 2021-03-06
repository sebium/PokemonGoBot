package com.pokemongobot;

import POGOProtos.Map.Fort.FortDataOuterClass;
import POGOProtos.Networking.Responses.ReleasePokemonResponseOuterClass;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.inventory.CandyJar;
import com.pokegoapi.api.inventory.EggIncubator;
import com.pokegoapi.api.inventory.Inventories;
import com.pokegoapi.api.inventory.Stats;
import com.pokegoapi.api.map.Map;
import com.pokegoapi.api.map.fort.FortDetails;
import com.pokegoapi.api.map.fort.Pokestop;
import com.pokegoapi.api.map.fort.PokestopLootResult;
import com.pokegoapi.api.map.pokemon.CatchResult;
import com.pokegoapi.api.map.pokemon.CatchablePokemon;
import com.pokegoapi.api.map.pokemon.EvolutionResult;
import com.pokegoapi.api.pokemon.Pokemon;
import com.pokegoapi.exceptions.AsyncPokemonGoException;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;
import com.pokegoapi.google.common.geometry.S2LatLng;
import com.pokemongobot.actions.*;
import com.pokemongobot.listeners.HeartBeatListener;
import com.pokemongobot.listeners.LocationListener;
import com.pokemongobot.listeners.SimpleHeartBeatListener;
import com.pokemongobot.listeners.SimpleLocationListener;
import com.pokemongobot.tasks.CatchPokemonActivity;
import com.pokemongobot.tasks.TransferPokemonActivity;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class SimplePokemonBot implements PokemonBot {

    private final Logger logger = Logger.getLogger(Thread.currentThread().getName());
    private final PokemonGo api;
    private State state;
    private BotWalker botWalker;
    private State currentOperation = State.NAN;

    private Options options;

    public SimplePokemonBot(PokemonGo api, Options options) {
        this.api = api;
        this.setCurrentLocation(options.getStartingLocation());

        CatchPokemonActivity catchPokemonActivity = new CatchPokemonActivity(this);
        TransferPokemonActivity transferPokemonActivity =
                new TransferPokemonActivity(this, options);

        HeartBeatListener heartBeatListener = new SimpleHeartBeatListener(50, this);
        heartBeatListener.addHeartBeatActivity(catchPokemonActivity);

        LocationListener locationListener = new SimpleLocationListener(this);

        if (options.isTransferPokemon()) {
            heartBeatListener.addHeartBeatActivity(transferPokemonActivity);
        }
        BotWalker botWalker = new BotWalker(this, options.getStartingLocation(), locationListener, heartBeatListener, options);
        botWalker.addPostStepActivity(catchPokemonActivity);
        this.botWalker = botWalker;
        this.state = State.NAN;
        this.options = options;
    }

    protected static Double getRandom() {
        return Math.random() * 750;
    }

    @Override
    public void wander() {
        boolean stop = false;
        while (!stop) {
            try {
                List<Pokestop> pokestops = getNearbyPokestops();
                logger.info("Found " + pokestops.size() + " Pokestops nearby");

                catchNearbyPokemon();

                lootNearbyPokestops(false);


                doEvolutions();
                Thread.sleep(100 + getRandom().longValue());


                doTransfers();
                Thread.sleep(100 + getRandom().longValue());

                if (options.isLootPokestops()) {
                    for (Pokestop p : pokestops) {
                        try {
                            logger.info("Walking to " + p.getDetails().getName() + " "
                                    + this.getCurrentLocation().getEarthDistance(S2LatLng.fromDegrees(p.getLatitude(), p.getLongitude())) + "m away");
                        } catch (AsyncPokemonGoException | RemoteServerException | LoginFailedException e) {
                            logger.debug(e);
                        }
                        botWalker.runTo(getCurrentLocation(), S2LatLng.fromDegrees(p.getLatitude(), p.getLongitude()));
                        this.setCurrentLocation(S2LatLng.fromDegrees(p.getLatitude(), p.getLongitude()));
                        //lootPokestop(p); TODO bot doesnt always seem to pick up pokestop if I don't add looking for fix

                        lootNearbyPokestops(false);
                        catchNearbyPokemon();
                        doEvolutions();
                        doTransfers();


                        try {
                            Thread.sleep(500 + new Random().nextInt(500 - 100 + 1) + 100);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } else if (options.isCatchPokemon()) {
                    //TODO add bot that will go towards possible pokemon spawn location
                } else if (options.isManageEggs()) {
                    //TODO just add miles to eggs
                }
            } catch (Exception e) {
                logger.debug("Error looping bot", e);
            }
        }
    }


    public final boolean fixSoftBan() {
        boolean running = true;
        while (running) {
            try {
                Pokestop pokestop = getNearestPokestop().get();
                running = !this.fixSoftBan(S2LatLng.fromDegrees(pokestop.getLatitude(), pokestop.getLongitude()));
                return running;
            } catch (Exception e) {
                running = false;
            }
        }
        return false;
    }

    public final boolean fixSoftBan(S2LatLng destination) {
        this.getWalker().runTo(this.getCurrentLocation(), destination);
        setCurrentLocation(destination);
        Optional<Pokestop> nearest = getNearestPokestop();
        if (!nearest.isPresent()) {
            return false;
        }

        Pokestop pokestop = nearest.get();

        try {
            long lon = Double.valueOf(pokestop.getLongitude()).longValue();
            long lat = Double.valueOf(pokestop.getLatitude()).longValue();

            Map map = getApi().getMap();

            for (int i = 0; i < 80; i++) {
                FortDetails d = map.getFortDetails(pokestop.getId(), lon, lat);

                if (d != null) {
                    logger.info("Attempted spin number " + i);
                } else {
                    logger.debug("Error getting pokestop");
                }

                PokestopLootResult r = pokestop.loot();
                if (r.wasSuccessful() && r.getItemsAwarded().size() > 0) {
                    //TODO log xp items gained etc
                    return true;
                } else {
                    logger.info("Failed unbanning");
                }
                try {
                    Thread.sleep(500);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            PokestopLootResult finalTry = pokestop.loot();
            return finalTry.wasSuccessful();
        } catch (AsyncPokemonGoException | RemoteServerException | LoginFailedException e) {
            logger.error("Error while trying to unban", e);
        }

        return false;
    }

    public final Optional<Pokestop> getNearestPokestop() {
        List<Pokestop> pokestops = getNearbyPokestops();
        return pokestops.stream().filter(Pokestop::canLoot).findFirst();
    }

    public final long getCurrentExperience() {
        try {
            Stats stats = getApi().getPlayerProfile().getStats();
            return stats.getExperience();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    protected final String getRuntime() {
        return "";
    }

    public List<Pokestop> getNearbyPokestops() {
        return getPokestops().stream().filter(pokestop ->
                getCurrentLocation().getEarthDistance(S2LatLng.fromDegrees(pokestop.getLatitude(), pokestop.getLongitude())) <= options.getMaxDistance()).sorted(
                (Pokestop a, Pokestop b) ->
                        Double.compare(
                                getCurrentLocation().getEarthDistance(S2LatLng.fromDegrees(a.getLatitude(), a.getLongitude())),
                                getCurrentLocation().getEarthDistance(S2LatLng.fromDegrees(b.getLatitude(), b.getLongitude())))
        ).collect(Collectors.toList());
    }


    private List<ReleasePokemonResponseOuterClass.ReleasePokemonResponse.Result> doTransfers() {
        if (options.isTransferPokemon())
            return new ArrayList<>();
        TransferPokemonActivity a = new TransferPokemonActivity(this, options);
        return a.transferPokemon();
    }

    public List<EvolutionResult> doEvolutions() {
        Inventories inventories = getInventory();
        if (inventories == null || options.isEvolve())
            return new ArrayList<>();

        final CandyJar candyJar = inventories.getCandyjar();

        final List<Pokemon> pokemons = inventories.getPokebank()
                .getPokemons()
                .stream()
                .sorted((Pokemon a, Pokemon b) ->
                        Integer.compare(b.getCp(), a.getCp()))
                .filter(pokemon -> {
                    for (String name : options.getKeepUnevolved()) {
                        if (name.equalsIgnoreCase(pokemon.getPokemonId().name()))
                            return false;
                    }
                    return true;
                })
                .filter(pokemon ->
                        (pokemon.getCandiesToEvolve() > 0 && candyJar.getCandies(pokemon.getPokemonFamily()) >= pokemon.getCandiesToEvolve())
                )
                .collect(Collectors.toList());

        return EvolvePokemon.evolvePokemon(logger, pokemons, candyJar);
    }

    public void manageEggs() {
        if (!options.isManageEggs())
            return;
        try {
            HatchEgg.getHatchedEggs(logger, getInventory().getHatchery()).forEach(hatchedEgg -> {
                //TODO log egg xp etc
            });

            final List<EggIncubator> filled = HatchEgg.fillIncubators(logger, getInventory());
            if (filled.size() > 0) {
                //TODO log filled incubator with egg
            }

            getInventory().getIncubators().stream().filter((incubator1) -> {
                try {
                    return incubator1.isInUse();
                } catch (LoginFailedException | RemoteServerException e) {
                    logger.debug("Error checking if incubator in use", e);
                }
                return true;
            }).forEach(incubator -> {
                //TODO log egg stats such as distance
            });

        } catch (AsyncPokemonGoException e) {
            logger.debug("Error managing eggs", e);
        }
    }

    @Override
    public Inventories getInventory() {
        try {
            return getApi().getInventories();
        } catch (AsyncPokemonGoException | LoginFailedException | RemoteServerException e) {
            logger.debug("Error getting inventory", e);
        }
        return null;
    }

    protected synchronized final State updateOpStatus(State status) {
        State lastOperation = this.currentOperation;
        this.currentOperation = status;

        if (lastOperation != currentOperation)
            logger.debug("Switching from " + lastOperation + " to " + this.currentOperation);

        return lastOperation;
    }

    @Override
    public final Map getMap() {
        return getApi().getMap();
    }

    public List<CatchResult> catchNearbyPokemon() {
        updateOpStatus(State.CATCHING);
        List<CatchablePokemon> catchablePokemon = getCatchablePokemon();
        if (catchablePokemon.size() == 0 || options.isCatchPokemon()) {
            return new ArrayList<>();
        }

        return CatchPokemon.catchPokemon(logger, catchablePokemon);
    }

    public final Collection<Pokestop> getPokestops() {
        try {
            return getMap().getMapObjects().getPokestops();
        } catch (AsyncPokemonGoException | RemoteServerException | LoginFailedException e) {
            logger.debug("Error getting pokestops", e);
        }

        return new ArrayList<>();
    }

    public Collection<FortDataOuterClass.FortData> getGyms() {
        try {
            return getMap().getMapObjects().getGyms();
        } catch (Exception e) {
            e.printStackTrace();

        }
        return new ArrayList<>();
    }

    public List<CatchablePokemon> getCatchablePokemon() {
        try {
            getCurrentLocation();
            return getMap().getCatchablePokemon();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    public synchronized List<PokestopLootResult> lootNearbyPokestops(boolean walkToStops) {

        if (options.isLootPokestops())
            return new ArrayList<>();

        final S2LatLng origin = getCurrentLocation();

        List<Pokestop> pokestops = getNearbyPokestops();
        final List<PokestopLootResult> results = LootPokestop.lootPokestops(logger, pokestops);

        if (!walkToStops) {
            return results;
        }

        pokestops.stream().filter(p -> p.canLoot(true)).forEach(p ->
        {
            double distance = options.getStartingLocation().getEarthDistance(S2LatLng.fromDegrees(p.getLatitude(), p.getLongitude()));
            if (distance < options.getMaxDistance()) {
                botWalker.runTo(getCurrentLocation(), S2LatLng.fromDegrees(p.getLatitude(), p.getLongitude()));
                results.add(lootPokestop(p));
            }
        });

        botWalker.runTo(getCurrentLocation(), origin);

        return results;
    }

    public PokestopLootResult lootPokestop(Pokestop pokestop) {
        updateOpStatus(State.LOOTING);
        return LootPokestop.lootPokestop(logger, pokestop);
    }

    public final S2LatLng getStartLocation() {
        return options.getStartingLocation();
    }

    public synchronized final PokemonGo getApi() {
        return api;
    }

    public Options getOptions() {
        return options;
    }

    public final synchronized S2LatLng setCurrentLocation(S2LatLng newLocation) {
        getApi().setLocation(newLocation.latDegrees(), newLocation.lngDegrees(), 1);
        return newLocation;
    }

    public final synchronized S2LatLng getCurrentLocation() {
        return S2LatLng.fromDegrees(getApi().getLatitude(), getApi().getLongitude());
    }

    public synchronized BotWalker getWalker() {
        return botWalker;
    }

    public synchronized void setWalker(BotWalker botWalker) {
        this.botWalker = botWalker;
    }

}
