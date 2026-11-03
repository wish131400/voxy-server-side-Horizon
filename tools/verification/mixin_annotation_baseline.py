"""Encode single @Redirect.at values in the form accepted by old/new Mixins.

New Mixin annotations declare an array here. Existing MixinExtras versions
expect an AnnotationNode. The original scalar representation works with both;
the transform preserves the constant pool and every method Code attribute.
"""
import struct


def scalar_redirect_at(data):
    position = 0
    pool = {}
    changes = 0

    def take(size):
        nonlocal position
        value = data[position:position + size]
        if len(value) != size:
            raise ValueError("Truncated class")
        position += size
        return value

    def number(size):
        return int.from_bytes(take(size), "big")

    if take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class")
    take(4)
    count = number(2)
    index = 1
    while index < count:
        tag = number(1)
        if tag == 1:
            pool[index] = take(number(2)).decode("utf-8", errors="replace")
        elif tag in (7, 8, 16, 19, 20):
            take(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError("Unknown constant pool tag")
        index += 1

    def annotation():
        nonlocal changes
        type_index = take(2)
        pairs = take(2)
        result = type_index + pairs
        is_redirect = pool[int.from_bytes(type_index)] == "Lorg/spongepowered/asm/mixin/injection/Redirect;"
        for _ in range(int.from_bytes(pairs)):
            key = take(2)
            value = element()
            if is_redirect and pool[int.from_bytes(key)] == "at" and value.startswith(b"["):
                if not value.startswith(b"[\x00\x01@"):
                    raise ValueError("Multiple Redirect injection points need a source compatibility change")
                value = value[3:]
                changes += 1
            result += key + value
        return result

    def element():
        tag = take(1)
        if tag in [bytes([value]) for value in b"BCDFIJSZsc"]:
            return tag + take(2)
        if tag == b"e":
            return tag + take(4)
        if tag == b"@":
            return tag + annotation()
        if tag == b"[":
            size = take(2)
            return tag + size + b"".join(element() for _ in range(int.from_bytes(size)))
        raise ValueError("Unknown annotation element")

    def attributes():
        size = take(2)
        output = size
        for _ in range(int.from_bytes(size)):
            name = take(2)
            length = number(4)
            start = position
            if pool[int.from_bytes(name)] in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                count_bytes = take(2)
                value = count_bytes + b"".join(annotation() for _ in range(int.from_bytes(count_bytes)))
                if position != start + length:
                    raise ValueError("Annotation length mismatch")
            else:
                value = take(length)
            output += name + struct.pack(">I", len(value)) + value
        return output

    header = data[:position] + take(6)
    interfaces = take(2)
    output = header + interfaces + take(2 * int.from_bytes(interfaces))
    for _ in range(2):
        members = take(2)
        output += members
        for _ in range(int.from_bytes(members)):
            output += take(6) + attributes()
    output += attributes()
    if position != len(data):
        raise ValueError("Unexpected class trailer")
    if changes == 0 and output != data:
        raise ValueError("Unchanged class failed round trip")
    return output, changes
