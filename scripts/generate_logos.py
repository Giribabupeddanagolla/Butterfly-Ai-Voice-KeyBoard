import zlib
import struct
import math
import os
import base64

def load_png(path):
    with open(path, 'rb') as f:
        data = f.read()
    pos = 8
    idat = b''
    w, h = 0, 0
    while pos < len(data):
        l, t = struct.unpack('>I4s', data[pos:pos+8])
        if t == b'IHDR':
            w, h = struct.unpack('>IIBBBBB', data[pos+8:pos+21])[:2]
        elif t == b'IDAT':
            idat += data[pos+8:pos+8+l]
        elif t == b'IEND':
            break
        pos += 12 + l

    raw = zlib.decompress(idat)
    stride = w * 4
    out = bytearray(w * h * 4)

    raw_idx = 0
    prev_row = bytearray(stride)
    for y in range(h):
        filter_type = raw[raw_idx]
        raw_idx += 1
        curr_row = bytearray(stride)
        for x in range(stride):
            filt_val = raw[raw_idx + x]
            a = curr_row[x - 4] if x >= 4 else 0
            b = prev_row[x]
            c = prev_row[x - 4] if x >= 4 else 0
            if filter_type == 0: v = filt_val
            elif filter_type == 1: v = (filt_val + a) & 0xff
            elif filter_type == 2: v = (filt_val + b) & 0xff
            elif filter_type == 3: v = (filt_val + ((a + b) >> 1)) & 0xff
            elif filter_type == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                v = (filt_val + pr) & 0xff
            curr_row[x] = v
        raw_idx += stride
        out[y*stride:(y+1)*stride] = curr_row
        prev_row = curr_row
    return w, h, out

def save_png(w, h, rgba_bytes, path):
    stride = w * 4
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw.extend(rgba_bytes[y*stride:(y+1)*stride])
    comp = zlib.compress(bytes(raw), 9)
    ihdr_data = struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)
    
    def make_chunk(chunk_type, chunk_data):
        crc = zlib.crc32(chunk_type + chunk_data) & 0xffffffff
        return struct.pack('>I', len(chunk_data)) + chunk_type + chunk_data + struct.pack('>I', crc)

    png_bytes = b'\x89PNG\r\n\x1a\n'
    png_bytes += make_chunk(b'IHDR', ihdr_data)
    png_bytes += make_chunk(b'IDAT', comp)
    png_bytes += make_chunk(b'IEND', b'')

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(png_bytes)
    return png_bytes

def resize_bilinear(src_w, src_h, src_rgba, dst_w, dst_h):
    dst = bytearray(dst_w * dst_h * 4)
    x_ratio = float(src_w - 1) / dst_w if dst_w > 0 else 0
    y_ratio = float(src_h - 1) / dst_h if dst_h > 0 else 0
    
    for y in range(dst_h):
        sy = y * y_ratio
        y0 = int(sy)
        y1 = min(y0 + 1, src_h - 1)
        y_lerp = sy - y0
        
        row_dst_idx = y * dst_w * 4
        row0_src_idx = y0 * src_w * 4
        row1_src_idx = y1 * src_w * 4
        
        for x in range(dst_w):
            sx = x * x_ratio
            x0 = int(sx)
            x1 = min(x0 + 1, src_w - 1)
            x_lerp = sx - x0
            
            p00_idx = row0_src_idx + x0 * 4
            p10_idx = row0_src_idx + x1 * 4
            p01_idx = row1_src_idx + x0 * 4
            p11_idx = row1_src_idx + x1 * 4
            
            out_idx = row_dst_idx + x * 4
            
            w00 = (1.0 - x_lerp) * (1.0 - y_lerp)
            w10 = x_lerp * (1.0 - y_lerp)
            w01 = (1.0 - x_lerp) * y_lerp
            w11 = x_lerp * y_lerp
            
            a00, a10 = src_rgba[p00_idx+3] / 255.0, src_rgba[p10_idx+3] / 255.0
            a01, a11 = src_rgba[p01_idx+3] / 255.0, src_rgba[p11_idx+3] / 255.0
            
            a_out = (a00 * w00 + a10 * w10 + a01 * w01 + a11 * w11)
            if a_out > 0.001:
                r_out = (src_rgba[p00_idx]*a00*w00 + src_rgba[p10_idx]*a10*w10 + src_rgba[p01_idx]*a01*w01 + src_rgba[p11_idx]*a11*w11) / a_out
                g_out = (src_rgba[p00_idx+1]*a00*w00 + src_rgba[p10_idx+1]*a10*w10 + src_rgba[p01_idx+1]*a01*w01 + src_rgba[p11_idx+1]*a11*w11) / a_out
                b_out = (src_rgba[p00_idx+2]*a00*w00 + src_rgba[p10_idx+2]*a10*w10 + src_rgba[p01_idx+2]*a01*w01 + src_rgba[p11_idx+2]*a11*w11) / a_out
                dst[out_idx] = int(min(255, max(0, r_out)))
                dst[out_idx+1] = int(min(255, max(0, g_out)))
                dst[out_idx+2] = int(min(255, max(0, b_out)))
                dst[out_idx+3] = int(min(255, max(0, a_out * 255.0)))
            else:
                dst[out_idx:out_idx+4] = b'\x00\x00\x00\x00'
    return dst

def make_square_icon(src_w, src_h, src_rgba, square_size):
    target_w = int(square_size * 0.96)
    target_h = int(target_w * src_h / src_w)
    if target_h > int(square_size * 0.96):
        target_h = int(square_size * 0.96)
        target_w = int(target_h * src_w / src_h)
    
    resized = resize_bilinear(src_w, src_h, src_rgba, target_w, target_h)
    
    canvas = bytearray(square_size * square_size * 4)
    off_x = (square_size - target_w) // 2
    off_y = (square_size - target_h) // 2
    
    for y in range(target_h):
        c_y = off_y + y
        if 0 <= c_y < square_size:
            c_row = c_y * square_size * 4
            r_row = y * target_w * 4
            for x in range(target_w):
                c_x = off_x + x
                if 0 <= c_x < square_size:
                    c_idx = c_row + c_x * 4
                    r_idx = r_row + x * 4
                    canvas[c_idx:c_idx+4] = resized[r_idx:r_idx+4]
    return canvas

def make_ico(png_list):
    header = struct.pack('<HHH', 0, 1, len(png_list))
    entries = bytearray()
    image_data = bytearray()
    offset = 6 + len(png_list) * 16
    for size, pbytes in png_list:
        w_byte = size if size < 256 else 0
        h_byte = size if size < 256 else 0
        entry = struct.pack('<BBBBHHII', w_byte, h_byte, 0, 0, 1, 32, len(pbytes), offset)
        entries += entry
        image_data += pbytes
        offset += len(pbytes)
    return header + entries + image_data

def main():
    print("Loading test transparent logo...")
    src_w, src_h, src_rgba = load_png("frontend/logo_transparent_test.png")

    # 1. Update frontend/logo.png
    save_png(src_w, src_h, src_rgba, "frontend/logo.png")
    print("Updated frontend/logo.png")

    # 2. Generate square icons
    png_icons = {}
    for s in [512, 192, 180, 48, 32, 16]:
        print(f"Generating {s}x{s} square icon...")
        sq = make_square_icon(src_w, src_h, src_rgba, s)
        if s == 512:
            png_icons[s] = save_png(s, s, sq, "frontend/icon-512.png")
            save_png(s, s, sq, "android/app/src/main/res/drawable/ic_butterfly_logo_bitmap.png")
            print("Updated frontend/icon-512.png and android bitmap")
        elif s == 192:
            png_icons[s] = save_png(s, s, sq, "frontend/icon-192.png")
            print("Updated frontend/icon-192.png")
        elif s == 180:
            png_icons[s] = save_png(s, s, sq, "frontend/apple-touch-icon.png")
            print("Updated frontend/apple-touch-icon.png")
        elif s == 48:
            png_icons[s] = save_png(s, s, sq, "frontend/icon-48.png")
        elif s == 32:
            png_icons[s] = save_png(s, s, sq, "frontend/favicon-32x32.png")
            print("Updated frontend/favicon-32x32.png")
        elif s == 16:
            png_icons[s] = save_png(s, s, sq, "frontend/favicon-16x16.png")
            print("Updated frontend/favicon-16x16.png")

    # 3. Save multi-res favicon.ico
    ico_data = make_ico([(16, png_icons[16]), (32, png_icons[32]), (48, png_icons[48])])
    with open("frontend/favicon.ico", "wb") as f:
        f.write(ico_data)
    print("Updated frontend/favicon.ico")

    # 4. Generate SVG favicon with embedded high-res butterfly logo
    b64_logo = base64.b64encode(png_icons[192]).decode("ascii")
    svg_content = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192" width="100%" height="100%">
  <image href="data:image/png;base64,{b64_logo}" width="192" height="192" />
</svg>
'''
    with open("frontend/butterfly-favicon.svg", "w", encoding="utf-8") as f:
        f.write(svg_content)
    print("Updated frontend/butterfly-favicon.svg")

    # Also clean up the temporary test file
    if os.path.exists("frontend/logo_transparent_test.png"):
        os.remove("frontend/logo_transparent_test.png")
    print("All logo assets updated successfully!")

if __name__ == "__main__":
    main()
