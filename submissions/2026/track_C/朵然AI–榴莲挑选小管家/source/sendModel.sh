#!/bin/bash
adb shell "mkdir -p /sdcard/Android/data/com.winter.durianai/files/models"
adb push ./gemma-4-e2b-it.litertlm /sdcard/Android/data/com.winter.durianai/files/models/gemma-4-e2b-it.litertlm
