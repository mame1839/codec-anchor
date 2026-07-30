# Codec Anchor

An Xposed module that stores a Bluetooth codec and audio quality per pair of earphones and applies it
when those earphones connect.

[日本語](README.md)

## What it does

- Stores a codec, sample rate, bit depth and bitrate per pair of earphones
- Applies what was stored when those earphones connect

## Requirements

- Android 12 or later
- An Xposed framework such as [Vector](https://github.com/JingMatrix/Vector)

## Getting started

1. Download the APK from [Releases](https://github.com/mame1839/codec-anchor/releases) and install it
2. Enable Codec Anchor in your Xposed framework and add Bluetooth to its scope
3. Turn Bluetooth off and on again, or reboot
4. Open the app, pick a pair of paired earphones and set it up

Settings take effect as soon as they are saved. Anything already connected is switched right away.

## Details

### When it applies

Once a connection is detected, the settings are applied after the configured wait. The same happens when
the device used for playback changes. Afterwards the values are read back and compared, and applied again
if they differ. The wait, the number of retries and the interval between them are set per pair of
earphones.

### What can be set

Four items: codec, sample rate, bit depth and bitrate. Each can be left unchanged independently, so it is
possible to pin only the codec and leave the rest to the system. The choices are built from what the phone
and the earphones support. While nothing is connected, all candidates are listed.

### Changes made by other apps

When the system or another app changes the codec, it is set back to the stored value. This can be turned
off.

### Force

Combinations the earphones do not advertise are tried as well. When the usual call refuses them, the last
retry addresses the Bluetooth stack directly.

### HD audio

Codecs other than SBC are not selected while HD audio is disabled for the device, so it is enabled before
applying.

### Switching by way of SBC

For devices that refuse to switch. The codec is set to SBC first, then to the one that was chosen.

### Announcements

A short message is shown when the codec changes. The brief states right after connecting are skipped;
only the settled result is shown. If the switch did not take, the current value is shown with it.

### Backup

Settings can be written to a JSON file and read back.

### Languages

18 languages: English, Japanese, Simplified Chinese, Traditional Chinese, Korean, Spanish, German,
French, Italian, Portuguese (Brazil), Russian, Polish, Turkish, Vietnamese, Indonesian, Thai, Arabic
and Hindi. The device language is followed, and anything else falls back to English.

## Building

```
./gradlew assembleDebug
```
