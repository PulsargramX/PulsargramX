#!/usr/bin/env python3
"""Convert the supplied SVG artwork into adaptive, legacy, themed and notification icons."""

from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
ANDROID = "http://schemas.android.com/apk/res/android"
SVG = "http://www.w3.org/2000/svg"
SCALE = 0.78


def render(svg, size, destination):
  subprocess.run(["magick", "-background", "none", str(svg), "-resize", f"{size}x{size}",
                  str(destination)], check=True, capture_output=True)
  return Image.open(destination).convert("RGBA")


def compose(size, logo, circle=False, border=False):
  background = Image.new("RGBA", (size, size))
  start, end = (21, 115, 155), (63, 10, 142)
  draw = ImageDraw.Draw(background)
  for y in range(size):
    fraction = y / max(1, size - 1)
    color = tuple(round(a + (b - a) * fraction) for a, b in zip(start, end)) + (255,)
    draw.line((0, y, size, y), fill=color)
  background.alpha_composite(logo.resize((size, size), Image.Resampling.LANCZOS))
  if circle:
    mask = Image.new("L", (size, size))
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    background.putalpha(mask)
  if border:
    ImageDraw.Draw(background).ellipse((1, 1, size - 2, size - 2), outline="white",
                                       width=max(1, round(size / 24)))
  return background


def generate(root=ROOT):
  source = root / "images/pulsargram-logo.svg"
  path_data = next(ET.parse(source).getroot().iter("{" + SVG + "}path")).attrib["d"]
  translate = (1 - SCALE) * 320
  vector = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="{ANDROID}"
  android:width="108dp" android:height="108dp"
  android:viewportWidth="640" android:viewportHeight="640">
  <group android:scaleX="{SCALE}" android:scaleY="{SCALE}"
    android:translateX="{translate:.2f}" android:translateY="{translate:.2f}">
    <path android:fillColor="#FFFFFFFF" android:pathData="{path_data}" />
  </group>
</vector>
'''
  resources = root / "app/src/main/res"
  (resources / "drawable/pulsargram_icon_foreground.xml").write_text(vector)
  (resources / "drawable/app_adaptive_fg_monochrome.xml").write_text(vector)
  (resources / "drawable/pulsargram_icon_background.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="{ANDROID}" xmlns:aapt="http://schemas.android.com/aapt"
  android:width="108dp" android:height="108dp"
  android:viewportWidth="640" android:viewportHeight="640">
  <path android:pathData="M0,0H640V640H0Z">
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear" android:startX="320" android:startY="0"
        android:endX="320" android:endY="640"
        android:startColor="#15739B" android:endColor="#3F0A8E" />
    </aapt:attr>
  </path>
</vector>
''')
  for name in ("app_launcher", "app_launcher_round"):
    (root / "app/src/sinceOreo/res/mipmap-anydpi-v26" / (name + ".xml")).write_text(
      f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="{ANDROID}">
  <background android:drawable="@drawable/pulsargram_icon_background" />
  <foreground android:drawable="@drawable/pulsargram_icon_foreground" />
  <monochrome android:drawable="@drawable/app_adaptive_fg_monochrome" />
</adaptive-icon>
''')
  with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    adapted = temporary / "adapted.svg"
    adapted.write_text(f'''<svg xmlns="{SVG}" width="640" height="640" viewBox="0 0 640 640">
<g transform="translate({translate} {translate}) scale({SCALE})">
<path fill="white" d="{path_data}" /></g></svg>''')
    logo = render(adapted, 1024, temporary / "adapted.png")
    for base in (resources, root / "app/src/sinceOreo/res"):
      for path in base.glob("mipmap-*/*"):
        if not path.is_file() or path.suffix not in (".png", ".webp"):
          continue
        if not path.name.startswith(("app_launcher", "app_adaptive", "app_notification", "logo_middle")):
          continue
        with Image.open(path) as current:
          width, height = current.size
        if width != height:
          raise ValueError("Expected square icon: " + str(path))
        size = width
        if path.name.startswith("app_adaptive_fg"):
          icon = logo.resize((size, size), Image.Resampling.LANCZOS)
        elif path.name.startswith("app_adaptive_bg"):
          icon = compose(size, Image.new("RGBA", (size, size))).convert("RGB")
        elif path.name.startswith("app_notification"):
          icon = render(source, size, temporary / "notification.png")
        else:
          # Rasterize large and reduce afterward for clean legacy-icon edges.
          icon = compose(1024, logo, circle=True, border=True).resize(
            (size, size), Image.Resampling.LANCZOS)
        if path.suffix == ".webp":
          icon.save(path, lossless=True, exact=True)
        else:
          icon.save(path)
    for name in ("logo.png", "logo_square.png"):
      compose(1024, logo).resize((512, 512), Image.Resampling.LANCZOS).save(root / "images" / name)
    preview = root / "build/icon-preview"
    preview.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (1000, 310), "#182235")
    labels = ("Adaptive circle", "Rounded square", "Themed", "48dp legacy")
    draw = ImageDraw.Draw(sheet)
    for index, label in enumerate(labels):
      size = 210
      if index == 2:
        icon = Image.new("RGBA", (size, size), "#CCDFFF")
        glyph = Image.new("RGBA", (size, size), "#183A66")
        edge = round(1024 / 6)
        viewport = logo.crop((edge, edge, 1024 - edge, 1024 - edge))
        glyph.putalpha(viewport.resize((size, size), Image.Resampling.LANCZOS).getchannel("A"))
        icon.alpha_composite(glyph)
      else:
        # Adaptive launchers crop the 108dp layers to a 72dp viewport.
        icon = compose(1024, logo)
        edge = round(1024 / 6)
        icon = icon.crop((edge, edge, 1024 - edge, 1024 - edge)).resize(
          (size, size), Image.Resampling.LANCZOS)
      mask = Image.new("L", (size, size))
      mask_draw = ImageDraw.Draw(mask)
      if index == 1:
        mask_draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=48, fill=255)
      else:
        mask_draw.ellipse((0, 0, size - 1, size - 1), fill=255)
      if index == 3:
        icon = compose(1024, logo, circle=True, border=True).resize((48, 48), Image.Resampling.LANCZOS)
        icon = icon.resize((192, 192), Image.Resampling.NEAREST)
        sheet.paste(icon, (index * 250 + 29, 36), icon)
      else:
        sheet.paste(icon, (index * 250 + 20, 30), mask)
      draw.text((index * 250 + 20, 265), label, fill="white")
    sheet.save(preview / "android-icons.png")
  print(preview / "android-icons.png")


if __name__ == "__main__":
  generate()
