# Codec Anchor

An Xposed module that remembers the Bluetooth codec and audio quality you want for each pair of
earphones, and applies it whenever those earphones connect.

[日本語](README.md)

## What it does

- Saves a codec, sample rate, bit depth and bitrate per pair of earphones
- Applies what you saved whenever those earphones connect

## Requirements

- Android 12 or later
- An Xposed framework such as [Vector](https://github.com/JingMatrix/Vector)

## Getting started

1. Download the APK from [Releases](https://github.com/mame1839/codec-anchor/releases) and install it
2. Enable Codec Anchor in your Xposed framework and add Bluetooth to its scope
3. Turn Bluetooth off and on again, or reboot
4. Open the app, pick a pair of paired earphones and set it up

Settings take effect as soon as you save them. Anything already connected is switched right away.

## Details

### When it applies

It watches for connections, changes of the device used for playback, and codec changes. After applying,
it reads the values back to confirm them, and tries again after a pause if they do not match. The wait
before applying, the number of tries and the interval between them can be set per pair of earphones.

### Leaving values unchanged

Codec, sample rate, bit depth and bitrate can each be left unchanged, so you can pin only the codec and
leave the rest to the system.

### Undoing changes made by other apps

If the system or another app changes the codec, it is switched back to what you saved. Turn this off to
let such changes stand.

### Force

Combinations the earphones do not advertise are tried as well. When the usual path refuses them, the
last try goes straight to the Bluetooth stack.

### Turning HD audio on automatically

Codecs other than SBC are never selected while HD audio is switched off for that device, so it is
enabled when needed.

### Switching by way of SBC

For devices that are reluctant to switch, it drops to SBC first and then moves to the codec you picked.

### Available values

Codecs and their combinations are read from what the phone and the earphones support. While nothing is
connected, every candidate can be selected.

### Announcements

A short message appears when the codec switches. Nothing is shown for the brief states right after
connecting — only the result once it settles. If the switch did not take, the message says so and
includes the current state.

### Backup

Settings can be written to a JSON file and read back.

### Languages

English, Japanese, Simplified Chinese, Korean and Spanish, following the language set on the device.

## Building

```
./gradlew assembleDebug
```
