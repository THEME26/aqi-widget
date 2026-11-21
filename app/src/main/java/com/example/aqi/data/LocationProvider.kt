package com.example.aqi.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * 粗定位：只读系统缓存的 lastKnownLocation，不注册持续监听、不持续开 GPS。
 * 这是「不申请 ACCESS_BACKGROUND_LOCATION、不跑 location 类型前台服务」的前提。
 * 缺点：首次安装 / 长时间未开定位可能拿不到位置，此时界面会明确提示，
 * 用户也可以填手动坐标完全绕过这里（见 AqiPrefs.manualCoord）。
 */
class LocationProvider(private val ctx: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        // 先取已启用的 provider；国产 ROM 上这些可能全都没有缓存，
        // 再补一次 allProviders —— 即使 provider 当前被关，系统里也可能还留着上次坐标。
        val enabled = try {
            lm.getProviders(true)
        } catch (e: Exception) {
            emptyList()
        }
        val all = try {
            lm.allProviders
        } catch (e: Exception) {
            emptyList()
        }
        return (enabled + all).distinct()
            .mapNotNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (e: SecurityException) {
                    null
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }
}
