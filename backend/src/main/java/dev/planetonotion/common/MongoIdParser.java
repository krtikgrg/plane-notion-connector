package dev.planetonotion.common;

import dev.planetonotion.common.exceptions.ServiceException;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;

public final class MongoIdParser {

    private MongoIdParser() {
    }

    public static ObjectId required(String value, String fieldName) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_ID", fieldName + " must be a valid ID.");
        }
        return new ObjectId(value);
    }

    public static ObjectId optional(String value, String fieldName) {
        return value == null || value.isBlank() ? null : required(value, fieldName);
    }
}
