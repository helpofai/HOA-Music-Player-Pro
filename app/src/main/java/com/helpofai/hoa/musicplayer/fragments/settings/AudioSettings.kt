/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package com.helpofai.hoa.musicplayer.fragments.settings

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.content.Intent
import android.widget.Toast
import androidx.preference.SeekBarPreference
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.audiofx.AudioEffect
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.preference.Preference
import com.helpofai.hoa.appthemehelper.util.VersionUtils
import com.helpofai.hoa.musicplayer.BLUETOOTH_PLAYBACK
import com.helpofai.hoa.musicplayer.EQUALIZER
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.activities.base.AbsBaseActivity.Companion.BLUETOOTH_PERMISSION_REQUEST
import com.helpofai.hoa.musicplayer.util.NavigationUtil

/**
 * @author Hemanth S (h4h13).
 */

class AudioSettings : AbsSettingsFragment() {
    override fun invalidateSettings() {
        val eqPreference: Preference? = findPreference(EQUALIZER)
        if (!hasEqualizer()) {
            eqPreference?.isEnabled = false
            eqPreference?.summary = resources.getString(R.string.no_equalizer)
        } else {
            eqPreference?.isEnabled = true
        }
        eqPreference?.setOnPreferenceClickListener {
            NavigationUtil.openEqualizer(requireActivity())
            true
        }
        val bluetoothPreference: Preference? = findPreference(BLUETOOTH_PLAYBACK)
        if (VersionUtils.hasS()) {
            bluetoothPreference?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            BLUETOOTH_CONNECT
                        ) != PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            requireActivity(), arrayOf(
                                BLUETOOTH_CONNECT
                            ), BLUETOOTH_PERMISSION_REQUEST
                        )
                    }
                }
                return@setOnPreferenceChangeListener true
            }
        }

        val resetPreference: Preference? = findPreference("restore_default_audio")
        resetPreference?.setOnPreferenceClickListener {
            resetAudioSettings()
            true
        }

        val signaturePreference: Preference? = findPreference("audio_signature")
        signaturePreference?.setOnPreferenceChangeListener { _, newValue ->
            applyAudioSignature(newValue as String)
            true
        }
    }

    private fun applyAudioSignature(signature: String) {
        if (signature == "custom") return

        var width = 100
        var clarity = 0
        var bass = 0
        var reverb = 0

        when (signature) {
            "signature" -> { // Balanced, Warm, Clear
                width = 100
                clarity = 30
                bass = 40
                reverb = 10
            }
            "bass_head" -> { // Heavy Bass
                width = 100
                clarity = 20
                bass = 85
                reverb = 0
            }
            "vocal_air" -> { // Mid-Side emphasis
                width = 120
                clarity = 80
                bass = 20
                reverb = 20
            }
            "live_stadium" -> { // Wide and Ambient
                width = 140
                clarity = 40
                bass = 50
                reverb = 60
            }
            "vinyl_warmth" -> { // Thick and Narrower
                width = 90
                clarity = 10
                bass = 60
                reverb = 30
            }
        }

        // Apply to Persistence (Engine picks this up)
        PreferenceUtil.stereoWidth = width / 100f
        PreferenceUtil.clarity = clarity / 100f
        PreferenceUtil.bassStrength = bass / 100f
        PreferenceUtil.reverbAmount = reverb / 100f

        // Update UI Sliders
        (findPreference("audio_stereo_width") as? SeekBarPreference)?.value = width
        (findPreference("audio_clarity") as? SeekBarPreference)?.value = clarity
        (findPreference("audio_bass_strength") as? SeekBarPreference)?.value = bass
        (findPreference("audio_reverb_amount") as? SeekBarPreference)?.value = reverb
        
        Toast.makeText(requireContext(), "$signature applied", Toast.LENGTH_SHORT).show()
    }

    private fun resetAudioSettings() {
        // Reset Values in PreferenceUtil
        PreferenceUtil.balance = 0f // Center (100)
        PreferenceUtil.stereoWidth = 1f // Normal (100)
        PreferenceUtil.clarity = 0f // Off (0)
        PreferenceUtil.bassStrength = 0f // Off (0)
        PreferenceUtil.reverbAmount = 0f // Off (0)

        // Refresh UI
        (findPreference("audio_balance") as? SeekBarPreference)?.value = 100
        (findPreference("audio_stereo_width") as? SeekBarPreference)?.value = 100
        (findPreference("audio_clarity") as? SeekBarPreference)?.value = 0
        (findPreference("audio_bass_strength") as? SeekBarPreference)?.value = 0
        (findPreference("audio_reverb_amount") as? SeekBarPreference)?.value = 0

        Toast.makeText(requireContext(), R.string.message_updated, Toast.LENGTH_SHORT).show()
    }

    private fun hasEqualizer(): Boolean {
        val effects = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)

        val pm = requireActivity().packageManager
        val ri = pm.resolveActivity(effects, 0)
        return ri != null
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_audio)
    }
}
