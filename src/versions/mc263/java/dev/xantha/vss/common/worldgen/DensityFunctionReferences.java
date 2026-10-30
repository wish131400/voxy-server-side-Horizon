package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Lossless sharing using Minecraft's density holder fields, not larger packet limits. */
public final class DensityFunctionReferences {
    private static final Set<String> BINARY = Set.of("minecraft:add", "minecraft:sub", "minecraft:mul",
            "minecraft:div", "minecraft:min", "minecraft:max");
    private static final Set<String> INPUT = Set.of("minecraft:abs", "minecraft:square", "minecraft:cube",
            "minecraft:sqrt", "minecraft:half_negative", "minecraft:quarter_negative", "minecraft:squeeze",
            "minecraft:reciprocal", "minecraft:negate", "minecraft:log", "minecraft:sign", "minecraft:cache",
            "minecraft:interpolated", "minecraft:blend_density", "minecraft:slice", "minecraft:clamp");
    private static final Set<String> ROUND = Set.of("minecraft:floor", "minecraft:round", "minecraft:ceil", "minecraft:truncate");
    private final JsonObject definitions = new JsonObject();
    private final Map<JsonElement, String> ids = new HashMap<>();

    public JsonObject definitions() { return definitions; }

    public JsonElement compact(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (var item : element.getAsJsonArray()) result.add(compact(item));
            return result;
        }
        if (!element.isJsonObject()) return element;
        JsonObject source = element.getAsJsonObject();
        JsonObject result = new JsonObject();
        String type = source.has("type") && source.get("type").isJsonPrimitive()
                ? source.get("type").getAsString() : "";
        for (var entry : source.entrySet()) {
            JsonElement child = compact(entry.getValue());
            if (holderField(type, entry.getKey()) && child.isJsonObject()) child = reference(child);
            if (type.equals("minecraft:interval_select") && entry.getKey().equals("functions") && child.isJsonArray()) {
                JsonArray values = new JsonArray();
                for (var function : child.getAsJsonArray()) values.add(function.isJsonObject() ? reference(function) : function);
                child = values;
            }
            if (type.equals("minecraft:spline") && entry.getKey().equals("spline")) child = compactSpline(child);
            result.add(entry.getKey(), child);
        }
        return result;
    }

    public JsonObject settings(JsonObject settings) {
        JsonObject result = compact(settings).getAsJsonObject();
        JsonObject router = result.getAsJsonObject("noise_router");
        if (router != null) for (var entry : router.entrySet()) {
            if (entry.getValue().isJsonObject()) entry.setValue(reference(entry.getValue()));
        }
        return result;
    }

    private JsonElement reference(JsonElement value) {
        String id = ids.get(value);
        if (id == null) {
            // A content-derived id stays stable across dimension/registry traversal order.
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(WorldgenJson.bytes(value));
                id = "vss:prediction_density/" + java.util.HexFormat.of().formatHex(hash);
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
            ids.put(value, id);
            definitions.add(id, value);
        }
        return new JsonPrimitive(id);
    }

    private JsonElement compactSpline(JsonElement element) {
        if (!element.isJsonObject()) return element;
        JsonObject spline = element.getAsJsonObject().deepCopy();
        JsonElement coordinate = spline.get("coordinate");
        if (coordinate != null && coordinate.isJsonObject()) spline.add("coordinate", reference(coordinate));
        JsonArray points = spline.getAsJsonArray("points");
        if (points != null) for (var point : points) {
            JsonObject row = point.getAsJsonObject();
            if (row.has("value")) row.add("value", compactSpline(row.get("value")));
        }
        return spline;
    }

    private static boolean holderField(String type, String field) {
        if (BINARY.contains(type)) return field.equals("left") || field.equals("right");
        if (INPUT.contains(type)) return field.equals("input");
        if (ROUND.contains(type)) return field.equals("input") || field.equals("multiple");
        return switch (type) {
            case "minecraft:range_choice" -> field.equals("input") || field.equals("when_in_range") || field.equals("when_out_of_range");
            case "minecraft:interval_select" -> field.equals("input");
            case "minecraft:noise" -> field.equals("shift_x") || field.equals("shift_y") || field.equals("shift_z");
            case "minecraft:find_top_surface" -> field.equals("density") || field.equals("upper_bound");
            case "minecraft:lerp" -> field.equals("alpha") || field.equals("first") || field.equals("second");
            case "minecraft:pow" -> field.equals("base") || field.equals("exponent");
            case "minecraft:ore_vein" -> field.equals("density") || field.equals("richness") || field.equals("filler_gap");
            case "vss:tectonic_config_noise" -> field.equals("shift_x") || field.equals("shift_z");
            // Unknown mod codec fields keep their original inline representation.
            default -> false;
        };
    }
}
