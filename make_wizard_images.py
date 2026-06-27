"""Generate the Inno Setup wizard branding images from clarifi.ico.

ClariFi.iss references WizardImage.bmp (left panel of the Welcome/Finished pages)
and WizardSmallImage.bmp (top-right corner of every inner page). Without them Inno
Setup shows its generic placeholder during install. These are derived from
clarifi.ico so the .ico stays the single source of truth for app branding, the same
way the Linux build derives clarifi.png from it.

Run from the directory holding clarifi.ico, before invoking ISCC:
    python make_wizard_images.py
"""
from PIL import Image

BG = (8, 8, 10)  # ClariFi brand dark (#08080a)


def compose(width, height, logo_frac, out_path):
    logo_src = Image.open("clarifi.ico").convert("RGBA")
    canvas = Image.new("RGB", (width, height), BG)
    side = int(min(width, height) * logo_frac)
    logo = logo_src.resize((side, side), Image.LANCZOS)
    canvas.paste(logo, ((width - side) // 2, (height - side) // 2), logo)
    canvas.save(out_path)
    print(f"wrote {out_path} ({width}x{height})")


if __name__ == "__main__":
    # 2x the Inno defaults (164x314 / 55x58) so they stay crisp when scaled for DPI.
    compose(328, 628, 0.72, "WizardImage.bmp")
    compose(110, 116, 0.82, "WizardSmallImage.bmp")
