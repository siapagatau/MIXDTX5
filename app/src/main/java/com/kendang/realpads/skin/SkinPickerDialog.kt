package com.kendang.realpads.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kendang.realpads.CtrlPanelColor
import com.kendang.realpads.PadTextColor
import com.kendang.realpads.PanelColor
import com.kendang.realpads.TextDim
import com.kendang.realpads.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Satu baris daftar: UI klasik, skin bawaan, atau skin repo. [repo] == null untuk dua yang pertama. */
private class SkinRow(
    val id: String, val title: String, val sub: String,
    val repo: SkinRepository.RepoSkin?, val status: SkinRepository.Status?,
)

/**
 * Pemilih skin: UI klasik dan skin dari repo SKIN-MIXER-HP (UNDUH / PERBARUI / PAKAI).
 * Offline: daftar dari cache index / skin terpasang. Skin gagal diunduh/divalidasi -> pilihan lama tetap.
 */
@Composable
fun SkinPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<SkinRow>() }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(SkinManager.selectedId(context)) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        val idx = SkinRepository.fetchIndex(context)
        val list = ArrayList<SkinRow>()
        list.add(SkinRow(SkinManager.DEFAULT_ID, "Mixer DTX (bawaan)", "satu kotak semua kontrol, warna ikut tema", null, null))
        list.add(SkinRow(SkinManager.CLASSIC_ID, "UI Klasik", "tampilan knob tanpa skin (cadangan)", null, null))
        val known = HashSet<String>()
        withContext(Dispatchers.IO) {
            for (s in idx.skins) {
                known.add(s.id)
                list.add(SkinRow(s.id, s.name, "v${s.version} · ${s.author}", s, SkinRepository.status(context, s)))
            }
            // terpasang tapi tidak ada di index (mis. offline tanpa cache index)
            for (s in SkinRepository.installedSkins(context)) if (known.add(s.id))
                list.add(SkinRow(s.id, s.name, "v${s.version} · ${s.author}", s, SkinRepository.Status.INSTALLED))
        }
        rows.clear(); rows.addAll(list)
        message = when {
            idx.error == null -> "${idx.skins.size} skin di repo"
            idx.skins.isNotEmpty() -> "Offline — daftar dari cache (${idx.skins.size} skin)"
            else -> "Offline — hanya skin terpasang"
        }
        loading = false
    }

    fun useId(id: String) {
        scope.launch {
            busyId = id
            val ok = SkinManager.useSkin(context, id)
            busyId = null
            selected = SkinManager.selectedId(context)
            if (ok) onDismiss() else message = "Gagal memakai skin '$id' — pilihan sebelumnya dipertahankan"
        }
    }

    fun install(s: SkinRepository.RepoSkin) {
        scope.launch {
            busyId = s.id
            message = "Mengunduh ${s.name}…"
            val err = SkinRepository.install(context, s)
            busyId = null
            if (err != null) { message = "Gagal: $err"; return@launch }
            useId(s.id)
        }
    }

    AlertDialog(
        // Selama unduh/pakai berjalan, dialog tidak boleh tertutup tak sengaja.
        onDismissRequest = { if (busyId == null) onDismiss() },
        containerColor = PanelColor,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pilih Skin", color = PadTextColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // Kotak 28dp tetap: ikon refresh <-> spinner tidak bergeser.
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(color = ThemeState.uiAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        IconButton(onClick = { if (busyId == null) refreshKey++ }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Refresh, "Muat ulang daftar skin", tint = TextDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { r ->
                        key(r.id) {
                            val isSel = r.id == selected
                            val canUse = r.repo == null || r.status == SkinRepository.Status.INSTALLED
                            val action = when {
                                r.status == SkinRepository.Status.UPDATE -> "UPDATE"
                                !canUse -> "UNDUH"
                                isSel -> null
                                else -> "PAKAI"
                            }
                            val sub = buildString {
                                append(r.sub)
                                if (r.status == SkinRepository.Status.NOT_INSTALLED) append(" · belum diunduh")
                                if (r.status == SkinRepository.Status.UPDATE) append(" · ada pembaruan")
                            }
                            SkinRowItem(
                                title = r.title, subtitle = sub, active = isSel, action = action,
                                busy = busyId == r.id,
                                enabled = busyId == null && (r.repo == null || r.repo.files.isNotEmpty())
                            ) {
                                if (canUse && r.status != SkinRepository.Status.UPDATE) useId(r.id) else install(r.repo!!)
                            }
                        }
                    }
                }
                // Status di BAWAH daftar supaya muncul/hilangnya tidak menggeser baris di atasnya.
                val status = when {
                    loading && rows.size <= 2 -> "Mengambil daftar skin..."
                    loading -> null
                    else -> message.ifBlank { null }
                }
                if (status != null) Text(status, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (!loading && rows.size <= 2) {
                    Text(
                        "Belum ada skin. Sambungkan internet lalu muat ulang untuk mengunduh skin dari repository.",
                        color = PadTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                enabled = busyId == null,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) { Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
        }
    )
}

// Baris skin: bentuk sama dengan dialog lain di DTX (CtrlPanelColor, rounded 8dp, seluruh baris bisa di-tap).
@Composable
private fun SkinRowItem(
    title: String, subtitle: String, active: Boolean, action: String?, busy: Boolean, enabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled && action != null, onClick = onAction)
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, color = if (!enabled && !busy) TextDim else PadTextColor,
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1
            )
            Text(subtitle, color = TextDim, fontSize = 10.sp, maxLines = 2)
        }
        Box(Modifier.width(8.dp))
        when {
            busy -> CircularProgressIndicator(color = ThemeState.uiAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            action == "UNDUH" || action == "UPDATE" -> Icon(
                Icons.Filled.CloudDownload, if (action == "UPDATE") "Update skin" else "Unduh skin",
                tint = if (enabled) ThemeState.uiAccent else TextDim, modifier = Modifier.size(18.dp)
            )
            action != null -> Text(
                action, color = if (enabled) ThemeState.uiAccent else TextDim,
                fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            active -> Icon(Icons.Filled.Check, "Sedang dipakai", tint = ThemeState.uiAccent, modifier = Modifier.size(18.dp))
        }
    }
}
