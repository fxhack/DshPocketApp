package com.dsh.pocket

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * 轻量子类，仅保留布局引用点。
 *
 * 下拉刷新与"上滑看历史"的冲突，改由 JS 桥接精确探测内部滚动容器的
 * scrollTop，再通过 SwipeRefreshLayout.setOnChildScrollUpCallback 判定——
 * 只有真正处于所有内容最顶端时才允许下拉刷新。
 */
class DshWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr)
