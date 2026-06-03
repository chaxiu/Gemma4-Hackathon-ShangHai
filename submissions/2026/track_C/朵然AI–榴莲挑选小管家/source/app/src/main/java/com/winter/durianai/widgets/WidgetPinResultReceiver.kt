package com.winter.durianai.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class WidgetPinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val provider = intent.getStringExtra("provider").orEmpty()
        Toast.makeText(context, "已添加到桌面", Toast.LENGTH_SHORT).show()
        when (provider) {
            DailyAdviceWidgetProvider::class.java.name -> DailyAdviceWidgetProvider.updateAll(context)
            BadgesWidgetProvider::class.java.name -> BadgesWidgetProvider.updateAll(context)
            Badge1WidgetProvider::class.java.name -> Badge1WidgetProvider.updateAll(context)
            Badge2WidgetProvider::class.java.name -> Badge2WidgetProvider.updateAll(context)
            Badge3WidgetProvider::class.java.name -> Badge3WidgetProvider.updateAll(context)
            Badge4WidgetProvider::class.java.name -> Badge4WidgetProvider.updateAll(context)
            Badge5WidgetProvider::class.java.name -> Badge5WidgetProvider.updateAll(context)
            else -> {
                DailyAdviceWidgetProvider.updateAll(context)
                BadgesWidgetProvider.updateAll(context)
            }
        }
    }
}
