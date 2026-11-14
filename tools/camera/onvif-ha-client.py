#!/usr/bin/env python3
# Drives an ONVIF camera's events the way Home Assistant's ONVIF integration does (HA 2026.11.1,
# event_manager.py, with onvif-zeep-async 4.3.0): a PullPoint subscription asked for 10 minutes, then
# PullMessages (limit 100, timeout 60 s) every 0.75 s; on a SOAP Fault it calls resume(), which
# renews the subscription and re-creates it when the Renew fails; other errors wait 60 s (2 s here).
# Prints each pull and a summary. Talks to the host and port given, over the network; changes
# nothing on the camera but its own subscription. For the camera pane's lab (docs/research/onvif-rtsp.md).
# Needs: pip install onvif-zeep-async==4.3.0
# Usage: onvif-ha-client.py <host> <port> <user> <password> <seconds>
import asyncio, datetime as dt, os, sys, time
import onvif
from onvif import ONVIFCamera
from zeep.exceptions import Fault


async def main(host, port, user, password, seconds):
    cam = ONVIFCamera(host, port, user, password, f"{os.path.dirname(onvif.__file__)}/wsdl/", no_cache=True)
    await cam.update_xaddrs()
    lost = []
    mgr = await cam.create_pullpoint_manager(dt.timedelta(minutes=10), lambda: lost.append(time.time()))
    await mgr.set_synchronization_point()
    t0 = time.time(); faults = pulls = errors = 0
    while time.time() - t0 < seconds:
        delay = 0.75
        try:
            r = await mgr.get_service().PullMessages({"MessageLimit": 100, "Timeout": dt.timedelta(seconds=60)})
            pulls += 1
            vals = [f"{m.Topic._value_1.split('/')[-1]}={si.Value}" for m in (r.NotificationMessage or [])
                    for si in m.Message._value_1.Data.SimpleItem]
            print(f"{time.time() - t0:6.1f}s pull ok: {vals} end={r.TerminationTime} now={r.CurrentTime}", flush=True)
        except Fault as e:
            faults += 1
            print(f"{time.time() - t0:6.1f}s pull FAULT: {e.message} -> resume()", flush=True)
            mgr.resume()
        except Exception as e:  # noqa: BLE001 (what HA does: wait, then pull again)
            errors += 1
            print(f"{time.time() - t0:6.1f}s pull error {type(e).__name__} -> 60 s in HA, 2 s here", flush=True)
            delay = 2
        await asyncio.sleep(delay)
    print(f"SUMMARY pulls={pulls} faults={faults} errors={errors} subscriptions_lost={len(lost)}", flush=True)
    await mgr.shutdown()
    await cam.close()


if __name__ == "__main__":
    h, p, u, pw, s = sys.argv[1:6]
    asyncio.run(main(h, int(p), u, pw, float(s)))
