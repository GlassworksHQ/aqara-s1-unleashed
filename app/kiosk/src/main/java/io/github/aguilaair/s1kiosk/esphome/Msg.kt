package io.github.aguilaair.s1kiosk.esphome

/**
 * ESPHome native API message type ids (`option (id)` in aioesphomeapi `api.proto`, checked against
 * aioesphomeapi v46.25.0, the version pinned by Home Assistant 2026.11.0).
 */
object Msg {
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    const val AUTHENTICATION_REQUEST = 3
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_BINARY_SENSOR = 12
    const val LIST_ENTITIES_LIGHT = 15
    const val LIST_ENTITIES_SENSOR = 16
    const val LIST_ENTITIES_SWITCH = 17
    const val LIST_ENTITIES_TEXT_SENSOR = 18
    const val LIST_ENTITIES_DONE = 19
    const val SUBSCRIBE_STATES_REQUEST = 20
    const val BINARY_SENSOR_STATE = 21
    const val LIGHT_STATE = 24
    const val SENSOR_STATE = 25
    const val SWITCH_STATE = 26
    const val TEXT_SENSOR_STATE = 27
    const val SUBSCRIBE_LOGS_REQUEST = 28
    const val LIGHT_COMMAND = 32
    const val SWITCH_COMMAND = 33
    const val SUBSCRIBE_HA_SERVICES_REQUEST = 34
    const val GET_TIME_REQUEST = 36
    const val GET_TIME_RESPONSE = 37
    const val SUBSCRIBE_HA_STATES_REQUEST = 38
    const val LIST_ENTITIES_NUMBER = 49
    const val NUMBER_STATE = 50
    const val NUMBER_COMMAND = 51
    const val LIST_ENTITIES_SELECT = 52
    const val SELECT_STATE = 53
    const val SELECT_COMMAND = 54
    const val LIST_ENTITIES_BUTTON = 61
    const val BUTTON_COMMAND = 62
    const val LIST_ENTITIES_MEDIA_PLAYER = 63
    const val MEDIA_PLAYER_STATE = 64
    const val MEDIA_PLAYER_COMMAND = 65
    const val SUBSCRIBE_VOICE_ASSISTANT_REQUEST = 89
    const val VOICE_ASSISTANT_REQUEST = 90
    const val VOICE_ASSISTANT_RESPONSE = 91
    const val VOICE_ASSISTANT_EVENT_RESPONSE = 92
    const val LIST_ENTITIES_TEXT = 97
    const val TEXT_STATE = 98
    const val TEXT_COMMAND = 99
    const val VOICE_ASSISTANT_AUDIO = 106
    const val VOICE_ASSISTANT_TIMER_EVENT = 115
    const val VOICE_ASSISTANT_ANNOUNCE_REQUEST = 119
    const val VOICE_ASSISTANT_ANNOUNCE_FINISHED = 120
    const val VOICE_ASSISTANT_CONFIGURATION_REQUEST = 121
    const val VOICE_ASSISTANT_CONFIGURATION_RESPONSE = 122
    const val VOICE_ASSISTANT_SET_CONFIGURATION = 123
    const val DEVICE_CAPABILITIES_REQUEST = 149
    const val DEVICE_CAPABILITIES_RESPONSE = 150
}

/** `VoiceAssistantFeature` (aioesphomeapi model.py; ESPHome voice_assistant.h). */
object VoiceFeature {
    const val VOICE_ASSISTANT = 1 shl 0
    const val SPEAKER = 1 shl 1
    const val API_AUDIO = 1 shl 2
    const val TIMERS = 1 shl 3
    const val ANNOUNCE = 1 shl 4
    const val START_CONVERSATION = 1 shl 5
}

/** `VoiceAssistantRequestFlag` (api.proto). */
object VoiceRequestFlag {
    const val USE_VAD = 1
    const val USE_WAKE_WORD = 2
}

/** `VoiceAssistantEvent` (api.proto). */
object VoiceEventType {
    const val ERROR = 0
    const val RUN_START = 1
    const val RUN_END = 2
    const val STT_START = 3
    const val STT_END = 4
    const val INTENT_START = 5
    const val INTENT_END = 6
    const val TTS_START = 7
    const val TTS_END = 8
    const val WAKE_WORD_START = 9
    const val WAKE_WORD_END = 10
    const val STT_VAD_START = 11
    const val STT_VAD_END = 12
    const val TTS_STREAM_START = 98
    const val TTS_STREAM_END = 99
    const val INTENT_PROGRESS = 100
}

/** `VoiceAssistantTimerEvent` (api.proto). */
object TimerEventType {
    const val STARTED = 0
    const val UPDATED = 1
    const val CANCELLED = 2
    const val FINISHED = 3
}
