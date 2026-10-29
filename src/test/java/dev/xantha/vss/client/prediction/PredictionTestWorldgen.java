package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;

/** Adapt legacy regression inputs at the Java codec boundary; keep native legacy fixtures intact. */
final class PredictionTestWorldgen {
    private PredictionTestWorldgen() { }

    static JsonObject current(JsonObject document) {
        var router = document.getAsJsonObject("settings").getAsJsonObject("noise_router");
        if (router.has("preliminary_surface_level")) return document;
        var copy = document.deepCopy();
        var settings = copy.getAsJsonObject("settings");
        var noise = settings.getAsJsonObject("noise");
        var target = settings.getAsJsonObject("noise_router");
        var density = new JsonObject();
        density.addProperty("type", "minecraft:add");
        density.add("argument1", target.remove("initial_density_without_jaggedness"));
        density.addProperty("argument2", -0.390625);
        var height = new JsonObject();
        height.addProperty("type", "minecraft:find_top_surface");
        height.add("density", density);
        height.addProperty("upper_bound", noise.get("min_y").getAsInt() + noise.get("height").getAsInt());
        height.addProperty("lower_bound", noise.get("min_y").getAsInt());
        height.addProperty("cell_height", noise.get("size_vertical").getAsInt() * 4);
        target.add("preliminary_surface_level", height);
        return copy;
    }
}
