package io.github.uwegeercken.bucketeer.infrastructure.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Append-only audit log of delete/move actions, stored as JSONL under
 * ~/.bucketeer/actions/actions.jsonl.
 */
@Component
public class ActionHistory {

    private static final Logger log = LoggerFactory.getLogger(ActionHistory.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public ActionHistory() {
        this(Path.of(System.getProperty("user.home"), ".bucketeer", "actions", "actions.jsonl"));
    }

    ActionHistory(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Appends an entry to the log. Failures are logged as error messages only. */
    public void append(ActionEntry entry) {
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                String line = mapper.writeValueAsString(entry);
                Files.writeString(file, line + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("Failed to append action history entry: {}", e.getMessage());
            }
        }
    }

    /** Returns all entries, newest first. */
    public List<ActionEntry> list() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return List.of();
            }
            List<ActionEntry> entries = new ArrayList<>();
            try (Stream<String> lines = Files.lines(file)) {
                lines.forEach(line -> {
                    if (line.isBlank()) return;
                    try {
                        entries.add(mapper.readValue(line, ActionEntry.class));
                    } catch (JsonProcessingException e) {
                        log.error("Failed to parse action history entry: {}", e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.error("Failed to read action history: {}", e.getMessage());
            }
            Collections.reverse(entries);
            return entries;
        }
    }

    /** Deletes the log file. */
    public void clear() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.error("Failed to clear action history: {}", e.getMessage());
            }
        }
    }
}
