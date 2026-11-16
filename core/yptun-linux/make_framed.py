from PIL import Image, ImageDraw, ImageFont
import os

def frame_window(src_path, dst_path, crop_box=None, title="YPtun"):
    im = Image.open(src_path).convert("RGBA")
    if crop_box:
        im = im.crop(crop_box)
    
    w, h = im.size
    title_h = 36
    border = 2
    
    total_w = w + border * 2
    total_h = h + title_h + border * 2
    
    out = Image.new("RGBA", (total_w, total_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(out)
    
    # Outer background & border
    draw.rounded_rectangle([(0, 0), (total_w - 1, total_h - 1)], radius=12, fill=(22, 23, 25, 255), outline=(42, 44, 46, 255), width=border)
    
    # Window controls (3 subtle dots)
    dot_y = title_h // 2 + border
    draw.ellipse([(16, dot_y - 6), (28, dot_y + 6)], fill=(239, 68, 68, 255))
    draw.ellipse([(36, dot_y - 6), (48, dot_y + 6)], fill=(234, 179, 8, 255))
    draw.ellipse([(56, dot_y - 6), (68, dot_y + 6)], fill=(34, 197, 94, 255))
    
    # Title centered
    draw.text((total_w // 2 - 60, 10), title, fill=(200, 203, 208, 255))
    
    # Paste app content
    out.paste(im, (border, title_h + border))
    
    out.save(dst_path)
    print(f"Saved {dst_path} ({total_w}x{total_h})")

brain = "/mnt/c/Users/Stanislav/.gemini/antigravity/brain/dfa1b178-3450-416d-8350-875792a86ae5"

# Clean compose screens
frame_window(f"{brain}/orig_yptun_compose.png", f"{brain}/screen_home_clean.png", (0, 22, 1090, 690), "YPtun - Главный экран")
frame_window(f"{brain}/orig_yptun_settings_sheet.png", f"{brain}/screen_settings_clean.png", (0, 22, 1090, 690), "YPtun - Настройки (Modal Bottom Sheet)")
frame_window(f"{brain}/orig_yptun_routing_sheet.png", f"{brain}/screen_routing_clean.png", (0, 22, 1090, 690), "YPtun - Маршрутизация и правила")

# Clean rust companion screens
frame_window(f"{brain}/rust_ui_traffic.png", f"{brain}/screen_traffic_clean.png", (0, 23, 1100, 713), "YPtun - Трафик и DNS")
frame_window(f"{brain}/rust_ui_appearance.png", f"{brain}/screen_appearance_clean.png", (0, 23, 1100, 713), "YPtun - Внешний вид")
frame_window(f"{brain}/rust_ui_about.png", f"{brain}/screen_about_clean.png", (0, 23, 1100, 713), "YPtun - О программе")
