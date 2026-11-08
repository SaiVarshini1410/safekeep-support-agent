# Storage pool full

Pool usage above 90% risks failed backups. First check the expiration service: if it has not run, expired backups still occupy space.

Running expiration frees the space of backups past retention. If usage stays high afterwards, expand the pool or reduce retention.

If the expiration service is stopped (for example exit code 137, killed for memory), restart it and check its memory limit.
