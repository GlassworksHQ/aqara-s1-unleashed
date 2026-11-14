# The Manage page

Every panel serves a Manage page at `http://<panel>:8099/` once it's set up. Open it from Home
Assistant (the panel's device page, **Visit**) or type the address: the panel's IP address
(`http://192.0.2.40:8099/`) or `http://s1-panel.local:8099/`. Other names for it, such as one your
router gives it (`panel.lan`) or a name with a trailing dot, get "Open this page with the panel's
address": the page only answers to its own addresses and names (see below). It shows how the panel is doing,
keeps a log, and runs the everyday actions. It's part of the Glassworks core, so every frame has it.

Status: **new, not yet checked on a panel.** It has the same settings as the panel's own menu, and
changes them the same way.

## What's on it

| Page | What it has |
|---|---|
| Overview | Running or not, version and uptime; Home Assistant, Wi-Fi, the web engine, memory; every pane with its state (running, off, or can't run and why). Buttons: **Reload dashboard**, **Restart app**, **Reboot**, **Check for updates** |
| Dashboard, Screen, each pane | Every setting the panel's own menu has, in the same order and words, and you can change each one here. A pane's page has its on/off at the top right and shows what it's doing; a pane that's off says what that means. The radio's page links to the Thread join |
| Log | The last 7 days, kept across reboots. Filter by warnings or errors, search, and **Download diagnostics** |
| Security | The browsers signed in, each with **Sign out** |
| About | Versions, frame, licences |

On a phone the menu folds behind the ☰ button. Light or dark follows your phone or computer.

Not on the page, because they need someone at the panel: the first Wi-Fi, Bluetooth's "Allow", and
Aqara's own settings app. Some pane pages still have parts only the panel draws, and the page says
"On the panel" for them: the radio's roles and Thread network, the time zone picker, the wall base's
relays, and the Bluetooth proxy's devices.

## Changing settings

A change applies on its own, as on the panel: a switch when you click it, a slider with **Save**, a
value, a time or a choice from its dialog. The panel checks each value as its own controls would (a
slider's range, a duration's choices), so the page can't set anything the panel wouldn't. A change
that only takes effect when the app starts again says so and offers **Restart now**. A switch that
asks first on the panel (turning the radio off) asks here too. The panel's menu, if it's open, shows
the new value within a couple of seconds, and the page shows a change made on the panel when you
reload it. Each change goes in the Log with who made it.

A sign-in (a camera stream's user name and password, which the panel makes for you) says Set or Not
set. Open it and press **Show** to read the password, to type it into Protect, Home Assistant's ONVIF
integration or Frigate. Show needs a Home Assistant sign-in, and the Log records who looked, never the
password. You can change the user name and password, or **Clear** them. Leave the password empty to
keep it.

**Turning a pane on or off needs a Home Assistant sign-in.** In Home Assistant these switches only
answer the panel's key, so a browser signed in with the panel's code sees them but can't change them.

## Signing in

1. **Continue with Home Assistant.** You sign in on Home Assistant's own login page, so the panel
   never sees your password. Only **administrators** get in. The panel uses the Home Assistant
   address it already has, and only when that address is on your network (a private IP or a `.local`
   name). It never uses a Nabu Casa or other public address.
2. **Use the code on the panel** when Home Assistant is down. The panel shows a 6-digit code for 5
   minutes; type it in the browser. If you sign in this way before the panel has a Home Assistant
   address, that sign-in ends when the panel first stores one; sign in again then.

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
  No new code goes up within 30 seconds of the last one, even when that one was used up or closed
  (unless it was typed right): asking again from the same browser keeps it, and another browser has
  to wait, so nobody can keep replacing your code. At most 6 new codes go up in 10 minutes, from all
  browsers together, so nobody can keep the screen lit with codes.
- A browser must send its whole request within 10 seconds, so a slow one can't hold the panel's few
  connections.
- If the panel can't draw the code (the app may not draw over other apps), the page says so instead
  of waiting for a code that never shows.
- The page answers only to the panel's own addresses and names (its IP addresses, `s1-panel.local`,
  its Android host name), so another name pointed at it (DNS rebinding, or a spoofed `.local` answer)
  gets nothing.
- No scripts, no outside files, and a strict Content-Security-Policy.
- The log and the diagnostics file have tokens, keys, passwords (including `Basic` credentials and a
  user and password in an address), every value in an address's query (`?auth=…`), Thread datasets and
  MAC addresses removed. It goes by what these look like, so it can miss a secret in an unusual form:
  look over a file before you attach it to an issue.

**What plain HTTP on your network doesn't protect.** The page is `http://`, like Home Assistant on
`http://`. Someone who can read your Wi-Fi traffic can see:

- **The panel's cookie.** With it they can use the Manage page as you until it's signed out. The
  cookie gets them nothing else: not Home Assistant (the panel already revoked that token), and not a
  reboot (that needs the code on the panel's screen).
- **Your Home Assistant password, if Home Assistant itself is on `http://`.** You type it on Home
  Assistant's own login page, not on the panel, so the panel never has it; but over plain HTTP that page
  sends it in the clear, as it does whenever you sign in to Home Assistant over `http://`. Revoking the
  token doesn't change the password. HTTPS for Home Assistant is what protects it on the way;
  multi-factor sign-in adds a second step, so a caught password alone doesn't get anyone in.
- **The sign-in's exchange with Home Assistant, if Home Assistant is on `http://`.** The panel trades
  the one-time code for Home Assistant's tokens, asks who you are and revokes them, over the address it
  has, so over `http://` these pass in the clear for a few seconds. Revoking the refresh token also ends
  its access token, so a caught token works only until then. If Home Assistant refuses the revoke twice,
  the refresh token stays until you delete it (see above), and the Log says so. Someone who can *change* that traffic could
  also answer for Home Assistant and sign in as an administrator. This is a choice: most Home Assistant
  installs on a home network are `http://`, the panel's own dashboard sign-in crosses the same way, and
  the code on the panel is there for whoever would rather not.
- **Nothing stops someone on your network from using up the codes.** Anyone who can reach the panel
  can ask for codes without signing in. After 6 in 10 minutes the panel shows no more, so for up to 10
  minutes nobody can sign in with the code (signing in with Home Assistant still works). The Log says
  *Too many code requests* with the address that asked.

Someone who can *change* traffic can also change the page. Use a network you trust; HTTPS on the panel
may come later.

## For developers

The server is `dev.glassworks.manage.ManageServer`; a frame supplies a `ManageHost` (status, actions,
diagnostics). See the [core's README](../app/kiosk/src/main/java/dev/glassworks/README.md#the-manage-page).
