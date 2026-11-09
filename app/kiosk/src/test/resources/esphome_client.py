"""Plays Home Assistant against the panel's ESPHome API server, using the real aioesphomeapi client.

Usage: esphome_client.py <plaintext port> <noise port> <noise key b64>
Driven by EspHomeApiTest. Prints "WAKE" when the test should fire the wake word, and a final
line "RESULT <json>". Any failed check raises, so the exit code is non-zero.
"""

import asyncio
import json
import sys

from aioesphomeapi import (
    APIClient,
    EncryptionPlaintextAPIError,
    InvalidEncryptionKeyAPIError,
    RequiresEncryptionAPIError,
    VoiceAssistantEventType as E,
    VoiceAssistantTimerEventType,
)

R = {}


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


async def wait_for(pred, what, timeout=5.0):
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while not pred():
        if loop.time() > end:
            raise AssertionError(f"timeout waiting for {what}")
        await asyncio.sleep(0.02)


async def plaintext(port):
    cli = APIClient("127.0.0.1", port, None, client_info="Home Assistant test")
    stopped = []

    async def on_stop(expected_disconnect):
        stopped.append(expected_disconnect)

    await cli.connect(on_stop=on_stop, login=True)
    check((cli.api_version.major, cli.api_version.minor) == (1, 20), f"api version {cli.api_version}")
    info = await cli.device_info()
    R["device_info"] = {
        "name": info.name, "friendly_name": info.friendly_name, "mac": info.mac_address,
        "esphome_version": info.esphome_version, "manufacturer": info.manufacturer, "model": info.model,
        "voice_flags": info.voice_assistant_feature_flags,
    }
    caps = await cli.device_capabilities_compat(info)
    R["caps_voice_flags"] = caps.voice_assistant.feature_flags

    entities, _ = await cli.list_entities_services()
    by_id = {e.object_id: e for e in entities}
    R["entities"] = sorted(f"{type(e).__name__}:{e.object_id}:{e.name}" for e in entities)
    check(by_id["screen"].supported_color_modes == [3], "light color modes")
    check(by_id["screen_mode"].options == ["presence", "always"], "select options")
    check(abs(by_id["presence_range"].max_value - 2.5) < 1e-6, "number max")
    check([(f.format, int(f.purpose)) for f in by_id["speaker"].supported_formats] == [("mp3", 0), ("mp3", 1)],
          "media formats")
    check(by_id["illuminance"].unit_of_measurement == "lx", "sensor unit")
    check(by_id["presence"].device_class == "occupancy", "binary sensor class")
    check(by_id["base_button_1"].event_types == ["single", "double"], "event types")
    check(by_id["base_button_1"].device_class == "button", "event device class")

    states = {}
    cli.subscribe_states(lambda s: states.__setitem__(s.key, s))
    with_state = [e for e in entities if type(e).__name__ not in ("ButtonInfo", "EventInfo")]
    await wait_for(lambda: all(e.key in states for e in with_state), "initial states")
    st = lambda oid: states[by_id[oid].key]
    check(st("illuminance").missing_state, "lux unknown -> missing_state")
    check(st("presence").state is True, "presence")
    check(st("ip_address").state == "192.0.2.10", "text sensor")

    cli.light_command(by_id["screen"].key, state=False, brightness=0.4)
    await wait_for(lambda: st("screen").state is False and abs(st("screen").brightness - 0.4) < 0.01, "light")
    cli.switch_command(by_id["auto_brightness"].key, False)
    await wait_for(lambda: st("auto_brightness").state is False, "switch")
    cli.number_command(by_id["idle_timeout"].key, 300)
    await wait_for(lambda: st("idle_timeout").state == 300, "number")
    cli.select_command(by_id["screen_mode"].key, "always")
    await wait_for(lambda: st("screen_mode").state == "always", "select")
    cli.text_command(by_id["night_window"].key, "23:00-06:30")
    await wait_for(lambda: st("night_window").state == "23:00-06:30", "text")
    cli.button_command(by_id["reload_dashboard"].key)
    cli.media_player_command(by_id["speaker"].key, volume=0.5)
    await wait_for(lambda: abs(st("speaker").volume - 0.5) < 0.01, "media volume")
    cli.media_player_command(by_id["speaker"].key, media_url="http://ha.test:8123/x.mp3", announcement=True)
    await asyncio.sleep(0.3)
    check(by_id["base_button_1"].key not in states, "events have no initial state")
    print("FIRE_EVENT", flush=True)
    await wait_for(lambda: by_id["base_button_1"].key in states, "event")
    R["event_type"] = states[by_id["base_button_1"].key].event_type

    # ---- voice assistant, the way homeassistant/components/esphome/assist_satellite.py drives it
    starts, audio, stops, finished = [], [], [], []

    async def handle_start(conversation_id, flags, audio_settings, wake_word_phrase):
        starts.append({"conversation_id": conversation_id, "flags": flags, "phrase": wake_word_phrase})
        return 0  # API audio: no UDP port

    async def handle_stop(abort):
        stops.append(abort)

    async def handle_audio(data, data2=None):
        audio.append(len(data))

    async def handle_finished(msg):
        finished.append(msg.success)

    cli.subscribe_voice_assistant(
        handle_start=handle_start, handle_stop=handle_stop,
        handle_audio=handle_audio, handle_announcement_finished=handle_finished,
    )
    cfg = await cli.get_voice_assistant_configuration(5)
    R["wake_words"] = [(w.id, w.wake_word) for w in cfg.available_wake_words]
    R["active_before"] = list(cfg.active_wake_words)
    check(cfg.max_active_wake_words == 1, "max active wake words")
    await cli.set_voice_assistant_configuration(["alexa_v0.1"])
    cfg = await cli.get_voice_assistant_configuration(5)
    R["active_after"] = list(cfg.active_wake_words)

    print("WAKE", flush=True)
    await wait_for(lambda: starts, "VoiceAssistantRequest(start)")
    await wait_for(lambda: len(audio) >= 5, "mic audio")
    tts_url = "http://ha.test:8123/api/tts_proxy/abc.mp3"
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_RUN_START, {"url": tts_url})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_STT_START, {})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_STT_VAD_START, {})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_STT_VAD_END, {})
    await asyncio.sleep(0.3)
    chunks_at_vad_end = len(audio)
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_STT_END, {"text": "turn on the lights"})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_INTENT_START, {})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_INTENT_PROGRESS, {"tts_start_streaming": "1"})
    cli.send_voice_assistant_event(
        E.VOICE_ASSISTANT_INTENT_END, {"conversation_id": "conv-1", "continue_conversation": "0", "speech": "Done"})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_TTS_START, {"text": "Done"})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_TTS_END, {"url": tts_url})
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_RUN_END, {})
    await wait_for(lambda: finished, "VoiceAssistantAnnounceFinished after TTS")
    await asyncio.sleep(0.3)
    R["voice"] = {
        "start": starts[0], "chunks": chunks_at_vad_end, "chunks_after": len(audio),
        "chunk_bytes": audio[0], "tts_finished": finished[0],
    }

    ann = await cli.send_voice_assistant_announcement_await_response(
        "http://ha.test:8123/media/a.mp3", 5, "Dinner is ready", preannounce_media_id="/local/chime.mp3")
    R["announce_success"] = ann.success
    cli.send_voice_assistant_timer_event(
        VoiceAssistantTimerEventType.VOICE_ASSISTANT_TIMER_FINISHED, "t1", "pasta", 60, 0, False)
    await asyncio.sleep(0.3)

    # ---- entity set change (wall base attached): the server asks us to disconnect, a new
    # connection lists the new entity, as Home Assistant's reconnect logic would.
    print("REBUILD", flush=True)
    await wait_for(lambda: stopped, "server-initiated disconnect")
    await cli.disconnect(force=True)
    cli2 = APIClient("127.0.0.1", port, None, client_info="Home Assistant test")
    await cli2.connect(login=True)
    entities2, _ = await cli2.list_entities_services()
    R["entities_after_rebuild"] = len(entities2)
    relay = next(e for e in entities2 if e.object_id == "base_relay_1")
    states2 = {}
    cli2.subscribe_states(lambda s: states2.__setitem__(s.key, s))
    await wait_for(lambda: relay.key in states2, "new entity state")
    R["rebuild_relay_state"] = states2[relay.key].state
    await cli2.disconnect()


async def noise(port, key):
    cli = APIClient("127.0.0.1", port, None, noise_psk=key, client_info="Home Assistant test")
    await cli.connect(login=True)
    info = await cli.device_info()
    entities, _ = await cli.list_entities_services()
    states = {}
    cli.subscribe_states(lambda s: states.__setitem__(s.key, s))
    sw = next(e for e in entities if e.object_id == "mute_microphone")
    await wait_for(lambda: sw.key in states, "noise states")
    cli.switch_command(sw.key, True)
    await wait_for(lambda: states[sw.key].state is True, "noise switch")
    R["noise"] = {"name": info.name, "entities": len(entities)}
    await cli.disconnect()

    errors = {}
    for label, cli_port, psk in (
        ("wrong_key", port, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
        ("plain_to_noise", port, None),
    ):
        c = APIClient("127.0.0.1", cli_port, None, noise_psk=psk)
        try:
            await asyncio.wait_for(c.connect(login=True), 5)
            errors[label] = "connected"
        except Exception as err:  # noqa: BLE001
            errors[label] = type(err).__name__
            if isinstance(err, InvalidEncryptionKeyAPIError):
                errors[label + "_name"] = err.received_name
                errors[label + "_mac"] = err.received_mac
        finally:
            await c.disconnect(force=True)
    R["noise_errors"] = errors
    check(errors["wrong_key"] == InvalidEncryptionKeyAPIError.__name__, f"wrong key: {errors}")
    check(errors["plain_to_noise"] == RequiresEncryptionAPIError.__name__, f"plaintext client: {errors}")


async def noise_to_plain(port, key):
    c = APIClient("127.0.0.1", port, None, noise_psk=key)
    try:
        await asyncio.wait_for(c.connect(login=True), 5)
        R["noise_to_plain"] = "connected"
    except Exception as err:  # noqa: BLE001
        R["noise_to_plain"] = type(err).__name__
    finally:
        await c.disconnect(force=True)
    check(R["noise_to_plain"] == EncryptionPlaintextAPIError.__name__, R["noise_to_plain"])


async def main():
    plain_port, noise_port, key = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
    await plaintext(plain_port)
    await noise(noise_port, key)
    await noise_to_plain(plain_port, key)
    print("RESULT " + json.dumps(R), flush=True)


asyncio.run(main())
