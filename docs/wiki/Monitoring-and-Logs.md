# Monitoring and Logs

## Logs

| Installed with | See the logs with |
|---|---|
| Docker | `docker logs -f videocall` |
| Docker Compose | `docker compose logs -f app` |
| Linux | `journalctl -u videocall -f` |
| Windows | `C:\ProgramData\VisionCall\videocall.log` |
| Run by hand | printed in the terminal |

For more detail while you investigate a problem, set **Log level** to `debug` in **Admin > Server Settings**. It takes effect straight away; set it back to `info` afterwards.

The Windows log file isn't rotated. If it grows large, stop the service, delete or trim the file, and start the service again.

## Health check

```
GET /api/healthz
```

Returns `{"status":"ok","version":"v1.0.1"}` without signing in. Use it with an uptime monitor or load balancer. The Docker image already runs this check itself, so `docker ps` shows the container as `healthy` or `unhealthy`.

## Metrics

```
GET /api/metrics
```

Returns Prometheus metrics without signing in: uptime, number of accounts, people online, active calls, database and upload disk usage, and Go runtime figures such as memory and goroutines. Point Prometheus at it:

```yaml
scrape_configs:
  - job_name: videocall
    scheme: https
    tls_config:
      insecure_skip_verify: true   # only needed with the automatic certificate
    metrics_path: /api/metrics
    static_configs:
      - targets: ["192.168.1.50:8443"]
```

Both endpoints are open to anyone who can reach the server, which is fine on a private network. If the server is reachable from the internet, block them at your reverse proxy.

## Admin dashboard

**Admin** in the app shows the total number of accounts, who is online and how many calls are running. **Admin > Audit Log** lists sign-ins, account changes, password changes and group management, and can be filtered.
