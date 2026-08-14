from pathlib import Path

from PIL import Image, ImageDraw


SIZE = 1024
ROOT = Path(__file__).resolve().parents[1]
ICON_DIR = ROOT / "assets" / "icon"


def rounded_line(draw, points, fill, width):
    draw.line(points, fill=fill, width=width, joint="curve")
    radius = width // 2
    for x, y in (points[0], points[-1]):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=fill)


def mascot_layer(colorized=True):
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    orange = "#FF8A24" if colorized else "#000000"
    orange_dark = "#F06412" if colorized else "#000000"
    leaf = "#45A65B" if colorized else "#000000"
    ink = "#713515" if colorized else "#000000"
    paper = "#FFFDF8" if colorized else "#000000"

    # Leaf and stem stay inside Android's adaptive icon safe zone.
    draw.rounded_rectangle((495, 190, 535, 310), radius=20, fill=orange_dark)
    draw.ellipse((515, 174, 720, 328), fill=leaf)
    draw.ellipse((365, 274, 774, 730), fill=orange_dark)
    draw.ellipse((280, 274, 689, 730), fill=orange)

    # Friendly face remains readable at small launcher sizes.
    draw.ellipse((392, 438, 430, 486), fill=ink)
    draw.ellipse((535, 438, 573, 486), fill=ink)
    rounded_line(draw, [(422, 535), (454, 562), (503, 565), (544, 532)], ink, 21)

    # Small receipt/checkmark gives the mark a bookkeeping identity.
    draw.rounded_rectangle((594, 536, 774, 760), radius=30, fill=paper)
    rounded_line(draw, [(628, 647), (666, 685), (738, 602)], leaf, 24)
    draw.rounded_rectangle((623, 579, 711, 592), radius=6, fill=orange_dark)
    draw.rounded_rectangle((623, 716, 742, 729), radius=6, fill=orange_dark)
    return image


def main():
    ICON_DIR.mkdir(parents=True, exist_ok=True)

    foreground = mascot_layer()
    foreground.save(ICON_DIR / "adaptive_foreground.png")

    monochrome = mascot_layer(colorized=False)
    monochrome.save(ICON_DIR / "adaptive_monochrome.png")

    legacy = Image.new("RGB", (SIZE, SIZE), "#FFF8EF")
    # A subtle flat circle improves contrast without adding gradients.
    backdrop = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(backdrop).ellipse((118, 118, 906, 906), fill="#FFE2BF")
    legacy.paste(backdrop, (0, 0), backdrop)
    legacy.paste(foreground, (0, 0), foreground)
    legacy.save(ICON_DIR / "launcher_legacy.png")


if __name__ == "__main__":
    main()
