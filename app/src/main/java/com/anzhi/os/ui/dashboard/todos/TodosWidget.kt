package com.anzhi.os.ui.dashboard.todos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.dashboard.TodoItem
import com.anzhi.os.ui.components.CornerCard
import com.anzhi.os.ui.theme.*

/**
 * 待办 Widget — 显示待办事项列表。
 *
 * @param todos 待办列表
 */
@Composable
fun TodosWidget(
    todos: List<TodoItem> = emptyList()
) {
    CornerCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
    ) {
        Column {
            Text(
                text = "待办",
                style = AnzhiTypography.widgetTitle,
                color = AnzhiTextTertiary
            )
            Spacer(Modifier.height(4.dp))

            if (todos.isEmpty()) {
                Text(
                    text = "今天没有待办",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiTextTertiary
                )
            } else {
                todos.take(5).forEach { todo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        // 状态标记
                        val prefix = if (todo.done) "✓ " else "○ "
                        val color = if (todo.done)
                            AnzhiTextTertiary
                        else when (todo.priority) {
                            "high" -> AnzhiAccentOrange
                            "urgent" -> AnzhiAccentRed
                            else -> AnzhiTextSecondary
                        }

                        Text(
                            text = prefix + todo.text,
                            style = AnzhiTypography.bodySmall,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if (todo.done)
                                TextDecoration.LineThrough
                            else
                                TextDecoration.None
                        )
                    }
                }
            }
        }
    }
}
