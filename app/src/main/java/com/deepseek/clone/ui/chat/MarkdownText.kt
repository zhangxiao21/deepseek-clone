package com.deepseek.clone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.clone.ui.theme.AppColors

/**
 * 轻量 Markdown 渲染器（纯 Compose，无外部依赖）。
 * 支持：标题(#)、加粗(**)、斜体(*)、行内代码(`)、代码块(```)、链接、引用(>)、无序/有序列表、分隔线。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var inCodeBlock = false
    val codeBuffer = StringBuilder()

    Column(modifier = modifier.fillMaxWidth()) {
        for (line in lines) {
            when {
                line.trimStart().startsWith("```") -> {
                    if (inCodeBlock) {
                        CodeBlock(codeBuffer.toString())
                        codeBuffer.clear()
                    }
                    inCodeBlock = !inCodeBlock
                }
                inCodeBlock -> codeBuffer.append(line).append("\n")
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> MarkdownLine(line)
            }
        }
        if (inCodeBlock && codeBuffer.isNotEmpty()) CodeBlock(codeBuffer.toString())
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val trimmed = line.trimStart()
    val indent = line.length - trimmed.length
    val modifier = Modifier.padding(start = (indent / 2).dp)

    when {
        trimmed.startsWith("# ") -> Text(trimmed.removePrefix("# "),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = modifier)
        trimmed.startsWith("## ") -> Text(trimmed.removePrefix("## "),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = modifier)
        trimmed.startsWith("### ") -> Text(trimmed.removePrefix("### "),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = modifier)
        trimmed.startsWith("> ") -> Text(trimmed.removePrefix("> "),
            style = MaterialTheme.typography.bodyMedium.copy(color = AppColors.TextSecondary),
            modifier = modifier.padding(start = 8.dp).background(AppColors.Surface, RoundedCornerShape(4.dp)).padding(6.dp))
        trimmed == "---" || trimmed == "***" -> Spacer(Modifier.height(4.dp).fillMaxWidth()
            .background(AppColors.Divider))
        Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> Text(parseInline(trimmed),
            style = MaterialTheme.typography.bodyLarge, modifier = modifier)
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text("• " + parseInline(trimmed.removePrefix("- ").removePrefix("* ")),
            style = MaterialTheme.typography.bodyLarge, modifier = modifier)
        else -> Text(parseInline(line), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(AppColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(code.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            color = AppColors.TextPrimary)
    }
}

/** 解析行内标记，返回 AnnotatedString */
private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("(\\*\\*.*?\\*\\*|`.*?`|\\*.*?\\*|\\[.*?\\]\\(.*?\\))")
    var last = 0
    pattern.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("**") && token.endsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removePrefix("**").removeSuffix("**"))
            }
            token.startsWith("`") && token.endsWith("`") -> withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace, color = AppColors.Primary,
                background = AppColors.SurfaceVariant)) {
                append(token.removePrefix("`").removeSuffix("`"))
            }
            token.startsWith("[") -> {
                val link = Regex("\\[(.*?)\\]\\((.*?)\\)").find(token)
                if (link != null) {
                    pushStringAnnotation("URL", link.groupValues[2])
                    withStyle(SpanStyle(color = AppColors.Primary,
                        textDecoration = TextDecoration.Underline)) {
                        append(link.groupValues[1])
                    }
                    pop()
                } else append(token)
            }
            token.startsWith("*") && token.endsWith("*") -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(token.removePrefix("*").removeSuffix("*"))
            }
            else -> append(token)
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
