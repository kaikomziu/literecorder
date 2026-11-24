"""サイト用のアイコンとスクリーンショットを作る（python tools/make_assets.py <スクショ1> <スクショ2>）"""
import sys
from PIL import Image, ImageDraw

def icon(size):
    s = 4  # 拡大して描いてから縮小し、ふちをなめらかにする
    n = size * s
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.225), fill=(22, 22, 27, 255))
    c = n / 2
    r1, w = n * 0.30, n * 0.045
    d.ellipse([c - r1, c - r1, c + r1, c + r1], outline=(255, 255, 255, 255), width=int(w))
    r2 = n * 0.165
    d.ellipse([c - r2, c - r2, c + r2, c + r2], fill=(255, 59, 48, 255))
    return im.resize((size, size), Image.LANCZOS)

icon(512).save("docs/icon.png")
icon(64).save("docs/favicon.png")

# スクショはステータスバーを切り落として小さくする
for i, path in enumerate(sys.argv[1:], 1):
    im = Image.open(path).convert("RGB")
    im = im.crop((0, 130, im.width, im.height))
    w = 600
    im = im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)
    im.save(f"docs/screen{i}.webp", quality=86)
