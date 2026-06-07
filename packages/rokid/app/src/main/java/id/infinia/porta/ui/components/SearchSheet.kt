package id.infinia.porta.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Full-text search bottom sheet for searching across all conversations.
 *
 * Features:
 * - Debounced search input
 * - Results with snippets and match counts
 * - Tap result to navigate to conversation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    viewModel: BridgeViewModel,
    onSelectConversation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var inputText by remember { mutableStateOf(searchQuery) }

    // Debounce search
    LaunchedEffect(inputText) {
        if (inputText.length >= 2) {
            kotlinx.coroutines.delay(400)
            viewModel.searchConversations(inputText)
        } else if (inputText.isEmpty()) {
            viewModel.clearSearch()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.clearSearch()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                "Search Conversations",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Search input
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search across all conversations…") },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = PortaPrimary)
                },
                trailingIcon = {
                    if (inputText.isNotEmpty()) {
                        IconButton(onClick = {
                            inputText = ""
                            viewModel.clearSearch()
                        }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.searchConversations(inputText) }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PortaPrimary,
                    cursorColor = PortaPrimary
                )
            )

            Spacer(Modifier.height(12.dp))

            // Loading indicator
            if (isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = PortaPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Searching…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Results
            if (searchResults.isNotEmpty()) {
                Text(
                    "${searchResults.size} result${if (searchResults.size != 1) "s" else ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchResults, key = { it.id }) { result ->
                    SearchResultCard(
                        result = result,
                        query = inputText,
                        onClick = {
                            viewModel.clearSearch()
                            viewModel.selectConversation(result.id)
                            onSelectConversation(result.id)
                            onDismiss()
                        }
                    )
                }
            }

            // Empty state
            if (!isSearching && searchResults.isEmpty() && inputText.length >= 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                        Text(
                            "No results for \"$inputText\"",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: BridgeViewModel.SearchResult,
    query: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title + match count
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = PortaPrimary
                )
                Text(
                    result.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PortaPrimary.copy(alpha = 0.12f)
                ) {
                    Text(
                        "${result.matchCount}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PortaPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Snippets with highlighted query
            result.snippets.take(2).forEach { snippet ->
                val highlighted = buildHighlightedSnippet(snippet, query)
                Text(
                    highlighted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Build an AnnotatedString that highlights occurrences of [query] in [text]
 * with a bold, colored span.
 */
@Composable
private fun buildHighlightedSnippet(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    val highlightColor = PortaPrimary
    val normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    return buildAnnotatedString {
        val lower = text.lowercase()
        val queryLower = query.lowercase()
        var lastIndex = 0

        while (true) {
            val idx = lower.indexOf(queryLower, lastIndex)
            if (idx == -1) break

            // Normal text before match
            if (idx > lastIndex) {
                withStyle(SpanStyle(color = normalColor)) {
                    append(text.substring(lastIndex, idx))
                }
            }
            // Highlighted match
            withStyle(SpanStyle(
                color = highlightColor,
                fontWeight = FontWeight.Bold,
                background = highlightColor.copy(alpha = 0.1f)
            )) {
                append(text.substring(idx, idx + query.length))
            }
            lastIndex = idx + query.length
        }

        // Remaining text
        if (lastIndex < text.length) {
            withStyle(SpanStyle(color = normalColor)) {
                append(text.substring(lastIndex))
            }
        }
    }
}
