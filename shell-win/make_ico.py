"""把若干 PNG 打包成一个 .ico（PNG 压缩格式，Windows Vista+ 支持）。用法：make_ico.py out.ico a.png b.png ..."""
import struct
import sys

out, pngs = sys.argv[1], sys.argv[2:]
datas = [open(p, 'rb').read() for p in pngs]
entries, offset = [], 6 + 16 * len(datas)
for d in datas:
    w, h = struct.unpack('>II', d[16:24])
    entries.append(struct.pack('<BBBBHHII', w % 256, h % 256, 0, 0, 1, 32, len(d), offset))
    offset += len(d)
with open(out, 'wb') as f:
    f.write(struct.pack('<HHH', 0, 1, len(datas)) + b''.join(entries) + b''.join(datas))
