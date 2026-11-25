#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Builds the blog post into the standalone page that vecbricks.github.io publishes.

    python3 page.py --out <vecbricks.github.io>/strictmath-at-simd-speed \\
      --og-image https://vecbricks.github.io/strictmath-at-simd-speed/card.png

and, for the link card (social sites show no SVG, so the card is a PNG), with chromium:

    python3 page.py --out <dir> --card    # also writes <dir>/card.html, 1200 x 630
    chromium --headless=new --hide-scrollbars --virtual-time-budget=5000 \\
      --window-size=1200,630 --screenshot=card.png <dir>/card.html
    rm <dir>/card.html                    # it is not published

The first post's prose is 2026-11-strictmath-at-simd-speed.md, its figures are img/*.svg, which
figures.py writes; this script turns the two into one index.html beside a figures/ copy of the
SVGs, the way the site's other posts are made. It is adapted from Varka's
dev/varka_post_page.py (vecbricks/varka, Apache License 2.0, as this project), keeping its page
style, so the post looks like the site's others; what differs is what this post's Markdown
needs:

- the title is the first line, `# ...`; HTML comments (notes to the post's editors) are dropped;
- a `<div ... markdown="1">` box is rendered as Markdown inside (the `md_in_html` extension);
- a figure is a linked image, `[![alt](img/x.svg)](img/x.svg)`, whose SVG is inlined in a
  `<figure>`; a plain link to `img/x.svg` points at the copy under figures/;
- fenced code with a language tag is highlighted by Pygments, light and dark.

It fails on a figure that is missing or not inlined, and on any `{{` left in the page, so a page
with a hole or a placeholder is not written. Requires the markdown and pygments packages
(`pip install markdown pygments`).

The second post, "Even StrictMath is not frozen", is built the same way from its own directory:

    python3 page.py --source strictmath-not-frozen/2026-11-even-strictmath-is-not-frozen.md \\
      --out <vecbricks.github.io>/even-strictmath-is-not-frozen \\
      --og-image https://vecbricks.github.io/even-strictmath-is-not-frozen/card.png \\
      --card --card-figure pow-errors
"""

import argparse
from html import unescape
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
POST = os.path.join(HERE, "2026-11-strictmath-at-simd-speed.md")

# The page style of Varka's dev/varka_post_page.py, with rules added for h3, wide tables, long
# inline code and the post's two boxes, and without the figures' 600px floor (these are 560 wide).
STYLE = """
:root{--bg:#fbfaf7;--fg:#1f2328;--muted:#5d6672;--accent:#6741d9;--rule:#e3ddd2;
--code:#f1eee8;--fig:#ffffff}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#15171b;--fg:#e6e3dc;
--muted:#a1a7b0;--accent:#b197fc;--rule:#2b2f36;--code:#1f2329;--fig:#ffffff}}
:root[data-theme="dark"]{--bg:#15171b;--fg:#e6e3dc;--muted:#a1a7b0;--accent:#b197fc;
--rule:#2b2f36;--code:#1f2329;--fig:#ffffff}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font-family:Literata,Georgia,serif;
font-size:18px;line-height:1.6}
main{max-width:42rem;margin:0 auto;padding:2.5rem 16px 5rem}
h1{font-family:"Source Sans 3",system-ui,sans-serif;font-size:2.1rem;line-height:1.15;
text-wrap:balance;margin:0 0 1rem;letter-spacing:-.01em}
h2{font-family:"Source Sans 3",system-ui,sans-serif;font-size:1.45rem;margin:2.6rem 0 .8rem;
text-wrap:balance}
h3{font-family:"Source Sans 3",system-ui,sans-serif;font-size:1.15rem;margin:1.8rem 0 .6rem}
p{margin:0 0 1.1rem}
hr{border:0;border-top:1px solid var(--rule);margin:2rem 0}
code{font-family:"JetBrains Mono",ui-monospace,monospace;font-size:.86em;background:var(--code);
padding:.08em .35em;border-radius:4px}
pre{background:var(--code);padding:1rem;border-radius:8px;overflow-x:auto;font-size:.85rem;
line-height:1.5}
pre code{background:none;padding:0}
table{border-collapse:collapse;width:100%;font-size:.92rem;margin:0 0 1.2rem;display:block;
overflow-x:auto}
th,td{border-bottom:1px solid var(--rule);padding:.4rem .5rem;text-align:left}
td,th{font-variant-numeric:tabular-nums}
blockquote{margin:1.6rem 0;padding:.9rem 1.2rem;background:var(--code);border:1px solid var(--rule);
border-radius:8px}
blockquote p:last-child,blockquote ol:last-child{margin-bottom:0}
blockquote ol{margin:.2rem 0 0;padding-left:1.3rem}
.fig{margin:1.6rem 0 1.6rem;background:var(--fig);border-radius:10px;padding:.6rem;
border:1px solid var(--rule);overflow-x:auto;-webkit-overflow-scrolling:touch}
.fig svg{width:100%;height:auto;display:block}
.cap{color:var(--muted);font-size:.92rem;line-height:1.5;margin-bottom:1.6rem}
a{color:var(--accent)}
main{overflow-wrap:break-word}
:not(pre)>code{overflow-wrap:anywhere}
.summary,.box{border:1px solid var(--rule)!important;border-radius:12px}
"""

FONTS = (
    "https://fonts.googleapis.com/css2?family=Literata:ital,opsz,wght@0,7..72,400;"
    "0,7..72,600;1,7..72,400&family=Source+Sans+3:wght@600;700&"
    "family=JetBrains+Mono:wght@400&display=swap"
)


def highlight_css():
    """Pygments' rules for the `.highlight` blocks, a light style and, under the page's dark
    theme, a dark one; the block's own background stays the page's --code colour."""
    from pygments.formatters import HtmlFormatter

    def rules(style, scope):
        css = HtmlFormatter(style=style).get_style_defs(scope + " .highlight")
        # Drop the style's own backgrounds: the <pre> keeps --code, in both themes.
        # Pygments' own pre and line-number rules would override the page's line height.
        return "\n".join(l for l in css.splitlines()
                         if "background" not in l and not l.startswith(("pre ", "td.linenos",
                                                                         "span.linenos")))

    light = rules("friendly", "")
    dark = rules("monokai", ':root[data-theme="dark"]')
    auto = rules("monokai", ':root:not([data-theme="light"])')
    return light + "\n" + dark + "\n@media (prefers-color-scheme:dark){" + auto + "}"


def lists_after_lines(text):
    """Puts a blank line before a list that follows a paragraph line directly, as the post's
    "**Lead-in**" lines do: kramdown starts a list there, Python-Markdown only after a blank line.
    Fenced code is left alone."""
    out, fenced, prev = [], False, ""
    for line in text.split("\n"):
        if line.startswith("```"):
            fenced = not fenced
        item = re.match(r"(- |\d+\. )", line)
        if not fenced and item and prev.strip() and not re.match(r"\s*(- |\d+\. )|\s", prev):
            out.append("")
        out.append(line)
        prev = line if not fenced else ""
    return "\n".join(out)


def inline_figures(html, source_dir):
    """Replaces each linked `img/*.svg` image, alone in its paragraph, with the SVG's markup."""

    def one(match):
        alt, src = match.group(2), match.group(3)
        path = os.path.join(source_dir, src)
        if not os.path.exists(path):
            raise SystemExit("missing figure %s - run figures.py" % src)
        with open(path) as handle:
            svg = handle.read()
        svg = re.sub(r"\A<\?xml[^>]*\?>\s*", "", svg)
        # The drawing scales with the column; its intrinsic size would win over the stylesheet.
        svg = re.sub(r'<svg ([^>]*?)width="[\d.]+" height="[\d.]+"', r"<svg \1", svg, count=1)
        # The post's alt text labels the figure: the SVG's own role and labelling make way for it.
        head = re.match(r"<svg[^>]*>", svg).group(0)
        bare = re.sub(r'\s(role|aria-labelledby|aria-label)="[^"]*"', "", head)
        svg = bare.replace("<svg", '<svg role="img" aria-label="%s"' % alt, 1) + svg[len(head):]
        return '<figure class="fig">%s</figure>' % svg

    pattern = (r'<p><a href="(img/[^"]+\.svg)"><img alt="([^"]*)" src="(img/[^"]+\.svg)" ?/?>'
               r"</a></p>")
    return re.sub(pattern, one, html)


def summarise(html, title):
    """The link card's blurb: the post's opening prose, cut at a sentence end within 220
    characters, or at a word boundary."""
    plain = " ".join(unescape(re.sub(r"<[^>]+>", " ", html)).split())
    plain = re.sub(r"\s+([,.;:!?)])", r"\1", plain)
    plain = re.sub(r"([(])\s+", r"\1", plain)
    if plain.startswith(title):
        plain = plain[len(title):].strip()
    plain = re.sub(r"\AIn short\.\s*", "", plain)
    if len(plain) > 220:
        cut = plain[:220]
        stop = cut.rfind(". ")
        if stop > 80:
            plain = cut[: stop + 1]
        else:
            plain = cut[: cut.rfind(" ")].rstrip(" ,;:-") + "..."
    return plain.replace("&", "&amp;").replace('"', "&quot;")


def render(source, out_dir, og_image=""):
    import markdown

    source_dir = os.path.dirname(os.path.abspath(source))
    with open(source) as handle:
        text = handle.read()
    # A note to the owner left in the post means the text is not theirs yet: no page.
    if re.search(r"<!--\s*owner:", text):
        raise SystemExit("the post still holds a note for the owner (<!-- owner: ... -->)")
    text = re.sub(r"<!--.*?-->\n?", "", text, flags=re.S)
    text = lists_after_lines(text)
    head, rest = text.lstrip().split("\n", 1)
    if not head.startswith("# "):
        raise SystemExit("the post's first line must be its title, '# ...'")
    title = head[2:].strip().replace("`", "")
    html = markdown.markdown(
        head + "\n" + rest,
        extensions=["fenced_code", "tables", "md_in_html", "codehilite"],
        extension_configs={"codehilite": {"guess_lang": False, "css_class": "highlight"}},
    )
    summary = summarise(html, title)
    html = inline_figures(html, source_dir)
    left = re.search(r'<img[^>]+src="img/[^"]*"', html)
    if left:
        raise SystemExit("a figure was not inlined: " + left.group(0))
    for target in re.findall(r'href="(img/[^"]+)"', html):
        if not os.path.exists(os.path.join(source_dir, target)):
            raise SystemExit("a link to a missing figure: " + target)
    html = html.replace('href="img/', 'href="figures/')
    if "{{" in html or "{%" in html:
        raise SystemExit("a placeholder or template tag is left in the page")
    og, card = "", "summary"
    if og_image:
        page_url = og_image.rsplit("/", 1)[0] + "/"
        og = ('<meta property="og:image" content="%s"><meta property="og:url" content="%s">'
              '<link rel="canonical" href="%s">' % (og_image, page_url, page_url))
        card = "summary_large_image"
    esc_title = title.replace("&", "&amp;").replace('"', "&quot;")
    page = (
        '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "<title>%s</title>"
        '<meta name="description" content="%s">'
        '<meta property="og:type" content="article">'
        '<meta property="og:title" content="%s">'
        '<meta property="og:description" content="%s">%s'
        '<meta name="twitter:card" content="%s">'
        '<link rel="preconnect" href="https://fonts.googleapis.com">'
        '<link rel="stylesheet" href="%s">'
        "<style>%s\n%s</style></head><body><main>%s</main></body></html>\n"
        % (esc_title, summary, esc_title, summary, og, card, FONTS, STYLE, highlight_css(), html)
    )
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "index.html")
    with open(out, "w") as handle:
        handle.write(page)
    # The SVGs travel too: a plain link to one points at its copy, and the page can be opened
    # from the output directory alone.
    figures = os.path.join(out_dir, "figures")
    shutil.rmtree(figures, ignore_errors=True)
    shutil.copytree(os.path.join(source_dir, "img"), figures)
    return out, page


CARD = """<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="%s"><style>
body{margin:0;width:1200px;height:630px;background:#fbfaf7;display:flex;align-items:center;
gap:48px;padding:0 56px;box-sizing:border-box;font-family:"Source Sans 3",system-ui,sans-serif}
h1{font-size:54px;line-height:1.1;color:#1f2328;margin:0;flex:1}
p{font-size:26px;color:#5d6672;margin:24px 0 0}
img{height:560px;flex:none}</style></head><body>
<div><h1>%s</h1><p>vecbricks.github.io</p></div><img src="figures/%s.svg"></body></html>
"""


def write_card(out_dir, title, figure="aggregate"):
    """The link card's page: the title (already escaped, from the page's <title>) beside one
    figure, for chromium to screenshot; give it time to load the font (--virtual-time-budget)."""
    path = os.path.join(out_dir, "card.html")
    with open(path, "w") as handle:
        handle.write(CARD % (FONTS, title, figure))
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", default=POST, help="the post's Markdown file")
    parser.add_argument("--out", required=True, help="output directory")
    parser.add_argument("--og-image", default="", help="absolute URL of the link-card image")
    parser.add_argument("--card", action="store_true", help="also write card.html (see above)")
    parser.add_argument("--card-figure", default="aggregate",
                        help="the figure beside the title on the card, img/<name>.svg")
    args = parser.parse_args()
    try:
        out, page = render(args.source, args.out, args.og_image)
    except ImportError:
        sys.exit("this needs the markdown and pygments packages: pip install markdown pygments")
    print("%s  %d bytes  %d figures" % (out, len(page), page.count('<figure class="fig">')))
    if args.card:
        title = re.search(r"<title>(.*?)</title>", page).group(1)
        if not os.path.exists(os.path.join(args.out, "figures", args.card_figure + ".svg")):
            sys.exit("no figure %s for the card" % args.card_figure)
        print(write_card(args.out, title, args.card_figure))


if __name__ == "__main__":
    main()
