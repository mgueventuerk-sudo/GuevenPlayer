package com.mg.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.Inter

/**
 * Texteingabe für die Fernbedienung: Das Feld ist zunächst nur eine fokussierbare Fläche.
 * Erst mit OK wird die Bildschirmtastatur geöffnet – so springt sie beim Durchnavigieren nicht ständig auf.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    leadingIcon: AppIcon? = null,
    large: Boolean = false,
) {
    if (isTouch) {
        TouchTextField(value, onValueChange, label, modifier, placeholder, password, keyboardType, imeAction, onSubmit, leadingIcon, large)
        return
    }
    var editing by remember { mutableStateOf(false) }
    val fieldRequester = remember { FocusRequester() }
    val surfaceRequester = remember { FocusRequester() }

    LaunchedEffect(editing) {
        // Das Feld wird erst mit editing fokussierbar – ein früher Versuch würfe IllegalStateException.
        if (editing) fieldRequester.requestFocusWhenReady()
    }

    val fontSize = if (large) 22.sp else 18.sp
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary)
        FocusSurface(
            onClick = { editing = true },
            modifier = Modifier
                .padding(top = if (label.isNotEmpty()) 6.dp else 0.dp)
                .fillMaxWidth()
                .focusRequester(surfaceRequester)
                .semantics { contentDescription = label.ifEmpty { placeholder } },
            shape = RoundedCornerShape(if (large) 50 else 14),
            focusedScale = 1.02f,
            glow = false,
            sheen = false,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    AnimatedIcon(
                        leadingIcon,
                        tint = if (editing) AppColors.AccentText else AppColors.TextSecondary,
                        active = editing,
                        size = if (large) 26.dp else 22.dp,
                        modifier = Modifier.padding(start = if (large) 22.dp else 14.dp),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = AppColors.TextPrimary, fontSize = fontSize, fontFamily = Inter),
                    cursorBrush = SolidColor(AppColors.Accent),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onAny = {
                        editing = false
                        runCatching { surfaceRequester.requestFocus() }
                        onSubmit()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = if (large) 18.dp else 16.dp, vertical = if (large) 16.dp else 14.dp)
                        .focusRequester(fieldRequester)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { state -> if (!state.isFocused && editing) editing = false },
                    decorationBox = { inner ->
                        if (value.isEmpty() && !editing) {
                            Text(
                                placeholder.ifEmpty {
                                    if (isTouch) "Tippen zum Eingeben" else "OK drücken zum Eingeben"
                                },
                                color = AppColors.TextMuted,
                                fontSize = fontSize,
                                maxLines = 1,
                            )
                        } else {
                            inner()
                        }
                    },
                )
            }
        }
    }
}

/**
 * Dasselbe Feld am Handy: ein ganz normales Textfeld. Ein Tipp irgendwo aufs Feld setzt den Cursor und öffnet die
 * Tastatur; "Fertig" schließt sie wieder. (Die Fernseh-Variante würde den Tipp verschlucken, weil ihr Textfeld erst
 * nach "OK" Eingaben annimmt.)
 */
@Composable
private fun TouchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    placeholder: String,
    password: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onSubmit: () -> Unit,
    leadingIcon: AppIcon?,
    large: Boolean,
) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (large) 50 else 14)
    val fontSize = if (large) 20.sp else 17.sp
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary)
        Row(
            Modifier
                .padding(top = if (label.isNotEmpty()) 6.dp else 0.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(if (focused) AppColors.GlassRaised else AppColors.Glass)
                .border(if (focused) 2.dp else 1.dp, if (focused) AppColors.Accent else AppColors.GlassBorder, shape)
                // Auch ein Tipp neben den Text (Symbol, Rand) öffnet das Feld.
                .clickable(interactionSource = interaction, indication = null) { runCatching { requester.requestFocus() } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                AnimatedIcon(
                    leadingIcon,
                    tint = if (focused) AppColors.AccentText else AppColors.TextSecondary,
                    active = focused,
                    size = if (large) 24.dp else 22.dp,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = AppColors.TextPrimary, fontSize = fontSize, fontFamily = Inter),
                cursorBrush = SolidColor(AppColors.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onAny = {
                    if (imeAction == ImeAction.Next) focusManager.moveFocus(FocusDirection.Down) else focusManager.clearFocus()
                    onSubmit()
                }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = if (large) 15.dp else 14.dp)
                    .focusRequester(requester)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label.ifEmpty { placeholder } },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(placeholder.ifEmpty { "Tippen zum Eingeben" }, color = AppColors.TextMuted, fontSize = fontSize, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
        }
    }
}
