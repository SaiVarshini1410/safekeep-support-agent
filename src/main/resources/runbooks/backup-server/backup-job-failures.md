# Backup job failures

Check the job details first: which VMs failed and the error text.

"insufficient space" errors mean the target storage server is full. Check storage target usage. Retrying does not help until space is freed: expand the pool, move the policy to another storage target, or reduce retention.

Errors with only a generic code (for example "code 1") need more information: check the VM's snapshot state in vCenter and the Data Mover Proxy logs for that VM.

Report jobs that time out usually cover too large a date range. Narrow the range or raise the query timeout in report settings.
