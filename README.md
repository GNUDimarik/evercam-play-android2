# Evercam Play [![Build Status](https://travis-ci.org/evercam/evercam-play-android.svg?branch=master)](https://travis-ci.org/evercam/evercam-play-android) [![Stories in Ready](https://badge.waffle.io/evercam/evercam-play-android.png?label=ready&title=Ready)](https://waffle.io/evercam/evercam-play-android)

Evercam Play allows connect your own IP cameras, public webcams and any Android devices that you use as an IP camera. It connects you to Evercam dashboard so that you can see all your cameras on your desktop as well. 

| Name   | Evercam Play  |
| --- | --- |
| Owner   | [@liutingdu](https://github.com/liutingdu)   |
| Version  | 1.4.4 |
| Evercam API Version  | 1.0  |
| Minimum Android version | Android 4.1 - version code 14 | 
| Licence | [AGPL](https://tldrlegal.com/license/gnu-affero-general-public-license-v3-%28agpl-3.0%29) |

## Features

* Support of a huge range of different camera types - business & residential CCTV, home & business WiFi security cameras, public webcams, Android devices.
* Add, edit and remove cameras from your account
* Scan the local network to find cameras to add
* Pre-populates camera details based on vendor
* Portrait and Landscape viewing
* Save snapshots from any camera and share them with your friends & family 
* Homescreen shortcut for single camera live view

## Published App
[![Google Play](http://developer.android.com/images/brand/en_generic_rgb_wo_45.png)](https://play.google.com/store/apps/details?id=io.evercam.androidapp&hl=en)

## Build

1. Checkout from Git:
    ```git clone https://github.com/evercam/evercam-play-android.git```
2. Install GStreamer 1.0 - 1.4.5 or higher for Android
3. Modify evercamPlay/src/main/jni/Android.mk with the installed Gstreamer path for ```GSTREAMER_ROOT_ANDROID```
4. Compile GStreamer - Navigate to evercamPlay/src/main and run ```ndk-build``` (Tested with NDK version r10c)
5. Open the project in Android Studio and run

## Help make it better

The entire Evercam codebase is open source, see details: http://www.evercam.io/open-source

If you have experience with Android SDK and IP cameras, we look forward to your pull requests!

For any bugs and discussions, please use [Github Issues](https://github.com/evercam/evercam-play-android/issues).

Any questions or suggestions around Evercam, drop us a line: http://www.evercam.io/contact






# evercam-play-android2
