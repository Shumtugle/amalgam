# Amalgam

A slow game for Android about growing a crystal in a black liquid.

Touch the glass and a ripple spreads through something thick and dark. The first touch plants a seed; from it grows a cluster of stepped, hollow, square crystals under a film of oxide that shifts colour as the light moves across it. Every touch feeds it. The crystal breathes: strike in time with the breath and the touch resonates, rings a deep bell and feeds the crystal three times as much.

There are no numbers on the screen. What you have gathered is heard, not read: a low string beneath everything grows deeper and thicker as the crystal gains mass.

Version 0.1 is the first playable slice: the liquid, the crystal, the breath, sound and touch feedback. Upgrades, collapse and new materials come later.

## How it is made

- OpenGL ES 3.0. The liquid is a wave equation on a coarse grid, shaded as a black mirror. The crystal is geometry built in code and shaded as a metal under a thin film.
- Every sound is synthesised while the game runs. Every picture except the icon is computed. The package carries no image or sound files.
- Java 8 without lambdas, built without an IDE: see `build.sh`.

## Building

```sh
export ANDROID_JAR=/path/to/android-34.jar
export KEYSTORE=/path/to/your.keystore KEY_ALIAS=yourkey KS_PASS=...
./build.sh
```

Needs `aapt`, `javac`, a dexer (`d8`, `dx` or `dalvik-exchange`), `zipalign` and `apksigner`.

Android 8.0 or newer, a GPU with OpenGL ES 3.0.

## Licence

Zero-Clause BSD, see `LICENSE`.
