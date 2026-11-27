#!/usr/bin/env python3
"""Generates the launcher icon from one shape list.

    python3 design/generate_icon.py

Writes design/app-icon.svg (master/preview) and the adaptive icon drawables. The motif:
a pocket gimbal camera and an action camera whose USB cables join into one arrow:
footage being pulled out of the cameras.

Each camera is one even-odd path, so lenses and screens are real holes and the same
artwork works as the themed (monochrome) icon. The cable and its arrowhead are strokes.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
BACKGROUND = "#0B6E69"
# The artwork is drawn in its own coordinates, then scaled so that its centre lands on
# the icon's centre and everything stays well inside the adaptive icon safe zone (the
# middle 66 of 108). CENTER is the centre of the smallest circle around the artwork, so
# the parts that come closest to a round mask (the gimbal's top-left corner, the
# arrowhead, the cable's lower-left bend) all keep the same distance from it.
SCALE = 0.76
CENTER = (59.5, 52.7)
STROKE = 2.8


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
# Action camera: shutter button, body, big lens ring.
ACTION = " ".join([
    rrect(54, 39.6, 8, 3.4, 1.6),
    rrect(48, 43, 33, 30, 6.5), circle(67, 58, 9.6), circle(67, 58, 5.6), circle(67, 58, 2.4),
])
FILLS = [GIMBAL, ACTION]

STROKES = [
    # The gimbal's cable runs down and along the bottom, then sweeps up on the right to
    # about the height of the cameras' middle, ending in an arrowhead. Turning upward
    # keeps the tip away from the corner of a round mask...
    "M35.5,73 V79 Q35.5,83.5 40,83.5 H71.5 C84,83.5 89,74 93.5,59 M87.4,63.3 L93.5,59 L96.2,66",
    # ...and the action camera's cable joins it on the way.
    "M64.5,73 V83.5",
]
STROKE_WIDTHS = [STROKE, STROKE]

TX = 54 - CENTER[0] * SCALE
TY = 54 - CENTER[1] * SCALE


def svg():
    fills = "\n".join(f'    <path fill="#FFFFFF" fill-rule="evenodd" d="{d}"/>' for d in FILLS)
    strokes = "\n".join(
        f'    <path fill="none" stroke="#FFFFFF" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round" d="{d}"/>'
        for d, w in zip(STROKES, STROKE_WIDTHS))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">\n'
            f'  <rect width="108" height="108" fill="{BACKGROUND}"/>\n'
            f'  <g transform="translate({TX:g} {TY:g}) scale({SCALE})">\n{fills}\n{strokes}\n  </g>\n</svg>\n')


def vector(color):
    fills = "\n".join(
        f'        <path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{d}" />' for d in FILLS)
    strokes = "\n".join(
        f'        <path android:strokeColor="{color}" android:strokeWidth="{w}" android:strokeLineCap="round" '
        f'android:strokeLineJoin="round" android:pathData="{d}" />'
        for d, w in zip(STROKES, STROKE_WIDTHS))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            f'    <group android:scaleX="{SCALE}" android:scaleY="{SCALE}" android:translateX="{TX:g}" android:translateY="{TY:g}">\n'
            f'{fills}\n{strokes}\n    </group>\n</vector>\n')


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
