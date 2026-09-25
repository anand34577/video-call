# Troubleshooting

Start by looking at the logs (see [Monitoring and Logs](Monitoring-and-Logs)). Most problems explain themselves there.

## I can't open the page at all

- Check the server is running: `docker ps`, `systemctl status visioncall` or `Get-Service VisionCall`.
- Open it from the server itself first: `https://localhost:8443`. If that works but other devices can't connect, a firewall is blocking the ports. See [Networking and Firewall](Networking-and-Firewall#ports).
- Make sure you typed `https://` and the port `:8443`.
- On Windows, the network must be set to **Private** in Settings > Network & internet, or Windows Firewall may block other devices.

## The browser says the connection isn't private

That's expected with the automatic certificate. Click **Advanced** and continue, or install the certificate so the warning goes away. See [HTTPS and Certificates](HTTPS-and-Certificates).

## Camera or microphone don't work

- Use the HTTPS address (`https://...:8443`). Browsers block the camera and microphone on plain `http://` pages, except on `localhost`.
- Check the browser has permission: click the icon at the left of the address bar and allow the camera and microphone.
- Close other apps that might be using the camera, such as Teams or Zoom.
- On macOS, allow the browser in System Settings > Privacy & Security > Camera and Microphone.

## A group call connects but nobody can see or hear each other

Group-call media uses **UDP port 7882**, separate from the web page.

- Open UDP 7882 in the server's firewall. With Docker, make sure the command includes `-p 7882:7882/udp`.
- If you changed the UDP port, `WEBRTC_UDP_PORT` and both sides of the Docker `-p` option must use the same number.
- If a reverse proxy on another machine sits in front of Vision Call, set `EXTERNAL_IP` to the Vision Call machine's IP.
- If you set `EXTERNAL_IP` yourself, check it's an address every device can reach, or remove it so the automatic address is used.

## One-to-one calls fail but group calls work

One-to-one calls go directly between the two devices. If those devices can't reach each other (different subnets, VLANs, some VPN meshes), set up a TURN relay. See [Networking and Firewall](Networking-and-Firewall#turn-relay-optional).

## I lost the admin password

- If there's another admin account, sign in with it and reset the password under **Admin > Users**.
- If email is set up, use **Forgot password?** on the sign-in page.
- You can't recover the password from the logs once it has been changed. The random password is only printed once, on the very first start.

## I get signed out on every restart

That happens when the server can't save its session key in the data folder. Check the data folder is writable by the service (on Docker, see [where the data lives](Install-with-Docker#where-the-data-lives)), or set a fixed `JWT_SECRET`.

## "Signed in on another device"

Each account can be signed in on one device at a time. Signing in somewhere else ends the older session. Create separate accounts for separate people.

## Port already in use

Another program is using port 8443, 8080 or 7882. Change the port (see [Networking and Firewall](Networking-and-Firewall#changing-the-ports)), or with Docker publish a different host port, like `-p 9443:8443`.

## The Android app can't connect

- Enter the full address, including `http://` or `https://` and the port, for example `http://192.168.1.50:8080`.
- Check the phone is on the same Wi-Fi or VPN as the server, and that port 8080 is open.
- With `https://` and the automatic certificate, the certificate must be installed on the phone first. Using `http://...:8080` avoids that.

## Windows: the installer says scripts are disabled

Run `Set-ExecutionPolicy -Scope Process Bypass` in the same PowerShell window, then run the installer again. This only affects that window.

## Still stuck?

Open an [issue](https://github.com/anand34577/vision-call/issues) with what you did, what happened, and the relevant part of the logs. Remove passwords and secrets before posting.
