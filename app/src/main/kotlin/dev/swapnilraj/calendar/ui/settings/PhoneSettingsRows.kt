package dev.swapnilraj.calendar.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R

@Composable
internal fun PhoneSettingsHeader(title: String, enabled: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val back = stringResource(R.string.phone_settings_back)
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.semantics { contentDescription = back }) {
            val color = LocalContentColor.current
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Canvas(Modifier.size(24.dp)) {
                val tip = if (rtl) 0.8f else 0.2f
                val edge = 1f - tip
                val mid = Offset(size.width * tip, size.height * 0.5f)
                drawLine(color, Offset(size.width * edge, size.height * 0.2f), mid, 2.dp.toPx())
                drawLine(color, mid, Offset(size.width * edge, size.height * 0.8f), 2.dp.toPx())
                drawLine(color, mid, Offset(size.width * edge, size.height * 0.5f), 2.dp.toPx())
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.ContentOrLtr),
                modifier = Modifier.semantics { heading() })
        }
    }
}

@Composable
internal fun PhoneSettingsRow(
    title: String,
    subtitle: String? = null,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val start = if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f) 24.dp else 72.dp
        val alpha = if (enabled) 1f else 0.38f
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = start, end = 24.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
    }
}

@Composable
internal fun PhoneSettingsSwitch(title: String, checked: Boolean, tag: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val start = if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f) 24.dp else 72.dp
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(start = start, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(end = 16.dp),
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
internal fun PhoneSettingsSection(title: String) {
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(title, Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrLtr),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
