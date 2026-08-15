package com.samarth.calibreios

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samarth.calibreios.reader.TtsDelimiter
import com.samarth.calibreios.settings.AppSettings
import com.samarth.calibreios.settings.ReaderFont
import com.samarth.calibreios.tts.VoiceInfo

/**
 * Appearance and read-aloud settings — seven appearance controls on one screen
 * (#10) plus four for read-aloud (#9, #13).
 *
 * Every change is applied and persisted immediately. There is no Save button:
 * these are all live, reversible preferences whose effect you can see behind
 * the sheet, and a commit step would only add a way to lose a change.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    voices: List<VoiceInfo>,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    val appearance = settings.appearance
    val readAloud = settings.readAloud

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Done") }
        }

        Section("Theme")
        ChipRow {
            AppSettings.Palette.entries.forEach { palette ->
                FilterChip(
                    selected = appearance.mode == AppSettings.Mode.Fixed &&
                        appearance.fixedPalette == palette,
                    onClick = {
                        onChange {
                            it.copy(
                                appearance = it.appearance.copy(
                                    mode = AppSettings.Mode.Fixed,
                                    fixedPalette = palette,
                                )
                            )
                        }
                    },
                    label = { Text(palette.label, fontSize = 12.sp) },
                )
            }
            FilterChip(
                selected = appearance.mode == AppSettings.Mode.Auto,
                onClick = {
                    onChange {
                        it.copy(appearance = it.appearance.copy(mode = AppSettings.Mode.Auto))
                    }
                },
                label = { Text("Auto", fontSize = 12.sp) },
            )
        }

        // Only meaningful in Auto, so only shown in Auto.
        if (appearance.mode == AppSettings.Mode.Auto) {
            Caption("Which palette to use as the system switches")
            PalettePicker("Day", appearance.dayPalette) { chosen ->
                onChange { it.copy(appearance = it.appearance.copy(dayPalette = chosen)) }
            }
            PalettePicker("Night", appearance.nightPalette) { chosen ->
                onChange { it.copy(appearance = it.appearance.copy(nightPalette = chosen)) }
            }
        }

        Section("Font")
        ChipRow {
            ReaderFont.all.forEach { font ->
                FilterChip(
                    selected = appearance.fontId == font.id,
                    onClick = {
                        onChange { it.copy(appearance = it.appearance.copy(fontId = font.id)) }
                    },
                    label = { Text(font.displayName, fontSize = 12.sp) },
                )
            }
        }

        Setting("Text size", "${(appearance.fontScale * 100).toInt()}%") {
            Slider(
                value = appearance.fontScale.toFloat(),
                onValueChange = { v ->
                    onChange {
                        it.copy(appearance = it.appearance.copy(fontScale = v.toDouble()))
                    }
                },
                valueRange = 0.7f..2.0f,
            )
        }

        Setting("Line spacing", format1(appearance.lineHeight)) {
            Slider(
                value = appearance.lineHeight.toFloat(),
                onValueChange = { v ->
                    onChange {
                        it.copy(appearance = it.appearance.copy(lineHeight = v.toDouble()))
                    }
                },
                valueRange = 1.0f..2.4f,
            )
        }

        Setting("Margins", "${appearance.marginPx} pt") {
            Slider(
                value = appearance.marginPx.toFloat(),
                onValueChange = { v ->
                    onChange { it.copy(appearance = it.appearance.copy(marginPx = v.toInt())) }
                },
                valueRange = 0f..80f,
            )
        }

        Toggle("Justify text", appearance.justify) { on ->
            onChange { it.copy(appearance = it.appearance.copy(justify = on)) }
        }
        Toggle("Hyphenate", appearance.hyphenate) { on ->
            onChange { it.copy(appearance = it.appearance.copy(hyphenate = on)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Section("Read aloud")
        Caption("Where the voice breaks, and what it highlights")
        ChipRow {
            TtsDelimiter.presets.forEach { preset ->
                FilterChip(
                    selected = readAloud.delimiterLabel == preset.label,
                    onClick = {
                        onChange {
                            it.copy(readAloud = it.readAloud.copy(delimiterLabel = preset.label))
                        }
                    },
                    label = { Text(preset.label, fontSize = 12.sp) },
                )
            }
        }

        Setting("Hold before speaking", "${readAloud.holdMillis} ms") {
            Slider(
                value = readAloud.holdMillis.toFloat(),
                onValueChange = { v ->
                    onChange { it.copy(readAloud = it.readAloud.copy(holdMillis = v.toLong())) }
                },
                valueRange = 0f..2000f,
            )
        }

        Setting("Speed", "${(readAloud.rate * 200).toInt()}%") {
            Slider(
                value = readAloud.rate,
                onValueChange = { v ->
                    onChange { it.copy(readAloud = it.readAloud.copy(rate = v)) }
                },
                valueRange = 0.25f..0.75f,
            )
        }

        Section("Voice")
        ChipRow {
            FilterChip(
                selected = readAloud.voiceId == null,
                onClick = { onChange { it.copy(readAloud = it.readAloud.copy(voiceId = null)) } },
                label = { Text("System", fontSize = 12.sp) },
            )
            voices.forEach { voice ->
                FilterChip(
                    selected = readAloud.voiceId == voice.identifier,
                    onClick = {
                        onChange {
                            it.copy(readAloud = it.readAloud.copy(voiceId = voice.identifier))
                        }
                    },
                    label = {
                        Text(
                            if (voice.quality == "default") voice.name
                            else "${voice.name} · ${voice.quality}",
                            fontSize = 12.sp,
                        )
                    },
                )
            }
        }
        // #3 established that enhanced voices cannot be installed, or even
        // detected as installable, through any API. All the app can do is say so.
        if (voices.none { it.quality != "default" }) {
            Caption(
                "Only standard voices are installed. Higher-quality voices are a " +
                    "much better listen — add them in Settings › Accessibility › " +
                    "Spoken Content › Voices, then reopen this screen."
            )
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Setting(label: String, value: String, control: @Composable () -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PalettePicker(
    label: String,
    selected: AppSettings.Palette,
    onSelect: (AppSettings.Palette) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
        AppSettings.Palette.entries.forEach { palette ->
            FilterChip(
                selected = selected == palette,
                onClick = { onSelect(palette) },
                label = { Text(palette.label, fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** One decimal place, without depending on a formatter. */
private fun format1(value: Double): String {
    val tenths = (value * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}
