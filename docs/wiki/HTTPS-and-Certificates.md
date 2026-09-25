# HTTPS and Certificates

Browsers only let a web page use the camera and microphone over HTTPS (or on `localhost`). That's why Vision Call serves HTTPS on port 8443 out of the box.

## The default: a certificate made by the server

On first start, Vision Call creates its own certificate in the data folder and renews it automatically before it expires. Nothing to set up. The catch is that browsers don't know this certificate, so each device shows a warning the first time.

You have two ways to deal with the warning.

### Easiest: click through it once per device

| Browser | What to click |
|---|---|
| Chrome, Edge, Brave | **Advanced**, then **Continue to ... (unsafe)** |
| Firefox | **Advanced...**, then **Accept the Risk and Continue** |
| Safari (Mac, iPhone, iPad) | **Show Details**, then **visit this website**, then **Visit Website** |

The connection is still encrypted, and calls work. The browser just keeps a "Not secure" label in the address bar.

### Cleaner: trust the certificate on each device

This removes the warning completely. Download the certificate from your server:

```
https://<server-ip>:8443/api/cert
```

The file is called `visioncall-ca.crt`. Then install it:

| Device | Steps |
|---|---|
| Windows | Double-click the file, **Install Certificate**, choose **Local Machine**, then **Place all certificates in the following store**, pick **Trusted Root Certification Authorities**, and finish. Restart the browser. |
| macOS | Double-click the file to add it to Keychain Access (System keychain). Double-click the certificate there, open **Trust**, set **When using this certificate** to **Always Trust**. |
| iPhone / iPad | Open the file, then Settings > **Profile Downloaded** > Install. Then Settings > General > About > **Certificate Trust Settings**, and turn on full trust for it. |
| Android | Settings > Security > Encryption & credentials > **Install a certificate** > **CA certificate** (menu names vary by manufacturer). |
| Firefox (any OS) | Settings > Privacy & Security > Certificates > **View Certificates** > Authorities > **Import**, tick "Trust this CA to identify websites". |
| Linux (Chrome) | Settings > Privacy and security > Security > **Manage certificates** > Authorities > **Import**. |

The certificate lists the addresses the server knew about when it made it. On a normal install that's the machine's own IP addresses and name. Inside Docker the server can't see your computer's LAN address, so add `EXTERNAL_IP=<your LAN IP>` to your settings and restart; the certificate is then remade to include it. Visiting by an address that isn't listed brings the warning back even when trusted.

## Use your own certificate

If your organisation has its own certificate authority, or the server has a real domain name, point Vision Call at your certificate and key files:

```
TLS_CERT=/path/to/server.crt
TLS_KEY=/path/to/server.key
```

With Docker, put the files in the data volume (for example `/data/certs/`) and use those paths. There are no warnings and nothing to install on devices.

Tip: [mkcert](https://github.com/FiloSottile/mkcert) makes a private certificate authority and certificates in two commands. The `deploy/gen-certs.sh` and `deploy/gen-certs.ps1` scripts in the repository wrap it.

## Behind a reverse proxy

If nginx, Caddy or Traefik already handles HTTPS for your other services, let it handle Vision Call too:

```
TRUST_PROXY=true
LISTEN_ADDR=:8080
```

Vision Call then serves plain HTTP on port 8080 and reads the `X-Forwarded-*` headers from your proxy. The proxy must pass WebSocket connections through.

**Caddy:**

```
visioncall.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

**nginx:**

```nginx
server {
    listen 443 ssl;
    server_name visioncall.example.com;
    # ssl_certificate and ssl_certificate_key lines here

    client_max_body_size 60m;   # a little above MAX_FILE_MB

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 1h;
    }
}
```

Group-call audio and video don't go through the proxy. They use UDP port 7882 directly, so keep that port open. If the proxy runs on a different machine from Vision Call, also set `EXTERNAL_IP` to the Vision Call machine's IP. See [Networking and Firewall](Networking-and-Firewall).

## Plain HTTP only

```
DISABLE_TLS=true
```

Vision Call then serves plain HTTP on port 8080 and makes no certificate. Chat, files and admin work everywhere, and the Android app can call, but browsers only allow calls from the server itself (`http://localhost:8080`). End-to-end encrypted chat also needs HTTPS in the browser. Use this for a quick trial or behind a proxy.

## Summary of the modes

| Setup | Settings | Ports |
|---|---|---|
| Automatic certificate (default) | nothing | HTTPS 8443 and HTTP 8080 |
| Your own certificate | `TLS_CERT`, `TLS_KEY` | HTTPS 8443 and HTTP 8080 |
| Force HTTPS | add `HTTPS_REDIRECT=true` | 8080 only redirects to 8443 |
| Reverse proxy | `TRUST_PROXY=true`, `LISTEN_ADDR=:8080` | HTTP 8080 for the proxy |
| HTTP only | `DISABLE_TLS=true` | HTTP 8080 |
