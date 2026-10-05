package com.anzhi.os.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anzhi.os.VpsConfig
import com.anzhi.os.ui.theme.AnzhiTextPrimary
import com.anzhi.os.ui.theme.AnzhiTextSecondary
import com.anzhi.os.ui.theme.AnzhiTextTertiary
import com.anzhi.os.ui.theme.AnzhiTheme

/**
 * VPS 接入设置：网关地址 + 接口令牌。
 *
 * 值只落 app 私有 SharedPreferences（/data 沙箱内），不进镜像、不进日志。
 * 系统属性 persist.vendor.anzhi.* 仍会被读取，但只作为"界面为空时"的开发期兜底，
 * 界面上明写出来，免得她以为改属性就生效。
 */
class VpsSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnzhiTheme {
                VpsSettingsScreen(
                    onSave = { url, token -> VpsConfig.save(this, url, token) },
                    onClear = { VpsConfig.clear(this) },
                    initialUrl = VpsConfig.rawUrl(this),
                    initialToken = VpsConfig.rawToken(this),
                    propUrl = VpsConfig.propUrl(),
                    propTokenSet = VpsConfig.propToken().isNotEmpty()
                )
            }
        }
    }
}

@Composable
private fun VpsSettingsScreen(
    initialUrl: String,
    initialToken: String,
    propUrl: String,
    propTokenSet: Boolean,
    onSave: (String, String) -> Unit,
    onClear: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var revealToken by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("VPS 接入", fontSize = 20.sp, color = AnzhiTextPrimary)
            Text(
                "换服务器或重置令牌时改这里，不用重新编译手机。",
                fontSize = 12.sp,
                color = AnzhiTextTertiary
            )

            FieldLabel("网关地址")
            LinedField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://chat.example.com",
                keyboardType = KeyboardType.Uri,
                singleLine = true
            )

            FieldLabel("接口令牌")
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinedField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = "填 VPS 接口令牌",
                    keyboardType = KeyboardType.Password,
                    singleLine = true,
                    visualTransformation = if (revealToken) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { revealToken = !revealToken }) {
                    Text(if (revealToken) "隐藏" else "显示", fontSize = 12.sp)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    onSave(url, token)
                    savedOnce = true
                }) { Text("保存") }
                TextButton(onClick = {
                    onClear()
                    url = ""
                    token = ""
                    savedOnce = true
                }) { Text("清空") }
            }

            Text(
                when {
                    token.isNotBlank() -> "以界面填写的令牌为准" +
                            if (savedOnce) " · 已保存，重开聊天生效" else ""
                    propTokenSet -> "界面为空 · 正在用系统属性兜底（开发期后门，任何 app 都能读到，建议现在填上）"
                    else -> "还没有令牌 · 安知连 VPS 会 401，聊天退回本地模式"
                },
                fontSize = 12.sp,
                color = AnzhiTextSecondary
            )

            if (propUrl.isNotBlank()) {
                Text("出厂默认网关：$propUrl", fontSize = 12.sp, color = AnzhiTextTertiary)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 13.sp, color = AnzhiTextSecondary)
}

@Composable
private fun LinedField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = TextStyle(fontSize = 15.sp, color = AnzhiTextPrimary),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 15.sp, color = AnzhiTextTertiary)
                }
                inner()
            }
        }
    )
}
