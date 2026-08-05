package com.enrola.agent.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A customer is a directory. Adding customer #2 is adding a directory. */
@Component
public class CustomerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CustomerRegistry.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Map<String, CustomerConfig> customers = new LinkedHashMap<>();

    private record Yaml(String id, String agentName, String calendlyEventId,
                        String timezone, int smsCharLimit) {}

    public CustomerRegistry(@Value("${enrola.customers-dir}") String customersDir) {
        var root = Path.of(customersDir);
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).sorted().forEach(dir -> {
                var yaml = read(dir.resolve("customer.yaml"));
                var dirName = dir.getFileName().toString();
                // The registry is keyed by id, so without this a copied directory whose yaml id
                // was never edited silently overwrites the customer it was copied from - one
                // entry in the map, no error. "Adding a customer is adding a directory" is the
                // headline claim of this design; it has to fail loudly when it is not true.
                if (!dirName.equals(yaml.id())) {
                    throw new IllegalStateException("Customer directory '" + dirName
                            + "' declares id '" + yaml.id() + "'. They must match.");
                }
                customers.put(yaml.id(), new CustomerConfig(
                        yaml.id(),
                        yaml.agentName(),
                        yaml.calendlyEventId(),
                        ZoneId.of(yaml.timezone()),
                        yaml.smsCharLimit(),
                        new ReloadableFile(dir.resolve("system-v1.md")),
                        new ReloadableFile(dir.resolve("info-pack.md"))));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan customers dir " + root.toAbsolutePath(), e);
        }
        log.info("Loaded customers: {}", customers.keySet());
    }

    private static Yaml read(Path path) {
        try {
            return YAML.readValue(path.toFile(), Yaml.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    public CustomerConfig get(String customerId) {
        var config = customers.get(customerId);
        if (config == null) {
            throw new IllegalArgumentException("Unknown customer: " + customerId);
        }
        return config;
    }

    public Set<String> ids() {
        return customers.keySet();
    }
}
