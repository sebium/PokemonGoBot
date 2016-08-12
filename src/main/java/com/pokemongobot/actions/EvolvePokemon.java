package com.pokemongobot.actions;

import com.pokegoapi.api.inventory.CandyJar;
import com.pokegoapi.api.map.pokemon.EvolutionResult;
import com.pokegoapi.api.pokemon.Pokemon;

import java.util.ArrayList;
import java.util.List;

public class EvolvePokemon {

    public static List<EvolutionResult> evolvePokemon(List<Pokemon> pokemons, CandyJar candyJar) {
        List<EvolutionResult> results = new ArrayList<>();
        pokemons.forEach(pokemon -> {
            int candies = candyJar.getCandies(pokemon.getPokemonFamily());
            int candiesEvolve = pokemon.getCandiesToEvolve();
            if(candies >= candiesEvolve) {
                EvolutionResult result = evolve(pokemon);
                if(result != null)
                    results.add(result);
            }
        });
        return results;
    }

    public static EvolutionResult evolve(Pokemon pokemon) {
        try {
            EvolutionResult result = pokemon.evolve();
            if(result.isSuccessful()) {
                //TODO LOG it here
                System.out.println("Evolved pokemon");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}