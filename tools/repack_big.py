# -*- coding: utf-8 -*-
"""Repacking of Rise of Legends .big archives: embedding mod files into the archive.

LEGACY: this tool is ported to Java — use `devkit repack` instead.
Kept as a reference implementation.

.big format (WAR-BUILDER, Big Huge Games):
    header: u32 0x0B, u8 0x01, UTF-16 string "WAR-BUILDER"
    reference block (usually empty) + entry block:
        u32 N, u32 N, u16 0, u8 0, then N entries:
        [u32 len][UTF-16 name][u32 0][u32 0][u32 offset][u32 size][u32 0][u32 ts]
        [u32 len][UTF-16 ext][u16 0]
    data: at offset there is [u32 zsize][zlib stream] (sometimes raw-deflate).

IMPORTANT (learned the hard way):
    In mod_data.big rule files exist TWICE: compiled (ext="bxml", binary)
    and text (ext="", starts with "<"). Compiled entries must NOT be
    touched — text inside them crashes the game on startup.
    Therefore the mod is injected ONLY into text entries, and missing
    files are added as new text entries.

Usage: set GAME_DIR and PRISTINE_DIR at the bottom, then run
    python repack_big.py
Output: mod_data.big is rewritten in GAME_DIR (3 copies: BIGS,
BIGS\\patch8, BIGS\\patches\\patch8), backups go to BACKUP_DIR.
"""
import struct, zlib, os, shutil

# Path to the modified game copy (with the extracted mod in Data\)
GAME_DIR = r'C:\root\backup\modded\Rise Of Legends'
# Path to the pristine (original) game copy used as the archive source
PRISTINE_DIR = r'C:\root\Rise Of Legends'
BACKUP_DIR = os.path.join(os.path.dirname(GAME_DIR), '_bigs_backup_original')

BS = chr(92)


def get_name(b, off):
    if off + 4 > len(b):
        return None, off
    (nsz,) = struct.unpack_from('<I', b, off)
    if nsz > 500:
        return None, off
    return b[off + 4:off + 4 + nsz * 2].decode('utf-16-le', 'replace'), off + 4 + nsz * 2


def inflate(raw):
    """zlib -> raw deflate -> None (passthrough)."""
    for wb in (15, -15):
        try:
            return zlib.decompress(raw, wb)
        except zlib.error:
            continue
    return None


def parse_big(b):
    """Parse a .big: returns (entry block offset, entry list)."""
    for start in range(0x14, 0x800):
        if start + 8 > len(b):
            continue
        n1, n2 = struct.unpack_from('<II', b, start)
        if not (0 < n1 < 1000 and n1 == n2):
            continue
        off = start + 8 + 3
        entries, ok = [], True
        for _ in range(n1):
            name, off = get_name(b, off)
            if name is None:
                ok = False; break
            if off + 28 > len(b):
                ok = False; break
            z1, z2, foff, fsize, z3, ts = struct.unpack_from('<IIIIII', b, off)
            off += 24
            ext, off = get_name(b, off)
            if ext is None:
                ok = False; break
            if off + 2 > len(b):
                ok = False; break
            off += 2
            if foff + 4 > len(b):
                ok = False; break
            (zsize,) = struct.unpack_from('<I', b, foff)
            if zsize == 0 or foff + 4 + zsize > len(b):
                ok = False; break
            raw = b[foff + 4:foff + 4 + zsize]
            payload = inflate(raw)
            if payload is not None and len(payload) != fsize:
                ok = False; break
            entries.append(dict(name=name, ext=ext, ts=ts,
                                payload=payload, raw=raw, size=fsize))
        if ok and entries:
            return start, entries
    return None, None


def norm(name):
    """Normalize an entry name for comparison with on-disk paths."""
    return name.lower().replace(BS, '/').strip('./')


def build_big(header, entries):
    """Build a .big: the header is copied as-is, the entry block is rebuilt;
    entries with keep_raw preserve their original compressed bytes."""
    out = bytearray(header)
    out += struct.pack('<IIHB', len(entries), len(entries), 0, 0)
    table_parts, data_parts = [], []
    for e in entries:
        nm = e['name'].encode('utf-16-le')
        ex = e['ext'].encode('utf-16-le')
        if e.get('keep_raw') and e['raw'] is not None:
            comp = e['raw']
        elif e['payload'] is not None:
            comp = zlib.compress(e['payload'], 9)
        else:
            comp = e['raw']
        table_parts.append(dict(nm=nm, ex=ex, size=e['size'], ts=e['ts'], comp=comp))
        data_parts.append(comp)
    table_len = sum(4 + len(t['nm']) + 24 + 4 + len(t['ex']) + 2 for t in table_parts)
    base = len(out)
    data_off = base + table_len
    cur = data_off
    for t in table_parts:
        out += struct.pack('<I', len(t['nm']) // 2)
        out += t['nm']
        out += struct.pack('<IIIII', 0, 0, cur, t['size'], 0)
        out += struct.pack('<I', t['ts'])
        out += struct.pack('<I', len(t['ex']) // 2)
        out += t['ex']
        out += struct.pack('<H', 0)
        cur += 4 + len(t['comp'])
    for comp in data_parts:
        out += struct.pack('<I', len(comp))
        out += comp
    return bytes(out)


def loose_files(root, exts):
    """All files with the given extensions under root: norm-name -> path."""
    res = {}
    for dirpath, _, files in os.walk(root):
        for f in files:
            if os.path.splitext(f)[1].lower() in exts:
                full = os.path.join(dirpath, f)
                rel = os.path.relpath(full, GAME_DIR)
                res[norm(rel)] = full
    return res


def main():
    os.makedirs(BACKUP_DIR, exist_ok=True)

    # which extracted mod files get embedded into mod_data.big
    mod_files = {}
    for k, p in loose_files(os.path.join(GAME_DIR, 'Data', 'tribes'), {'.xml'}).items():
        mod_files[k] = p
    for k, p in loose_files(os.path.join(GAME_DIR, 'Data', 'spells'), {'.xml'}).items():
        mod_files[k] = p
    for f in os.listdir(os.path.join(GAME_DIR, 'Data')):
        full = os.path.join(GAME_DIR, 'Data', f)
        if not os.path.isfile(full):
            continue
        if os.path.splitext(f)[1].lower() != '.xml':
            continue
        # these files live in multiplayer_data.big, and the schema is not needed
        if f in ('how_to_play.xml', 'resourcerules_strings.xml', 'unitrules.xsd'):
            continue
        mod_files[norm('data/' + f)] = full

    targets = [
        ('BIGS', 'mod_data.big'),
        (os.path.join('BIGS', 'patch8'), 'mod_data.big'),
        (os.path.join('BIGS', 'patches', 'patch8'), 'mod_data.big'),
    ]
    for sub, name in targets:
        src = os.path.join(PRISTINE_DIR, sub, name)
        dst = os.path.join(GAME_DIR, sub, name)
        if not os.path.exists(src):
            print('SKIP (no source):', src)
            continue
        data = open(src, 'rb').read()
        start, entries = parse_big(data)
        if start is None:
            print('ERROR: cannot parse', src)
            continue
        shutil.copy2(src, os.path.join(BACKUP_DIR, 'orig_' + os.path.basename(src)))

        used, n_bxml, n_text = set(), 0, 0
        for e in entries:
            p = e['payload']
            is_bxml = p is not None and p[:4] == b'\x01\x00\x00\x00'
            if is_bxml:
                # never touch the compiled entry
                e['keep_raw'] = True
                n_bxml += 1
                continue
            key = norm(e['name'])
            if key in mod_files:
                with open(mod_files[key], 'rb') as fh:
                    content = fh.read()
                e['payload'] = content
                e['raw'] = None
                e['size'] = len(content)
                e['ts'] = int(os.path.getmtime(mod_files[key]))
                used.add(key)
                n_text += 1
            else:
                e['keep_raw'] = True

        for key, path in mod_files.items():
            if key in used:
                continue
            arch_name = '.' + BS + key.replace('/', BS)
            with open(path, 'rb') as fh:
                content = fh.read()
            entries.append(dict(name=arch_name, ext='', ts=int(os.path.getmtime(path)),
                                payload=content, raw=None, size=len(content)))

        out = build_big(data[:start], entries)
        with open(dst, 'wb') as fh:
            fh.write(out)
        _, check = parse_big(out)
        ok = check is not None and len(check) == len(entries)
        print('%s: entries=%d (bxml untouched=%d, text replaced=%d, new=%d) verify=%s'
              % (dst, len(entries), n_bxml, n_text, len(mod_files) - len(used),
                 'OK' if ok else 'FAIL'))


if __name__ == '__main__':
    main()
