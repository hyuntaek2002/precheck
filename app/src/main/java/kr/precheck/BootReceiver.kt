package kr.precheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// 시동(부팅) 때 감시 서비스를 올린다. 운전자가 앱을 열 필요가 없어야 하므로
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) CarMonitorService.start(c)
    }
}
