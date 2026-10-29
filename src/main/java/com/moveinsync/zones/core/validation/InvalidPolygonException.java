package com.moveinsync.zones.core.validation;

import com.moveinsync.zones.core.error.BadRequestException;

import java.util.ArrayList;
import java.util.List;

public class InvalidPolygonException extends BadRequestException {

    private final List<ValidationError> errors;

    public InvalidPolygonException(List<ValidationError> errors) {
        super("INVALID_POLYGON", "Polygon failed validation", toDetails(errors));
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }

    private static List<String> toDetails(List<ValidationError> errors) {
        List<String> out = new ArrayList<>();
        for (ValidationError e : errors) {
            out.add(e.toString());
        }
        return out;
    }
}
