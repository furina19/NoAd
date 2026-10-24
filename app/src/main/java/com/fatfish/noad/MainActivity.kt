package com.fatfish.noad

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fatfish.noad.databinding.ActivityMainBinding
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val appAdapter = AppStatsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun setupToolbar() {
        binding.toolbar.title = "无广告"
    }

    private fun setupRecyclerView() {
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = appAdapter
    }

    private fun setupButtons() {
        // 跳转无障碍服务设置
        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        // 清空统计
        binding.btnClearStats.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("确定要清空所有拦截记录吗？")
                .setPositiveButton("清空") { _, _ ->
                    AdStatsManager.clearAll(this)
                    refreshUI()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        // 启动前台保活服务
        binding.btnStartService.setOnClickListener {
            startKeepAliveService()
            refreshUI()
        }
    }

    /**
     * 刷新界面数据
     */
    private fun refreshUI() {
        val total = AdStatsManager.getTotalCount(this)
        val stats = AdStatsManager.getAppStats(this)
        val lastApp = AdStatsManager.getLastSkipApp(this)
        val lastTime = AdStatsManager.getLastSkipTime(this)
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()

        // 总拦截次数
        binding.textTotalCount.text = total.toString()
        binding.textTotalCount.setTextColor(
            ContextCompat.getColor(this, if (total > 0) R.color.accent_green else R.color.text_secondary)
        )

        // 最近拦截
        if (total > 0) {
            val timeStr = DateFormat.format("MM-dd HH:mm", Date(lastTime)).toString()
            binding.textLastSkip.text = "$lastApp · $timeStr"
            binding.textLastSkip.visibility = View.VISIBLE
        } else {
            binding.textLastSkip.visibility = View.GONE
        }

        // 无障碍服务状态
        if (isAccessibilityEnabled) {
            binding.textServiceStatus.text = "● 已开启"
            binding.textServiceStatus.setTextColor(
                ContextCompat.getColor(this, R.color.accent_green)
            )
            binding.btnOpenAccessibility.text = "无障碍已开启 ✓"
            binding.btnOpenAccessibility.isEnabled = false
            binding.btnOpenAccessibility.alpha = 0.5f
        } else {
            binding.textServiceStatus.text = "○ 未开启"
            binding.textServiceStatus.setTextColor(
                ContextCompat.getColor(this, R.color.accent_red)
            )
            binding.btnOpenAccessibility.text = "前往开启无障碍服务 →"
            binding.btnOpenAccessibility.isEnabled = true
            binding.btnOpenAccessibility.alpha = 1f
        }

        // 服务运行状态
        val isServiceRunning = isKeepAliveServiceRunning()
        binding.textServiceRunning.text = if (isServiceRunning) "● 运行中" else "○ 未启动"
        binding.textServiceRunning.setTextColor(
            ContextCompat.getColor(
                this,
                if (isServiceRunning) R.color.accent_green else R.color.accent_red
            )
        )

        // 应用拦截列表
        appAdapter.submitList(stats)
        binding.layoutEmpty.visibility = if (stats.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerApps.visibility = if (stats.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 打开无障碍服务设置页面
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // 备用方案
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    /**
     * 启动前台保活服务
     */
    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * 检查无障碍服务是否已开启
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${AdSkipService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(service, ignoreCase = true) }
    }

    /**
     * 检查保活服务是否在运行
     */
    private fun isKeepAliveServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == KeepAliveService::class.java.name }
    }

    // ============ RecyclerView Adapter ============

    inner class AppStatsAdapter :
        RecyclerView.Adapter<AppStatsAdapter.ViewHolder>() {

        private var data: List<AdStatsManager.AppSkipStat> = emptyList()

        fun submitList(list: List<AdStatsManager.AppSkipStat>) {
            data = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_stat, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(data[position])
        }

        override fun getItemCount(): Int = data.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textAppName: TextView = itemView.findViewById(R.id.textAppName)
            private val textSkipCount: TextView = itemView.findViewById(R.id.textSkipCount)
            private val textRank: TextView = itemView.findViewById(R.id.textRank)

            fun bind(stat: AdStatsManager.AppSkipStat) {
                textAppName.text = stat.appName
                textSkipCount.text = "拦截 ${stat.count} 次"
                textRank.text = "${adapterPosition + 1}"
            }
        }
    }
}