"""
Generate Android launcher icons for Butterfly AI Voice Keyboard.
Creates:
- mipmap-mdpi (48x48)
- mipmap-hdpi (72x72)
- mipmap-xhdpi (96x96)
- mipmap-xxhdpi (144x144)
- mipmap-xxxhdpi (192x192)
Both square/rounded (ic_launcher.png) and round (ic_launcher_round.png).
Also creates high-res adaptive icon foreground (432x432) in drawable and all density mipmaps:
- mipmap-mdpi (108x108)
- mipmap-hdpi (162x162)
- mipmap-xhdpi (216x216)
- mipmap-xxhdpi (324x324)
- mipmap-xxxhdpi (432x432)
"""
import os
import math
from PIL import Image, ImageDraw, ImageFilter

def create_background(size):
    """
    Creates a rich, premium navy-to-indigo gradient background with a soft ambient center glow.
    """
    w, h = size
    # Base dark slate / navy
    bg = Image.new("RGBA", (w, h), (15, 23, 42, 255))  # #0F172A
    draw = ImageDraw.Draw(bg)
    
    # Draw radial gradient layers
    center_x, center_y = w / 2, h / 2
    max_radius = math.sqrt(center_x**2 + center_y**2)
    
    # Gradient circles from center outwards
    # Center: #1E3A8A (deep royal blue) -> Outer: #0B1120 (midnight slate)
    for r in range(int(max_radius), 0, -2):
        t = r / max_radius
        red = int(30 * (1 - t) + 11 * t)
        green = int(58 * (1 - t) + 17 * t)
        blue = int(138 * (1 - t) + 32 * t)
        draw.ellipse(
            [center_x - r, center_y - r, center_x + r, center_y + r],
            fill=(red, green, blue, 255)
        )
    
    # Subtle inner vibrant glow circle
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    inner_r = w * 0.38
    glow_draw.ellipse(
        [center_x - inner_r, center_y - inner_r, center_x + inner_r, center_y + inner_r],
        fill=(37, 99, 235, 110)  # #2563EB with opacity
    )
    glow = glow.filter(ImageFilter.GaussianBlur(radius=w * 0.12))
    bg = Image.alpha_composite(bg, glow)
    
    return bg

def make_round_mask(size):
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([0, 0, size[0] - 1, size[1] - 1], fill=255)
    return mask

def make_squircle_mask(size, radius_ratio=0.22):
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    r = int(size[0] * radius_ratio)
    draw.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=r, fill=255)
    return mask

def generate_icons():
    source_logo_path = "android/app/src/main/res/drawable/ic_butterfly_logo_bitmap.png"
    if not os.path.exists(source_logo_path):
        source_logo_path = "frontend/icon-512.png"
    
    print(f"Loading butterfly logo from: {source_logo_path}")
    logo = Image.open(source_logo_path).convert("RGBA")
    
    # Densities for Android mipmaps (legacy icons and adaptive foregrounds)
    # Adaptive icon canvas is 108dp, inner safe area is 72dp.
    densities = {
        "mipmap-mdpi": {"legacy": 48, "foreground": 108},
        "mipmap-hdpi": {"legacy": 72, "foreground": 162},
        "mipmap-xhdpi": {"legacy": 96, "foreground": 216},
        "mipmap-xxhdpi": {"legacy": 144, "foreground": 324},
        "mipmap-xxxhdpi": {"legacy": 192, "foreground": 432},
    }
    
    res_dir = "android/app/src/main/res"
    
    for folder, sizes in densities.items():
        out_folder = os.path.join(res_dir, folder)
        os.makedirs(out_folder, exist_ok=True)
        
        legacy_size = sizes["legacy"]
        # 1. Base composed legacy icon
        bg = create_background((legacy_size, legacy_size))
        
        # Scale butterfly to ~72% of legacy icon size
        butterfly_w = int(legacy_size * 0.72)
        aspect = logo.height / logo.width
        butterfly_h = int(butterfly_w * aspect)
        logo_resized = logo.resize((butterfly_w, butterfly_h), Image.Resampling.LANCZOS)
        
        offset_x = (legacy_size - butterfly_w) // 2
        offset_y = (legacy_size - butterfly_h) // 2
        
        composed = bg.copy()
        composed.paste(logo_resized, (offset_x, offset_y), logo_resized)
        
        # Save standard (squircle)
        squircle_mask = make_squircle_mask((legacy_size, legacy_size))
        squircle_icon = Image.new("RGBA", (legacy_size, legacy_size), (0, 0, 0, 0))
        squircle_icon.paste(composed, (0, 0), squircle_mask)
        squircle_path = os.path.join(out_folder, "ic_launcher.png")
        squircle_icon.save(squircle_path, "PNG")
        
        # Save round (circular mask)
        round_mask = make_round_mask((legacy_size, legacy_size))
        round_icon = Image.new("RGBA", (legacy_size, legacy_size), (0, 0, 0, 0))
        round_icon.paste(composed, (0, 0), round_mask)
        round_path = os.path.join(out_folder, "ic_launcher_round.png")
        round_icon.save(round_path, "PNG")
        
        # 2. Adaptive Icon Foreground for density
        fg_size = sizes["foreground"]
        adaptive_fg = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
        # Inner safe zone is 72/108 of fg_size. Butterfly occupies ~58% of fg_size width.
        fg_butterfly_w = int(fg_size * 0.58)
        fg_butterfly_h = int(fg_butterfly_w * aspect)
        fg_logo_resized = logo.resize((fg_butterfly_w, fg_butterfly_h), Image.Resampling.LANCZOS)
        fg_x = (fg_size - fg_butterfly_w) // 2
        fg_y = (fg_size - fg_butterfly_h) // 2
        adaptive_fg.paste(fg_logo_resized, (fg_x, fg_y), fg_logo_resized)
        
        fg_path = os.path.join(out_folder, "ic_launcher_foreground.png")
        adaptive_fg.save(fg_path, "PNG")
        
        print(f"Generated {folder}: ic_launcher.png ({legacy_size}x{legacy_size}), "
              f"ic_launcher_round.png ({legacy_size}x{legacy_size}), "
              f"ic_launcher_foreground.png ({fg_size}x{fg_size})")

    # Generate Adaptive Icon Foreground PNG (432x432) in drawable folder
    drawable_dir = os.path.join(res_dir, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)
    adaptive_fg_432 = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    fg_butterfly_w = int(432 * 0.58)
    fg_butterfly_h = int(fg_butterfly_w * (logo.height / logo.width))
    fg_logo_resized = logo.resize((fg_butterfly_w, fg_butterfly_h), Image.Resampling.LANCZOS)
    fg_x = (432 - fg_butterfly_w) // 2
    fg_y = (432 - fg_butterfly_h) // 2
    adaptive_fg_432.paste(fg_logo_resized, (fg_x, fg_y), fg_logo_resized)
    
    fg_bitmap_path = os.path.join(drawable_dir, "ic_launcher_foreground_bitmap.png")
    adaptive_fg_432.save(fg_bitmap_path, "PNG")
    print(f"Generated drawable adaptive foreground: {fg_bitmap_path} (432x432)")

if __name__ == "__main__":
    generate_icons()
