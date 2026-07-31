package cn.termux.ubuntumanager.operation

import android.app.ActivityManager
import android.content.Context

object RecentTaskVisibility {
    fun setExcluded(context: Context, excluded: Boolean) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.appTasks.forEach { task ->
            runCatching { task.setExcludeFromRecents(excluded) }
        }
    }
}
