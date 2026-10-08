package com.example.couplecredit.api;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/** Compatibility for MySQL TINYINT flags in older chat API responses. */
public final class CompatibleBooleanAdapter extends TypeAdapter<Boolean> {
    @Override public Boolean read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.BOOLEAN) return in.nextBoolean();
        if (in.peek() == JsonToken.NUMBER || in.peek() == JsonToken.STRING) {
            String value = in.nextString();
            if ("1".equals(value) || "true".equalsIgnoreCase(value)) return true;
            if ("0".equals(value) || "false".equalsIgnoreCase(value)) return false;
        }
        throw new JsonParseException("Invalid chat boolean at " + in.getPath());
    }

    @Override public void write(JsonWriter out, Boolean value) throws IOException {
        if (value == null) out.nullValue(); else out.value(value);
    }
}
