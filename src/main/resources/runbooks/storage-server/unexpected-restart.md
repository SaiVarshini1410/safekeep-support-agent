# Unexpected restart

Read the system log of the previous boot to find why the server stopped. Kernel I/O errors on one disk followed by blocked tasks or a kernel panic point to a failing disk.

Check disk health. A disk marked FAILING must be replaced. After replacement, run a pool health check (scrub) and confirm the pool is no longer DEGRADED.

Replication and expiration resume automatically after restart; verify their next runs.
