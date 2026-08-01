package io.github.totomika.pocketmcp.ui.data

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataOverviewScreen(
    namespace: String,
    onBack: () -> Unit,
    onNavigateToKv: (String) -> Unit,
    onNavigateToSql: (String) -> Unit,
    viewModel: DataOverviewViewModel = viewModel(),
) {
    LaunchedEffect(namespace) { viewModel.load(namespace) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_management_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            DataCard(
                icon = Icons.Filled.VpnKey,
                title = stringResource(R.string.data_kv_title),
                summary = when {
                    uiState.kvError -> stringResource(R.string.data_load_failed)
                    uiState.kvCount == null -> null
                    uiState.kvCount == 0 -> stringResource(R.string.data_empty)
                    else -> stringResource(R.string.data_overview_kv_count, uiState.kvCount!!)
                },
                isEmpty = uiState.kvCount == 0,
                isLoading = uiState.kvCount == null && !uiState.kvError,
                onClick = { onNavigateToKv(namespace) },
            )

            DataCard(
                icon = Icons.Filled.Dataset,
                title = stringResource(R.string.data_sql_title),
                summary = when {
                    uiState.sqlError -> stringResource(R.string.data_load_failed)
                    uiState.sqlDbCount == null || uiState.sqlTotalSize == null -> null
                    uiState.sqlDbCount == 0 -> stringResource(R.string.data_sql_empty)
                    else -> {
                        val sizeText = formatBytes(uiState.sqlTotalSize!!)
                        stringResource(R.string.data_overview_sql_count, uiState.sqlDbCount!!, sizeText)
                    }
                },
                isEmpty = uiState.sqlDbCount == 0,
                isLoading = uiState.sqlDbCount == null && !uiState.sqlError,
                onClick = { onNavigateToSql(namespace) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DataCard(
    icon: ImageVector,
    title: String,
    summary: String?,
    isEmpty: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Icon container
            Column(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                when {
                    isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    summary != null -> {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                summary == stringResource(R.string.data_load_failed) -> MaterialTheme.colorScheme.error
                                isEmpty -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> stringResource(R.string.format_bytes_b, bytes)
        bytes < 1024 * 1024 -> stringResource(R.string.format_bytes_kb, bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> stringResource(R.string.format_bytes_mb, bytes / (1024.0 * 1024.0))
        else -> stringResource(R.string.format_bytes_gb, bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
