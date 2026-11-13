# The Manage page

Every panel serves a Manage page at `http://<panel>:8099/` once it's set up. Open it from Home
Assistant (the panel's device page, **Visit**) or type the address. It shows how the panel is doing,
keeps a log, and runs the everyday actions. It's part of the Glassworks core, so every frame has it.

Status: **new, not yet checked on a panel.** This first version shows the panes' settings but
doesn't change them yet; change them on the panel's own settings for now.

## What's on it

| Page | What it has |
|---|---|
| Overview | Running or not, version and uptime; Home Assistant, Wi-Fi, the web engine, memory; every pane with its state (running, off, or can't run and why). Buttons: **Reload dashboard**, **Restart app**, **Reboot**, **Check for updates** |
| Dashboard, Panel, each pane | The settings the panel's own menu has, read-only for now. The radio's page links to the Thread join |
| Log | The last 7 days, kept across reboots. Filter by warnings or errors, search, and **Download diagnostics** |
| Security | The browsers signed in, each with **Sign out** |
| About | Versions, frame, licences |

On a phone the menu folds behind the ☰ button. Light or dark follows your phone or computer.

Not on the page, because they need someone at the panel: the first Wi-Fi, Bluetooth's "Allow", and
Aqara's own settings app.

## Signing in

1. **Continue with Home Assistant.** You sign in on Home Assistant's own login page, so the panel
   never sees your password. Only **administrators** get in. The panel uses the Home Assistant
   address it already has, and only when that address is on your network (a private IP or a `.local`
   name). It never uses a Nabu Casa or other public address.
2. **Use the code on the panel** when Home Assistant is down. The panel shows a 6-digit code for 5
   minutes; type it in the browser.

**Remember this browser for 30 days** keeps you signed in on that browser. Without it, the sign-in
ends when you close the browser (at the latest after 12 hours). Security lists every signed-in
browser and signs any of them out. All of them are signed out when the panel's Home Assistant
address changes.

**Rebooting asks for the code on the panel** too, because a reboot takes the radio's network down
for everyone for about a minute. The Reboot button only shows where the app can reboot the panel
(when it's the device owner).

**Administrators only, for now.** Like Home Assistant's own device pages, only an administrator can
manage a panel. Whether other users get a say is open (#124).

## How it keeps you safe

- The panel asks Home Assistant who you are, checks you're an administrator, and **revokes Home
  Assistant's token straight away**. It keeps no Home Assistant token from your sign-in. If Home
  Assistant doesn't take the revoke, the panel tries once more and then logs a warning; that token
  then stays under your profile in Home Assistant (*Security → Refresh tokens*) until you delete it. Its own
  long-lived sign-in (the one the dashboard uses) is separate, and the Manage page never shows or
  sends it.
- Your browser gets the panel's own sign-in cookie: `HttpOnly` (scripts can't read it) and
  `SameSite=Strict` (other sites can't use it). The panel stores only a hash of it.
- Every form must come from the page itself: its `Origin` is checked, and it carries a token only
  your signed-in page has. A link or form on another site can't make the panel do anything.
- The code on the panel works once, for one browser and one purpose, for 5 minutes. Five wrong tries
  end a code; ten wrong tries in 15 minutes lock code entry for everyone until they're 15 minutes old.
  A code stays up for at least 30 seconds: asking again from the same browser keeps it, and another
  browser has to wait, so nobody can keep replacing your code.
- If the panel can't draw the code (the app may not draw over other apps), the page says so instead
  of waiting for a code that never shows.
- The page answers only to the panel's own addresses and names (its IP addresses, `s1-panel.local`,
  its Android host name), so another name pointed at it (DNS rebinding, or a spoofed `.local` answer)
  gets nothing.
- No scripts, no outside files, and a strict Content-Security-Policy.
- The log and the diagnostics file have tokens, keys, passwords, Thread datasets and MAC addresses
  removed, so you can attach them to an issue.

**What plain HTTP on your network doesn't protect.** The page is `http://`, like Home Assistant on
`http://`. Someone who can read your Wi-Fi traffic can see the few seconds of the Home Assistant
sign-in and the panel's cookie. With that cookie they can use the Manage page as you, until it's
signed out. They can't use Home Assistant (that token is already revoked), and they can't reboot
the panel (that needs the code on its screen). Use a network you trust; HTTPS on the panel may come
later.

## For developers

The server is `dev.glassworks.manage.ManageServer`; a frame supplies a `ManageHost` (status, actions,
diagnostics). See the [core's README](../app/kiosk/src/main/java/dev/glassworks/README.md#the-manage-page).
