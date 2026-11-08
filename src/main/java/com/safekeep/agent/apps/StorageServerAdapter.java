package com.safekeep.agent.apps;

import com.safekeep.agent.common.Json;
import org.springframework.stereotype.Component;

@Component
public class StorageServerAdapter extends SeedDataAdapter {
    public StorageServerAdapter(Json json, ApplicationRegistry registry) {
        super("storage-server", json, registry);
    }
}
