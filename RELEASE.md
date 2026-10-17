# Making a release

1. Raise the version in `AndroidManifest.xml`: `versionCode` by one and
   `versionName` to the new number.
2. Add a section for the version to `CHANGELOG.md`.
3. Build:

   ```sh
   export ANDROID_JAR=/path/to/android.jar
   export KEYSTORE=/path/to/your.keystore KEY_ALIAS=yourkey KS_PASS=yourpassword
   ./build.sh
   ```

4. Check the signature. The certificate digest must be the same as in every
   earlier release, or phones will refuse the update:

   ```sh
   apksigner verify --print-certs out/amalgam.apk
   ```

5. Name the package after the version, for example `amalgam-0.1.apk`.
6. Commit and push. On the repository page, draft a new release with a new
   tag such as `v0.1`, paste the version's section of the changelog, and
   attach the APK.
7. The project page, when there is one, is the `docs` folder. Once, in the
   repository settings, set the pages source to the `docs` folder of the
   main branch.

The signing key never goes into the repository: `.gitignore` keeps keystores
and packages out. Keep a copy of the key somewhere safe; without it there are
no updates, only new installs.
