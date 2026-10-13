import sys
sys.dont_write_bytecode = True
"""
Parametric geometric model for tamarutuca mantis shrimp and ocean wave.
Derives SVG vectors (simbolo.svg, logo_final.svg, splash.svg) and Lottie
motion keyframes (intro.json) from a unified parametric model.
"""

import json
import re

# --- Color Palette Tokens ---
DEEP_TEAL = "#0B4650"
CYAN_TURQUOISE = "#16B3B0"
LIGHT_AQUA = "#63D9D2"
DARK_TEAL = "#11808A"
PALE_CYAN_FOAM = "#D9F6F2"
FLAME_ORANGE = "#FF6B00"
WARM_ORANGE = "#FF8F45"
CRIMSON_RED = "#E2452B"
GOLDEN_YELLOW = "#FFC247"
EMERALD_GREEN = "#2ED3A2"
VOID_BG = "#121212"
PALE_CYAN_TEXT = "#F2FBFA"

# --- Parametric Master Geometry (512x512 Coordinate Space) ---
SYMBOL_ELEMENTS = [
    {'id': 'p_00', 'group': 'fundo', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M256 500C390.757 500 500 390.757 500 256C500 121.243 390.757 12 256 12C121.243 12 12 121.243 12 256C12 390.757 121.243 500 256 500Z'},
    {'id': 'p_01', 'group': 'onda', 'attrs': {'stroke': '#16B3B0', 'stroke-width': '32', 'stroke-linecap': 'round'}, 'd_rest': 'M182.8 457.1C146.013 443.709 113.604 420.483 89.1009 389.951C64.5978 359.419 48.9395 322.751 43.8303 283.937C38.7211 245.124 44.3569 205.652 60.1241 169.819C75.8914 133.986 101.186 103.165 133.254 80.7102C165.323 58.2555 202.937 45.0278 242.001 42.4669C281.066 39.9061 320.085 48.1102 354.81 66.1862C389.536 84.2623 418.638 111.518 438.948 144.986C459.258 178.453 469.998 216.851 470 256'},
    {'id': 'p_02', 'group': 'onda', 'attrs': {'stroke': '#16B3B0', 'stroke-width': '32', 'stroke-linecap': 'round', 'stroke-linejoin': 'round'}, 'd_rest': 'M470 256L468.6 264L466.2 271.6L462.9 278.7L458.7 285.3L453.8 291.1L448.2 296.2L442.1 300.5L435.6 303.8L428.8 306.2L421.8 307.7L414.9 308.2L408 307.8L401.4 306.5L395 304.4L389.2 301.5L383.8 297.9L379.1 293.7L375 289L371.7 283.9L369.1 278.5L367.3 272.9L366.2 267.2L366 261.5L366.5 256L367.7 250.7L369.5 245.7L372 241.1L375 236.9L378.4 233.3L382.2 230.2L386.3 227.8L390.6 225.9L395 224.7L399.5 224.1H403.8L408 224.7L412 225.8L415.7 227.4L419 229.4L421.9 231.8'},
    {'id': 'p_03', 'group': 'onda', 'attrs': {'stroke': '#63D9D2', 'stroke-width': '18', 'stroke-linecap': 'round'}, 'd_rest': 'M90.6 316.2C80.2581 287.783 77.4126 257.181 82.3379 227.344C87.2632 197.508 99.7932 169.443 118.719 145.858C137.646 122.273 162.33 103.962 190.391 92.6907C218.453 81.4199 248.945 77.5696 278.927 81.5111C308.91 85.4526 337.371 97.0529 361.566 115.193C385.761 133.334 404.874 157.402 417.061 185.078C429.249 212.754 434.1 243.103 431.146 273.199C428.193 303.294 417.535 332.121 400.2 356.9'},
    {'id': 'p_04', 'group': 'onda', 'attrs': {'stroke': '#11808A', 'stroke-width': '12', 'stroke-linecap': 'round'}, 'd_rest': 'M133.1 170C147.563 149.333 167.004 132.647 189.625 121.485C212.246 110.323 237.317 105.047 262.518 106.143C287.719 107.24 312.237 114.674 333.803 127.759C355.369 140.843 373.287 159.155 385.9 181'},
    {'id': 'p_05', 'group': 'espuma', 'attrs': {'fill': '#D9F6F2'}, 'd_rest': 'M182.8 481.1C196.055 481.1 206.8 470.355 206.8 457.1C206.8 443.845 196.055 433.1 182.8 433.1C169.545 433.1 158.8 443.845 158.8 457.1C158.8 470.355 169.545 481.1 182.8 481.1Z'},
    {'id': 'p_06', 'group': 'espuma', 'attrs': {'fill': '#D9F6F2'}, 'd_rest': 'M226.2 479.9C232.827 479.9 238.2 474.527 238.2 467.9C238.2 461.273 232.827 455.9 226.2 455.9C219.573 455.9 214.2 461.273 214.2 467.9C214.2 474.527 219.573 479.9 226.2 479.9Z'},
    {'id': 'p_07', 'group': 'espuma', 'attrs': {'fill': '#D9F6F2'}, 'd_rest': 'M133.3 440.3C138.271 440.3 142.3 436.271 142.3 431.3C142.3 426.329 138.271 422.3 133.3 422.3C128.329 422.3 124.3 426.329 124.3 431.3C124.3 436.271 128.329 440.3 133.3 440.3Z'},
    {'id': 'p_08', 'group': 'swimmerets', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M268.14 309.116C271.083 307.744 271.402 302.2 268.854 296.734C266.305 291.268 261.853 287.95 258.91 289.322C255.966 290.695 255.647 296.238 258.195 301.704C260.744 307.17 265.196 310.488 268.14 309.116Z'},
    {'id': 'p_09', 'group': 'swimmerets', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M253.765 313.015C256.392 311.106 255.648 305.604 252.103 300.724C248.558 295.845 243.555 293.437 240.928 295.346C238.3 297.255 239.044 302.758 242.589 307.637C246.134 312.516 251.138 314.924 253.765 313.015Z'},
    {'id': 'p_10', 'group': 'swimmerets', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M240.226 319.659C242.441 317.284 240.661 312.024 236.25 307.911C231.839 303.798 226.468 302.389 224.254 304.764C222.039 307.139 223.819 312.399 228.23 316.512C232.641 320.625 238.012 322.034 240.226 319.659Z'},
    {'id': 'p_11', 'group': 'swimmerets', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M228.222 328.786C229.943 326.032 227.192 321.209 222.077 318.013C216.963 314.817 211.421 314.458 209.701 317.212C207.98 319.966 210.731 324.79 215.845 327.986C220.96 331.182 226.501 331.54 228.222 328.786Z'},
    {'id': 'p_12', 'group': 'swimmerets', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M218.156 340.182C219.319 337.15 215.698 332.94 210.068 330.779C204.438 328.618 198.93 329.323 197.766 332.355C196.602 335.387 200.223 339.597 205.854 341.758C211.484 343.919 216.992 343.214 218.156 340.182Z'},
    {'id': 'p_13', 'group': 'pleopods', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M292.328 290.081C293.023 288.36 292.192 286.402 290.472 285.707C288.751 285.011 286.793 285.843 286.098 287.563L276.028 312.486C275.333 314.206 276.164 316.165 277.885 316.86C279.605 317.555 281.564 316.724 282.259 315.003L292.328 290.081Z'},
    {'id': 'p_14', 'group': 'pleopods', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M307.439 289.553C308.134 287.832 307.303 285.874 305.582 285.179C303.862 284.484 301.904 285.315 301.208 287.036L291.139 311.958C290.444 313.679 291.275 315.637 292.996 316.332C294.716 317.027 296.674 316.196 297.37 314.476L307.439 289.553Z'},
    {'id': 'p_15', 'group': 'pleopods', 'attrs': {'fill': '#E2452B'}, 'd_rest': 'M322.55 289.025C323.245 287.305 322.414 285.346 320.693 284.651C318.973 283.956 317.014 284.787 316.319 286.508L306.25 311.431C305.555 313.151 306.386 315.109 308.106 315.805C309.827 316.5 311.785 315.668 312.48 313.948L322.55 289.025Z'},
    {'id': 'p_16', 'group': 'tail_fan', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M170.766 384.442C178.691 402.242 190.269 414.378 196.626 411.547C202.984 408.717 201.713 391.993 193.787 374.193C185.862 356.393 174.284 344.257 167.927 347.088C161.57 349.918 162.841 366.642 170.766 384.442Z'},
    {'id': 'p_17', 'group': 'tail_fan', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M184.085 406.097C186.538 411.607 192.306 414.39 196.968 412.315C201.63 410.239 203.421 404.09 200.968 398.581C198.515 393.071 192.747 390.287 188.085 392.363C183.423 394.439 181.632 400.588 184.085 406.097Z'},
    {'id': 'p_18', 'group': 'tail_fan', 'attrs': {'fill': '#63D9D2'}, 'd_rest': 'M156.329 381.474C155.649 400.947 160.736 416.929 167.69 417.172C174.645 417.415 180.834 401.826 181.514 382.353C182.194 362.88 177.108 346.898 170.153 346.655C163.198 346.412 157.009 362.001 156.329 381.474Z'},
    {'id': 'p_19', 'group': 'tail_fan', 'attrs': {'fill': '#FF8F45'}, 'd_rest': 'M158.808 406.776C158.597 412.803 162.561 417.833 167.661 418.011C172.761 418.19 177.066 413.448 177.277 407.421C177.487 401.393 173.523 396.363 168.423 396.185C163.323 396.007 159.018 400.748 158.808 406.776Z'},
    {'id': 'p_20', 'group': 'tail_fan', 'attrs': {'fill': '#63D9D2'}, 'd_rest': 'M140.379 365.734C127.341 380.213 120.964 395.726 126.136 400.383C131.307 405.039 146.068 397.076 159.106 382.596C172.144 368.116 178.521 352.603 173.349 347.946C168.178 343.29 153.417 351.254 140.379 365.734Z'},
    {'id': 'p_21', 'group': 'tail_fan', 'attrs': {'fill': '#FF8F45'}, 'd_rest': 'M126.014 386.709C121.978 391.191 121.781 397.592 125.574 401.007C129.366 404.422 135.712 403.556 139.747 399.075C143.783 394.593 143.98 388.191 140.187 384.777C136.395 381.362 130.049 382.227 126.014 386.709Z'},
    {'id': 'p_22', 'group': 'tail_fan', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M137.219 351.337C119.153 358.637 106.621 369.784 109.228 376.236C111.835 382.688 128.593 382.002 146.659 374.703C164.725 367.403 177.257 356.256 174.65 349.804C172.043 343.352 155.285 344.038 137.219 351.337Z'},
    {'id': 'p_23', 'group': 'tail_fan', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M115.113 363.893C109.521 366.152 106.537 371.819 108.449 376.551C110.361 381.282 116.444 383.287 122.035 381.027C127.627 378.768 130.611 373.101 128.699 368.369C126.787 363.638 120.704 361.634 115.113 363.893Z'},
    {'id': 'p_24', 'group': 'tail_fan', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M191.186 353.013L167.334 394.111L155.919 402.182L152.985 393.749L145.016 397.777L142.412 384.041L153.802 337.909L191.186 353.013Z'},
    {'id': 'p_25', 'group': 'tail_fan', 'attrs': {'fill': '#63D9D2'}, 'd_rest': 'M165.871 366.34C165.176 368.061 166.007 370.019 167.727 370.714C169.448 371.409 171.406 370.578 172.101 368.858C172.797 367.137 171.965 365.179 170.245 364.484C168.524 363.788 166.566 364.62 165.871 366.34Z'},
    {'id': 'p_26', 'group': 'tail_fan', 'attrs': {'fill': '#63D9D2'}, 'd_rest': 'M156.525 362.564C155.83 364.285 156.661 366.243 158.381 366.938C160.102 367.633 162.06 366.802 162.755 365.082C163.451 363.361 162.619 361.403 160.899 360.708C159.178 360.012 157.22 360.844 156.525 362.564Z'},
    {'id': 'p_27', 'group': 'abdomen', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M148.308 328.012L146.42 332.685C143.813 339.137 146.93 346.481 153.382 349.088L183.757 361.36C190.209 363.967 197.553 360.85 200.159 354.398L202.047 349.725C204.654 343.273 201.537 335.929 195.085 333.322L164.71 321.05C158.258 318.443 150.915 321.56 148.308 328.012Z'},
    {'id': 'p_28', 'group': 'abdomen', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M151.077 339.097C149.787 338.576 148.318 339.199 147.797 340.489C147.275 341.78 147.899 343.249 149.189 343.77L190.467 360.447C191.758 360.969 193.227 360.345 193.748 359.055C194.269 357.765 193.646 356.296 192.355 355.774L151.077 339.097Z'},
    {'id': 'p_29', 'group': 'abdomen', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M165.179 332.111C165.735 330.734 165.07 329.168 163.694 328.612C162.317 328.056 160.751 328.721 160.195 330.097C159.638 331.473 160.303 333.04 161.68 333.596C163.056 334.152 164.623 333.487 165.179 332.111Z'},
    {'id': 'p_30', 'group': 'abdomen', 'attrs': {'fill': '#FF8F45'}, 'd_rest': 'M161.428 303.045L158.683 307.272C154.893 313.108 156.552 320.912 162.388 324.702L190.568 343.002C196.404 346.792 204.207 345.133 207.997 339.297L210.742 335.07C214.532 329.234 212.874 321.43 207.037 317.64L178.858 299.34C173.022 295.55 165.218 297.209 161.428 303.045Z'},
    {'id': 'p_31', 'group': 'abdomen', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M162.032 314.455C160.865 313.697 159.304 314.028 158.546 315.195C157.788 316.363 158.12 317.923 159.287 318.681L197.329 343.386C198.496 344.144 200.057 343.813 200.815 342.645C201.573 341.478 201.241 339.917 200.074 339.159L162.032 314.455Z'},
    {'id': 'p_32', 'group': 'abdomen', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M177.384 310.402C178.192 309.157 177.838 307.492 176.593 306.684C175.348 305.875 173.684 306.229 172.875 307.474C172.067 308.719 172.42 310.384 173.665 311.192C174.91 312.001 176.575 311.647 177.384 310.402Z'},
    {'id': 'p_33', 'group': 'abdomen', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M179.019 280.924L175.518 284.549C170.684 289.555 170.823 297.532 175.829 302.366L200.603 326.29C205.609 331.124 213.585 330.985 218.419 325.979L221.92 322.353C226.754 317.348 226.615 309.371 221.609 304.537L196.835 280.613C191.829 275.779 183.853 275.918 179.019 280.924Z'},
    {'id': 'p_34', 'group': 'abdomen', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M177.434 292.239C176.433 291.272 174.838 291.3 173.871 292.301C172.904 293.302 172.932 294.898 173.933 295.864L207.167 327.958C208.168 328.924 209.763 328.896 210.73 327.895C211.697 326.894 211.669 325.299 210.668 324.332L177.434 292.239Z'},
    {'id': 'p_35', 'group': 'abdomen', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M193.428 291.336C194.46 290.268 194.43 288.566 193.362 287.535C192.294 286.504 190.592 286.533 189.561 287.601C188.53 288.669 188.56 290.371 189.627 291.402C190.695 292.433 192.397 292.404 193.428 291.336Z'},
    {'id': 'p_36', 'group': 'abdomen', 'attrs': {'fill': '#FF8F45'}, 'd_rest': 'M200.578 262.6L196.449 265.491C190.749 269.482 189.364 277.339 193.355 283.039L213.591 311.939C217.582 317.639 225.439 319.024 231.139 315.033L235.268 312.142C240.968 308.151 242.354 300.294 238.362 294.594L218.126 265.694C214.135 259.994 206.278 258.608 200.578 262.6Z'},
    {'id': 'p_37', 'group': 'abdomen', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M196.863 273.404C196.065 272.264 194.494 271.987 193.354 272.786C192.214 273.584 191.937 275.155 192.735 276.295L219.716 314.828C220.514 315.968 222.086 316.245 223.226 315.447C224.366 314.649 224.643 313.077 223.845 311.937L196.863 273.404Z'},
    {'id': 'p_38', 'group': 'abdomen', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M212.857 275.742C214.073 274.89 214.368 273.214 213.517 271.998C212.665 270.782 210.989 270.487 209.773 271.338C208.557 272.19 208.261 273.866 209.113 275.082C209.964 276.298 211.64 276.593 212.857 275.742Z'},
    {'id': 'p_39', 'group': 'abdomen', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M225.391 248.672L220.786 250.722C214.429 253.552 211.57 261 214.4 267.357L229.092 300.355C231.922 306.712 239.37 309.571 245.727 306.74L250.332 304.69C256.689 301.86 259.548 294.412 256.717 288.055L242.026 255.058C239.196 248.7 231.748 245.841 225.391 248.672Z'},
    {'id': 'p_40', 'group': 'abdomen', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M219.683 258.569C219.117 257.298 217.627 256.726 216.356 257.292C215.084 257.858 214.512 259.348 215.078 260.619L234.553 304.36C235.119 305.631 236.609 306.203 237.88 305.637C239.151 305.071 239.723 303.581 239.157 302.31L219.683 258.569Z'},
    {'id': 'p_41', 'group': 'abdomen', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M235.021 264.107C236.377 263.503 236.987 261.914 236.384 260.558C235.78 259.202 234.191 258.592 232.835 259.196C231.478 259.8 230.869 261.389 231.472 262.745C232.076 264.101 233.665 264.711 235.021 264.107Z'},
    {'id': 'p_42', 'group': 'abdomen', 'attrs': {'fill': '#FF8F45'}, 'd_rest': 'M252.375 239.722L247.464 240.856C240.684 242.421 236.456 249.187 238.021 255.967L246.336 291.98C247.901 298.761 254.667 302.988 261.447 301.423L266.358 300.289C273.138 298.724 277.366 291.958 275.801 285.178L267.486 249.165C265.921 242.385 259.155 238.157 252.375 239.722Z'},
    {'id': 'p_43', 'group': 'abdomen', 'attrs': {'fill': '#16B3B0'}, 'd_rest': 'M244.883 248.349C244.57 246.992 243.217 246.147 241.861 246.46C240.505 246.773 239.66 248.126 239.973 249.482L250.932 296.954C251.245 298.31 252.598 299.155 253.955 298.842C255.311 298.529 256.156 297.176 255.843 295.82L244.883 248.349Z'},
    {'id': 'p_44', 'group': 'abdomen', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M258.931 256.916C260.377 256.582 261.279 255.139 260.945 253.692C260.611 252.246 259.168 251.344 257.721 251.678C256.275 252.012 255.373 253.455 255.707 254.902C256.041 256.348 257.484 257.25 258.931 256.916Z'},
    {'id': 'p_45', 'group': 'carapace', 'attrs': {'fill': '#FF8F45'}, 'd_rest': 'M293.691 233.482L280.259 233.951C270.986 234.275 263.732 242.055 264.056 251.327L265.228 284.907C265.552 294.18 273.332 301.434 282.604 301.11L296.036 300.641C305.309 300.318 312.564 292.538 312.24 283.265L311.067 249.686C310.743 240.413 302.964 233.159 293.691 233.482Z'},
    {'id': 'p_46', 'group': 'carapace', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M291.836 228.504L354.134 231.372C375.518 233.987 387.662 244.77 390.565 263.72C388.991 282.827 377.629 294.43 356.479 298.531L294.533 305.737C285.799 280.266 284.9 254.522 291.836 228.504Z'},
    {'id': 'p_47', 'group': 'carapace', 'attrs': {'fill': '#FF8F45'}, 'd_rest': 'M295.341 232.584L352.602 235.628C368.35 237.319 378.639 243.124 383.468 253.041L294.483 256.149L295.341 232.584Z'},
    {'id': 'p_48', 'group': 'carapace', 'attrs': {'fill': 'black'}, 'd_rest': 'M297.87 256.871L385.294 257.18L297.87 256.871Z'},
    {'id': 'p_49', 'group': 'carapace', 'attrs': {'stroke': '#16B3B0', 'stroke-width': '4.2', 'stroke-linecap': 'round'}, 'd_rest': 'M297.87 256.871L385.294 257.18'},
    {'id': 'p_50', 'group': 'carapace', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M315.392 277.272C317.711 277.191 319.524 275.246 319.443 272.928C319.362 270.609 317.417 268.796 315.099 268.877C312.781 268.958 310.967 270.903 311.048 273.221C311.129 275.539 313.074 277.353 315.392 277.272Z'},
    {'id': 'p_51', 'group': 'carapace', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M334.125 284.182C335.98 284.117 337.43 282.561 337.366 280.707C337.301 278.852 335.745 277.401 333.89 277.466C332.036 277.531 330.585 279.087 330.65 280.941C330.715 282.796 332.27 284.247 334.125 284.182Z'},
    {'id': 'p_52', 'group': 'carapace', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M350.578 273.942C352.664 273.869 354.296 272.118 354.223 270.032C354.151 267.946 352.4 266.313 350.314 266.386C348.227 266.459 346.595 268.21 346.668 270.296C346.741 272.382 348.491 274.015 350.578 273.942Z'},
    {'id': 'p_53', 'group': 'carapace', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M326.163 248.738C327.786 248.682 329.055 247.32 328.999 245.698C328.942 244.075 327.581 242.805 325.958 242.862C324.335 242.919 323.066 244.28 323.122 245.903C323.179 247.526 324.54 248.795 326.163 248.738Z'},
    {'id': 'p_54', 'group': 'carapace', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M367.749 284.269C369.371 284.212 370.641 282.851 370.584 281.228C370.527 279.605 369.166 278.336 367.543 278.392C365.921 278.449 364.651 279.81 364.708 281.433C364.764 283.056 366.126 284.325 367.749 284.269Z'},
    {'id': 'p_55', 'group': 'carapace', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M387.031 258.8L402.318 263.31L387.383 268.874L387.031 258.8Z'},
    {'id': 'p_56', 'group': 'antennae', 'attrs': {'stroke': '#FFC247', 'stroke-width': '5.04', 'stroke-linecap': 'round'}, 'd_rest': 'M379.905 247.282C400.156 217.437 425.431 203.106 455.731 204.289'},
    {'id': 'p_57', 'group': 'antennae', 'attrs': {'stroke': '#FFC247', 'stroke-width': '5.04', 'stroke-linecap': 'round'}, 'd_rest': 'M383.556 255.56C412.229 242.231 439.554 238.475 465.533 244.292'},
    {'id': 'p_58', 'group': 'antennae', 'attrs': {'fill': '#63D9D2'}, 'd_rest': 'M410.731 245.8C420.624 241.803 427.517 235.773 426.126 232.332C424.736 228.891 415.589 229.342 405.696 233.339C395.803 237.336 388.91 243.366 390.3 246.807C391.69 250.248 400.837 249.797 410.731 245.8Z'},
    {'id': 'p_59', 'group': 'garra', 'attrs': {'fill': 'black'}, 'd_rest': 'M349.529 292.049L383.929 314.382L349.529 292.049Z', 'd_strike': 'M349.529 292.049L396.833 298.803L349.529 292.049Z'},
    {'id': 'p_60', 'group': 'garra', 'attrs': {'stroke': '#E2452B', 'stroke-width': '15.12', 'stroke-linecap': 'round'}, 'd_rest': 'M349.529 292.049L383.929 314.382', 'd_strike': 'M349.529 292.049L396.833 298.803'},
    {'id': 'p_61', 'group': 'garra', 'attrs': {'fill': 'black'}, 'd_rest': 'M383.929 314.382L355.739 325.453L383.929 314.382Z', 'd_strike': 'M396.834 298.803L443.728 293.803L396.834 298.803Z'},
    {'id': 'p_62', 'group': 'garra', 'attrs': {'stroke': '#FF6B00', 'stroke-width': '11.76', 'stroke-linecap': 'round'}, 'd_rest': 'M383.929 314.382L355.739 325.453', 'd_strike': 'M396.834 298.803L443.728 293.803'},
    {'id': 'p_63', 'group': 'garra', 'attrs': {'fill': '#FFC247'}, 'd_rest': 'M387.756 327.697C394.711 327.454 400.152 321.619 399.909 314.665C399.666 307.71 393.831 302.269 386.877 302.512C379.922 302.755 374.481 308.59 374.724 315.544C374.967 322.499 380.802 327.94 387.756 327.697Z', 'd_strike': 'M450.971 308.679C459.317 308.388 465.846 301.386 465.554 293.041C465.263 284.695 458.261 278.166 449.916 278.458C441.57 278.749 435.041 285.751 435.333 294.096C435.624 302.442 442.626 308.971 450.971 308.679Z'},
    {'id': 'p_64', 'group': 'garra', 'attrs': {'fill': '#FF6B00'}, 'd_rest': 'M387.493 320.142C390.274 320.044 392.451 317.711 392.354 314.929C392.256 312.147 389.923 309.971 387.141 310.068C384.359 310.165 382.183 312.499 382.28 315.28C382.377 318.062 384.711 320.239 387.493 320.142Z', 'd_strike': 'M450.649 299.445C453.894 299.331 456.433 296.609 456.32 293.363C456.207 290.118 453.484 287.579 450.238 287.692C446.993 287.805 444.454 290.528 444.567 293.774C444.68 297.019 447.403 299.558 450.649 299.445Z'},
    {'id': 'p_65', 'group': 'olho_esq', 'attrs': {'fill': 'black'}, 'd_rest': 'M354.31 236.409L359.795 200.916L354.31 236.409Z'},
    {'id': 'p_66', 'group': 'olho_esq', 'attrs': {'stroke': '#FF8F45', 'stroke-width': '9.24', 'stroke-linecap': 'round'}, 'd_rest': 'M354.31 236.409L359.795 200.916'},
    {'id': 'p_67', 'group': 'olho_esq', 'attrs': {'fill': '#2ED3A2'}, 'd_rest': 'M364.327 204.26C373.918 200.77 379.506 191.932 376.808 184.521C374.111 177.11 364.15 173.932 354.559 177.423C344.968 180.914 339.38 189.751 342.077 197.162C344.775 204.573 354.736 207.751 364.327 204.26Z'},
    {'id': 'p_68', 'group': 'olho_esq', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M375.372 180.574L340.641 193.216L343.514 201.109L378.245 188.468L375.372 180.574Z'},
    {'id': 'p_69', 'group': 'olho_esq', 'attrs': {'fill': '#63D9D2'}, 'd_rest': 'M374.885 184.149L343.311 195.64L344.001 197.535L375.574 186.043L374.885 184.149Z'},
    {'id': 'p_70', 'group': 'olho_esq', 'attrs': {'fill': '#D9F6F2'}, 'd_rest': 'M364.593 182.888C365.988 182.381 366.708 180.838 366.2 179.443C365.692 178.048 364.15 177.329 362.755 177.837C361.36 178.344 360.64 179.887 361.148 181.282C361.656 182.677 363.198 183.396 364.593 182.888Z'},
    {'id': 'p_71', 'group': 'olho_dir', 'attrs': {'fill': 'black'}, 'd_rest': 'M366.121 237.677L385.214 206.752L366.121 237.677Z'},
    {'id': 'p_72', 'group': 'olho_dir', 'attrs': {'stroke': '#FF8F45', 'stroke-width': '9.24', 'stroke-linecap': 'round'}, 'd_rest': 'M366.121 237.677L385.214 206.752'},
    {'id': 'p_73', 'group': 'olho_dir', 'attrs': {'fill': '#2ED3A2'}, 'd_rest': 'M382.382 210.741C392.434 212.513 401.692 207.654 403.061 199.887C404.431 192.12 397.393 184.387 387.342 182.615C377.291 180.843 368.032 185.702 366.663 193.469C365.293 201.236 372.331 208.969 382.382 210.741Z'},
    {'id': 'p_74', 'group': 'olho_dir', 'attrs': {'fill': '#0B4650'}, 'd_rest': 'M403.791 195.751L367.392 189.333L365.934 197.605L402.332 204.023L403.791 195.751Z'},
    {'id': 'p_75', 'group': 'olho_dir', 'attrs': {'fill': '#63D9D2'}, 'd_rest': 'M401.582 198.603L368.492 192.768L368.142 194.754L401.232 200.588L401.582 198.603Z'},
    {'id': 'p_76', 'group': 'olho_dir', 'attrs': {'fill': '#D9F6F2'}, 'd_rest': 'M393.299 192.366C394.761 192.623 396.155 191.647 396.413 190.185C396.671 188.723 395.695 187.329 394.233 187.071C392.771 186.814 391.377 187.79 391.119 189.252C390.861 190.714 391.837 192.108 393.299 192.366Z'},
]


def hex_to_rgb(hex_str: str) -> list:
    if hex_str == "black":
        return [0.0, 0.0, 0.0, 1.0]
    if hex_str == "white":
        return [1.0, 1.0, 1.0, 1.0]
    hex_clean = hex_str.lstrip("#")
    if len(hex_clean) == 3:
        hex_clean = "".join(c * 2 for c in hex_clean)
    r = int(hex_clean[0:2], 16) / 255.0
    g = int(hex_clean[2:4], 16) / 255.0
    b = int(hex_clean[4:6], 16) / 255.0
    return [round(r, 4), round(g, 4), round(b, 4), 1.0]


def parse_svg_path(d: str) -> list:
    """Parse SVG path string into list of Lottie bezier contours {v, i, o, c}."""
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


def transform_svg_d(d: str, scale: float, tx: float, ty: float) -> str:
    tokens = re.findall(r"([A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?)", d)
    idx = 0
    out = []
    last_cmd = "M"
    while idx < len(tokens):
        cmd = tokens[idx]
        if cmd.isalpha():
            idx += 1
        else:
            cmd = last_cmd
        if cmd in ("M", "L"):
            x = float(tokens[idx]) * scale + tx
            y = float(tokens[idx + 1]) * scale + ty
            idx += 2
            out.append(f"{cmd}{x:.4f}".rstrip("0").rstrip(".") + f" {y:.4f}".rstrip("0").rstrip("."))
            last_cmd = "L"
        elif cmd == "H":
            x = float(tokens[idx]) * scale + tx
            idx += 1
            out.append(f"H{x:.4f}".rstrip("0").rstrip("."))
            last_cmd = "H"
        elif cmd == "V":
            y = float(tokens[idx]) * scale + ty
            idx += 1
            out.append(f"V{y:.4f}".rstrip("0").rstrip("."))
            last_cmd = "V"
        elif cmd == "C":
            x1 = float(tokens[idx]) * scale + tx
            y1 = float(tokens[idx + 1]) * scale + ty
            x2 = float(tokens[idx + 2]) * scale + tx
            y2 = float(tokens[idx + 3]) * scale + ty
            x3 = float(tokens[idx + 4]) * scale + tx
            y3 = float(tokens[idx + 5]) * scale + ty
            idx += 6
            c_str = (
                f"C{x1:.4f}".rstrip("0").rstrip(".")
                + f" {y1:.4f}".rstrip("0").rstrip(".")
                + f" {x2:.4f}".rstrip("0").rstrip(".")
                + f" {y2:.4f}".rstrip("0").rstrip(".")
                + f" {x3:.4f}".rstrip("0").rstrip(".")
                + f" {y3:.4f}".rstrip("0").rstrip(".")
            )
            out.append(c_str)
            last_cmd = "C"
        elif cmd == "Z":
            out.append("Z")
            last_cmd = "Z"
    return "".join(out)


def build_simbolo_svg(pose: str = "rest") -> str:
    lines = [
        "<svg width=\"512\" height=\"512\" viewBox=\"0 0 512 512\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">"
    ]
    for elem in SYMBOL_ELEMENTS:
        d = elem.get("d_strike") if pose == "strike" and "d_strike" in elem else elem["d_rest"]
        attrs = elem["attrs"]
        attr_strs = []
        for k, v in attrs.items():
            attr_strs.append(f"{k}=\"{v}\"")
        attr_joined = " ".join(attr_strs)
        if attr_joined:
            lines.append(f"<path d=\"{d}\" {attr_joined}/>")
        else:
            lines.append(f"<path d=\"{d}\"/>")
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def build_logo_final_svg(letters_data: dict) -> str:
    lines = [
        "<svg width=\"778\" height=\"300\" viewBox=\"0 0 778 300\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">",
        "<rect width=\"778\" height=\"300\" rx=\"28\" fill=\"#121212\"/>",
    ]
    scale = 220.0 / 512.0
    tx = 44.0
    ty = 40.0

    for elem in SYMBOL_ELEMENTS:
        d = transform_svg_d(elem["d_rest"], scale, tx, ty)
        attrs = dict(elem["attrs"])
        if "stroke-width" in attrs:
            sw = float(attrs["stroke-width"]) * scale
            attrs["stroke-width"] = f"{sw:.4f}".rstrip("0").rstrip(".")
        attr_strs = [f"{k}=\"{v}\"" for k, v in attrs.items()]
        attr_joined = " ".join(attr_strs)
        if attr_joined:
            lines.append(f"<path d=\"{d}\" {attr_joined}/>")
        else:
            lines.append(f"<path d=\"{d}\"/>")

    # Wordmark text paths
    tuca_path = "".join(letters_data["letters"][c]["svg_path"] for c in ["t", "u", "c", "a"])
    vr_path = "".join(letters_data["letters"][c]["svg_path"] for c in ["V", "R"])
    lines.append(f"<path d=\"{tuca_path}\" fill=\"#F2FBFA\"/>")
    lines.append(f"<path d=\"{vr_path}\" fill=\"#FF6B00\"/>")
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def build_splash_svg(letters_data: dict) -> str:
    lines = [
        "<svg width=\"1400\" height=\"560\" viewBox=\"0 0 1400 560\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">",
    ]
    # Center 778x300 horizontal logo in 1400x560 canvas
    # tx = (1400 - 778)/2 = 311, ty = (560 - 300)/2 = 130
    # No background: the Horizon OS compositor shows an opaque #121212 plate as a
    # grey box, and the intro that follows has a transparent background too.
    lines.append("<g transform=\"translate(311, 130)\">")

    scale = 220.0 / 512.0
    tx = 44.0
    ty = 40.0
    for elem in SYMBOL_ELEMENTS:
        d = transform_svg_d(elem["d_rest"], scale, tx, ty)
        attrs = dict(elem["attrs"])
        if "stroke-width" in attrs:
            sw = float(attrs["stroke-width"]) * scale
            attrs["stroke-width"] = f"{sw:.4f}".rstrip("0").rstrip(".")
        attr_strs = [f"{k}=\"{v}\"" for k, v in attrs.items()]
        attr_joined = " ".join(attr_strs)
        if attr_joined:
            lines.append(f"<path d=\"{d}\" {attr_joined}/>")
        else:
            lines.append(f"<path d=\"{d}\"/>")

    tuca_path = "".join(letters_data["letters"][c]["svg_path"] for c in ["t", "u", "c", "a"])
    vr_path = "".join(letters_data["letters"][c]["svg_path"] for c in ["V", "R"])
    lines.append(f"<path d=\"{tuca_path}\" fill=\"#F2FBFA\"/>")
    lines.append(f"<path d=\"{vr_path}\" fill=\"#FF6B00\"/>")
    lines.append("</g>")
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def make_lottie_val(val, animated=False, keyframes=None):
    if not animated:
        return {"a": 0, "k": val}
    return {"a": 1, "k": keyframes or []}


def make_lottie_shape_item(nm: str, contour: dict):
    return {
        "ty": "sh",
        "nm": nm,
        "ks": {
            "a": 0,
            "k": {
                "c": contour["c"],
                "i": contour["i"],
                "o": contour["o"],
                "v": contour["v"],
            },
        },
    }


def make_lottie_animated_shape_item(nm: str, contour_rest: dict, contour_strike: dict, kf_times=None):
    # Animates between rest and strike
    k_rest = {
        "c": contour_rest["c"],
        "i": contour_rest["i"],
        "o": contour_rest["o"],
        "v": contour_rest["v"],
    }
    k_strike = {
        "c": contour_strike["c"],
        "i": contour_strike["i"],
        "o": contour_strike["o"],
        "v": contour_strike["v"],
    }
    keyframes = [
        {"t": 0, "s": [k_rest], "e": [k_rest]},
        {"t": 54, "s": [k_rest], "e": [k_strike]},
        {"t": 60, "s": [k_strike], "e": [k_strike]},
        {"t": 62, "s": [k_strike], "e": [k_rest]},
        {"t": 68, "s": [k_rest], "e": [k_rest]},
        {"t": 96, "s": [k_rest]},
    ]
    return {
        "ty": "sh",
        "nm": nm,
        "ks": {
            "a": 1,
            "k": keyframes,
        },
    }


def make_lottie_fill(nm: str, hex_color: str, opacity=100.0):
    return {
        "ty": "fl",
        "nm": nm,
        "c": {"a": 0, "k": hex_to_rgb(hex_color)},
        "o": {"a": 0, "k": opacity},
        "r": 1,
    }


def make_lottie_stroke(nm: str, hex_color: str, width: float, linecap="round", linejoin="round", opacity=100.0):
    lc = 2 if linecap == "round" else (3 if linecap == "square" else 1)
    lj = 2 if linejoin == "round" else (3 if linejoin == "bevel" else 1)
    return {
        "ty": "st",
        "nm": nm,
        "c": {"a": 0, "k": hex_to_rgb(hex_color)},
        "o": {"a": 0, "k": opacity},
        "w": {"a": 0, "k": width},
        "lc": lc,
        "lj": lj,
    }


def make_lottie_transform(anchor=[0, 0], pos=[0, 0], scale=[100, 100], rot=0, opacity=100, pos_kf=None, scale_kf=None, op_kf=None):
    tr = {"ty": "tr"}
    tr["a"] = {"a": 0, "k": [anchor[0], anchor[1], 0.0]}
    if pos_kf:
        tr["p"] = {"a": 1, "k": pos_kf}
    else:
        tr["p"] = {"a": 0, "k": [pos[0], pos[1], 0.0]}
    if scale_kf:
        tr["s"] = {"a": 1, "k": scale_kf}
    else:
        tr["s"] = {"a": 0, "k": [scale[0], scale[1], 100.0]}
    tr["r"] = {"a": 0, "k": rot}
    if op_kf:
        tr["o"] = {"a": 1, "k": op_kf}
    else:
        tr["o"] = {"a": 0, "k": opacity}
    return tr


def build_lottie_animation(letters_data: dict) -> dict:
    """
    Build the complete 96-frame (1.6s @ 60fps) Lottie animation JSON at 1400x560.
    Choreography:
    - 0-30 frames: Wave draw-in via trim path & bubble pop
    - 30-54 frames: Mantis body slides in & compound eyes open
    - 54-66 frames: The punch strike & cavitation shockwave
    - 66-90 frames: Symbol slides to left anchor & wordmark tucaVR ascends/fades in
    - 90-96 frames: Static lock matching logo_final.svg and vr_splash.png
    """
    layers = []

    # Layer 1: Wordmark Layer (tucaVR)
    # Rises from Y+30 to Y and fades in 0% to 100% during frames 66-90, then locks
    wm_shapes = []

    # tuca group
    tuca_items = []
    for char in ["t", "u", "c", "a"]:
        ch_data = letters_data["letters"][char]
        for idx, contour in enumerate(ch_data["contours"]):
            tuca_items.append(make_lottie_shape_item(f"{char}_{idx}", contour))
    tuca_items.append(make_lottie_fill("tuca Fill", PALE_CYAN_TEXT))
    tuca_items.append(make_lottie_transform())
    wm_shapes.append({"ty": "gr", "nm": "tuca", "it": tuca_items})

    # VR group
    vr_items = []
    for char in ["V", "R"]:
        ch_data = letters_data["letters"][char]
        for idx, contour in enumerate(ch_data["contours"]):
            vr_items.append(make_lottie_shape_item(f"{char}_{idx}", contour))
    vr_items.append(make_lottie_fill("VR Fill", FLAME_ORANGE))
    vr_items.append(make_lottie_transform())
    wm_shapes.append({"ty": "gr", "nm": "VR", "it": vr_items})

    # Keyframes for Wordmark position: Y+30 -> Y during frames 66-90
    wm_pos_kf = [
        {"t": 0, "s": [311.0, 160.0, 0.0], "e": [311.0, 160.0, 0.0]},
        {"t": 66, "s": [311.0, 160.0, 0.0], "e": [311.0, 130.0, 0.0]},
        {"t": 90, "s": [311.0, 130.0, 0.0], "e": [311.0, 130.0, 0.0]},
        {"t": 96, "s": [311.0, 130.0, 0.0]},
    ]
    # Keyframes for Wordmark opacity: 0% -> 100% during frames 66-90
    wm_op_kf = [
        {"t": 0, "s": [0.0], "e": [0.0]},
        {"t": 66, "s": [0.0], "e": [100.0]},
        {"t": 90, "s": [100.0], "e": [100.0]},
        {"t": 96, "s": [100.0]},
    ]

    wordmark_layer = {
        "ddd": 0,
        "ind": 2,
        "ty": 4,
        "nm": "Wordmark (tucaVR)",
        "sr": 1,
        "st": 0,
        "ip": 0,
        "op": 96,
        "ks": {
            "a": {"a": 0, "k": [0, 0, 0]},
            "p": {"a": 1, "k": wm_pos_kf},
            "s": {"a": 0, "k": [100, 100, 100]},
            "r": {"a": 0, "k": 0},
            "o": {"a": 1, "k": wm_op_kf},
        },
        "shapes": wm_shapes,
    }
    layers.append(wordmark_layer)

    # Layer 2: Cavitation Shockwave Bubble at punch impact (frames 60-66)
    cav_kf_scale = [
        {"t": 60, "s": [20.0, 20.0, 100.0], "e": [220.0, 220.0, 100.0]},
        {"t": 66, "s": [220.0, 220.0, 100.0]},
    ]
    cav_kf_op = [
        {"t": 60, "s": [100.0], "e": [0.0]},
        {"t": 66, "s": [0.0]},
    ]
    # Impact point in 512 space: ~ (451, 293). When scaled by 220/512 and offset to center:
    # At frame 60, center is (700, 280), scale is 42.96875%:
    # X_impact = 700 + (451 - 256) * (220/512) = 700 + 83.8 = 783.8
    # Y_impact = 280 + (293 - 256) * (220/512) = 280 + 15.9 = 295.9
    cav_layer = {
        "ddd": 0,
        "ind": 3,
        "ty": 4,
        "nm": "Cavitation Bubble",
        "sr": 1,
        "st": 60,
        "ip": 60,
        "op": 67,
        "ks": {
            "a": {"a": 0, "k": [0, 0, 0]},
            "p": {"a": 0, "k": [783.8, 295.9, 0]},
            "s": {"a": 1, "k": cav_kf_scale},
            "r": {"a": 0, "k": 0},
            "o": {"a": 1, "k": cav_kf_op},
        },
        "shapes": [
            {
                "ty": "el",
                "nm": "Bubble Oval",
                "p": {"a": 0, "k": [0, 0]},
                "s": {"a": 0, "k": [24, 24]},
            },
            make_lottie_stroke("Bubble Stroke", PALE_CYAN_FOAM, 6.0),
            make_lottie_transform(),
        ],
    }
    layers.append(cav_layer)

    # Layer 3: Main Symbol (Tamarutaca & Wave)
    # Slides from center anchor [700, 280] to left anchor [465, 280] during frames 66-90
    sym_pos_kf = [
        {"t": 0, "s": [700.0, 280.0, 0.0], "e": [700.0, 280.0, 0.0]},
        {"t": 66, "s": [700.0, 280.0, 0.0], "e": [465.0, 280.0, 0.0]},
        {"t": 90, "s": [465.0, 280.0, 0.0], "e": [465.0, 280.0, 0.0]},
        {"t": 96, "s": [465.0, 280.0, 0.0]},
    ]
    # Scale settles to exact 220/512 = 42.96875%
    scale_factor = (220.0 / 512.0) * 100.0
    sym_scale = [scale_factor, scale_factor, 100.0]

    sym_shapes = []

    # 1. Background disk (fundo)
    disk_elem = SYMBOL_ELEMENTS[0]
    disk_contours = parse_svg_path(disk_elem["d_rest"])
    disk_items = [make_lottie_shape_item("Disk Path", disk_contours[0])]
    disk_items.append(make_lottie_fill("Disk Fill", disk_elem["attrs"]["fill"]))
    disk_op_kf = [
        {"t": 0, "s": [0.0], "e": [100.0]},
        {"t": 15, "s": [100.0], "e": [100.0]},
        {"t": 96, "s": [100.0]},
    ]
    disk_items.append(make_lottie_transform(op_kf=disk_op_kf))
    sym_shapes.append({"ty": "gr", "nm": "fundo", "it": disk_items})

    # 2. Wave arcs (onda) with Trim Path
    # Trim path 0% -> 100% on outer arc during frames 0-30
    for idx in [1, 2, 3, 4]:
        elem = SYMBOL_ELEMENTS[idx]
        contours = parse_svg_path(elem["d_rest"])
        wave_items = []
        for c_idx, contour in enumerate(contours):
            wave_items.append(make_lottie_shape_item(f"WaveArc_{idx}_{c_idx}", contour))
        attrs = elem["attrs"]
        sw = float(attrs["stroke-width"])
        wave_items.append(
            make_lottie_stroke(
                "Wave Stroke",
                attrs["stroke"],
                sw,
                attrs.get("stroke-linecap", "round"),
                attrs.get("stroke-linejoin", "round"),
            )
        )
        if idx in [1, 2]:
            # Trim path for outer arc & crest
            trim_end_kf = [
                {"t": 0, "s": [0.0], "e": [100.0]},
                {"t": 30, "s": [100.0], "e": [100.0]},
                {"t": 96, "s": [100.0]},
            ]
            wave_items.append({
                "ty": "tm",
                "nm": "Wave Trim Path",
                "s": {"a": 0, "k": 0.0},
                "e": {"a": 1, "k": trim_end_kf},
                "o": {"a": 0, "k": 0.0},
                "m": 1,
            })
        wave_items.append(make_lottie_transform())
        sym_shapes.append({"ty": "gr", "nm": f"onda_{idx}", "it": wave_items})

    # 3. Foam bubbles (espuma) popping at frames 25-30
    foam_scale_kf = [
        {"t": 0, "s": [0.0, 0.0, 100.0], "e": [0.0, 0.0, 100.0]},
        {"t": 24, "s": [0.0, 0.0, 100.0], "e": [115.0, 115.0, 100.0]},
        {"t": 28, "s": [115.0, 115.0, 100.0], "e": [100.0, 100.0, 100.0]},
        {"t": 30, "s": [100.0, 100.0, 100.0], "e": [100.0, 100.0, 100.0]},
        {"t": 96, "s": [100.0, 100.0, 100.0]},
    ]
    foam_items = []
    for idx in [5, 6, 7]:
        elem = SYMBOL_ELEMENTS[idx]
        contours = parse_svg_path(elem["d_rest"])
        foam_items.append(make_lottie_shape_item(f"Bubble_{idx}", contours[0]))
    foam_items.append(make_lottie_fill("Bubble Fill", PALE_CYAN_FOAM))
    foam_items.append(make_lottie_transform(anchor=[182.8, 457.1], pos=[182.8, 457.1], scale_kf=foam_scale_kf))
    sym_shapes.append({"ty": "gr", "nm": "espuma", "it": foam_items})

    # 4. Mantis shrimp body (tamarutaca) - slides into view frames 30-54
    body_pos_kf = [
        {"t": 0, "s": [-30.0, 20.0, 0.0], "e": [-30.0, 20.0, 0.0]},
        {"t": 30, "s": [-30.0, 20.0, 0.0], "e": [0.0, 0.0, 0.0]},
        {"t": 54, "s": [0.0, 0.0, 0.0], "e": [0.0, 0.0, 0.0]},
        {"t": 96, "s": [0.0, 0.0, 0.0]},
    ]
    body_op_kf = [
        {"t": 0, "s": [0.0], "e": [0.0]},
        {"t": 30, "s": [0.0], "e": [100.0]},
        {"t": 54, "s": [100.0], "e": [100.0]},
        {"t": 96, "s": [100.0]},
    ]

    body_groups = []
    for idx in range(8, 77):
        elem = SYMBOL_ELEMENTS[idx]
        group_name = elem["group"]
        attrs = elem["attrs"]
        
        # Claw paths have rest and strike animations
        if idx in [59, 60, 61, 62, 63, 64]:
            c_rest = parse_svg_path(elem["d_rest"])
            c_strike = parse_svg_path(elem.get("d_strike", elem["d_rest"]))
            if c_rest and c_strike and len(c_rest[0]["v"]) == len(c_strike[0]["v"]):
                item_shape = make_lottie_animated_shape_item(f"Claw_{idx}", c_rest[0], c_strike[0])
            else:
                item_shape = make_lottie_shape_item(f"Claw_{idx}", c_rest[0])
            
            p_items = [item_shape]
            if "fill" in attrs and attrs["fill"] != "none":
                p_items.append(make_lottie_fill(f"Fill_{idx}", attrs["fill"]))
            if "stroke" in attrs:
                sw = float(attrs["stroke-width"])
                p_items.append(make_lottie_stroke(f"Stroke_{idx}", attrs["stroke"], sw, attrs.get("stroke-linecap", "round"), attrs.get("stroke-linejoin", "round")))
            p_items.append(make_lottie_transform())
            body_groups.append({"ty": "gr", "nm": f"elem_{idx}_{group_name}", "it": p_items})
        else:
            contours = parse_svg_path(elem["d_rest"])
            p_items = []
            for c_i, c in enumerate(contours):
                p_items.append(make_lottie_shape_item(f"Path_{idx}_{c_i}", c))
            if "fill" in attrs and attrs["fill"] != "none":
                p_items.append(make_lottie_fill(f"Fill_{idx}", attrs["fill"]))
            if "stroke" in attrs:
                sw = float(attrs["stroke-width"])
                p_items.append(make_lottie_stroke(f"Stroke_{idx}", attrs["stroke"], sw, attrs.get("stroke-linecap", "round"), attrs.get("stroke-linejoin", "round")))
            p_items.append(make_lottie_transform())
            body_groups.append({"ty": "gr", "nm": f"elem_{idx}_{group_name}", "it": p_items})

    body_groups.append(make_lottie_transform(pos_kf=body_pos_kf, op_kf=body_op_kf))
    sym_shapes.append({"ty": "gr", "nm": "tamarutaca", "it": body_groups})

    symbol_layer = {
        "ddd": 0,
        "ind": 4,
        "ty": 4,
        "nm": "Symbol (Mantis & Wave)",
        "sr": 1,
        "st": 0,
        "ip": 0,
        "op": 96,
        "ks": {
            "a": {"a": 0, "k": [256.0, 256.0, 0.0]},
            "p": {"a": 1, "k": sym_pos_kf},
            "s": {"a": 0, "k": sym_scale},
            "r": {"a": 0, "k": 0},
            "o": {"a": 0, "k": 100},
        },
        "shapes": sym_shapes,
    }
    layers.append(symbol_layer)

    return {
        "v": "5.5.2",
        "fr": 60,
        "ip": 0,
        "op": 96,
        "w": 1400,
        "h": 560,
        "nm": "tucaVR Intro",
        "ddd": 0,
        "assets": [],
        "layers": layers,
    }
