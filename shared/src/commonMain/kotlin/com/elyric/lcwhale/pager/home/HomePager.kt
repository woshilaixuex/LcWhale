package com.elyric.lcwhale.pager.home

import com.elyric.lcwhale.base.BasePager
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.views.View

@Page("home_pager")
internal class HomePager : BasePager() {
    
    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr{

                }
            }
        }
    }
}