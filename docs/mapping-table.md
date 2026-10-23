# Mapping function skin -> kode karaoke (final)
Dispatcher: `skin/SkinActionDispatcher.kt`; binding Compose: `skin/SkinBinding.kt`. Nilai skin 0..1 = nilai `ReverbSettings`.

| function | ReverbSettings | ke engine |
|---|---|---|
| mic.volume / mic.gate | micVol / gate | nativeSetMicGain(v*2) / nativeSetGate |
| reverb.wet/dry/width/damp/room | wet/dry/width/damp/roomSize | nativeSetReverbParams |
| reverb.cess | cess | nativeSetCess |
| echo.delay/feedback/mix | echoDelay/echoFeedback/echoMix | nativeSetEchoParams(50+d*550, fb*0.75, mix) |
| eqmic.low/mid/high | eqLow/eqMid/eqHigh | nativeSetEQParams(eqKnobToDb) |
| eqaudio.gain/sub/low/mid/high | gEqGain/gEqSub/gEqLow/gEqMid/gEqHigh | GlobalEqEngine.applySettings |
| reverb/echo/eqmic.toggle | reverbEnabled/echoEnabled/eqEnabled | nativeSet*Enabled |
| eqaudio.toggle | gEqEnabled | simpan, lalu start/stopGlobalEq |
| mic.toggle | (status engine) | nativeStart + pushAllSettingsToEngine / nativeStop + MicStateBus |
| meter.mic / meter.output | - | sqrt(MeanSquare mic) / MusicMeter.readMixRms (fallback keluaran engine), skala dB -60..0 |
| floating.toggle / theme.open / skin.pick | - | callback MainActivity |
| display.status | - | template `{mic|reverb|echo|eqmic|eqaudio:ONOFF}` |

Sinkron: setiap perubahan -> SettingsStore.save + SettingsBus.publish; penerima (UI klasik, floating, skin) hanya menyalin ke tampilan.
