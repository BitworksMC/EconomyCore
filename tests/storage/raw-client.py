"""Minimal offline protocol-777 client for the disposable 26.3 OnlineItemProbe server.

This only connects to 127.0.0.1. It completes login/configuration, acknowledges the initial
teleport and keeps reading until the test server stops; it sends no gameplay or chat actions.
"""
import argparse
import io
import socket
import struct
import time
import uuid
import zlib


def varint(value):
    data = bytearray()
    value &= 0xFFFFFFFF
    while True:
        part = value & 0x7F
        value >>= 7
        data.append(part | (0x80 if value else 0))
        if not value:
            return bytes(data)


def read_varint(stream):
    result = 0
    for shift in range(0, 35, 7):
        data = stream.read(1)
        if not data:
            raise EOFError("Connection closed")
        result |= (data[0] & 0x7F) << shift
        if not data[0] & 0x80:
            return result
    raise ValueError("Invalid VarInt")


def string(value):
    encoded = value.encode("utf-8")
    return varint(len(encoded)) + encoded


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--name", default="OnlineItemBot")
    parser.add_argument("--timeout", type=int, default=100)
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    while True:
        try:
            connection = socket.create_connection(("127.0.0.1", args.port), timeout=2)
            break
        except OSError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.25)
    connection.settimeout(10)
    stream = connection.makefile("rb")
    compression = None

    def send(packet_id, payload=b""):
        packet = varint(packet_id) + payload
        if compression is not None:
            packet = (varint(len(packet)) + zlib.compress(packet)) if len(packet) >= compression else b"\x00" + packet
        connection.sendall(varint(len(packet)) + packet)

    send(0x00, varint(777) + string("127.0.0.1") + struct.pack(">H", args.port) + varint(2))
    send(0x00, string(args.name) + uuid.uuid4().bytes)
    state = "login"
    play_packets = 0
    try:
        while time.monotonic() < deadline:
            length = read_varint(stream)
            if length > 32 * 1024 * 1024:
                raise ValueError("Oversized packet")
            frame = stream.read(length)
            if len(frame) != length:
                raise EOFError("Truncated frame")
            if compression is not None:
                compressed = io.BytesIO(frame)
                uncompressed_length = read_varint(compressed)
                body = compressed.read()
                frame = zlib.decompress(body) if uncompressed_length else body
                if uncompressed_length and len(frame) != uncompressed_length:
                    raise ValueError("Invalid compressed frame length")
            packet = io.BytesIO(frame)
            packet_id = read_varint(packet)
            payload = packet.read()
            if state != "play":
                print("RAW_CLIENT_PACKET state=" + state + " id=" + hex(packet_id) + " bytes=" + str(len(payload)), flush=True)
            if state == "login":
                if packet_id == 0x03:
                    compression = read_varint(io.BytesIO(payload))
                elif packet_id == 0x02:
                    print("RAW_CLIENT_LOGIN_SUCCESS", flush=True)
                    send(0x03)
                    state = "configuration"
                elif packet_id == 0x00:
                    raise RuntimeError("Login rejected: " + repr(payload[:250]))
                elif packet_id == 0x01:
                    raise RuntimeError("Server requires authentication; this probe requires offline-mode on loopback")
            elif state == "configuration":
                if packet_id == 0x0F:
                    send(0x07, varint(0))
                elif packet_id == 0x03:
                    send(0x03)
                    state = "play"
                    print("RAW_CLIENT_PLAY", flush=True)
                elif packet_id in (0x04, 0x05):
                    send(packet_id, payload)
                elif packet_id == 0x14:
                    send(0x09)
                elif packet_id == 0x02:
                    raise RuntimeError("Configuration rejected: " + repr(payload[:250]))
            else:
                play_packets += 1
                if packet_id == 0x49:
                    position = io.BytesIO(payload)
                    teleport_id = read_varint(position)
                    coordinates = position.read(24)
                    position.read(24)  # The teleport's velocity is not part of the acknowledgement.
                    rotations = position.read(8)
                    send(0x00, varint(teleport_id) + coordinates + rotations)
                elif packet_id == 0x2D and len(payload) == 8:
                    send(0x1C, payload)
                elif packet_id == 0x0B:
                    send(0x0B, struct.pack(">f", 10.0))
    except EOFError:
        if state != "play":
            raise
        print("RAW_CLIENT_CLOSED state=play packets=" + str(play_packets), flush=True)
    finally:
        stream.close()
        connection.close()


if __name__ == "__main__":
    main()
