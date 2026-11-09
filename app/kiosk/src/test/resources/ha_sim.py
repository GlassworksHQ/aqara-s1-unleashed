"""Plays Home Assistant's side of a Wyoming satellite session against the Kotlin server.

Uses the real `wyoming` package (pip install wyoming) so event names, fields and framing are
checked by the reference implementation, and follows the order of events that
homeassistant/components/wyoming/{data,assist_satellite}.py produce. Run by SatelliteFlowTest.

Protocol with the test: prints READY when the satellite is set up; the test then triggers the
wake word and feeds mic audio. Exit code 0 = pass.
"""

import asyncio
import sys

from wyoming.asr import Transcribe, Transcript
from wyoming.audio import AudioChunk, AudioStart, AudioStop
from wyoming.client import AsyncTcpClient
from wyoming.info import Describe, Info
from wyoming.ping import Ping, Pong
from wyoming.pipeline import PipelineStage, RunPipeline
from wyoming.satellite import PauseSatellite, RunSatellite
from wyoming.snd import Played
from wyoming.tts import Synthesize
from wyoming.vad import VoiceStarted, VoiceStopped
from wyoming.wake import Detection

HOST = "127.0.0.1"
PORT = int(sys.argv[1])


async def expect(client, event_type, timeout=10.0, skip=("audio-chunk", "pong")):
    while True:
        event = await asyncio.wait_for(client.read_event(), timeout)
        assert event is not None, f"disconnected while waiting for {event_type}"
        if event.type == event_type:
            return event
        if event.type in skip:
            continue
        raise AssertionError(f"expected {event_type}, got {event.type} {event.data}")


async def main():
    # 1. Config flow (wyoming/data.py load_wyoming_info): short-lived describe -> info.
    async with AsyncTcpClient(HOST, PORT) as c:
        await c.write_event(Describe().event())
        info = Info.from_event(await expect(c, "info"))
        assert info.satellite is not None and info.satellite.installed, info
        assert info.satellite.name == "Test Panel", info.satellite
        models = [m for w in info.wake for m in w.models]
        assert models and models[0].name == "hey_jarvis_v0.1", models
        assert models[0].phrase == "hey jarvis", models
        assert info.mic[0].mic_format.rate == 16000
        print("info ok:", info.satellite.name, [m.name for m in models], flush=True)

    # 2. Satellite entity (assist_satellite.py): connect, run-satellite, describe, ping.
    c = AsyncTcpClient(HOST, PORT)
    await c.connect()
    await c.write_event(RunSatellite().event())
    await c.write_event(Describe().event())
    await expect(c, "info")
    await c.write_event(Ping(text="hi").event())
    pong = Pong.from_event(await expect(c, "pong", skip=("audio-chunk",)))
    assert pong.text == "hi", pong
    print("READY", flush=True)

    # 3. Local wake word -> detection, run-pipeline(asr -> tts), audio-start, audio-chunk...
    detection = Detection.from_event(await expect(c, "detection", timeout=15))
    assert detection.name == "hey_jarvis_v0.1", detection
    run = RunPipeline.from_event(await expect(c, "run-pipeline"))
    assert run.start_stage == PipelineStage.ASR and run.end_stage == PipelineStage.TTS, run
    assert not run.restart_on_end
    start = AudioStart.from_event(await expect(c, "audio-start"))
    assert (start.rate, start.width, start.channels) == (16000, 2, 1), start
    for _ in range(5):
        chunk = AudioChunk.from_event(await expect(c, "audio-chunk", skip=()))
        assert (chunk.rate, chunk.width, chunk.channels) == (16000, 2, 1), chunk
        assert len(chunk.audio) == 2560, len(chunk.audio)
    print("streaming ok", flush=True)

    # 4. STT events as HA sends them; the satellite must stop streaming (audio-stop).
    await c.write_event(Transcribe(language="en").event())
    await c.write_event(VoiceStarted(timestamp=0).event())
    await c.write_event(VoiceStopped(timestamp=400).event())
    await expect(c, "audio-stop")
    await c.write_event(Transcript(text="what time is it").event())
    print("stt ok", flush=True)

    # 5. TTS: synthesize, audio-start/chunk/stop (as _stream_tts does) -> played.
    await c.write_event(Synthesize(text="It is noon.").event())
    await c.write_event(AudioStart(rate=22050, width=2, channels=1, timestamp=0).event())
    for i in range(10):
        await c.write_event(
            AudioChunk(rate=22050, width=2, channels=1, audio=bytes(2048), timestamp=i * 46).event()
        )
    await c.write_event(AudioStop(timestamp=464).event())
    await expect(c, "played")
    print("tts ok", flush=True)

    # 6. Ping again, then pause and disconnect (HA's teardown).
    await c.write_event(Ping(text="bye").event())
    assert Pong.from_event(await expect(c, "pong", skip=("audio-chunk",))).text == "bye"
    await c.write_event(PauseSatellite().event())
    await c.disconnect()
    print("OK", flush=True)


asyncio.run(asyncio.wait_for(main(), 60))
