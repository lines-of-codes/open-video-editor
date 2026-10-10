# Open Video Editor

A fork of [Open Video Editor](https://github.com/devhyper/open-video-editor)

This fork intends to keep the app up-to-date and fix bugs, however big shiny 
features likely aren't going to be added (by me, others are welcome to add it)

## Build Instructions

These are instructions to make a debug build, your biggest obstacle will be 
building FFmpeg. These instructions also assume you have basic command-line
knowledge.

First, Clone (or download) this repository

```sh
git clone https://github.com/lines-of-codes/open-video-editor
```

### FFmpegKitNext

Download and extract the latest version of FFmpegKitNext from 
[GitHub Releases](https://github.com/arthenica/ffmpeg-kit-next/releases/latest)
(or clone the repository and do a checkout), make sure the folder is named `ffmpeg-kit-next` and 
placed as a sibling of this repository's folder. You can also create a symbolic link for this 
purpose.

Follow [FFmpegKitNext Android](https://github.com/arthenica/ffmpeg-kit-next/tree/main/android#readme)
for the full and up-to-date instructions, but for the short version:

It is recommended that you install Nix for this, then run:

```sh
./nix-android.sh -p android-r27d --enable-lib-android-media-codec --enable-lib-android-zlib
```

This builds FFmpeg with a minimal set of libraries with a preconfigured environment with Android NDK
r27d. (Beware, This *will* use a large amount of storage)

If you did not listen to the instructions above and named the FFmpegKitNext folder something else or
the location of the maven repository changed in the future, you can change the location in the 
`settings.gradle.kts` file.

FFmpeg is only used for the "Lossless cut" feature, which I'm guessing is for exporting trims with 
as minimal of a change in quality as possible.

### Actually Building

Going back to the root of this project, Run `./gradlew build`

The APK will be available at `app/build/outputs/apk/debug/app-debug.apk`

---

Most of the original README below:

## Features
- Trim
- Grayscale
- Resolution
- Scale
- Rotate

## Translations
https://hosted.weblate.org/engage/open-video-editor

## Roadmap
https://github.com/devhyper/open-video-editor/milestones

## Screenshots
<p float="left">
    <img src="./metadata/en-US/images/phoneScreenshots/1.png" alt="Phone screenshot 1" width="150">
    <img src="./metadata/en-US/images/phoneScreenshots/2.png" alt="Phone screenshot 2" width="150">
    <img src="./metadata/en-US/images/phoneScreenshots/3.png" alt="Phone screenshot 3" width="150">
    <img src="./metadata/en-US/images/phoneScreenshots/4.png" alt="Phone screenshot 4" width="150">
    <img src="./metadata/en-US/images/phoneScreenshots/5.png" alt="Phone screenshot 5" width="150">
    <img src="./metadata/en-US/images/phoneScreenshots/6.png" alt="Phone screenshot 6" width="150">
    <img src="./metadata/en-US/images/phoneScreenshots/7.png" alt="Phone screenshot 7" width="150">
    <img src="./metadata/en-US/images/phoneScreenshots/8.png" alt="Phone screenshot 8" width="150">
</p>
