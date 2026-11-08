# Backup Server memory and job sessions

Every backup or report job creates a job session. The session-cleanup task releases sessions of finished jobs every 10 minutes. If cleanup cannot run, sessions accumulate and memory of the backup-core process grows.

To check: compare sessions held in memory with running jobs. Many more sessions than running jobs means stale sessions.

Stale sessions can be released with the purge action or from Jobs > Sessions in the admin console. If cleanup keeps failing, collect logs and a thread dump of backup-core and escalate to engineering.

High CPU together with a very high login rate usually means a client creates a new authentication session for every request instead of reusing its token. Clearing expired sessions gives temporary relief; the client must be fixed or rolled back.
