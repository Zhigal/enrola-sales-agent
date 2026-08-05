package com.enrola.agent.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CustomerRegistryTest {

    private static final Path FIXTURES = Path.of("src/test/resources/customers");

    @Test
    void resolvesTwoCustomersIndependentlyWithNoCodeChange() {
        var registry = new CustomerRegistry(FIXTURES.toString());

        assertThat(registry.ids()).containsExactlyInAnyOrder("alpha", "beta");

        var alpha = registry.get("alpha");
        var beta = registry.get("beta");

        assertThat(alpha.agentName()).isEqualTo("Ada");
        assertThat(beta.agentName()).isEqualTo("Bo");
        assertThat(alpha.calendlyEventId()).isEqualTo("evt_alpha");
        assertThat(beta.calendlyEventId()).isEqualTo("evt_beta");
        assertThat(alpha.timezone()).isEqualTo(ZoneId.of("Australia/Melbourne"));
        assertThat(beta.timezone()).isEqualTo(ZoneId.of("Australia/Perth"));
        assertThat(beta.smsCharLimit()).isEqualTo(160);
        assertThat(alpha.prompt().content()).isEqualTo("Alpha prompt.");
        assertThat(beta.prompt().content()).isEqualTo("Beta prompt.");
        assertThat(alpha.infoPack().content()).isEqualTo("Alpha info.");
    }

    @Test
    void unknownCustomerFailsLoudly() {
        var registry = new CustomerRegistry(FIXTURES.toString());
        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void versionChangesWithContentUnderTheSameFilename(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws IOException {
        var file = tmp.resolve("system-v1.md");
        Files.writeString(file, "one");
        var reloadable = new ReloadableFile(file);
        var first = reloadable.version();

        assertThat(first).startsWith("system-v1@");

        Files.writeString(file, "two");
        // Bump mtime explicitly: two writes inside the filesystem's timestamp granularity can
        // otherwise leave it unchanged, and the reload would not fire.
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2000));

        assertThat(reloadable.content()).isEqualTo("two");
        assertThat(reloadable.version()).isNotEqualTo(first);
    }
}
