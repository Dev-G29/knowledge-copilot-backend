package com.devgangavkar.knowledgecopilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter(autoApply = false)
public class EmbeddingVectorConverter implements AttributeConverter<List<Double>, String> {

    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {
    };
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<Double> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize embedding vector", exception);
        }
    }

    @Override
    public List<Double> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }

        try {
            return OBJECT_MAPPER.readValue(dbData, DOUBLE_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize embedding vector", exception);
        }
    }
}
