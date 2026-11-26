#!/usr/bin/env python3
"""Generates the launcher icon from one shape list.

    python3 design/generate_icon.py

Writes design/app-icon.svg (master/preview) and the adaptive icon drawables. The motif:
a pocket gimbal camera and an action camera side by side, sitting on a USB cable.
Each camera is one even-odd path, so lenses and screens are real holes and the same
artwork works as the themed (monochrome) icon.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
BACKGROUND = "#0B6E69"
# Keep the artwork inside the adaptive icon safe zone (the middle 66 of 108).
SCALE = 0.82
SHIFT_Y = -1.8


def rrect(x, y, w, h, r):
    return (f"M{x + r},{y} H{x + w - r} A{r},{r} 0 0 1 {x + w},{y + r} V{y + h - r} "
            f"A{r},{r} 0 0 1 {x + w - r},{y + h} H{x + r} A{r},{r} 0 0 1 {x},{y + h - r} "
            f"V{y + r} A{r},{r} 0 0 1 {x + r},{y} Z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy} A{r},{r} 0 1 0 {cx + r},{cy} A{r},{r} 0 1 0 {cx - r},{cy} Z"


# Pocket gimbal camera: head with lens, neck, handle with screen.
GIMBAL = " ".join([
    rrect(27, 26, 17, 14, 4), circle(35.5, 33, 3.9),
    rrect(33.5, 40, 4, 4, 0.01),
    rrect(29, 44, 13, 29, 4.5), rrect(32, 48.5, 7, 9, 1.4),
])
# Action camera: body, big lens ring, shutter button.
ACTION = " ".join([
    rrect(54, 39.6, 8, 3.4, 1.6),
    rrect(48, 43, 33, 30, 6.5), circle(67, 58, 9.6), circle(67, 58, 5.6), circle(67, 58, 2.4),
])
# USB cable both cameras sit on, with the plug pointing down to the phone.
CABLE = " ".join([rrect(27, 77, 54, 3.2, 1.6), rrect(50.5, 80.2, 7, 6.8, 1.8)])
PATHS = [GIMBAL, ACTION, CABLE]


def svg():
    paths = "\n".join(f'    <path fill="#FFFFFF" fill-rule="evenodd" d="{d}"/>' for d in PATHS)
    move = 54 * (1 - SCALE)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">\n'
            f'  <rect width="108" height="108" fill="{BACKGROUND}"/>\n'
            f'  <g transform="translate({move:g} {move + SHIFT_Y:g}) scale({SCALE})">\n{paths}\n  </g>\n</svg>\n')


def vector(color):
    paths = "\n".join(
        f'        <path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{d}" />' for d in PATHS)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            f'    <group android:pivotX="54" android:pivotY="54" android:scaleX="{SCALE}" android:scaleY="{SCALE}" android:translateY="{SHIFT_Y}">\n'
            f'{paths}\n    </group>\n</vector>\n')


ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'''

(ROOT / "design/app-icon.svg").write_text(svg())
(RES / "drawable/ic_launcher_foreground.xml").write_text(vector("#FFFFFFFF"))
(RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(ADAPTIVE)
(RES / "values/ic_launcher_background.xml").write_text(
    f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{BACKGROUND}</color>\n</resources>\n')
