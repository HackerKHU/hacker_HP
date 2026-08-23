"""로고 원본(brand/source) 1장에서 배포용 변형 전부를 다시 만든다.  python3 brand/_generate.py"""
from pathlib import Path
from PIL import Image

SRC = Path(__file__).parent / "source/hacker-logo-master.png"
OUT = Path(__file__).parent
BLACK_PT, WHITE_PT = 32, 250      # 원본 잉크/배경 실측값
SPLIT_Y = 985                     # 엠블럼과 워드마크 사이 빈 줄(965~1006)의 중앙

INK = (26, 26, 26)                # 원본 잉크색
PAPER = (255, 255, 255)


def ink_alpha(path):
    """흰 배경 스캔본 → 잉크 모양만 남긴 알파 마스크."""
    g = path if isinstance(path, Image.Image) else Image.open(path)
    g = g.convert("L")
    scale = 255 / (WHITE_PT - BLACK_PT)
    return g.point(lambda v: max(0, min(255, int((WHITE_PT - v) * scale))))


def tinted(alpha, color):
    im = Image.new("RGBA", alpha.size, color + (0,))
    im.putalpha(alpha)
    return im.crop(alpha.getbbox())


def flatten(rgba, bg):
    out = Image.new("RGB", rgba.size, bg)
    out.paste(rgba, mask=rgba)
    return out


def fit(im, longest):
    w, h = im.size
    s = longest / max(w, h)
    return im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)


def save(im, name, sizes):
    for s in sizes:
        p = OUT / f"{name}-{s}.png"
        fit(im, s).save(p)
        print(p.relative_to(OUT.parent))


def padded(rgba, size, pad=0.12, bg=None):
    """정사각 캔버스 가운데 정렬 (아이콘용 여백 포함)."""
    inner = round(size * (1 - pad * 2))
    art = fit(rgba, inner)
    canvas = Image.new("RGBA", (size, size), (bg or (0, 0, 0)) + (0 if bg is None else 255,))
    canvas.paste(art, ((size - art.width) // 2, (size - art.height) // 2), art)
    return canvas


master = ink_alpha(SRC)
mark_a = master.crop((0, 0, master.width, SPLIT_Y))
word_a = master.crop((0, SPLIT_Y, master.width, master.height))

parts = {"lockup": master, "mark": mark_a, "wordmark": word_a}
art = {k: {"black": tinted(a, INK), "white": tinted(a, PAPER)} for k, a in parts.items()}

# 가로형에서 워드마크를 원본 비율보다 키운다. 1.0이면 엠블럼에 눌려 안 읽힌다.
#
# 원작의 `HACKER` 줄은 심볼 높이의 0.10배(90px vs 858px)다 — 세로 락업에서는 심볼 아래
# 받침이라 그 비율이 맞지만, 옆에 나란히 놓으면 글자가 심볼에 먹힌다. 2.4배(=0.25)로는
# 헤더 크기(높이 36px)에서 여전히 안 읽혔다. 6.5배면 `HACKER`가 심볼의 0.68배가 되어
# 둘이 대등하게 읽힌다.
WORD_SCALE = 4.0


def headline_center(rgba):
    """워드마크 첫 줄(HACKER)의 세로 중심. 아래 태그라인은 매달리게 둔다."""
    a = rgba.getchannel("A")
    for y in range(a.height):
        if a.crop((0, y, a.width, y + 1)).getbbox() is None:
            return y / 2
    return a.height / 2


def horizontal(color):
    """엠블럼 오른쪽에 워드마크. HACKER 줄의 중심을 엠블럼 중심에 맞춘다."""
    m, w = art["mark"][color], art["wordmark"][color]
    h = round(w.height * WORD_SCALE)
    w = w.resize((round(w.width * h / w.height), h), Image.LANCZOS)
    # 심볼과 워드마크 사이. 심볼 폭 기준이라 크기를 바꿔도 비율이 유지된다.
    #
    # 0.14는 둘이 붙어 한 글자처럼 뭉쳐 보였다. 0.14/0.24/0.34/0.44를 헤더 크기로 줄여
    # 비교해 0.34로 정했다 — 분명히 떨어지면서도 한 덩어리로 읽히는 지점이다.
    # 0.44는 심볼과 글자가 따로 노는 것처럼 보인다.
    gap = round(m.width * 0.34)
    y = m.height // 2 - round(headline_center(w))
    canvas = Image.new("RGBA", (m.width + gap + w.width, max(m.height, y + w.height)), (0, 0, 0, 0))
    canvas.paste(m, (0, 0), m)
    canvas.paste(w, (m.width + gap, y), w)
    return canvas.crop(canvas.getchannel("A").getbbox())


BIG = (2048, 1024, 512, 256)

for color in ("black", "white"):
    save(art["lockup"][color], f"vertical/lockup-vertical-{color}", BIG)
    save(horizontal(color), f"horizontal/lockup-horizontal-{color}", BIG)
    save(art["mark"][color], f"mark/mark-{color}", BIG + (128, 64))
    save(art["wordmark"][color], f"wordmark/wordmark-{color}", (2048, 1024, 512))

def with_bg(rgba, bg, pad=0.08):
    """비율은 그대로 두고 사방에 여백을 붙여 배경을 채운다."""
    m = round(max(rgba.size) * pad)
    out = Image.new("RGB", (rgba.width + m * 2, rgba.height + m * 2), bg)
    out.paste(rgba, (m, m), rgba)
    return out


# 배경 있는 버전 (투명 못 쓰는 곳: 인쇄물, 카톡 프로필, 문서 삽입)
for bg_name, bg, color in (("on-white", PAPER, "black"), ("on-black", INK, "white")):
    for path, im in (("vertical/lockup-vertical", art["lockup"][color]),
                     ("mark/mark", art["mark"][color]),
                     ("horizontal/lockup-horizontal", horizontal(color))):
        save(with_bg(im, bg), f"{path}-{bg_name}", (2048, 1024, 512))

# 파비콘·앱 아이콘 — 엠블럼만, 여백 포함
icon_src = art["mark"]["black"]
for s in (512, 192, 180, 48, 32, 16):
    padded(icon_src, s, 0.08 if s > 48 else 0.02).save(OUT / f"icon/icon-{s}.png")   # 작은 크기는 여백을 줄여야 형태가 남는다
flatten(padded(icon_src, 180, 0.10, PAPER), PAPER).save(OUT / "icon/apple-touch-icon-180.png")   # iOS는 투명 미지원
flatten(padded(art["mark"]["white"], 512, 0.08, INK), INK).save(OUT / "icon/icon-512-dark.png")
padded(icon_src, 512, 0.20).save(OUT / "icon/icon-maskable-512.png")                             # 안드로이드 세이프존
padded(icon_src, 256, 0.02).save(OUT / "icon/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
print("brand/icon/*")

# SNS·오픈그래프 1200x630
for name, bg, color in (("og-light", PAPER, "black"), ("og-dark", INK, "white")):
    canvas = Image.new("RGB", (1200, 630), bg)
    art_h = fit(horizontal(color), 820)
    canvas.paste(art_h, ((1200 - art_h.width) // 2, (630 - art_h.height) // 2), art_h)
    canvas.save(OUT / f"social/{name}-1200x630.png")
print("brand/social/*")
