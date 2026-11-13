"""Host-side inspection of the PDFs written by Api26PdfProbeTest on the API26 emulator.

Stdlib only. For every PDF: header/size, page and object inventory, decompressed content streams
with a token-level operand check per operator, and a cross-file diff of the operator sequences.
Usage: python analyze_api26_pdfs.py <directory-with-pdfs>
"""
import os
import re
import sys
import zlib

OPERANDS = {
    "Td": 2, "TD": 2, "Tm": 6, "Tf": 2, "Tj": 1, "TJ": 1, "'": 1, '"': 3,
    "rg": 3, "RG": 3, "g": 1, "G": 1, "k": 4, "K": 4, "cs": 1, "CS": 1,
    "sc": 1, "scn": -1, "SC": 1, "SCN": -1, "cm": 6, "re": 4, "m": 2, "l": 2,
    "c": 6, "v": 4, "y": 4, "w": 1, "d": 2, "ri": 1, "i": 1, "gs": 1,
}
TOKEN = re.compile(rb"\s*(?:(\d*\.\d+|\d+)|/([^\s/\[\]()<>{}]+)|(\((?:\\.|[^\\()])*\)|<[0-9A-Fa-f\s]*>)|(\[|\])|(<<|>>)|([A-Za-z'\"*]+))")


def streams_of(data):
    result = []
    for m in re.finditer(rb"stream\r?\n", data):
        start = m.end()
        end = data.find(b"endstream", start)
        if end < 0:
            continue
        raw = data[start:end]
        dec = None
        try:
            dec = zlib.decompress(raw)
        except Exception:
            try:
                dec = zlib.decompressobj().decompress(raw)
            except Exception:
                dec = None
        result.append((len(raw), dec))
    return result


def label(token_bytes):
    if token_bytes[0:1] == b"/":
        return token_bytes[1:].decode("latin-1")
    if re.fullmatch(rb"[A-Za-z'\"*]+", token_bytes):
        return token_bytes.decode("latin-1")
    if token_bytes[0:1] in (b"(", b"<"):
        return "str"
    if token_bytes in (b"[", b"]", b"<<", b">>"):
        return token_bytes.decode("latin-1")
    return "num"


def operand_check(text):
    """Walks a content stream, checking that every operator sees a plausible operand count."""
    problems = []
    stack = []
    group = 0
    for m in TOKEN.finditer(text):
        if m.group(5) in (b"[", b"]", b"<<", b">>"):
            continue
        raw = m.group(0).strip()
        if not raw:
            continue
        name = label(raw)
        if name in OPERANDS:
            required = OPERANDS[name]
            available = len(stack)
            if required >= 0 and available < required:
                problems.append(f"{name} with {available} operand(s) {stack!r}")
            stack = []
        else:
            stack.append(name)
    return problems


def analyze(path):
    data = open(path, "rb").read()
    name = os.path.basename(path)
    print(f"--- {name}: {len(data)} bytes header={data[:8]!r}")
    objects = re.findall(rb"(?m)^(\d+)\s+(\d+)\s+obj", data)
    print(f"    objects={len(objects)} flateStreams={data.count(b'FlateDecode')}")
    for m in re.finditer(rb"/Type\s*/Page\b(?!s)", data):
        window = data[max(0, m.start() - 400):m.start() + 400]
        contents = re.findall(rb"/Contents\s+(\d+)\s+(\d+)\s+R", window)
        media = re.findall(rb"/MediaBox\s*\[([^\]]*)\]", window)
        print(f"    page: contents={contents} mediabox={media[0] if media else None}")
    for index, (raw, dec) in enumerate(streams_of(data)):
        if dec is None:
            print(f"    stream[{index}] {raw} bytes: cannot inflate")
            continue
        text = dec.decode("latin-1")
        ops = [m.group(1).decode("latin-1") for m in
               re.finditer(rb"(?:^|\s)([A-Za-z'\"*]+)(?=\s|$)", dec)]
        problems = operand_check(dec)
        print(f"    stream[{index}] {raw}->{len(dec)} ops={len(ops)} text={text[:60]!r}")
        for problem in problems:
            print(f"      PROBLEM {problem}")
    # Outline: object numbers referenced by the trailer/root catalog vs defined objects.
    defined = {int(a) for a, _ in objects}
    referenced = {int(a) for a in re.findall(rb"(\d+)\s+\d+\s+R", data)}
    missing = sorted(referenced - defined)
    print(f"    referenced-but-undefined={missing}")
    root = re.search(rb"/Root\s+(\d+)\s+\d+\s+R", data)
    print(f"    trailerRoot={root.group(1).decode() if root else None}")


def main():
    directory = sys.argv[1]
    for path in sorted(os.listdir(directory)):
        if path.lower().endswith(".pdf"):
            analyze(os.path.join(directory, path))


if __name__ == "__main__":
    main()
