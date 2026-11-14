"""Prints the golden bytes in ActionsCameraWireTest, made by aioesphomeapi's own api_pb2 (the client
Home Assistant uses). Run it with the aioesphomeapi version in test-requirements.txt:
  python -I esphome_golden.py
It only prints; it touches nothing."""
from aioesphomeapi import api_pb2 as p
r = p.ExecuteServiceRequest(key=0x12345678, call_id=7, return_response=True)
a = r.args
a.add(bool_=True); a.add(int_=-42); a.add(float_=1.5); a.add(string_="héllo")
x = a.add(); x.bool_array.extend([True, False, True])
x = a.add(); x.int_array.extend([1, -2, 300])
x = a.add(); x.float_array.extend([0.5, -2.0])
x = a.add(); x.string_array.extend(["a", "", "c"])
print("execute", r.SerializeToString().hex())
r2 = p.ExecuteServiceRequest(key=0x12345678); r2.args.add(legacy_int=-5)
print("legacy", r2.SerializeToString().hex())
def fnv(s):
    h = 0x811C9DC5
    for b in s.encode(): h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
    return h
l = p.ListEntitiesServicesResponse(name="notify", key=fnv("notify"), supports_response=p.SUPPORTS_RESPONSE_OPTIONAL)
l.args.add(name="title", type=p.SERVICE_ARG_TYPE_STRING); l.args.add(name="flag", type=p.SERVICE_ARG_TYPE_BOOL); l.args.add(name="levels", type=p.SERVICE_ARG_TYPE_INT_ARRAY)
print("list_services", l.SerializeToString().hex())
c = p.ListEntitiesCameraResponse(object_id="porch", key=fnv("porch"), name="Porch", icon="mdi:cctv", entity_category=2)
print("list_camera", c.SerializeToString().hex())
print("image_chunk", p.CameraImageResponse(key=fnv("porch"), data=bytes([0xFF,0xD8,0xFF]), done=True).SerializeToString().hex())
print("image_empty", p.CameraImageResponse(key=fnv("porch"), done=True).SerializeToString().hex())
print("resp_ok", p.ExecuteServiceResponse(call_id=7, success=True, response_data=b'{"a":1}').SerializeToString().hex())
print("resp_err", p.ExecuteServiceResponse(call_id=7, error_message="no").SerializeToString().hex())
print("req_cam", p.CameraImageRequest(stream=True).SerializeToString().hex())
e = p.HomeassistantActionRequest(service="esphome.notification_action", is_event=True)
e.data.add(key="action", value="open_gate"); e.data.add(key="tag", value="doorbell")
print("ha_event", e.SerializeToString().hex())
