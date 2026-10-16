package com.tapin.shared.web;

import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** Explicit allowlist: omitted fields are unchanged; only phone may be null. */
public final class ProfilePatch {
    private final Map<String, JsonNode> fields;

    public ProfilePatch(Map<String, JsonNode> fields, Set<String> allowed) {
        this.fields = fields;
        if (fields.size() < 2 || !allowed.containsAll(fields.keySet())) throw invalid("Zahtjev sadrži nedopuštena polja.");
        version();
    }

    public long version() {
        JsonNode value = fields.get("expectedVersion");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
            throw invalid("Obvezna je valjana verzija zapisa.");
        return value.asLong();
    }

    public boolean has(String field) { return fields.containsKey(field); }

    public String text(String field, String fallback) {
        if (!has(field)) return fallback;
        JsonNode value = fields.get(field);
        if (value == null || value.isNull()) {
            if (field.equals("phone")) return null;
            throw invalid("Polje " + field + " ne smije biti prazno.");
        }
        if (!value.isString()) throw invalid("Neispravna vrijednost polja " + field + ".");
        String text = value.asString().strip();
        return field.equals("phone") && text.isBlank() ? null : text;
    }

    /** {@code null} when the field is absent; a JSON boolean otherwise. */
    public Boolean bool(String field) {
        if (!has(field)) return null;
        JsonNode value = fields.get(field);
        if (value == null || !value.isBoolean()) throw invalid("Polje " + field + " mora biti true ili false.");
        return value.asBoolean();
    }

    public LocalDate date(String field, LocalDate fallback) {
        try { return has(field) ? LocalDate.parse(text(field, null)) : fallback; }
        catch (java.time.DateTimeException ex) { throw invalid("Datum mora biti u obliku YYYY-MM-DD."); }
    }

    public static ApiException invalid(String message) { return new ApiException(ErrorCode.VALIDATION_FAILED, message); }
}
