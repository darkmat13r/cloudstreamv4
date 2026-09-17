# Regenerating `:core:strings`' checked-in oracles

Neither tool is part of the build and neither adds a dependency: both read jars that AGP already put in
the Gradle cache. Rerun them when AGP (aapt2 / aaptcompiler) is bumped, and review the diff.

## `android-locale-data.txt` (build-logic `src/main/resources/strings/`)

AOSP's `LocaleDataTables.cpp`, via the Kotlin copy shipped in `com.android.tools.build:aaptcompiler`.

```bash
J=$(find ~/.gradle/caches/modules-2 -name 'aaptcompiler-<agp>-sources.jar' | head -1)
unzip -o "$J" 'com/android/aaptcompiler/android/LocaleDataTables.kt' -d /tmp/lt
python3 core/strings/tools/gen_locale_data.py /tmp/lt/com/android/aaptcompiler/android/LocaleDataTables.kt \
  build-logic/convention/src/main/resources/strings/android-locale-data.txt
```

## `golden-decoded.tsv` (`fixtures/src/jvmTest/resources/`)

An independent decode of `fixtures/res`: aapt2-proto's generated classes + protobuf-java, not the
generator's wire reader, so a reader bug cannot pass `FixtureGoldenDecodeTest`.

```bash
AAPT2=$(find ~/.gradle/caches -type f -name aapt2 -path '*<agp>*' | head -1)
CP=$(find ~/.gradle/caches/modules-2 -name 'protobuf-java-3*.jar' ! -name '*sources*' | sort | tail -1):$(find ~/.gradle/caches/modules-2 -name 'aapt2-proto-<agp>*.jar' ! -name '*sources*' | head -1)
# aapt2 rejects stray text, which the generator's sanitiser removes: compile a copy without it
# (today: the trailing `"` after k_everywhere in fixtures/res/values/strings.xml).
cp -R core/strings/fixtures/res /tmp/fixtures-res   # then delete the stray text in the copy
"$AAPT2" compile --legacy --dir /tmp/fixtures-res -o /tmp/compiled.zip
javac -d /tmp/golden -cp "$CP" core/strings/tools/GoldenDump.java
java -cp /tmp/golden:"$CP" GoldenDump /tmp/compiled.zip > /tmp/golden.tsv
```

Compare `/tmp/golden.tsv` with the rows of `golden-decoded.tsv` after sorting both (`LC_ALL=C sort`); keep
the committed header and hand-review any changed value before replacing rows.
