package com.tiam.play.service;

import com.tiam.play.dto.MemoryCard;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MemoryCardGenerator {

    private static final List<CardTemplate> TEMPLATES = List.of(
        new CardTemplate("sol",      "Sol",     "Sun"),
        new CardTemplate("casa",     "Casa",    "Home"),
        new CardTemplate("perro",    "Perro",   "PawPrint"),
        new CardTemplate("flor",     "Flor",    "Flower2"),
        new CardTemplate("auto",     "Auto",    "Car"),
        new CardTemplate("estrella", "Estrella","Star")
    );

    public List<MemoryCard> generate() {
        return TEMPLATES.stream()
            .flatMap(t -> List.of(
                new MemoryCard(t.key() + "-1", t.key(), t.label(), t.icon()),
                new MemoryCard(t.key() + "-2", t.key(), t.label(), t.icon())
            ).stream())
            .toList();
    }

    private record CardTemplate(String key, String label, String icon) {}
}
