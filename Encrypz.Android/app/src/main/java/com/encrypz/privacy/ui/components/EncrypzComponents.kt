package com.encrypz.privacy.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.encrypz.privacy.theme.EncrypzBlack
import com.encrypz.privacy.theme.EncrypzBlue
import com.encrypz.privacy.theme.EncrypzBlueBright
import com.encrypz.privacy.theme.EncrypzBorder
import com.encrypz.privacy.theme.EncrypzBorderGlow
import com.encrypz.privacy.theme.EncrypzDeepBlack
import com.encrypz.privacy.theme.EncrypzEmeraldBright
import com.encrypz.privacy.theme.EncrypzFeaturedCardGradient
import com.encrypz.privacy.theme.EncrypzGlassBorder
import com.encrypz.privacy.theme.EncrypzGlowBlue
import com.encrypz.privacy.theme.EncrypzGlowCyan
import com.encrypz.privacy.theme.EncrypzGlowPurple
import com.encrypz.privacy.theme.EncrypzMuted
import com.encrypz.privacy.theme.EncrypzNavyGlass
import com.encrypz.privacy.theme.EncrypzSecondaryBg
import com.encrypz.privacy.theme.EncrypzSuccessBorder
import com.encrypz.privacy.theme.EncrypzSuccessBg
import com.encrypz.privacy.theme.EncrypzSurface
import com.encrypz.privacy.theme.EncrypzText

/**
 * Ambient radial glow background matching encrypz.com hero & body styling:
 * - Top radial blue glow (#3b82f626 -> #0a0a0a)
 * - Subtle purple (#c084fc) and cyan (#06b6d4) ambient glows
 */
@Composable
fun EncrypzAmbientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(EncrypzBlack)
            .background(
                Brush.radialGradient(
                    colors = listOf(EncrypzGlowBlue, Color.Transparent),
                    center = Offset(500f, 150f),
                    radius = 1100f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(EncrypzGlowPurple.copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(120f, 900f),
                    radius = 700f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(EncrypzGlowCyan.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(900f, 1400f),
                    radius = 700f
                )
            ),
        content = content
    )
}

/**
 * Floating pill navbar header matching encrypz.com `.navbar`:
 * background-color: #020d1c99, border: 1px solid #ffffff14, border-radius: 100px
 */
@Composable
fun EncrypzFloatingNavBar(
    statusText: String = "Zero-Knowledge",
    versionText: String = "v1.0.0",
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(100.dp),
        color = EncrypzNavyGlass.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, EncrypzGlassBorder),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.encrypz.privacy.R.drawable.encrypz_logo),
                    contentDescription = "Encrypz Logo",
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    text = "ENCRYPZ",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = EncrypzText
                )
            }

            if (trailingContent != null) {
                trailingContent()
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EncrypzStatusBadge(text = statusText)
                    EncrypzVersionBadge(version = versionText)
                }
            }
        }
    }
}

/**
 * Active status pill matching `.badge-status.badge-active` on encrypz.com:
 * Emerald pulsing dot + #34d399 text on #10b98126 background with #10b9814d border.
 */
@Composable
fun EncrypzStatusBadge(
    text: String,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusDotPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )

    val bgColor = if (isActive) EncrypzSuccessBg else EncrypzMuted.copy(alpha = 0.1f)
    val borderColor = if (isActive) EncrypzSuccessBorder else EncrypzGlassBorder
    val contentColor = if (isActive) EncrypzEmeraldBright else EncrypzMuted

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(100.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .scale(dotScale)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(EncrypzEmeraldBright)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                ),
                color = contentColor
            )
        }
    }
}

/**
 * Monospace version badge matching `.badge-version` on encrypz.com
 */
@Composable
fun EncrypzVersionBadge(
    version: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = EncrypzSecondaryBg,
        border = BorderStroke(1.dp, EncrypzBlue.copy(alpha = 0.25f))
    ) {
        Text(
            text = version,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = EncrypzBlue
        )
    }
}

/**
 * Animated client-side encryption pipeline scene matching encrypz.com `.encryption-anim-wrapper`:
 * Plaintext File -> Local AES-256-GCM Lock (pulses at 35%) -> Encrypted Cipher Chip -> Cloud Storage
 */
@Composable
fun EncrypzEncryptionPipelineVisual(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "encryptionPipeline")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pipelineProgress"
    )

    val lockScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4000
                1f at 0
                1f at 1200
                1.22f at 1450
                1f at 2000
                1f at 4000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "lockPulse"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp),
        contentAlignment = Alignment.Center
    ) {
        // Connecting track (.anim-path)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            EncrypzBorder,
                            EncrypzBlue.copy(alpha = 0.6f),
                            EncrypzBorder
                        )
                    )
                )
        )

        // Moving Plaintext File (0% -> 35% of cycle)
        if (progress <= 0.36f) {
            val fileFraction = (progress / 0.35f).coerceIn(0f, 1f)
            val fileAlpha = if (progress > 0.30f) ((0.36f - progress) / 0.06f).coerceIn(0f, 1f) else 1f
            // Moves from -120.dp to 0.dp (center lock)
            val xOffset = (-125f + (125f * fileFraction)).dp
            Box(
                modifier = Modifier
                    .offset(x = xOffset)
                    .alpha(fileAlpha)
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EncrypzSurface)
                    .border(1.dp, EncrypzBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "Plaintext File",
                    tint = EncrypzText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Moving Encrypted Cipher Chip (35% -> 90% of cycle)
        if (progress in 0.35f..0.92f) {
            val cipherFraction = ((progress - 0.35f) / 0.50f).coerceIn(0f, 1f)
            val cipherAlpha = when {
                progress < 0.40f -> ((progress - 0.35f) / 0.05f).coerceIn(0f, 1f)
                progress > 0.82f -> ((0.92f - progress) / 0.10f).coerceIn(0f, 1f)
                else -> 1f
            }
            // Moves from 0.dp (center lock) to +115.dp (cloud)
            val xOffset = (115f * cipherFraction).dp
            Box(
                modifier = Modifier
                    .offset(x = xOffset)
                    .alpha(cipherAlpha)
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EncrypzSecondaryBg)
                    .border(1.dp, EncrypzBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = "AES-256-GCM Cipher",
                    tint = EncrypzBlueBright,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Left Endpoint Label: Device
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EncrypzSurface)
                    .border(1.dp, EncrypzBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "Your Device",
                    tint = EncrypzMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Device",
                style = MaterialTheme.typography.labelSmall,
                color = EncrypzMuted
            )
        }

        // Center Lock (.anim-lock)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .scale(lockScale)
                    .size(48.dp)
                    .shadow(16.dp, RoundedCornerShape(12.dp), spotColor = EncrypzBlue)
                    .clip(RoundedCornerShape(12.dp))
                    .background(EncrypzSurface)
                    .border(1.dp, EncrypzBorderGlow, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Local AES-256-GCM Encryption",
                    tint = EncrypzBlue,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "AES-256-GCM",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = EncrypzBlueBright
            )
        }

        // Right Endpoint: Cloud (.anim-cloud)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EncrypzSecondaryBg)
                    .border(1.dp, EncrypzBlue.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = "Encrypted Cloud",
                    tint = EncrypzBlue,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Cloud",
                style = MaterialTheme.typography.labelSmall,
                color = EncrypzMuted
            )
        }
    }
}

/**
 * Bento Card matching encrypz.com `.bento-card` and `.download-card.card-featured`
 */
@Composable
fun EncrypzBentoCard(
    modifier: Modifier = Modifier,
    isFeatured: Boolean = false,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit
) {
    val borderColor = if (isFeatured) EncrypzBorderGlow else EncrypzBorder
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = EncrypzSurface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (isFeatured) 12.dp else 4.dp
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isFeatured) {
                        Modifier.background(EncrypzFeaturedCardGradient)
                    } else {
                        Modifier
                    }
                )
                .padding(padding)
        ) {
            content()
        }
    }
}

/**
 * Card Icon Box matching `.card-icon` on encrypz.com:
 * 44-48dp rounded square, `--secondary` (#3b82f61a) background, 1px border, primary blue icon.
 */
@Composable
fun EncrypzCardIconBox(
    icon: ImageVector,
    tint: Color = EncrypzBlue,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(EncrypzSecondaryBg)
            .border(1.dp, EncrypzBorder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * Security badges row matching `.security-badges-container` on encrypz.com:
 * "Open Source", "Zero Knowledge", "No Trackers"
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EncrypzSecurityBadgesRow(
    modifier: Modifier = Modifier
) {
    val badges = listOf("Open Source", "Zero Knowledge", "AES-256-GCM", "No Trackers")
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badges.forEach { label ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = EncrypzSecondaryBg,
                border = BorderStroke(1.dp, EncrypzBorder)
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = EncrypzBlue
                )
            }
        }
    }
}

/**
 * Spec box matching `.download-specs` on encrypz.com (#050505 / #0000004d background with monospace values)
 */
@Composable
fun EncrypzSpecsBox(
    specs: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = EncrypzDeepBlack.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, EncrypzGlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            specs.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = EncrypzMuted
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        color = EncrypzText
                    )
                }
            }
        }
    }
}

