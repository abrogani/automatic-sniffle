package com.abrogani.stremio.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
internal fun ImageWithSmallPlaceholder(
    modifier: Modifier = Modifier,
    placeholderModifier: Modifier = Modifier,
    urlImage: String?,
    placeholder: Painter,
    contentDesc: String,
    shape: Shape = CircleShape
) {
    var isSuccess by remember { mutableStateOf(false) }

    val background by animateColorAsState(
        targetValue = if (isSuccess) Color.Transparent else MaterialTheme.colorScheme.surface,
        label = "",
    )

    Surface(
        modifier = modifier,
        tonalElevation = 65.dp,
        color = background,
        shape = shape
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            if (!isSuccess) {
                Icon(
                    painter = placeholder,
                    contentDescription = contentDesc,
                    tint = LocalContentColor.current.copy(0.8F),
                    modifier = placeholderModifier
                )
            }

            if (urlImage != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(urlImage)
                        .crossfade(true)
                        .build(),
                    imageLoader = LocalContext.current.imageLoader,
                    contentDescription = contentDesc,
                    onState = { isSuccess = it is AsyncImagePainter.State.Success },
                    modifier = Modifier
                        .fillMaxSize()
                )
            }
        }
    }
}
