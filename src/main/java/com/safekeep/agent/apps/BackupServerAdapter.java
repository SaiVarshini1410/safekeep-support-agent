package com.safekeep.agent.apps;

import com.safekeep.agent.common.Json;
import org.springframework.stereotype.Component;

@Component
public class BackupServerAdapter extends SeedDataAdapter {
    public BackupServerAdapter(Json json, ApplicationRegistry registry) {
        super("backup-server", json, registry);
    }
}
