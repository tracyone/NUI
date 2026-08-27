"""生成 NUI 启动图标（字母 N）多密度 PNG。

用法: python3 scripts/gen_launcher_icon.py
输出: app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
      及 ic_launcher_round.png
"""
import os
from PIL import Image, ImageDraw

BG_DARK = (11, 20, 38)        # #0B1426
TEAL = (0, 197, 211)          # #00C5D3

SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
RES_ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

# N 在 192 画布中的四个顶点：左上→左下→右上→右下（描边后成 N）
N_PTS = [(60, 150), (60, 42), (132, 150), (132, 42)]
N_WIDTH = 26  # 在 192 画布中的线宽


def draw_icon(size: int, shape: str) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if shape == "rounded":
        radius = size // 6
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=BG_DARK)
    else:  # circle
        d.ellipse([0, 0, size - 1, size - 1], fill=BG_DARK)

    s = size / 192.0
    pts = [(x * s, y * s) for x, y in N_PTS]
    w = max(4, int(N_WIDTH * s))
    d.line(pts, fill=TEAL, width=w, joint="curve")
    r = w // 2
    for px, py in pts:
        d.ellipse([px - r, py - r, px + r, py + r], fill=TEAL)
    return img


def main() -> None:
    for dens, sz in SIZES.items():
        out = os.path.normpath(os.path.join(RES_ROOT, f"mipmap-{dens}"))
        os.makedirs(out, exist_ok=True)
        draw_icon(sz, "rounded").save(os.path.join(out, "ic_launcher.png"))
        draw_icon(sz, "circle").save(os.path.join(out, "ic_launcher_round.png"))
    print("icon generated:", ", ".join(SIZES.keys()))


if __name__ == "__main__":
    main()
