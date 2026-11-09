# Mic / secure-context test page

`mictest.html` checks, inside the kiosk, that `isSecureContext`, `navigator.mediaDevices`,
AudioWorklet and `getUserMedia({audio:true})` work, and measures the input peak over 3 s.
Results are shown on screen and logged to the console (logcat lines starting with `MICTEST`).

To test against your HA origin, temporarily copy it to `/config/www/` and open
`http://<ha>:8123/local/mictest.html` with
`adb shell am start -n io.github.aguilaair.s1kiosk/.MainActivity --es open <url>`.
Delete it afterwards.

Result on the S1 Plus (GeckoView 159, HA over plain http):
`isSecureContext: true`, `getUserMedia OK: Default audio input device`, `sampleRate=48000`, `RESULT: PASS`.
