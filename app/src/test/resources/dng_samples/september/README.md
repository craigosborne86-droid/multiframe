# September ground-truth fixtures

Imported intact with `adb pull /sdcard/DCIM/Multiframe/. app/src/test/resources/dng_samples/september/` on 19 September 2026.

225 original files (97 DNGs, including 93 September DNGs). The bench selects September DNGs only; accompanying phone JPEGs and four August DNGs remain for provenance. AppleDouble `._` entries are ignored. Binary originals are gitignored and never copied into Gradle test resources. `manifest.json` records size and SHA-256 for each original.

Representative fixtures:

| Scene | DNG |
|---|---|
| Backlit tower | MF_20260917_145908_zsl_10f.dng |
| Flower | MF_20260917_145555_zsl_10f.dng |
| Dog | MF_20260917_104934_zsl_10f.dng |
| Street | MF_20260917_145003_zsl_10f.dng |
| High ISO (918) | MF_20260915_154430_sr2x_16f.dng |

Run from the repository root:

```sh
./gradlew :app:testDebugUnitTest --tests '*DngDevelopBench*' -Pmf.out.dir=/absolute/output/directory -Pmf.variants=beforeclip,current
```

The default half-scale render is for tone. Use `-Pmf.scale=full` for detail, or `-Pmf.crop=x,y,w,h` to develop the full-resolution frame before extracting a window from its corrected valid image. The original phone JPEG is historical output, not a pixel-equal target for a new tone pipeline. Shipped DNGs contain merged CFA data, not the 16 original phase-diverse frames, so they cannot validate super-resolution reconstruction detail.
