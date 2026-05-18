package org.qortal.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.transaction.ChatTransactionData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class JsonUtils {

    private static final Logger LOGGER = LogManager.getLogger(JsonUtils.class);

    // A single, reusable ObjectMapper is thread-safe and efficient.
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        // Configure standard settings
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty-print the JSON
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // Ignore properties in JSON not in class
    }

    /**
     * Saves a list of objects to a JSON file.
     *
     * @param dataFile The file to save the data to.
     * @param list     The list of objects to save.
     * @param <T>      The type of the objects in the list.
     */
    public static <T> void saveListToJson(File dataFile, List<T> list) {
        try {
            // Ensure the parent directory exists
            Path parentPath = dataFile.toPath().getParent();
            if (parentPath != null) {
                Files.createDirectories(parentPath);
            }

            // Write the list to the file using the configured ObjectMapper
            objectMapper.writeValue(dataFile, list);
            LOGGER.info("Successfully saved " + list.size() + " items to " + dataFile.getPath());

        } catch (IOException e) {
            LOGGER.error("Error saving list to JSON file " + dataFile.getPath() + ": " + e.getMessage(), e);
            e.printStackTrace();
        }
    }

    /**
     * Loads a list of objects from a JSON file.
     *
     * @param dataFile The file to load data from.
     * @return A List of objects, or an empty list if the file doesn't exist or an error occurs.
     */
    public static List<ChatTransactionData> loadListFromJson(File dataFile) {
        if (!dataFile.exists() || !dataFile.isFile()) {
            LOGGER.info("Data file not found at " + dataFile.getPath() + ". Starting with an empty list.");
            return new ArrayList<>();
        }

        try {
            // Use a TypeReference to tell Jackson to deserialize into a List<T>
            List<ChatTransactionData> list = objectMapper.readValue(dataFile, new TypeReference<List<ChatTransactionData>>() {});
            LOGGER.info("Successfully loaded " + list.size() + " items from " + dataFile.getPath());
            return list;

        } catch (IOException e) {
            LOGGER.error("Error loading list from JSON file " + dataFile.getPath() + ": " + e.getMessage(), e);
            return new ArrayList<>(); // Return an empty list on error
        }
    }
}