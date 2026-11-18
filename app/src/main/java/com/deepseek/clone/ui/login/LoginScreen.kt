package com.deepseek.clone.ui.login

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.repository.Repository
import com.deepseek.clone.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * API Key 输入页。
 * 用户从 platform.deepseek.com/api_keys 创建 sk- 开头的密钥，输入后验证并保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    store: SecureStore,
    repository: Repository,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))

        // Logo
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AppColors.Primary),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "🐋", color = AppColors.OnPrimary,
                style = MaterialTheme.typography.displaySmall
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("赛博大鲸鱼", style = MaterialTheme.typography.headlineMedium,
            color = AppColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("输入 API Key 开始使用", style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary)

        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key (sk-...)") },
            placeholder = { Text("sk-xxxxxxxxxxxxxxxx") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "显示/隐藏"
                    )
                }
            },
            isError = error != null,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = AppColors.Primary,
                unfocusedBorderColor = AppColors.Divider
            )
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val key = apiKey.trim()
                if (!key.startsWith("sk-") || key.length < 20) {
                    error = "请输入有效的 API Key（以 sk- 开头）"
                    return@Button
                }
                scope.launch {
                    verifying = true
                    error = null
                    repository.saveApiKey(key)
                    val result = repository.validateApiKey()
                    verifying = false
                    result.fold(
                        onSuccess = { onLoginSuccess() },
                        onFailure = { e ->
                            error = e.message ?: "验证失败"
                            repository.clearApiKey()
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !verifying && apiKey.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
        ) {
            if (verifying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.OnPrimary
                )
            } else {
                Text("验证并登录")
            }
        }

        Spacer(Modifier.height(24.dp))

        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ApiConfig.API_KEY_URL)))
        }) {
            Text("没有 API Key？前往 DeepSeek 开放平台创建")
        }

        // 已保存多个 Key 时（例如某个 Key 额度用尽被临时登出），可直接切回其它 Key
        val savedKeys = store.apiKeys()
        if (savedKeys.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("或使用已保存的密钥", style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            savedKeys.forEach { entry ->
                OutlinedButton(
                    onClick = {
                        store.setActiveApiKey(entry.id)
                        onLoginSuccess()
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("${entry.label}  ${entry.masked}", maxLines = 1)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "API Key 保存在本机加密存储中，不会上传到任何第三方。\n调用 API 产生的费用由你的 DeepSeek 账户承担。",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
