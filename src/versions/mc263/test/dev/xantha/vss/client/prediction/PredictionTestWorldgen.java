package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;

/** 26.3 fixtures already contain real current codecs; do not reinterpret old graphs. */
final class PredictionTestWorldgen {
    private PredictionTestWorldgen() { }
    static JsonObject current(JsonObject document) { return document; }
}
