# Networking and Firewall

Vision Call is meant for a local network or a VPN. Every device that uses it must be able to reach the server directly.

## Ports

| Port | Protocol | Used for | Open it? |
|---|---|---|---|
| 8443 | TCP | Web app over HTTPS | Yes |
| 8080 | TCP | Web app over plain HTTP, the Android app | Yes, unless you use `HTTPS_REDIRECT` or don't need it |
| 7882 | UDP | Audio and video in group calls | Yes |
| 3478 | UDP and TCP | TURN relay (optional) | Only if you run the relay |
| 49160 to 49200 | UDP | TURN relay media (optional) | Only if you run the relay |

The Linux and Windows installers open the needed ports for you. With Docker, the `-p` options publish them.

One-to-one calls normally go straight between the two devices, not through the server, so the devices must be able to reach each other as well. On a normal home or office network they can.

## How group calls find the server

A group call sends its audio and video to the server over UDP port 7882. To do that, the server tells each device which address to send to. By default it uses **the same address that device used to open the app**. If someone opened `https://192.168.1.50:8443`, their call media goes to `192.168.1.50:7882`.

This works without any setup on a LAN, over a VPN (people on the VPN use the VPN address) and inside Docker. You only need `EXTERNAL_IP` when that address can't carry the UDP traffic, for example:

- a reverse proxy on a **different machine** sits in front of Vision Call
- people open the app through a port-forward or tunnel that only carries TCP

In those cases set `EXTERNAL_IP` to an address every device can reach the Vision Call machine on.

## VPNs

Vision Call works well over WireGuard, Tailscale, ZeroTier, OpenVPN and similar tools. Give people the server's VPN address, for example `https://100.64.0.5:8443` on Tailscale. A Tailscale MagicDNS name works too.

If calls between two people fail over the VPN while group calls work, their devices probably can't reach each other directly. Add a TURN relay (below).

## TURN relay (optional)

A TURN relay passes call media along when two devices can't connect directly. Most LANs don't need one. Consider it when:

- people are on different subnets or VLANs that block traffic between each other
- a VPN mesh doesn't allow device-to-device traffic
- strict firewalls sit between devices

Vision Call works with [coturn](https://github.com/coturn/coturn), a free TURN server that runs on your network. The relay never talks to the internet.

**With Docker Compose**, see [Install with Docker](Install-with-Docker#optional-turn-relay).

**Without Docker**, install coturn from your Linux distribution (`sudo apt install coturn`), and use `deploy/coturn/turnserver.conf` from the repository as its config. Change `static-auth-secret` to a long random string, then tell Vision Call about it, either in **Admin > Server Settings** or in `.env`:

```
TURN_HOST=192.168.1.50:3478
TURN_SECRET=the-same-long-random-string
```

Vision Call hands each device short-lived relay credentials, so the secret itself never leaves the server.

## Changing the ports

| To change | Set |
|---|---|
| HTTPS port | `LISTEN_ADDR=:9443` |
| HTTP port | `HTTP_ADDR=:9080` |
| Group-call UDP port | `WEBRTC_UDP_PORT=7000` |

With Docker, you can change the left-hand port numbers of `-p` for TCP without touching any settings. For the UDP port, the number must be the same on both sides and match `WEBRTC_UDP_PORT`.

Set `WEBRTC_UDP_PORT=0` to let each call use random UDP ports instead of one fixed port. That only makes sense when there's no firewall on the server.

## Reaching it from the internet

Vision Call is built for private networks. The simplest safe way to use it away from the office is a VPN. If you do publish it on the internet, put it behind a reverse proxy with a real certificate (see [HTTPS and Certificates](HTTPS-and-Certificates#behind-a-reverse-proxy)), forward UDP 7882, set `EXTERNAL_IP` to your public IP, and read [Security](Security) first.
