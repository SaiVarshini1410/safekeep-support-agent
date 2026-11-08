# Dependencies: vCenter and storage servers

If a vCenter datasource is unreachable, VMware backups, restores and inventory refresh for that vCenter stop. Other datasources and Kubernetes backups are not affected. Existing backup data remains safe.

TLS handshake failures with "certificate expired" are fixed on the vCenter side: the VMware administrator must renew the certificate, then re-register the datasource certificate in the Backup Server.

If a storage server is unreachable, backups targeting it are queued and restores from it are unavailable. Check whether the server is powered on, then check its system logs after it returns.
