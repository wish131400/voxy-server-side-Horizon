package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;

/** The shared native regression documents already use the Minecraft 1.21.1 router codec. */
final class PredictionTestWorldgen {
    private PredictionTestWorldgen() { }

    static JsonObject current(JsonObject document) {
        return document;
    }
}
