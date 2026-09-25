# First Steps

You've installed Vision Call. Here's how to get your team using it.

## 1. Sign in as admin

Open `https://<server-ip>:8443` and sign in as `admin` with the password from the installer output. If you missed it:

| Installed with | Find the password with |
|---|---|
| Docker | `docker logs visioncall 2>&1 \| grep -i password` |
| Docker Compose | `docker compose logs app \| grep -i password` |
| Linux | `journalctl -u visioncall \| grep -i password` |
| Windows | Search for `password` in `C:\ProgramData\VisionCall\visioncall.log` |

The password is only generated once, the first time the server starts with an empty database. To choose it yourself, set `BOOTSTRAP_ADMIN_PASSWORD` before the first start.

## 2. Change the admin password

Open **Settings** from the menu and change your password. Anyone who has seen the install output or the logs knows the first one.

## 3. Create accounts

People can't sign themselves up. An admin creates every account:

1. Open **Admin > Users** and choose **New User**.
2. Enter a username, a display name and a starting password, and pick the role (**user** or **admin**).
3. Share the address, username and password with that person. They can change the password under **Settings**.

### Managing accounts

Everything happens in **Admin > Users**. Filter the list by admins, users, suspended or deleted accounts, and search by name, username or email.

| Action | What happens |
|---|---|
| **Edit** | Change the name, role, email or password. Admins can change any account's password, including another admin's. A new password signs that person out of every device. |
| **Suspend** | They're signed out on the spot, even in the middle of a call, and can't sign in until you reactivate them. Nothing is deleted. |
| **Reactivate** | A suspended account can sign in again. |
| **Sign out everywhere** | In the edit dialog. Ends every session without suspending the account, for example after a lost phone. |
| **Delete > Remove account** | The account can never sign in again. Their messages stay in other people's chats, shown as "Deleted user". Removed accounts appear under the **Deleted** filter. |
| **Delete > Erase everything** | Permanently deletes the account with all their messages (in both directions), direct chats, calls, files and rooms. Groups they created pass to another member. You type the username to confirm. A removed account can be erased later too. |

Changes reach everyone straight away: people lists update on every open app, and someone who is suspended, removed or given a new password sees a message explaining why they were signed out. The last active admin can't be suspended, demoted or deleted, and you can't suspend or delete yourself.

Want people to sign in with your company login instead? Set up single sign-on, see [Configuration](Configuration#single-sign-on).

## 4. Chat

- **Chats** lists your conversations. Start one from **Directory**, which lists everyone on the server with their online status.
- Create a **group** for team conversations. Groups can also hold group calls.
- Messages support replies, reactions, edits, pinning, saving and search. Drag a file onto the chat to share it.

## 5. Call

- **One-to-one**: open a chat or someone's entry in the Directory and press the call or video button. The call goes directly between the two devices.
- **Group calls**: start a call from a group chat, or use **Rooms**. Up to 8 people can join by default (change it with `MAX_CALL_PARTICIPANTS`).
- **Rooms** are named meeting rooms. When you create one you can require a passcode, require the owner's approval to join, and invite people.

During a call you can mute, turn the camera off, switch devices and share your screen. The host of a group call can mute or remove participants.

## 6. Tweak the server

**Admin > Server Settings** shows every setting with its current value and where it came from. Most can be changed right there. See [Configuration](Configuration) for what each one does.

**Admin > Audit Log** records sign-ins, account changes, password changes and group management.

## Tips

- Your status (online, away, Do Not Disturb) is next to your name. You're set to away automatically after 5 minutes of inactivity. Do Not Disturb silences notifications.
- In a phone browser, use **Add to Home Screen** to open Vision Call like an app.
- An account can be signed in on one device at a time. Signing in somewhere else signs out the old session.
