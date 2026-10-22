#!/usr/bin/env python3
"""Builds the README pictures in docs/screenshots from the test renders.

Run `./gradlew testReleaseUnitTest` first: ScreenshotsTest writes the island on a mock phone to app/build/screenshots and
SettingsRender*Test the settings pages to app/build/render. This script crops them to the top of the phone, fades the cut
edge out, and puts the settings pages into the same phone frame. Needs Pillow.
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "app/build/screenshots")
RENDER = os.path.join(ROOT, "app/build/render")
OUT = os.path.join(ROOT, "docs/screenshots")

BEZEL = 34
SCREEN_W = 1080
VISIBLE = 1500          # pixels of the island pictures that stay visible, counted from the top of the phone
VISIBLE_APP = 1950      # the same for the settings pages, which have more to show
FADE = 260              # height of the fade at the cut edge
OUT_W = 480             # width of the pictures in the README


def phone_from_screen(screen: Image.Image, height: int) -> Image.Image:
    """Puts a settings page into a phone frame like the island pictures (top part only)."""
    w, h = SCREEN_W + 2 * BEZEL, height + BEZEL
    phone = Image.new("RGBA", (w, h + 200), (0, 0, 0, 0))
    d = ImageDraw.Draw(phone)
    d.rounded_rectangle((0, 0, w - 1, h + 199), 150, fill=(26, 27, 31, 255), outline=(69, 71, 79, 255), width=5)
    mask = Image.new("L", (SCREEN_W, h + 200 - BEZEL), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, SCREEN_W - 1, h + 199 - BEZEL - 1), 118, fill=255)
    page = Image.new("RGBA", mask.size, (0, 0, 0, 255))
    page.paste(screen.crop((0, 0, SCREEN_W, min(screen.height, mask.size[1]))), (0, 0))
    phone.paste(page, (BEZEL, BEZEL), mask)
    return phone


def finish(phone: Image.Image, name: str, keep: int = VISIBLE) -> None:
    visible = min(keep, phone.height)
    cut = phone.crop((0, 0, phone.width, visible)).convert("RGBA")
    px = cut.load()
    for y in range(visible - FADE, visible):
        keep = (visible - 1 - y) / FADE
        for x in range(cut.width):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, int(a * keep))
    scale = OUT_W / cut.width
    cut = cut.resize((OUT_W, round(cut.height * scale)), Image.LANCZOS)
    cut.save(os.path.join(OUT, name + ".png"), optimize=True)


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    for lang in ("", "_ko"):
        for name in ("pill", "live", "call", "music", "stack"):
            src = os.path.join(SHOTS, name + lang + ".png")
            finish(Image.open(src), "island_" + name + lang)
        prefix = "settings_ko_" if lang else "settings_"
        for page, key in (("hub", "hub"), ("notificationsPage", "notifications"), ("rulesList", "rules"),
                          ("rulesDetail", "rule"), ("rulesTestFilled", "tester")):
            screen = Image.open(os.path.join(RENDER, prefix + page + ".png")).convert("RGBA")
            finish(phone_from_screen(screen, VISIBLE_APP), "app_" + key + lang, VISIBLE_APP)
    print("wrote", len(os.listdir(OUT)), "pictures to", OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
