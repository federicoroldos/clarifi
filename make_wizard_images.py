"""Generate the Inno Setup wizard branding images from clarifi.ico.

ClariFi.iss references WizardImage*.bmp (left panel of the Welcome/Finished pages)
and WizardSmallImage*.bmp (top-right corner of every inner page). Without them Inno
Setup shows its generic placeholder during install. These are derived from
clarifi.ico so the .ico stays the single source of truth for app branding, the same
way the Linux build derives clarifi.png from it.

Why a whole *set* of images per slot instead of one:
Inno's small-image slot has a DIFFERENT aspect ratio at each display-scaling level
(55x58, 64x68, 83x80, 92x97, 110x116, 119x122, 138x140). If you supply a single
fixed-size bitmap, Inno stretches it non-uniformly to whatever slot the user's DPI
needs, which distorts the round logo into an ellipse. Supplying one image per
scaling level (comma-separated in ClariFi.iss) lets Inno pick an exact match and
skip scaling entirely, so the logo stays crisp and round at every DPI.

Backgrounds differ by slot on purpose:
- Small image -> WHITE. The modern wizard's inner-page header is white, so the small
  bitmap must sit on white to read as an app-icon badge instead of a black box.
  (clarifi.ico is a rounded badge with transparent corners, so white shows through
  the corners and blends into the header.)
- Large image -> brand dark. It fills the full-height left panel of the Welcome /
  Finished pages, where a dark branded panel looks intentional.

Run from the directory holding clarifi.ico, before invoking ISCC:
    python make_wizard_images.py
"""
from PIL import Image

DARK = (8, 8, 10)        # ClariFi brand dark (#08080a) -> full welcome panel
WHITE = (255, 255, 255)  # modern wizard header is white -> small image blends in

# Inno Setup 6 recommended sizes, one per supported scaling level. The large-image
# set covers 100..225%, the small-image set 100..250%. Filenames below line up with
# the comma-separated WizardImageFile / WizardSmallImageFile lists in ClariFi.iss.
LARGE = [
    ((164, 314), "WizardImage.bmp"),
    ((192, 386), "WizardImage-125.bmp"),
    ((205, 392), "WizardImage-150.bmp"),
    ((246, 470), "WizardImage-175.bmp"),
    ((273, 556), "WizardImage-200.bmp"),
    ((328, 628), "WizardImage-225.bmp"),
]
SMALL = [
    ((55, 58), "WizardSmallImage.bmp"),
    ((64, 68), "WizardSmallImage-125.bmp"),
    ((83, 80), "WizardSmallImage-150.bmp"),
    ((92, 97), "WizardSmallImage-175.bmp"),
    ((110, 116), "WizardSmallImage-200.bmp"),
    ((119, 122), "WizardSmallImage-225.bmp"),
    ((138, 140), "WizardSmallImage-250.bmp"),
]


def compose(width, height, logo_frac, bg, out_path):
    logo_src = Image.open("clarifi.ico").convert("RGBA")
    canvas = Image.new("RGB", (width, height), bg)
    side = int(min(width, height) * logo_frac)
    logo = logo_src.resize((side, side), Image.LANCZOS)
    canvas.paste(logo, ((width - side) // 2, (height - side) // 2), logo)
    canvas.save(out_path)
    print(f"wrote {out_path} ({width}x{height})")


if __name__ == "__main__":
    for (w, h), name in LARGE:
        compose(w, h, 0.55, DARK, name)
    for (w, h), name in SMALL:
        compose(w, h, 0.86, WHITE, name)
