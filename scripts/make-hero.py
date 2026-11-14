#!/usr/bin/env python3
"""Composes docs/hero.png from the phone screenshots (requires Pillow).

    docs/screenshots/setup.png      the app's own screen, everything set up
    docs/screenshots/listening.png  the bubble recording over another app
    docs/screenshots/pasted.png     the transcription pasted into that app
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

DOCS = Path(__file__).resolve().parent.parent / "docs"
PHONE = (486, 1080)  # each 1080x2400 screenshot, scaled down
RADIUS, PAD_X, PAD_Y, GAP = 40, 90, 80, 56


def phone(name: str) -> Image.Image:
    shot = Image.open(DOCS / "screenshots" / f"{name}.png").convert("RGBA").resize(PHONE, Image.LANCZOS)
    mask = Image.new("L", PHONE, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, PHONE[0] - 1, PHONE[1] - 1], radius=RADIUS, fill=255)
    shot.putalpha(mask)
    return shot


phones = [phone(name) for name in ("setup", "listening", "pasted")]
W = PAD_X * 2 + PHONE[0] * len(phones) + GAP * (len(phones) - 1)
H = PAD_Y * 2 + PHONE[1]

bg = Image.new("RGBA", (W, H))
draw = ImageDraw.Draw(bg)
top, bottom = (91, 63, 228), (160, 150, 250)
for y in range(H):
    t = y / H
    draw.line([(0, y), (W, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,))

glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
g = ImageDraw.Draw(glow)
g.ellipse([-W // 4, H // 2, W * 3 // 4, H * 3 // 2], fill=(255, 255, 255, 40))
g.ellipse([W // 3, -H // 2, W * 4 // 3, H // 2], fill=(255, 255, 255, 28))
bg = Image.alpha_composite(bg, glow.filter(ImageFilter.GaussianBlur(60)))

shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
s = ImageDraw.Draw(shadow)
for i in range(len(phones)):
    x = PAD_X + i * (PHONE[0] + GAP)
    s.rounded_rectangle([x, PAD_Y + 14, x + PHONE[0], PAD_Y + PHONE[1] + 14], radius=RADIUS, fill=(20, 10, 70, 90))
bg = Image.alpha_composite(bg, shadow.filter(ImageFilter.GaussianBlur(22)))

for i, shot in enumerate(phones):
    bg.alpha_composite(shot, (PAD_X + i * (PHONE[0] + GAP), PAD_Y))

mask = Image.new("L", (W, H), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, H - 1], radius=48, fill=255)
bg.putalpha(mask)
bg.save(DOCS / "hero.png", optimize=True)
print(f"wrote {DOCS / 'hero.png'} {bg.size}")
