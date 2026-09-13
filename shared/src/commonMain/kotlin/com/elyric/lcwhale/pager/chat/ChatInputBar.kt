package com.elyric.lcwhale.pager.chat

import com.elyric.lcwhale.dsh.RunState
import com.elyric.lcwhale.foundation.theme.ThemePalette
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

internal fun ViewContainer<*, *>.ChatInputBar(
    value: String,
    running: Boolean,
    onChange: (String) -> Unit,
    onStop: () -> Unit,
    onSend: () -> Boolean,
    onKeyboardDismiss: () -> Unit = {},
    palette: ThemePalette,
) {
    lateinit var inputRef: ViewRef<InputView>
    View {
        attr {
            padding(top = 10f, bottom = 14f, left = 10f, right = 10f)
            marginLeft(4f)
            marginRight(4f)
            flexDirectionRow()
            backgroundColor(palette.input)
            borderRadius(18f)
        }
        Button { attr { size(32f, 32f); borderRadius(16f); backgroundColor(palette.surfaceMuted); titleAttr { text("+"); fontSize(22f); color(palette.textMuted) } } }
        Input {
            ref { inputRef = it }
            attr { flex(1f); height(40f); marginLeft(8f); fontSize(14f); color(palette.text); placeholder("输入消息"); placeholderColor(palette.textMuted); backgroundColor(Color.TRANSPARENT) }
            event { textDidChange { onChange(it.text) } }
        }
        vif({ running }) {
            Button { attr { size(32f, 32f); borderRadius(16f); marginLeft(6f); backgroundColor(palette.error); titleAttr { text("■"); fontSize(13f); color(Color.WHITE) } }; event { click { onStop() } } }
        }
        Button {
            attr { size(32f, 32f); borderRadius(16f); marginLeft(6f); backgroundColor(palette.accent); titleAttr { text("→"); fontSize(18f); color(Color.WHITE) } }
            event {
                click {
                    if (onSend()) {
                        inputRef.view?.setText("")
                        onKeyboardDismiss()
                    }
                }
            }
        }
    }
}
