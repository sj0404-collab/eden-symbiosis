# Compile stubs for the panel app

Minimal stand-ins for the Android and AndroidX classes `MainActivity` touches,
so the file can be type-checked with a plain `kotlinc` and no Android SDK.

## Why they exist

The first Panel APK build failed on CI after four minutes with:

```
MainActivity.kt:209:23 Unresolved reference: visibility
```

The field was called `error`. Inside `apply { ... }` on a `Button`, the bare
name `error` does not resolve to the activity's field: `TextView` has
`getError()`/`setError()`, so the receiver wins and the expression becomes a
`CharSequence?`, which has no `visibility`. Renaming the field to `errorView`
fixed it.

That is a scoping mistake no amount of reading catches reliably, and paying
four minutes of CI to find it is the wrong loop. These stubs make the same
error appear locally in about ten seconds.

`TextView.error` in `android_widget.kt` is load-bearing for exactly this
reason: without it the stubs compile the broken code happily and prove
nothing. There is a check below that keeps it honest.

## Running

```sh
kotlinc panel-app/stubs/*.kt \
        panel-app/app/src/main/java/dev/symbiosis/panel/MainActivity.kt \
        -d /tmp/out
```

No output means it compiles.

## Keeping the harness honest

A harness that cannot fail is worse than none, so verify it still catches the
original bug before trusting it:

```sh
cp panel-app/app/src/main/java/dev/symbiosis/panel/MainActivity.kt /tmp/keep.kt
sed -i 's/errorView/error/g' panel-app/app/src/main/java/dev/symbiosis/panel/MainActivity.kt
kotlinc panel-app/stubs/*.kt panel-app/app/src/main/java/dev/symbiosis/panel/MainActivity.kt -d /tmp/out
# expected: unresolved reference 'visibility'
cp /tmp/keep.kt panel-app/app/src/main/java/dev/symbiosis/panel/MainActivity.kt
```

## What these are not

Not a substitute for the real build. Signatures are approximate and only cover
what this one file calls; resources, the manifest and packaging are still only
checked by Gradle on CI. The value here is catching name-resolution errors in
seconds instead of minutes.
