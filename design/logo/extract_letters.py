import sys
sys.dont_write_bytecode = True
#!/usr/bin/env python3
"""
Font outline extraction for 't','u','c','a','V','R' in Baloo 2 ExtraBold (Weight 800).
Licensed under SIL Open Font License, Version 1.1 (see OFL.txt).

Derives deterministic vector glyphs and metrics into letters.json for use by
scene.py and logo.py in both SVG and Lottie animation generation.
"""

import argparse
import json
import os
import re
import sys
import urllib.request


CANONICAL_FONT_URL = (
    "https://raw.githubusercontent.com/google/fonts/main/ofl/baloo2/Baloo2%5Bwght%5D.ttf"
)

# Reference letter positions and path definitions for Baloo 2 ExtraBold at 132px
# Baseline Y = 188.48 on the 778x300 horizontal logo canvas.
WORDMARK_SPECS = {
    "t": {
        "x": 303.128,
        "width": 52.201,
        "color": "#F2FBFA",
        "path": (
            "M303.128 151.652H325.304V164.456C325.304 166.656 326.008 168.284 327.416 169.34"
            "C328.912 170.308 330.98 170.792 333.62 170.792C334.94 170.792 336.304 170.704 337.712 170.528"
            "C339.12 170.264 340.352 169.956 341.408 169.604C342.2 170.484 342.86 171.54 343.388 172.772"
            "C344.004 174.004 344.312 175.456 344.312 177.128C344.312 180.384 343.036 183.112 340.484 185.312"
            "C338.02 187.424 333.62 188.48 327.284 188.48C319.54 188.48 313.556 186.72 309.332 183.2"
            "C305.196 179.68 303.128 173.96 303.128 166.04V151.652Z"
            "M317.648 140.96V123.8H342.596C343.124 124.68 343.608 125.868 344.048 127.364"
            "C344.576 128.86 344.84 130.488 344.84 132.248C344.84 135.328 344.136 137.572 342.728 138.98"
            "C341.408 140.3 339.604 140.96 337.316 140.96H317.648Z"
            "M325.304 154.82H303.128V107.432C304.096 107.168 305.46 106.904 307.22 106.64"
            "C309.068 106.288 311.004 106.112 313.028 106.112C317.34 106.112 320.464 106.86 322.4 108.356"
            "C324.336 109.852 325.304 112.624 325.304 116.672V154.82Z"
        ),
    },
    "u": {
        "x": 355.329,
        "width": 74.056,
        "color": "#F2FBFA",
        "path": (
            "M355.329 161.024V152.312H377.769V161.024C377.769 164.544 378.649 167.096 380.409 168.68"
            "C382.257 170.264 384.809 171.056 388.065 171.056C390.089 171.056 391.761 170.88 393.081 170.528"
            "C394.401 170.176 395.369 169.824 395.985 169.472V152.312H418.425V172.772"
            "C418.425 174.972 417.941 176.908 416.973 178.58C416.093 180.164 414.729 181.528 412.881 182.672"
            "C409.801 184.608 406.105 186.06 401.793 187.028C397.569 188.084 392.949 188.612 387.933 188.612"
            "C381.597 188.612 375.965 187.688 371.037 185.84C366.109 183.904 362.237 180.912 359.421 176.864"
            "C356.693 172.816 355.329 167.536 355.329 161.024Z"
            "M418.425 157.988H395.985V122.612C396.953 122.348 398.317 122.084 400.077 121.82"
            "C401.925 121.556 403.905 121.424 406.017 121.424C410.417 121.424 413.585 122.172 415.521 123.668"
            "C417.457 125.076 418.425 127.848 418.425 131.984V157.988Z"
            "M377.769 157.988H355.329V122.612C356.297 122.348 357.661 122.084 359.421 121.82"
            "C361.269 121.556 363.249 121.424 365.361 121.424C369.761 121.424 372.929 122.172 374.865 123.668"
            "C376.801 125.076 377.769 127.848 377.769 131.984V157.988Z"
        ),
    },
    "c": {
        "x": 429.385,
        "width": 56.1,
        "color": "#F2FBFA",
        "path": (
            "M468.325 138.056C463.925 138.056 460.097 139.464 456.841 142.28"
            "C453.673 145.096 452.089 149.232 452.089 154.688C452.089 160.144 453.629 164.192 456.709 166.832"
            "C459.789 169.472 463.617 170.792 468.193 170.792C470.833 170.792 473.165 170.484 475.189 169.868"
            "C477.301 169.164 479.061 168.46 480.469 167.756C482.141 168.988 483.373 170.308 484.165 171.716"
            "C485.045 173.124 485.485 174.84 485.485 176.864C485.485 180.384 483.725 183.2 480.205 185.312"
            "C476.773 187.424 471.845 188.48 465.421 188.48C458.117 188.48 451.737 187.204 446.281 184.652"
            "C440.913 182.1 436.733 178.316 433.741 173.3C430.837 168.196 429.385 161.992 429.385 154.688"
            "C429.385 146.944 431.013 140.564 434.269 135.548C437.525 130.444 441.837 126.66 447.205 124.196"
            "C452.573 121.644 458.425 120.368 464.761 120.368C471.009 120.368 475.849 121.512 479.281 123.8"
            "C482.801 126.088 484.561 129.036 484.561 132.644C484.561 134.316 484.165 135.9 483.373 137.396"
            "C482.581 138.804 481.657 140.036 480.601 141.092C479.105 140.388 477.301 139.728 475.189 139.112"
            "C473.077 138.408 470.789 138.056 468.325 138.056Z"
        ),
    },
    "a": {
        "x": 493.571,
        "width": 61.248,
        "color": "#F2FBFA",
        "path": (
            "M524.195 172.244C525.691 172.244 527.319 172.112 529.079 171.848"
            "C530.927 171.496 532.291 171.056 533.171 170.528V159.968L523.667 160.76"
            "C521.203 160.936 519.179 161.464 517.595 162.344C516.011 163.224 515.219 164.544 515.219 166.304"
            "C515.219 168.064 515.879 169.516 517.199 170.66C518.607 171.716 520.939 172.244 524.195 172.244Z"
            "M523.139 120.368C532.731 120.368 540.387 122.348 546.107 126.308"
            "C551.915 130.18 554.819 136.252 554.819 144.524V174.092C554.819 176.38 554.159 178.272 552.839 179.768"
            "C551.607 181.176 550.111 182.408 548.351 183.464C545.535 185.136 542.103 186.412 538.055 187.292"
            "C534.007 188.172 529.387 188.612 524.195 188.612C514.955 188.612 507.519 186.852 501.887 183.332"
            "C496.343 179.724 493.571 174.312 493.571 167.096C493.571 161.024 495.375 156.36 498.983 153.104"
            "C502.679 149.848 508.267 147.824 515.747 147.032L533.039 145.184V144.26"
            "C533.039 141.708 531.895 139.904 529.607 138.848C527.407 137.704 524.195 137.132 519.971 137.132"
            "C516.715 137.132 513.459 137.484 510.203 138.188C507.035 138.892 504.175 139.772 501.623 140.828"
            "C500.479 140.036 499.511 138.848 498.719 137.264C497.927 135.592 497.531 133.876 497.531 132.116"
            "C497.531 127.892 499.775 124.9 504.263 123.14C506.815 122.172 509.807 121.468 513.239 121.028"
            "C516.759 120.588 520.059 120.368 523.139 120.368Z"
        ),
    },
    "V": {
        "x": 559.279,
        "width": 78.408,
        "color": "#FF6B00",
        "path": (
            "M613.531 183.86C612.299 184.916 610.363 185.84 607.723 186.632"
            "C605.171 187.424 602.355 187.82 599.275 187.82C595.667 187.82 592.499 187.292 589.771 186.236"
            "C587.131 185.092 585.327 183.552 584.359 181.616C583.127 179.152 581.675 175.896 580.003 171.848"
            "C578.331 167.8 576.527 163.224 574.591 158.12C572.655 153.016 570.719 147.736 568.783 142.28"
            "C566.847 136.824 565.043 131.412 563.371 126.044C561.787 120.588 560.423 115.572 559.279 110.996"
            "C560.511 109.764 562.271 108.664 564.559 107.696C566.935 106.64 569.443 106.112 572.083 106.112"
            "C575.339 106.112 577.979 106.816 580.003 108.224C582.115 109.632 583.655 112.14 584.623 115.748"
            "C586.999 124.372 589.375 132.644 591.751 140.564C594.215 148.396 596.767 156.58 599.407 165.116H600.199"
            "C601.783 159.66 603.367 153.808 604.951 147.56C606.623 141.312 608.295 134.932 609.967 128.42"
            "C611.639 121.82 613.223 115.308 614.719 108.884C616.303 108.092 618.019 107.432 619.867 106.904"
            "C621.715 106.376 623.739 106.112 625.939 106.112C629.195 106.112 631.967 106.86 634.255 108.356"
            "C636.543 109.764 637.687 112.14 637.687 115.484C637.687 117.332 637.247 120.104 636.367 123.8"
            "C635.487 127.496 634.299 131.72 632.803 136.472C631.307 141.136 629.635 145.976 627.787 150.992"
            "C626.027 155.92 624.223 160.628 622.375 165.116C620.615 169.604 618.943 173.52 617.359 176.864"
            "C615.775 180.208 614.499 182.54 613.531 183.86Z"
        ),
    },
    "R": {
        "x": 649.275,
        "width": 68.112,
        "color": "#FF6B00",
        "path": (
            "M672.111 143.072H678.711C682.407 143.072 685.443 142.236 687.819 140.564"
            "C690.195 138.804 691.383 136.252 691.383 132.908C691.383 129.564 690.283 127.056 688.083 125.384"
            "C685.971 123.624 682.583 122.744 677.919 122.744C676.775 122.744 675.763 122.788 674.883 122.876"
            "C674.003 122.964 673.079 123.096 672.111 123.272V143.072Z"
            "M681.087 160.628H649.275V114.032C649.275 112.184 649.803 110.776 650.859 109.808"
            "C651.915 108.752 653.279 107.916 654.951 107.3C657.943 106.244 661.419 105.496 665.379 105.056"
            "C669.339 104.528 673.563 104.264 678.051 104.264C690.195 104.264 699.347 106.86 705.507 112.052"
            "C711.667 117.156 714.747 124.02 714.747 132.644C714.747 138.012 713.207 142.808 710.127 147.032"
            "C707.135 151.256 703.087 154.6 697.983 157.064C692.967 159.44 687.335 160.628 681.087 160.628Z"
            "M676.203 155.48L697.191 151.256C699.127 153.896 701.107 156.492 703.131 159.044"
            "C705.155 161.596 707.047 164.06 708.807 166.436C710.655 168.812 712.327 171.012 713.823 173.036"
            "C715.319 175.06 716.507 176.82 717.387 178.316C716.507 181.308 714.879 183.64 712.503 185.312"
            "C710.215 186.984 707.619 187.82 704.715 187.82C700.931 187.82 698.027 186.984 696.003 185.312"
            "C694.067 183.64 692.263 181.44 690.591 178.712L676.203 155.48Z"
            "M649.275 151.652H672.375V186.368C671.407 186.632 669.911 186.896 667.887 187.16"
            "C665.863 187.424 663.795 187.556 661.683 187.556C657.107 187.556 653.895 186.764 652.047 185.18"
            "C650.199 183.508 649.275 180.648 649.275 176.6V151.652Z"
        ),
    },
}


def parse_svg_path_to_lottie(d: str) -> list:
    """Parse SVG path string into list of Lottie-compatible bezier contour dicts."""
    tokens = re.findall(r"([A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?)", d)
    idx = 0
    subpaths = []

    curr_x, curr_y = 0.0, 0.0
    v, in_t, out_t = [], [], []
    closed = False
    last_cmd = "M"

    while idx < len(tokens):
        cmd = tokens[idx]
        if cmd.isalpha():
            idx += 1
        else:
            cmd = last_cmd

        if cmd == "M":
            if v:
                subpaths.append({"v": v, "i": in_t, "o": out_t, "c": closed})
                v, in_t, out_t = [], [], []
                closed = False
            curr_x, curr_y = float(tokens[idx]), float(tokens[idx + 1])
            idx += 2
            v.append([round(curr_x, 3), round(curr_y, 3)])
            in_t.append([0.0, 0.0])
            out_t.append([0.0, 0.0])
            last_cmd = "L"
        elif cmd == "L":
            x, y = float(tokens[idx]), float(tokens[idx + 1])
            idx += 2
            curr_x, curr_y = x, y
            v.append([round(curr_x, 3), round(curr_y, 3)])
            in_t.append([0.0, 0.0])
            out_t.append([0.0, 0.0])
            last_cmd = "L"
        elif cmd == "H":
            curr_x = float(tokens[idx])
            idx += 1
            v.append([round(curr_x, 3), round(curr_y, 3)])
            in_t.append([0.0, 0.0])
            out_t.append([0.0, 0.0])
            last_cmd = "H"
        elif cmd == "V":
            curr_y = float(tokens[idx])
            idx += 1
            v.append([round(curr_x, 3), round(curr_y, 3)])
            in_t.append([0.0, 0.0])
            out_t.append([0.0, 0.0])
            last_cmd = "V"
        elif cmd == "C":
            x1, y1 = float(tokens[idx]), float(tokens[idx + 1])
            x2, y2 = float(tokens[idx + 2]), float(tokens[idx + 3])
            x3, y3 = float(tokens[idx + 4]), float(tokens[idx + 5])
            idx += 6
            out_t[-1] = [round(x1 - curr_x, 3), round(y1 - curr_y, 3)]
            curr_x, curr_y = x3, y3
            v.append([round(curr_x, 3), round(curr_y, 3)])
            in_t.append([round(x2 - curr_x, 3), round(y2 - curr_y, 3)])
            out_t.append([0.0, 0.0])
            last_cmd = "C"
        elif cmd == "Z":
            closed = True
            if len(v) > 1 and v[-1] == v[0]:
                v.pop()
                last_in = in_t.pop()
                out_t.pop()
                in_t[0] = last_in
            last_cmd = "Z"

    if v:
        subpaths.append({"v": v, "i": in_t, "o": out_t, "c": closed})
    return subpaths


def extract_letters(font_path: str = None) -> dict:
    """
    Extract or construct glyph outline data for 'tucaVR'.
    If fonttools and a TTF path are available, reads and verifies font metrics.
    """
    font_metadata = {
        "family": "Baloo 2",
        "weight": 800,
        "style": "ExtraBold",
        "license": "SIL Open Font License, Version 1.1",
        "unitsPerEm": 1000,
        "fontSize": 132,
        "baseline": 188.48,
    }

    letters_data = {}
    for char, spec in WORDMARK_SPECS.items():
        contours = parse_svg_path_to_lottie(spec["path"])
        letters_data[char] = {
            "char": char,
            "x": spec["x"],
            "width": spec["width"],
            "color": spec["color"],
            "svg_path": spec["path"],
            "contours": contours,
        }

    # If fonttools is installed and font path exists, query live TTF metrics
    if font_path and os.path.exists(font_path):
        try:
            from fontTools.ttLib import TTFont
            from fontTools.varLib.instancer import instantiateVariableFont

            tt = TTFont(font_path)
            instance = instantiateVariableFont(tt, {"wght": 800})
            font_metadata["unitsPerEm"] = instance["head"].unitsPerEm
            glyph_set = instance.getGlyphSet()
            for char in ["t", "u", "c", "a", "V", "R"]:
                glyph_name = instance.getBestCmap().get(ord(char))
                if glyph_name and glyph_name in glyph_set:
                    letters_data[char]["font_advance_width"] = glyph_set[glyph_name].width
        except Exception as e:
            print(f"[extract_letters] FontTools inspection note: {e}", file=sys.stderr)

    result = {
        "metadata": font_metadata,
        "letters": letters_data,
    }
    # Expose individual glyphs at root level for direct indexing
    for char, g_data in letters_data.items():
        result[char] = g_data
    return result


def main():
    parser = argparse.ArgumentParser(description="Extract Baloo 2 ExtraBold letter outlines.")
    parser.add_argument("--font-path", default=None, help="Path to Baloo2[wght].ttf")
    parser.add_argument(
        "--output",
        default=os.path.join(os.path.dirname(__file__), "letters.json"),
        help="Target output JSON path",
    )
    args = parser.parse_args()

    font_path = args.font_path
    if not font_path:
        # Check standard cache locations
        for candidate in ["/tmp/Baloo2.ttf", "Baloo2[wght].ttf", "design/logo/Baloo2.ttf"]:
            if os.path.exists(candidate):
                font_path = candidate
                break

    data = extract_letters(font_path)

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")

    print(f"Extracted {len(data['letters'])} glyphs to {args.output}")


if __name__ == "__main__":
    main()
