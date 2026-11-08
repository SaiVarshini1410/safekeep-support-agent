package com.safekeep.agent.support;

import com.safekeep.agent.config.SafekeepProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Loads the production application YAML files without starting Spring. */
public final class TestConfigs {

    private TestConfigs() { }

    public static SafekeepProperties productionApps() {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            for (String r : new String[]{"apps/backup-server.yml", "apps/storage-server.yml"}) {
                loader.load(r, new ClassPathResource(r)).forEach(env.getPropertySources()::addLast);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Binder.get(env).bind("safekeep", SafekeepProperties.class).get();
    }
}
