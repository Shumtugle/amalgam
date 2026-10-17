# Amalgam

A slow game for Android about growing a crystal in a black liquid.

Touch the glass and a ripple spreads through something thick and dark. The first touch plants a seed; from it grows a cluster of stepped, hollow, square crystals under a film of oxide that shifts colour as the light moves across it.

The liquid is a vacuum. Where its waves pile up, pairs of quicksilver drops rise out of it and, if nobody looks, sink back as though they had never been. Touching a drop is an observation: the pair becomes real. One drop spirals down past the crystal's horizon and feeds it; the other escapes and is heard. Touching empty liquid observes nothing and unsettles the pairs nearby.

A hopper grows as a frame round nothing: every finished one opens an empty pit. A drop that falls in settles into the next empty pit as a qubit, a small cube of light. Qubits take their colour from the spectrum in the order they arrive, so the cluster reads as a rainbow of its own history.

The crystal breathes, and pairs rise on the breath. An observation made on the breath counts twice and rings a deep bell. As the crystal gains mass, it starts to bend the liquid round itself like a lens.

There are no numbers on the screen. What you have gathered is heard, not read: a low string beneath everything sinks as resonance gathers and thickens as the crystal grows.

The game remembers the crystal when closed, and it grows a little while you are away.

Upgrades, collapse and new materials come later.

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
